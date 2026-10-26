package com.premiumlab.galleryx;

import com.premiumlab.galleryx.util.Toasts;
import androidx.annotation.Nullable;
import android.animation.Animator;
import android.animation.AnimatorListenerAdapter;
import android.graphics.RectF;
import android.content.Intent;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.view.animation.AccelerateInterpolator;
import android.view.animation.DecelerateInterpolator;
import android.view.animation.PathInterpolator;
import android.widget.ImageView;
import android.widget.SeekBar;
import android.widget.TextView;
import android.widget.Toast;

import androidx.activity.OnBackPressedCallback;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.content.FileProvider;
import androidx.core.graphics.Insets;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowCompat;
import androidx.core.view.WindowInsetsCompat;
import androidx.core.view.WindowInsetsControllerCompat;
import androidx.recyclerview.widget.RecyclerView;
import androidx.viewpager2.widget.ViewPager2;

import com.premiumlab.galleryx.data.FavStore;
import com.premiumlab.galleryx.data.MaskGuard;
import com.premiumlab.galleryx.data.MediaEngine;
import com.premiumlab.galleryx.data.MediaItem;
import com.premiumlab.galleryx.data.NoteStore;
import com.premiumlab.galleryx.data.Prefs;
import com.premiumlab.galleryx.data.SafeStore;
import com.premiumlab.galleryx.data.TrashStore;
import com.premiumlab.galleryx.ui.Actions;
import com.premiumlab.galleryx.ui.SafeDialogs;
import com.premiumlab.galleryx.ui.adapter.PhotoPagerAdapter;
import com.premiumlab.galleryx.ui.dialog.DestSheet;
import com.premiumlab.galleryx.ui.dialog.OpProgressDialog;
import com.premiumlab.galleryx.ui.sheet.NoteSheet;
import com.premiumlab.galleryx.ui.sheet.OptionsSheet;
import com.premiumlab.galleryx.ui.view.ZoomableImageView;
import com.premiumlab.galleryx.util.FileOp;
import com.premiumlab.galleryx.util.Fmt;
import com.premiumlab.galleryx.util.Press;

import java.io.File;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Полноэкранный просмотрщик фото и видео.
 * Режимы: 0 — обычный, 1 — корзина, 2 — сейф.
 *
 * Оформление — «стеклянные» плавающие капсулы поверх чёрного фона:
 * сверху круглая «назад», капсула «день недели / время» (тап — свойства) и «ещё»;
 * снизу капсула плеера (для видео) и капсула действий.
 *
 * Жесты: тап — показать/скрыть панели, двойной тап — зум,
 * свайп вниз — закрыть (картинка сжимается и возвращается к миниатюре),
 * свайп вверх — свойства файла.
 */
public class PhotoViewerActivity extends AppCompatActivity {

    public static final int MODE_NORMAL = 0;
    public static final int MODE_TRASH = 1;
    public static final int MODE_SAFE = 2;

    private View root;
    private ViewPager2 pager;
    private PhotoPagerAdapter adapter;
    private View topBar, bottomBar, pillDate;
    /** Второй ряд верхней панели: скорость и поворот (только видео). */
    private View viewerVideoTools;
    /** Закрытие после возврата ориентации в портретную (из ландшафта). */
    private boolean returningToPortrait;
    private TextView txtDay, txtTime;
    private ImageView btnFav, btnRestore, btnDelete, btnShare, btnMore;
    private int mode = MODE_NORMAL;
    private boolean barsVisible = true;
    // --- Направленное автопролистывание после действий с файлом ---
    /** Последняя показанная страница (для определения направления листания). */
    private int lastPos = -1;
    /** Путь текущего файла: отличает настоящий переход на другой файл от
     *  сдвига номеров страниц после удаления соседней (автопроход). */
    private String lastPagePath;
    /** Куда пользователь листает: +1 — вперёд (к следующим), -1 — назад. */
    private int swipeDir = 1;
    /** Идёт программное пролистывание (не путать с ручным свайпом). */
    private boolean autoAdvancing = false;
    /** Страница, которую надо убрать ПОСЛЕ анимации пролистывания. */
    private String pendingRemovePath = null;
    private int lastScrollState = ViewPager2.SCROLL_STATE_IDLE;
    private boolean dismissing = false;
    /** Кадр миниатюры на экране (left, top, width, height) — для анимации открытия/закрытия. */
    private int[] srcRect;
    private int startIndex;

    /**
     * Сетка, из которой открыли просмотрщик: умеет подготовить (прокрутить к) и
     * найти на экране ячейку файла — закрытие «возвращает» кадр в ячейку текущего
     * файла, даже если пролистали далеко от исходного.
     */
    public interface ThumbLocator {
        /** Прокрутить сетку так, чтобы ячейка файла была на экране (вызывается заранее). */
        void prepareThumb(String path);

        /** Кадр ячейки файла на экране (left, top, width, height) или null. */
        int[] locateThumb(String path);
    }

    /**
     * Порог суммарной длины путей (в символах): списки больше передаются не
     * через Intent, а через память — иначе превышается лимит Binder (~1 МБ)
     * и приложение вылетает при открытии файла из большой галереи.
     */
    public static final long MAX_INTENT_CHARS = 150000L;

    private static java.util.ArrayList<String> sharedPaths;

    /** Большой список путей для просмотрщика (вместо переполняющего Intent). */
    public static void setSharedPaths(java.util.ArrayList<String> p) {
        sharedPaths = p;
    }

    private static java.lang.ref.WeakReference<ThumbLocator> locatorRef;

    public static void setThumbLocator(ThumbLocator l) {
        locatorRef = l == null ? null : new java.lang.ref.WeakReference<>(l);
    }

    private ThumbLocator locator() {
        return locatorRef == null ? null : locatorRef.get();
    }

    /** Кадр миниатюры для страницы: сначала спрашиваем сетку, иначе — исходная ячейка. */
    private int[] thumbRectFor(int position) {
        ThumbLocator l = locator();
        if (l != null && adapter != null) {
            try {
                int[] r = l.locateThumb(adapter.getPath(position));
                if (r != null && r[2] > 0 && r[3] > 0) return r;
            } catch (Exception ignored) {
            }
        }
        return position == startIndex ? srcRect : null;
    }

    /** Панели спрятаны жестом (свайп вниз) — вернуть при отмене жеста. */
    private boolean barsDragHidden = false;
    private int enterAttempts = 0;
    private static final PathInterpolator EASE = new PathInterpolator(0.4f, 0f, 0.2f, 1f);
    /** «Выразительная» кривая Material 3: быстрый старт и долгое мягкое торможение. */
    private static final PathInterpolator EMPHASIZED = new PathInterpolator(0.2f, 0f, 0f, 1f);
    private static final PathInterpolator SPRING = new PathInterpolator(0.34f, 1.36f, 0.64f, 1f);

    /** Управление видео на текущей странице. */
    private View layoutVideoCtl;
    private ImageView btnVwPlay, btnVwMute, btnVwRotate, btnNote;
    private TextView btnVwSpeed;

    /** Подпись кнопки скорости: временная скорость текущего видео. */
    private void updateSpeedButton() {
        if (btnVwSpeed != null && adapter != null) {
            btnVwSpeed.setText(com.premiumlab.galleryx.ui.sheet.SpeedSheet.label(
                    adapter.getSpeed()));
        }
    }

    /** Скорость временная: новое видео всегда начинается с обычной 1×. */
    private void resetSpeed() {
        if (adapter != null && Math.abs(adapter.getSpeed() - 1f) > 0.01f) {
            adapter.setSpeed(1f);
        }
        updateSpeedButton();
    }
    private TextView txtVwCur, txtVwDur;
    private SeekBar seekVw;
    private PhotoPagerAdapter.VH currentPage;
    private boolean seeking = false;
    private boolean resumePlayback = false;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private final Runnable progressTick = new Runnable() {
        @Override
        public void run() {
            updateVideoProgress();
            handler.postDelayed(this, 400);
        }
    };

    /** Исходные отступы панелей (к ним прибавляются системные insets). */
    private int topBarPadTop, topBarPadSide, bottomBarPadBottom, bottomBarPadSide;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        com.premiumlab.galleryx.util.Theming.apply(this);
        if (Prefs.flagSecure()) {
            getWindow().addFlags(WindowManager.LayoutParams.FLAG_SECURE);
        }
        // Рисуем под системными панелями; отступы для своих панелей берём из insets
        WindowCompat.setDecorFitsSystemWindows(getWindow(), false);
        setContentView(R.layout.activity_photo_viewer);

        // Открытие СНАРУЖИ (проводник, мессенджер, «Открыть с помощью»,
        // галерея по умолчанию): ACTION_VIEW / REVIEW с Uri файла. Строим
        // список из файлов той же папки — можно листать, как обычно.
        java.util.ArrayList<String> extPaths = null;
        int extIndex = 0;
        String inAction = getIntent().getAction();
        if ((Intent.ACTION_VIEW.equals(inAction)
                || "android.provider.action.REVIEW".equals(inAction)
                || "com.android.camera.action.REVIEW".equals(inAction))
                && getIntent().getData() != null) {
            String opened = resolveExternalPath(getIntent().getData());
            if (opened == null) {
                Toasts.makeText(this, R.string.no_app_to_open, Toast.LENGTH_SHORT).show();
                finish();
                return;
            }
            // Маскировка «закрыта» — скрытое не светим даже по внешней ссылке
            if (MaskGuard.hidden() && MaskGuard.isHiddenPath(opened)) {
                finish();
                return;
            }
            extPaths = new ArrayList<>();
            File parent = new File(opened).getParentFile();
            if (parent != null) {
                for (MediaItem sib : MediaEngine.listFolder(parent)) {
                    if (opened.equals(sib.path)) extIndex = extPaths.size();
                    extPaths.add(sib.path);
                }
            }
            if (extPaths.isEmpty()) extPaths.add(opened);
        }

        ArrayList<String> paths = extPaths != null ? extPaths
                : getIntent().getStringArrayListExtra("paths");
        if (paths == null && getIntent().getBooleanExtra("shared", false)) {
            paths = sharedPaths;
        }
        int index = extPaths != null ? extIndex : getIntent().getIntExtra("index", 0);
        mode = getIntent().getIntExtra("mode", MODE_NORMAL);
        if (paths == null || paths.isEmpty()) {
            finish();
            return;
        }

        root = findViewById(R.id.viewerRoot);
        pager = findViewById(R.id.pager);
        topBar = findViewById(R.id.viewerTopBar);
        viewerVideoTools = findViewById(R.id.viewerVideoTools);
        bottomBar = findViewById(R.id.viewerBottomBar);
        pillDate = findViewById(R.id.pillViewerDate);
        txtDay = findViewById(R.id.txtViewerDay);
        txtTime = findViewById(R.id.txtViewerTime);
        btnFav = findViewById(R.id.btnVwFavorite);
        btnShare = findViewById(R.id.btnVwShare);
        btnMore = findViewById(R.id.btnViewerMore);
        btnRestore = findViewById(R.id.btnVwRestore);
        btnDelete = findViewById(R.id.btnVwDelete);
        layoutVideoCtl = findViewById(R.id.layoutVideoCtl);
        btnVwPlay = findViewById(R.id.btnVwPlay);
        txtVwCur = findViewById(R.id.txtVwCur);
        txtVwDur = findViewById(R.id.txtVwDur);
        seekVw = findViewById(R.id.seekVw);
        applySliderStyle();

        topBarPadTop = topBar.getPaddingTop();
        topBarPadSide = topBar.getPaddingLeft();
        bottomBarPadBottom = bottomBar.getPaddingBottom();
        bottomBarPadSide = bottomBar.getPaddingLeft();
        applyInsets();

        // После пересоздания (поворот) координаты миниатюры уже неверны
        srcRect = savedInstanceState == null ? getIntent().getIntArrayExtra("src") : null;
        startIndex = Math.min(index, paths.size() - 1);

        findViewById(R.id.btnViewerBack).setOnClickListener(v -> close());
        btnMore.setOnClickListener(v -> showMenu());
        pillDate.setOnClickListener(v -> showInfo());
        for (int id : new int[]{R.id.btnViewerBack, R.id.btnViewerMore, R.id.btnVwFavorite,
                R.id.btnVwShare, R.id.btnVwEdit, R.id.btnVwRestore, R.id.btnVwDelete,
                R.id.btnVwInfo, R.id.btnVwPlay, R.id.btnVwMute}) {
            Press.attach(findViewById(id));
        }
        Press.attach(pillDate, 0.95f);
        getOnBackPressedDispatcher().addCallback(this, new OnBackPressedCallback(true) {
            @Override
            public void handleOnBackPressed() {
                close();
            }
        });

        adapter = new PhotoPagerAdapter(this, this::toggleBars, dragListener, videoListener);
        adapter.setRotateListener((view, degrees) -> rotateCurrentPhoto(view, degrees));
        setupVideoControls();
        if (mode == MODE_SAFE) {
            java.util.HashSet<String> videos = new java.util.HashSet<>();
            for (SafeStore.Entry e : SafeStore.get().entries()) {
                if (e.video) videos.add(e.path);
            }
            adapter.setVideoPaths(videos);
        } else if (mode == MODE_TRASH) {
            java.util.HashSet<String> videos = new java.util.HashSet<>();
            for (TrashStore.Entry e : TrashStore.get().entries()) {
                if (e.video) videos.add(e.path);
            }
            adapter.setVideoPaths(videos);
        }
        long heroSig = getIntent().getLongExtra("heroSig", Long.MIN_VALUE);
        int heroSize = getIntent().getIntExtra("heroSize", 0);
        if (heroSig != Long.MIN_VALUE && heroSize > 0) {
            adapter.setHero(paths.get(startIndex), heroSig, heroSize);
        }
        adapter.submit(paths);
        pager.setAdapter(adapter);
        pager.setCurrentItem(Math.min(index, paths.size() - 1), false);
        pager.setOffscreenPageLimit(1);
        pager.setPageTransformer(new androidx.viewpager2.widget.MarginPageTransformer(
                Math.round(16 * getResources().getDisplayMetrics().density)));
        lastPos = pager.getCurrentItem();
        pager.registerOnPageChangeCallback(new ViewPager2.OnPageChangeCallback() {
            @Override
            public void onPageSelected(int position) {
                // Сменился ли сам файл. После автоперехода удалённая страница
                // убирается из списка, номера страниц сдвигаются, и тот же
                // файл приходит сюда с позицией на единицу меньше — раньше
                // это принималось за «человек листнул влево», и направление
                // автоперехода переворачивалось после первого же удаления
                String pagePath = adapter != null ? adapter.getPath(position) : null;
                boolean samePage = pagePath != null && pagePath.equals(lastPagePath);
                if (pagePath != null) lastPagePath = pagePath;
                // Запоминаем, куда человек листает, — после действий с файлом
                // автопролистывание продолжит движение в ту же сторону
                if (!autoAdvancing && !samePage && lastPos >= 0 && position != lastPos) {
                    swipeDir = position > lastPos ? 1 : -1;
                }
                // Перелистнули на другой файл — временная скорость сброшена
                if (!samePage && position != lastPos) resetSpeed();
                lastPos = position;
                updateToolbar(position);
                // Видео на новой странице стартует само; предыдущее — останавливается
                pager.post(() -> attachPage(position));
                // Сетка под нами заранее прокручивается к текущему файлу — при закрытии
                // кадр «вернётся» именно в его ячейку
                ThumbLocator l = locator();
                if (l != null && adapter != null) {
                    try {
                        l.prepareThumb(adapter.getPath(position));
                    } catch (Exception ignored) {
                    }
                }
            }

            @Override
            public void onPageScrollStateChanged(int state) {
                lastScrollState = state;
                if (state == ViewPager2.SCROLL_STATE_IDLE) {
                    // Анимация пролистывания закончилась — теперь можно тихо
                    // убрать старую страницу из-под уже показанной новой
                    if (pendingRemovePath != null) finalizePendingRemoval();
                    else autoAdvancing = false;
                }
            }
        });
        pager.post(() -> attachPage(pager.getCurrentItem()));

        setupActions();
        updateToolbar(pager.getCurrentItem());
        if (srcRect != null && savedInstanceState == null) {
            prepareEnter();
            root.post(this::animateEnter);
        }
    }

    /** Панели не должны уходить под статус-бар и навигационную панель. */
    /**
     * Настройка «Ползунок плеера»: 0 — тонкая линия с круглой ручкой, 1 — толстая
     * капсула без ручки (как в GorillaGallery), 2 — тонкая капсула без ручки.
     * Дорожка строится программно, чтобы стиль менялся гарантированно.
     */
    private void applySliderStyle() {
        int style = Prefs.sliderStyle();
        float d = getResources().getDisplayMetrics().density;
        int h = Math.round((style == 1 ? 14f : style == 2 ? 6f : 3f) * d);
        android.graphics.drawable.GradientDrawable track = new android.graphics.drawable.GradientDrawable();
        track.setColor(0x4DFFFFFF);
        track.setCornerRadius(h);
        android.graphics.drawable.GradientDrawable fill = new android.graphics.drawable.GradientDrawable();
        fill.setColor(0xFFFFFFFF);
        fill.setCornerRadius(h);
        android.graphics.drawable.ClipDrawable clip = new android.graphics.drawable.ClipDrawable(
                fill, android.view.Gravity.START, android.graphics.drawable.ClipDrawable.HORIZONTAL);
        android.graphics.drawable.LayerDrawable layers = new android.graphics.drawable.LayerDrawable(
                new android.graphics.drawable.Drawable[]{track, clip});
        layers.setId(0, android.R.id.background);
        layers.setId(1, android.R.id.progress);
        seekVw.setProgressDrawable(layers);
        seekVw.setMinHeight(h);
        seekVw.setMaxHeight(h);
        seekVw.setSplitTrack(false);
        if (style == 0) {
            android.graphics.drawable.GradientDrawable thumb = new android.graphics.drawable.GradientDrawable();
            thumb.setShape(android.graphics.drawable.GradientDrawable.OVAL);
            thumb.setColor(0xFFFFFFFF);
            int ts = Math.round(14f * d);
            thumb.setSize(ts, ts);
            seekVw.setThumb(thumb);
            seekVw.setThumbOffset(0);
            seekVw.setPadding(Math.round(8f * d), 0, Math.round(8f * d), 0);
        } else {
            // Капсула без ручки: прозрачный thumb, дорожка от края до края
            android.graphics.drawable.GradientDrawable none = new android.graphics.drawable.GradientDrawable();
            none.setColor(0x00000000);
            none.setSize(1, 1);
            seekVw.setThumb(none);
            seekVw.setThumbOffset(0);
            seekVw.setPadding(0, 0, 0, 0);
        }
        seekVw.requestLayout();
    }

    private void applyInsets() {
        ViewCompat.setOnApplyWindowInsetsListener(root, (v, insets) -> {
            Insets sb = insets.getInsets(WindowInsetsCompat.Type.systemBars()
                    | WindowInsetsCompat.Type.displayCutout());
            // В ландшафте верхний отступ от системных панелей бывает
            // заметно больше, и кнопки «висели» далеко от статус-бара —
            // ограничиваем его разумной высотой
            int topInset = sb.top;
            boolean landscape = v.getWidth() > v.getHeight() && v.getHeight() > 0;
            if (landscape) {
                int cap = Math.round(28 * getResources().getDisplayMetrics().density);
                topInset = Math.min(topInset, cap);
            }
            topBar.setPadding(topBarPadSide + sb.left, topBarPadTop + topInset,
                    topBarPadSide + sb.right, topBar.getPaddingBottom());
            bottomBar.setPadding(bottomBarPadSide + sb.left, bottomBar.getPaddingTop(),
                    bottomBarPadSide + sb.right, bottomBarPadBottom + sb.bottom);
            return insets;
        });
        ViewCompat.requestApplyInsets(root);
    }

    private void setupActions() {
        if (mode == MODE_NORMAL) {
            btnRestore.setVisibility(View.GONE);
            btnFav.setOnClickListener(v -> {
                MediaItem it = current();
                if (it == null) return;
                boolean nowFav = FavStore.toggle(it.path);
                Toasts.makeText(this, nowFav ? R.string.fav_added : R.string.fav_removed,
                        Toast.LENGTH_SHORT).show();
                updateFavIcon(it.path);
            });
            btnShare.setOnClickListener(v -> {
                MediaItem it = current();
                if (it != null) Actions.share(this, Collections.singletonList(it));
            });
            btnDelete.setOnClickListener(v -> {
                MediaItem it = current();
                if (it == null) return;
                Actions.confirmTrash(this, Collections.singletonList(it),
                        this::afterCurrentRemoved);
            });
        } else {
            btnFav.setVisibility(View.GONE);
            btnShare.setOnClickListener(v -> {
                MediaItem it = current();
                if (it != null) Actions.share(this, Collections.singletonList(decorateName(it)));
            });
            btnRestore.setVisibility(View.VISIBLE);
            btnRestore.setOnClickListener(v -> restoreCurrent());
            btnDelete.setOnClickListener(v -> {
                MediaItem it = current();
                if (it == null) return;
                Actions.confirmDeleteForever(this, Collections.singletonList(decorateName(it)),
                        this::afterCurrentRemoved);
            });
        }
        findViewById(R.id.btnVwInfo).setOnClickListener(v -> showInfo());
        btnNote = findViewById(R.id.btnVwEdit);
        btnNote.setOnClickListener(v -> editCurrent());
    }

    // ---------- Видео на странице ----------

    private final PhotoPagerAdapter.VideoListener videoListener =
            new PhotoPagerAdapter.VideoListener() {
                @Override
                public void onVideoPrepared(PhotoPagerAdapter.VH page, int durationMs) {
                    if (page != currentPage) return;
                    seekVw.setMax(Math.max(1, durationMs));
                    txtVwDur.setText(Fmt.duration(durationMs));
                    updateVideoProgress();
                    // Без автоповорота: экран поворачивается только вручную,
                    // кнопкой поворота в панели плеера
                }

                @Override
                public void onSeeked(PhotoPagerAdapter.VH page) {
                    if (page != currentPage) return;
                    updateVideoProgress();
                }

                @Override
                public void onVideoPlayState(PhotoPagerAdapter.VH page, boolean playing) {
                    if (page != currentPage) return;
                    btnVwPlay.setImageResource(playing ? R.drawable.ic_pause : R.drawable.ic_play);
                    handler.removeCallbacks(progressTick);
                    if (playing) handler.post(progressTick);
                }

                @Override
                public void onVideoCompleted(PhotoPagerAdapter.VH page) {
                    if (page != currentPage) return;
                    btnVwPlay.setImageResource(R.drawable.ic_play);
                    handler.removeCallbacks(progressTick);
                    updateVideoProgress();
                    setBarsVisible(true);
                }
            };

    private PhotoPagerAdapter.VH pageAt(int position) {
        View child = pager.getChildAt(0);
        if (!(child instanceof RecyclerView)) return null;
        RecyclerView.ViewHolder vh = ((RecyclerView) child)
                .findViewHolderForAdapterPosition(position);
        return vh instanceof PhotoPagerAdapter.VH ? (PhotoPagerAdapter.VH) vh : null;
    }

    /** Делает страницу текущей: останавливает прежнее видео, запускает новое. */
    private void attachPage(int position) {
        PhotoPagerAdapter.VH page = pageAt(position);
        if (page == null) {
            // Страница ещё не разложена — пробуем на следующем кадре
            pager.postDelayed(() -> {
                if (pager.getCurrentItem() == position && pageAt(position) != null) {
                    attachPage(position);
                }
            }, 50);
            return;
        }
        if (currentPage != null && currentPage != page) currentPage.stopVideo();
        currentPage = page;
        handler.removeCallbacks(progressTick);
        boolean video = page.isVideoPage();
        layoutVideoCtl.setVisibility(video ? View.VISIBLE : View.GONE);
        // Ряд «скорость/поворот» в верхней панели живёт только на видео
        if (viewerVideoTools != null) {
            viewerVideoTools.setVisibility(video ? View.VISIBLE : View.GONE);
        }
        if (!video) {
            // Ушли с видео на фото — экран обратно в портретную ориентацию
            if (getRequestedOrientation()
                    != android.content.pm.ActivityInfo.SCREEN_ORIENTATION_PORTRAIT) {
                setRequestedOrientation(
                        android.content.pm.ActivityInfo.SCREEN_ORIENTATION_PORTRAIT);
            }
        }
        if (video) {
            seekVw.setProgress(0);
            txtVwCur.setText(Fmt.duration(0));
            txtVwDur.setText("");
            btnVwPlay.setImageResource(R.drawable.ic_pause);
            page.setOverlayVisible(barsVisible);
            page.startVideo();
        }
    }

    private void setupVideoControls() {
        btnVwPlay.setOnClickListener(v -> {
            if (currentPage != null) currentPage.togglePlay();
        });
        btnVwMute = findViewById(R.id.btnVwMute);
        updateMuteIcon();
        btnVwMute.setOnClickListener(v -> {
            // Видео стартует без звука; одно нажатие включает звук, второе — выключает.
            // Выбор действует на все видео, пока открыт просмотрщик.
            adapter.setMuted(!adapter.isMuted());
            if (currentPage != null) currentPage.video.setMuted(adapter.isMuted());
            updateMuteIcon();
        });
        btnVwSpeed = findViewById(R.id.btnVwSpeed);
        updateSpeedButton();
        btnVwSpeed.setOnClickListener(v -> {
            // Скорость воспроизведения, как в YouTube: лист с вариантами,
            // выбор запоминается для всех видео
            com.premiumlab.galleryx.ui.sheet.SpeedSheet sheet =
                    com.premiumlab.galleryx.ui.sheet.SpeedSheet.newInstance(adapter.getSpeed());
            sheet.setListener(speed -> {
                adapter.setSpeed(speed);
                if (currentPage != null && currentPage.isVideoPage()) {
                    currentPage.video.setSpeed(speed);
                }
                updateSpeedButton();
            });
            sheet.show(getSupportFragmentManager(), "speed");
        });
        btnVwRotate = findViewById(R.id.btnVwRotate);
        btnVwRotate.setOnClickListener(v -> {
            // Ручной поворот экрана для видео: горизонтально ↔ вертикально
            int cur = getRequestedOrientation();
            boolean landscape = cur
                    == android.content.pm.ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
                    || cur == android.content.pm.ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE
                    || cur == android.content.pm.ActivityInfo.SCREEN_ORIENTATION_REVERSE_LANDSCAPE;
            setRequestedOrientation(landscape
                    ? android.content.pm.ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
                    : android.content.pm.ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE);
        });
        seekVw.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override
            public void onProgressChanged(SeekBar sb, int progress, boolean fromUser) {
                if (fromUser) txtVwCur.setText(Fmt.duration(progress));
            }

            @Override
            public void onStartTrackingTouch(SeekBar sb) {
                seeking = true;
            }

            @Override
            public void onStopTrackingTouch(SeekBar sb) {
                seeking = false;
                if (currentPage != null) currentPage.video.seekTo(sb.getProgress());
            }
        });
    }

    private void updateMuteIcon() {
        btnVwMute.setImageResource(adapter.isMuted()
                ? R.drawable.ic_volume_off : R.drawable.ic_volume_on);
        btnVwMute.setAlpha(adapter.isMuted() ? 0.75f : 1f);
    }

    private void updateVideoProgress() {
        if (currentPage == null || !currentPage.isVideoPage() || seeking) return;
        int pos = currentPage.video.getPosition();
        int dur = currentPage.video.getDuration();
        if (dur > 0 && seekVw.getMax() != dur) seekVw.setMax(dur);
        seekVw.setProgress(pos);
        txtVwCur.setText(Fmt.duration(pos));
        if (dur > 0) txtVwDur.setText(Fmt.duration(dur));
    }

    @Override
    protected void onPause() {
        super.onPause();
        handler.removeCallbacks(progressTick);
        if (inPip()) return; // в «картинке в картинке» видео продолжает играть
        if (currentPage != null && currentPage.isVideoPage()) {
            resumePlayback = currentPage.video.isPlaying();
            currentPage.pauseVideo();
        }
    }

    // ---------- Картинка в картинке (настройка «Картинка в картинке») ----------

    private boolean inPip() {
        return Build.VERSION.SDK_INT >= 26 && isInPictureInPictureMode();
    }

    @Override
    protected void onUserLeaveHint() {
        super.onUserLeaveHint();
        maybeEnterPip();
    }

    private void maybeEnterPip() {
        if (Build.VERSION.SDK_INT < 26 || !Prefs.pip() || dismissing) return;
        if (currentPage == null || !currentPage.isVideoPage() || !currentPage.video.isPlaying()) return;
        if (!getPackageManager().hasSystemFeature(android.content.pm.PackageManager.FEATURE_PICTURE_IN_PICTURE)) return;
        try {
            int w = Math.max(1, currentPage.video.getVideoWidth());
            int h = Math.max(1, currentPage.video.getVideoHeight());
            float ar = w / (float) h;
            // Допустимый диапазон PiP — от 1:2.39 до 2.39:1
            if (ar < 0.42f) { w = 42; h = 100; } else if (ar > 2.39f) { w = 239; h = 100; }
            android.app.PictureInPictureParams params = new android.app.PictureInPictureParams.Builder()
                    .setAspectRatio(new android.util.Rational(w, h))
                    .build();
            enterPictureInPictureMode(params);
        } catch (Exception ignored) {
        }
    }

    @Override
    public void onPictureInPictureModeChanged(boolean isInPip, android.content.res.Configuration cfg) {
        super.onPictureInPictureModeChanged(isInPip, cfg);
        topBar.setVisibility(isInPip ? View.GONE : View.VISIBLE);
        bottomBar.setVisibility(isInPip ? View.GONE : View.VISIBLE);
        if (currentPage != null) currentPage.setOverlayVisible(!isInPip && barsVisible);
        pager.setUserInputEnabled(!isInPip);
        root.setBackgroundColor(isInPip ? 0xFF000000 : scrim(1f));
        if (!isInPip && currentPage != null && currentPage.isVideoPage()
                && !currentPage.video.isPlaying()) {
            // Пользователь закрыл окно PiP — просмотрщик тоже закрываем
            if (isFinishing()) return;
            finish();
        }
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (resumePlayback && currentPage != null) {
            resumePlayback = false;
            currentPage.resumeVideo();
        }
    }

    @Override
    protected void onDestroy() {
        handler.removeCallbacks(progressTick);
        if (currentPage != null) currentPage.stopVideo();
        // Закрыт окончательно (не поворот) — большой список больше не нужен
        if (isFinishing()) sharedPaths = null;
        super.onDestroy();
    }

    private void showInfo() {
        MediaItem it = current();
        if (it != null) Actions.showInfo(this, decorateName(it));
    }

    /** Меню «ещё»: перемещение, копирование, сейф, открыть в другом приложении и т. д. */
    private void showMenu() {
        MediaItem it = current();
        if (it == null) return;
        it = decorateName(it);
        boolean video = it.isVideo || adapter.isVideo(it.path);
        OptionsSheet sheet = new OptionsSheet().setHeader(it.name,
                Fmt.dateTimeShort(it.dateModified) + " · " + Fmt.size(it.size));
        if (mode == MODE_NORMAL) {
            sheet.add(new OptionsSheet.Item(R.drawable.ic_move,
                    getString(R.string.action_move), getString(R.string.viewer_move_sub),
                    this::moveCurrent));
            sheet.add(new OptionsSheet.Item(R.drawable.ic_copy,
                    getString(R.string.action_copy), getString(R.string.viewer_copy_sub),
                    this::copyCurrent));
            // Пока галерея «закрыта» маскировкой — сейф не показываем
            if (!MaskGuard.hidden()) {
                sheet.add(new OptionsSheet.Item(R.drawable.ic_shield,
                        getString(R.string.action_safe), getString(R.string.viewer_safe_sub),
                        () -> {
                            MediaItem cur = current();
                            if (cur == null) return;
                            Actions.confirmSafe(this, Collections.singletonList(cur),
                                    this::afterCurrentRemoved);
                        }));
            }
            sheet.add(new OptionsSheet.Item(R.drawable.ic_note,
                    getString(R.string.note_title), getString(R.string.viewer_note_sub),
                    this::showNote));
        }
        if (mode == MODE_SAFE) {
            // В сейфе — то же меню, что и обычно, но без пункта «В сейф»:
            // перемещение/копирование идут между папками сейфа того же вида
            sheet.add(new OptionsSheet.Item(R.drawable.ic_move,
                    getString(R.string.action_move), getString(R.string.viewer_move_sub),
                    () -> safeCopyMove(false)));
            sheet.add(new OptionsSheet.Item(R.drawable.ic_copy,
                    getString(R.string.action_copy), getString(R.string.viewer_copy_sub),
                    () -> safeCopyMove(true)));
            sheet.add(new OptionsSheet.Item(R.drawable.ic_note,
                    getString(R.string.note_title), getString(R.string.viewer_note_sub),
                    this::showNote));
        }
        sheet.add(new OptionsSheet.Item(R.drawable.ic_open_in,
                getString(R.string.viewer_open_in), getString(R.string.viewer_open_in_sub),
                () -> openInOtherApp(false)));
        if (!video) {
            sheet.add(new OptionsSheet.Item(R.drawable.ic_wallpaper,
                    getString(R.string.viewer_wallpaper),
                    getString(R.string.viewer_wallpaper_sub),
                    this::setAsWallpaper));
        }
        if (!video && mode == MODE_NORMAL) {
            // Копия с новым размером/сжатием сохраняется в Pictures/GalleryResizer
            sheet.add(new OptionsSheet.Item(R.drawable.ic_resize,
                    getString(R.string.resizer_title), getString(R.string.viewer_resize_sub),
                    () -> {
                        MediaItem cur = current();
                        if (cur == null) return;
                        Intent ri = new Intent(this,
                                com.premiumlab.galleryx.resizer.ResizerActivity.class);
                        ri.putExtra(com.premiumlab.galleryx.resizer.ResizerActivity.EXTRA_PATH,
                                cur.path);
                        startActivity(ri);
                    }));
        }
        sheet.add(new OptionsSheet.Item(R.drawable.ic_info,
                getString(R.string.action_info), getString(R.string.viewer_info_sub),
                this::showInfo));
        if (mode == MODE_NORMAL) {
            sheet.add(new OptionsSheet.Item(R.drawable.ic_delete,
                    getString(R.string.delete), null, true, () -> {
                        MediaItem cur = current();
                        if (cur == null) return;
                        Actions.confirmTrash(this, Collections.singletonList(cur),
                                this::afterCurrentRemoved);
                    }));
        } else {
            // Сейф и корзина: удаление навсегда
            sheet.add(new OptionsSheet.Item(R.drawable.ic_delete_forever,
                    getString(R.string.delete), null, true, () -> {
                        MediaItem cur = current();
                        if (cur == null) return;
                        Actions.confirmDeleteForever(this, Collections.singletonList(
                                decorateName(cur)), this::afterCurrentRemoved);
                    }));
        }
        sheet.show(getSupportFragmentManager(), "options");
    }

    /** Сразу открывает системный установщик обоев (кадрирование + выбор экрана). */
    private void setAsWallpaper() {
        MediaItem it = current();
        if (it == null) return;
        android.net.Uri uri;
        try {
            uri = FileProvider.getUriForFile(this, getPackageName() + ".fileprovider",
                    new File(it.path));
        } catch (Exception e) {
            Toasts.makeText(this, R.string.no_app_to_open, Toast.LENGTH_SHORT).show();
            return;
        }
        // 1. Штатный установщик обоев текущего лаунчера/системы
        try {
            android.app.WallpaperManager wm = android.app.WallpaperManager.getInstance(this);
            Intent intent = wm.getCropAndSetWallpaperIntent(uri);
            intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
            startActivity(intent);
            return;
        } catch (Exception ignored) {
        }
        // 2. Запасной путь: ACTION_ATTACH_DATA без выбора приложений (системный компонент)
        try {
            Intent intent = new Intent(Intent.ACTION_ATTACH_DATA);
            intent.setDataAndType(uri, "image/*");
            intent.putExtra("mimeType", "image/*");
            intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
            startActivity(intent);
        } catch (Exception e) {
            Toasts.makeText(this, R.string.no_app_to_open, Toast.LENGTH_SHORT).show();
        }
    }

    /** ACTION_VIEW (открыть в другом приложении) или ACTION_ATTACH_DATA (обои). */
    private void openInOtherApp(boolean attach) {
        MediaItem it = current();
        if (it == null) return;
        try {
            android.net.Uri uri = FileProvider.getUriForFile(this,
                    getPackageName() + ".fileprovider", new File(it.path));
            boolean video = it.isVideo || adapter.isVideo(it.path);
            String mime = it.mime != null && !it.mime.isEmpty()
                    ? it.mime : (video ? "video/*" : "image/*");
            Intent intent = new Intent(attach ? Intent.ACTION_ATTACH_DATA : Intent.ACTION_VIEW);
            intent.setDataAndType(uri, mime);
            if (attach) intent.putExtra("mimeType", mime);
            intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
            startActivity(Intent.createChooser(intent, getString(
                    attach ? R.string.viewer_wallpaper : R.string.viewer_open_in)));
        } catch (Exception e) {
            Toasts.makeText(this, R.string.no_app_to_open, Toast.LENGTH_SHORT).show();
        }
    }

    /** Путь файла по внешнему Uri (content:// из MediaStore, file:// и т.п.). */
    private String resolveExternalPath(android.net.Uri uri) {
        try {
            if ("file".equals(uri.getScheme())) {
                String p = uri.getPath();
                return p != null && new File(p).exists() ? p : null;
            }
            try (android.database.Cursor c = getContentResolver().query(uri,
                    new String[]{android.provider.MediaStore.MediaColumns.DATA},
                    null, null, null)) {
                if (c != null && c.moveToFirst()) {
                    int col = c.getColumnIndex(
                            android.provider.MediaStore.MediaColumns.DATA);
                    String p = col >= 0 ? c.getString(col) : null;
                    if (p != null && new File(p).exists()) return p;
                }
            }
        } catch (Exception ignored) {
        }
        return null;
    }

    private MediaItem current() {
        String path = adapter.getPath(pager.getCurrentItem());
        if (path == null) return null;
        return MediaItem.fromFile(new File(path));
    }


    /**
     * Текущий файл исчез из просмотра (удалён, восстановлен,
     * отправлен в сейф): КРАСИВО пролистываем к соседнему файлу — в ту
     * сторону, куда человек листал до этого (при первом входе — вперёд),
     * а если в той стороне файлов нет — в обратную. Старая страница
     * убирается только после анимации, поэтому переход неотличим от
     * ручного свайпа. Закрываемся, только когда файлов не осталось.
     */
    private void afterCurrentRemoved() {
        if (pendingRemovePath != null) return; // уже листаем после прошлого действия
        int pos = pager.getCurrentItem();
        String path = adapter.getPath(pos);
        if (path == null) return;
        // Операцию могли отменить или она не удалась — файл ещё на месте
        if (new File(path).exists()) return;
        if (adapter.getItemCount() <= 1) {
            finish();
            return;
        }
        int target = neighborInSwipeDir(pos);
        pendingRemovePath = path;
        autoAdvancing = true;
        // Сначала плавный скролл (как ручной свайп)...
        pager.setCurrentItem(target, true);
        // ...а страницу уберём в onPageScrollStateChanged, когда анимация
        // встанет. Страховка, если событие покоя вдруг не придёт:
        pager.postDelayed(() -> {
            if (pendingRemovePath != null
                    && lastScrollState == ViewPager2.SCROLL_STATE_IDLE) {
                finalizePendingRemoval();
            }
        }, 800);
    }

    /** Убирает старую страницу после окончания анимации пролистывания. */
    private void finalizePendingRemoval() {
        String gone = pendingRemovePath;
        pendingRemovePath = null;
        autoAdvancing = false;
        if (gone == null) return;
        int idx = adapter.indexOf(gone);
        if (idx >= 0) adapter.removeAt(idx);
        // На экране теперь другой файл — временная скорость не наследуется
        resetSpeed();
        if (adapter.getItemCount() == 0) {
            finish();
            return;
        }
        int cur = pager.getCurrentItem();
        lastPos = cur;
        updateToolbar(cur);
        pager.post(() -> attachPage(cur));
    }

    /**
     * Соседняя страница в стороне листания пользователя; если там пусто —
     * сосед с другой стороны. Вызывающий код гарантирует count >= 2.
     */
    private int neighborInSwipeDir(int pos) {
        int count = adapter.getItemCount();
        if (swipeDir > 0) return pos < count - 1 ? pos + 1 : pos - 1;
        return pos > 0 ? pos - 1 : pos + 1;
    }

    /**
     * В сейфе/корзине файл лежит под служебным именем (.bin): подставляем
     * оригинальное имя, тип (фото/видео) и MIME — иначе «Свойства» не покажут
     * разрешение видео, а «Открыть в…» не поймёт, что это за файл.
     */
    private MediaItem decorateName(MediaItem it) {
        if (it == null) return null;
        if (mode == MODE_TRASH) {
            for (TrashStore.Entry e : TrashStore.get().entries()) {
                if (e.path.equals(it.path)) {
                    applyEntry(it, e.name, e.video, e.size);
                    break;
                }
            }
        } else if (mode == MODE_SAFE) {
            SafeStore.Entry e = safeEntry(it.path);
            if (e != null) applyEntry(it, e.name, e.video, e.size);
        }
        return it;
    }

    private static void applyEntry(MediaItem it, String name, boolean video, long size) {
        if (name != null && !name.isEmpty()) it.name = name;
        it.isVideo = video;
        if (it.mime == null || it.mime.isEmpty() || it.mime.endsWith("/*")
                || it.mime.equals("application/octet-stream")) {
            it.mime = video ? "video/*" : "image/*";
        }
        if (it.size <= 0 && size > 0) it.size = size;
    }

    @Nullable
    private SafeStore.Entry safeEntry(String path) {
        if (path == null) return null;
        for (SafeStore.Entry e : SafeStore.get().entries()) {
            if (path.equals(e.path)) return e;
        }
        return null;
    }

    private void updateToolbar(int position) {
        MediaItem it = current();
        if (it == null) return;
        it = decorateName(it);
        txtDay.setText(Fmt.dayOfWeek(it.date()));
        txtTime.setText(Fmt.dateTimeShort(it.date()));
        updateFavIcon(it.path);
        updateNoteIcon(it.path);
    }

    /** Карандаш в нижней панели; заметка открывается из меню «⋯». */
    private void updateNoteIcon(String path) {
        if (btnNote == null) return;
        btnNote.setImageTintList(android.content.res.ColorStateList.valueOf(
                getColor(R.color.white)));
    }

    /**
     * Редактирование текущего файла. Пока открывает системный/установленный редактор
     * (ACTION_EDIT) с правом записи; встроенный редактор появится в следующей фазе.
     */
    private static final int REQ_EDIT = 71;

    private void editCurrent() {
        MediaItem it = current();
        if (it == null) return;
        boolean video = it.isVideo || adapter.isVideo(it.path);
        if (mode != MODE_NORMAL) {
            // В сейфе и корзине файлы хранятся под служебными именами — сначала восстановить
            Toasts.makeText(this, R.string.editor_restore_first, Toast.LENGTH_SHORT).show();
            return;
        }
        Intent intent = new Intent(this, video
                ? com.premiumlab.galleryx.editor.VideoEditorActivity.class
                : com.premiumlab.galleryx.editor.EditorActivity.class);
        intent.putExtra(com.premiumlab.galleryx.editor.EditorActivity.EXTRA_PATH, it.path);
        if (currentPage != null && currentPage.isVideoPage()) {
            currentPage.pauseVideo();
            resumePlayback = false;
        }
        startActivityForResult(intent, REQ_EDIT);
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode != REQ_EDIT || resultCode != RESULT_OK || data == null) return;
        String out = data.getStringExtra(com.premiumlab.galleryx.editor.EditorActivity.RESULT_PATH);
        if (out == null) return;
        int pos = pager.getCurrentItem();
        String cur = adapter.getPath(pos);
        if (cur != null && cur.equals(out)) {
            // Перезаписали текущий файл — Glide подхватит по новой подписи (lastModified)
            if (currentPage != null && currentPage.isVideoPage()) currentPage.stopVideo();
            adapter.refreshAt(pos);
        } else {
            // Сохранили копию — показываем её сразу после оригинала
            adapter.insertAfter(pos, out);
            pager.setCurrentItem(pos + 1, true);
        }
        updateToolbar(pager.getCurrentItem());
    }

    /**
     * Жест «кручение двумя пальцами»: предпросмотр уже довернулся до
     * ближайших 90° — теперь поворачиваем сам файл и сохраняем.
     * Работает только в обычной галерее/папках: в сейфе и корзине файлы
     * служебные, жест там просто отскочит обратно (вернём false).
     */
    private boolean rotateCurrentPhoto(android.view.View view, int degrees) {
        if (mode != MODE_NORMAL) return false;
        MediaItem it = current();
        if (it == null || it.isVideo || adapter.isVideo(it.path)) return false;
        final String path = it.path;
        final int pos = pager.getCurrentItem();
        pager.setUserInputEnabled(false);
        new Thread(() -> {
            boolean ok = com.premiumlab.galleryx.util.ImageRotator.rotate(
                    getApplicationContext(), new File(path), degrees);
            runOnUiThread(() -> {
                pager.setUserInputEnabled(true);
                if (isFinishing() || isDestroyed()) return;
                if (ok) {
                    MediaEngine.invalidateAll();
                    Toasts.makeText(this, R.string.viewer_rotated, Toast.LENGTH_SHORT).show();
                    // Страница перезагрузится уже повёрнутой (подпись Glide —
                    // время изменения файла, оно обновилось)
                    adapter.refreshAt(pos);
                } else {
                    Toasts.makeText(this, R.string.error_generic, Toast.LENGTH_SHORT).show();
                    adapter.refreshAt(pos); // вернуть предпросмотр как было
                }
            });
        }, "rotate-photo").start();
        return true;
    }

    private void showNote() {
        MediaItem it = current();
        if (it == null) return;
        String path = it.path;
        NoteSheet sheet = NoteSheet.newInstance(path, decorateName(it).name);
        sheet.setListener((p, hasNote) -> {
            MediaItem cur = current();
            if (cur != null && cur.path.equals(p)) updateNoteIcon(p);
        });
        sheet.show(getSupportFragmentManager(), "note");
    }

    private void updateFavIcon(String path) {
        if (mode != MODE_NORMAL) return;
        boolean fav = FavStore.isFav(path);
        btnFav.setImageResource(fav ? R.drawable.ic_heart : R.drawable.ic_heart_outline);
        btnFav.setColorFilter(fav ? 0xFFFF2D55 : 0xFFFFFFFF);
    }

    private void moveCurrent() {
        MediaItem it = current();
        if (it == null) return;
        DestSheet sheet = DestSheet.newInstance(false, it.folderPath);
        sheet.setListener(dir -> runCopyMove(Collections.singletonList(current()), dir));
        sheet.show(getSupportFragmentManager(), "dest");
    }

    private void copyCurrent() {
        MediaItem it = current();
        if (it == null) return;
        DestSheet sheet = DestSheet.newInstance(true, it.folderPath);
        sheet.setListener(dir -> {
            MediaItem cur = current();
            if (cur == null) return;
            FileOp op = new FileOp(this);
            OpProgressDialog dlg = OpProgressDialog.show(this,
                    getString(R.string.op_copy), op::cancel);
            op.copy(Collections.singletonList(cur), dir, dlg, (ok, fail, cancelled) -> {
                dlg.dismiss();
                Toasts.makeText(this, Fmt.plural(this, R.plurals.result_copied, ok),
                        Toast.LENGTH_SHORT).show();
                // Копирование: остаёмся на этом файле, никуда не листаем
            });
        });
        sheet.show(getSupportFragmentManager(), "dest");
    }

    private void runCopyMove(List<MediaItem> items, File dir) {
        if (items == null || items.isEmpty()) return;
        // Куда ляжет файл (FileOp кладёт через Fmt.uniqueFile) — чтобы после
        // перемещения остаться на ТОМ ЖЕ файле, уже с нового места
        final String oldPath = items.get(0).path;
        final File expected = Fmt.uniqueFile(dir, items.get(0).name);
        FileOp op = new FileOp(this);
        OpProgressDialog dlg = OpProgressDialog.show(this,
                getString(R.string.op_move), op::cancel);
        op.move(items, dir, dlg, (ok, fail, cancelled) -> {
            dlg.dismiss();
            Toasts.makeText(this, Fmt.plural(this, R.plurals.result_moved, ok),
                    Toast.LENGTH_SHORT).show();
            // Остаёмся на том же самом файле: никуда не листаем и не
            // закрываемся — просто страница теперь показывает файл
            // с его нового места в другой папке
            if (ok > 0 && expected.isFile()) {
                int idx = adapter.indexOf(oldPath);
                if (idx >= 0) {
                    adapter.replacePath(idx, expected.getAbsolutePath());
                    updateToolbar(pager.getCurrentItem());
                }
            }
        });
    }

    private void restoreCurrent() {
        String path = adapter.getPath(pager.getCurrentItem());
        if (path == null) return;
        FileOp op = new FileOp(this);
        OpProgressDialog dlg = OpProgressDialog.show(this,
                getString(R.string.op_restore), op::cancel);
        if (mode == MODE_TRASH) {
            List<TrashStore.Entry> entries = new ArrayList<>();
            for (TrashStore.Entry e : TrashStore.get().entries()) {
                if (e.path.equals(path)) entries.add(e);
            }
            op.restoreTrash(entries, dlg, (ok, fail, cancelled) -> {
                dlg.dismiss();
                Toasts.makeText(this, Fmt.plural(this, R.plurals.result_restored, ok),
                        Toast.LENGTH_SHORT).show();
                afterCurrentRemoved();
            });
        } else {
            List<SafeStore.Entry> entries = new ArrayList<>();
            for (SafeStore.Entry e : SafeStore.get().entries()) {
                if (e.path.equals(path)) entries.add(e);
            }
            op.restoreSafe(entries, dlg, (ok, fail, cancelled) -> {
                dlg.dismiss();
                Toasts.makeText(this, Fmt.plural(this, R.plurals.result_restored, ok),
                        Toast.LENGTH_SHORT).show();
                afterCurrentRemoved();
            });
        }
    }

    /**
     * Копирование / перемещение текущего файла между папками сейфа (того же
     * вида). Папки сейфа виртуальные: перемещение мгновенное, копирование —
     * через FileOp.safeCopy с диалогом прогресса.
     */
    private void safeCopyMove(boolean copy) {
        String path = adapter.getPath(pager.getCurrentItem());
        SafeStore.Entry entry = safeEntry(path);
        if (entry == null) {
            Toasts.makeText(this, R.string.error_generic, Toast.LENGTH_SHORT).show();
            return;
        }
        SafeDialogs.pickFolder(this, getSupportFragmentManager(), copy, entry.video, entry.folder,
                key -> {
                    if (isFinishing() || isDestroyed()) return;
                    if (copy) {
                        FileOp op = new FileOp(this);
                        OpProgressDialog dlg = OpProgressDialog.show(this,
                                getString(R.string.op_copy), op::cancel);
                        op.safeCopy(Collections.singletonList(entry), key, dlg,
                                (ok, fail, cancelled) -> {
                                    dlg.dismiss();
                                    Toasts.makeText(this, cancelled
                                            ? getString(R.string.op_cancelled)
                                            : Fmt.plural(this, R.plurals.result_copied, ok),
                                            Toast.LENGTH_SHORT).show();
                                    // Копирование в сейфе: остаёмся на файле
                                });
                    } else {
                        int n = SafeStore.get().moveToFolder(
                                Collections.singletonList(entry.path), key);
                        Toasts.makeText(this, Fmt.plural(this, R.plurals.result_moved, n),
                                Toast.LENGTH_SHORT).show();
                        // Перемещение между папками сейфа: файл физически
                        // не двигается (папки виртуальные) — остаёмся на
                        // том же самом файле, ничего не листаем
                    }
                });
    }

    // ---------- Панели ----------

    private void toggleBars() {
        setBarsVisible(!barsVisible);
    }

    /** Панели выезжают/уезжают за край экрана с растворением (150 мс). */
    private void setBarsVisible(boolean visible) {
        if (barsVisible == visible) return;
        barsVisible = visible;
        if (currentPage != null) currentPage.setOverlayVisible(visible);
        slideBar(topBar, visible, -1);
        slideBar(bottomBar, visible, 1);
        applySystemBars();
    }

    private void slideBar(View bar, boolean visible, int direction) {
        bar.animate().cancel();
        float offset = direction * bar.getHeight() * 0.5f;
        if (offset == 0f) offset = direction * 48f * getResources().getDisplayMetrics().density;
        if (visible) {
            bar.setVisibility(View.VISIBLE);
            if (bar.getAlpha() >= 0.99f) bar.setTranslationY(offset);
        }
        bar.animate()
                .setStartDelay(0)
                .alpha(visible ? 1f : 0f)
                .translationY(visible ? 0f : offset)
                .setDuration(visible ? 180 : 150)
                .setInterpolator(EASE)
                .setListener(new AnimatorListenerAdapter() {
                    @Override
                    public void onAnimationEnd(Animator a) {
                        bar.setVisibility(barsVisible ? View.VISIBLE : View.GONE);
                    }
                })
                .start();
    }

    private void applySystemBars() {
        WindowInsetsControllerCompat ic = WindowCompat.getInsetsController(
                getWindow(), getWindow().getDecorView());
        if (ic == null) return;
        ic.setSystemBarsBehavior(
                WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE);
        if (barsVisible) {
            ic.show(WindowInsetsCompat.Type.systemBars());
            ic.setAppearanceLightStatusBars(false);
            ic.setAppearanceLightNavigationBars(false);
        } else {
            ic.hide(WindowInsetsCompat.Type.systemBars());
        }
    }

    // ---------- Открытие из миниатюры ----------

    /** Стартовое состояние: страница сжата до миниатюры, фон прозрачный, панели скрыты. */
    private void prepareEnter() {
        root.setBackgroundColor(scrim(0f));
        topBar.setAlpha(0f);
        bottomBar.setAlpha(0f);
        pager.setAlpha(0f);
    }

    private void animateEnter() {
        if (srcRect == null || root.getWidth() == 0) {
            finishEnter();
            topBar.setAlpha(1f);
            bottomBar.setAlpha(1f);
            return;
        }
        enterAttempts = 0;
        tryMorphEnter();
    }

    /**
     * Как в GorillaGallery: кадр «вырастает» из квадратной миниатюры — сначала виден
     * только центральный квадрат картинки со скруглёнными углами (ровно то, что было в
     * ячейке), и он плавно раскрывается в полный кадр. Ждём первую отрисовку картинки
     * (несколько кадров), иначе — обычное увеличение страницы.
     */
    private void tryMorphEnter() {
        if (dismissing || isFinishing()) return;
        PhotoPagerAdapter.VH page = pageAt(pager.getCurrentItem());
        Morph m = page == null ? null : morphFor(page.page, page.zoom, srcRect, true);
        if (m != null) {
            morphEnter(page.page, page.zoom, m);
            return;
        }
        if (++enterAttempts < 14) {
            root.postOnAnimation(this::tryMorphEnter);
            return;
        }
        plainEnter();
    }

    private void morphEnter(View page, ZoomableImageView zoom, Morph m) {
        final float w = page.getWidth(), h = page.getHeight();
        page.setPivotX(w / 2f);
        page.setPivotY(h / 2f);
        page.setScaleX(m.scale);
        page.setScaleY(m.scale);
        page.setTranslationX(m.tx);
        page.setTranslationY(m.ty);
        zoom.setClipRect(m.clip, m.radius);
        pager.setAlpha(1f);

        final RectF cur = new RectF();
        android.animation.ValueAnimator an = android.animation.ValueAnimator.ofFloat(0f, 1f);
        an.setDuration(400);
        an.setInterpolator(EMPHASIZED);
        an.addUpdateListener(a -> {
            float v = (float) a.getAnimatedValue();
            float sc = m.scale + (1f - m.scale) * v;
            page.setScaleX(sc);
            page.setScaleY(sc);
            page.setTranslationX(m.tx * (1f - v));
            page.setTranslationY(m.ty * (1f - v));
            cur.set(m.clip.left * (1f - v), m.clip.top * (1f - v),
                    m.clip.right + (w - m.clip.right) * v, m.clip.bottom + (h - m.clip.bottom) * v);
            zoom.setClipRect(cur, m.radius * (1f - v));
            root.setBackgroundColor(scrim(Math.min(1f, v * 1.25f)));
        });
        an.addListener(new AnimatorListenerAdapter() {
            @Override
            public void onAnimationEnd(Animator a) {
                zoom.setClipRect(null, 0f);
                page.setScaleX(1f);
                page.setScaleY(1f);
                page.setTranslationX(0f);
                page.setTranslationY(0f);
                finishEnter();
            }
        });
        an.start();
        enterBars();
    }

    /** Старое поведение: вся страница увеличивается из ячейки (если кадр ещё не готов). */
    private void plainEnter() {
        if (root.getWidth() == 0) {
            finishEnter();
            topBar.setAlpha(1f);
            bottomBar.setAlpha(1f);
            return;
        }
        float scale = Math.max(0.05f, Math.min(1f, (float) srcRect[2] / root.getWidth()));
        int[] loc = new int[2];
        root.getLocationOnScreen(loc);
        float cx = srcRect[0] + srcRect[2] / 2f - loc[0] - root.getWidth() / 2f;
        float cy = srcRect[1] + srcRect[3] / 2f - loc[1] - root.getHeight() / 2f;
        pager.setPivotX(root.getWidth() / 2f);
        pager.setPivotY(root.getHeight() / 2f);
        pager.setScaleX(scale);
        pager.setScaleY(scale);
        pager.setTranslationX(cx);
        pager.setTranslationY(cy);
        pager.setAlpha(1f);

        android.animation.ValueAnimator bg = android.animation.ValueAnimator.ofFloat(0f, 1f);
        bg.setDuration(320);
        bg.setInterpolator(EMPHASIZED);
        bg.addUpdateListener(a -> root.setBackgroundColor(scrim((float) a.getAnimatedValue())));
        bg.start();
        pager.animate()
                .scaleX(1f).scaleY(1f)
                .translationX(0f).translationY(0f)
                .setDuration(360)
                .setInterpolator(EMPHASIZED)
                .withEndAction(this::finishEnter)
                .start();
        enterBars();
    }

    private void enterBars() {
        float d = getResources().getDisplayMetrics().density;
        topBar.setTranslationY(-24f * d);
        bottomBar.setTranslationY(24f * d);
        topBar.animate().alpha(1f).translationY(0f).setStartDelay(140).setDuration(240)
                .setInterpolator(EASE).start();
        bottomBar.animate().alpha(1f).translationY(0f).setStartDelay(140).setDuration(240)
                .setInterpolator(EASE).start();
    }

    // ---------- Геометрия «перетекания» миниатюра ↔ кадр ----------

    private static final class Morph {
        final float scale, tx, ty;
        final RectF clip;
        final float radius;

        Morph(float scale, float tx, float ty, RectF clip, float radius) {
            this.scale = scale;
            this.tx = tx;
            this.ty = ty;
            this.clip = clip;
            this.radius = radius;
        }
    }

    /**
     * Считает состояние страницы, при котором виден ровно центральный квадрат картинки
     * размером с миниатюру и на её месте. {@code needImage} — требовать известный кадр
     * картинки (при открытии), иначе берём всю страницу.
     */
    private Morph morphFor(View page, ZoomableImageView zoom, int[] thumb, boolean needImage) {
        if (thumb == null || thumb[2] <= 0 || thumb[3] <= 0 || root.getWidth() == 0
                || page == null || page.getWidth() == 0) {
            return null;
        }
        float w = page.getWidth(), h = page.getHeight();
        RectF r = new RectF();
        boolean has = zoom != null && zoom.displayedRect(r) && r.width() > 1f && r.height() > 1f;
        if (!has) {
            if (needImage) return null;
            r.set(0f, 0f, w, h);
        }
        float m = Math.min(r.width(), r.height());
        float cx = r.centerX(), cy = r.centerY();
        RectF clip = new RectF(cx - m / 2f, cy - m / 2f, cx + m / 2f, cy + m / 2f);
        float side = Math.min(thumb[2], thumb[3]);
        float k = Math.max(0.02f, Math.min(1.5f, side / m));
        int[] loc = new int[2];
        root.getLocationOnScreen(loc);
        float thumbCx = thumb[0] + thumb[2] / 2f - loc[0];
        float thumbCy = thumb[1] + thumb[3] / 2f - loc[1];
        float px = w / 2f, py = h / 2f;
        float tx = thumbCx - px - k * (cx - px);
        float ty = thumbCy - py - k * (cy - py);
        // Ячейки сетки квадратные — кадр при открытии/закрытии тоже без скругления
        float radius = 0f;
        return new Morph(k, tx, ty, clip, radius);
    }

    private ZoomableImageView zoomOf(View page) {
        if (page instanceof ZoomableImageView) return (ZoomableImageView) page;
        if (page instanceof ViewGroup) {
            ViewGroup g = (ViewGroup) page;
            for (int i = 0; i < g.getChildCount(); i++) {
                if (g.getChildAt(i) instanceof ZoomableImageView) {
                    return (ZoomableImageView) g.getChildAt(i);
                }
            }
        }
        return null;
    }

    private void finishEnter() {
        topBar.animate().setStartDelay(0);
        bottomBar.animate().setStartDelay(0);
        pager.setAlpha(1f);
        pager.setScaleX(1f);
        pager.setScaleY(1f);
        pager.setTranslationX(0f);
        pager.setTranslationY(0f);
        root.setBackgroundColor(scrim(1f));
    }

    /** Закрытие кнопкой «назад»/системным жестом: сжимаемся к миниатюре, если она известна. */
    private void close() {
        if (dismissing) return;
        // Из ландшафта сначала возвращаем ориентацию в портретную и даём
        // экрану повернуться ПОД просмотрщиком — иначе галерея под нами
        // секунду висела в горизонтальном положении и доворачивалась
        // на глазах у пользователя
        int o = getRequestedOrientation();
        boolean landscape = o == android.content.pm.ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE
                || o == android.content.pm.ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
                || o == android.content.pm.ActivityInfo.SCREEN_ORIENTATION_REVERSE_LANDSCAPE
                || o == android.content.pm.ActivityInfo.SCREEN_ORIENTATION_USER_LANDSCAPE;
        if (landscape && !returningToPortrait) {
            returningToPortrait = true;
            setRequestedOrientation(
                    android.content.pm.ActivityInfo.SCREEN_ORIENTATION_PORTRAIT);
            new android.os.Handler(android.os.Looper.getMainLooper())
                    .postDelayed(() -> {
                        returningToPortrait = false;
                        close();
                    }, 320);
            return;
        }
        PhotoPagerAdapter.VH page = currentPage != null ? currentPage
                : pageAt(pager.getCurrentItem());
        if (page != null && thumbRectFor(pager.getCurrentItem()) != null) {
            animateDismiss(page.page, 0f, 0f, 0f);
        } else if (page != null) {
            animateFadeOut(page.page);
        } else {
            finish();
        }
    }

    /** Мягкое закрытие без «улетания» — для страниц, у которых нет исходной миниатюры. */
    private void animateFadeOut(View view) {
        dismissing = true;
        long duration = 200;
        topBar.animate().alpha(0f).setDuration(duration).setListener(null).start();
        bottomBar.animate().alpha(0f).setDuration(duration).setListener(null).start();
        android.animation.ValueAnimator bg = android.animation.ValueAnimator.ofFloat(
                alphaOf(root), 0f);
        bg.setDuration(duration);
        bg.addUpdateListener(a -> root.setBackgroundColor(scrim((float) a.getAnimatedValue())));
        bg.start();
        view.animate().cancel();
        view.animate()
                .scaleX(0.85f).scaleY(0.85f)
                .alpha(0f)
                .setDuration(duration)
                .setInterpolator(EASE)
                .withEndAction(() -> {
                    finish();
                    overridePendingTransition(0, 0);
                })
                .start();
    }

    // ---------- Свайп вниз / вверх ----------

    private final ZoomableImageView.DragListener dragListener =
            new ZoomableImageView.DragListener() {
                @Override
                public void onDragStart(ZoomableImageView view) {
                    if (dismissing) return;
                    topBar.animate().cancel();
                    bottomBar.animate().cancel();
                }

                @Override
                public void onDrag(ZoomableImageView image, float dx, float dy) {
                    if (dismissing) return;
                    View view = pageOf(image);
                    if (dy > 0f) {
                        // Как в GorillaGallery: картинка идёт за пальцем 1:1, слегка
                        // сжимается (до 0.89), фон растворяется, панели прячутся сразу.
                        float progress = Math.min(1f, dy / (root.getHeight() * 0.48f));
                        float scale = 1f - 0.11f * progress;
                        view.setPivotX(view.getWidth() / 2f);
                        view.setPivotY(view.getHeight() / 2f);
                        view.setTranslationX(dx);
                        view.setTranslationY(dy);
                        view.setScaleX(scale);
                        view.setScaleY(scale);
                        root.setBackgroundColor(scrim(1f - progress));
                        // Панели плавно уезжают за край, как только начался свайп вниз
                        if (barsVisible && !barsDragHidden && dy > 2f) dragBars(true);
                    } else {
                        // Тянем вверх: небольшое сопротивление, намёк на «свойства»
                        view.setTranslationX(0f);
                        view.setTranslationY(dy * 0.35f);
                        view.setScaleX(1f);
                        view.setScaleY(1f);
                        root.setBackgroundColor(scrim(1f));
                    }
                }

                @Override
                public void onDragEnd(ZoomableImageView image, float dx, float dy,
                                      float velocityY) {
                    if (dismissing) return;
                    View view = pageOf(image);
                    float density = getResources().getDisplayMetrics().density;
                    float dismissDist = Math.max(72f * density, root.getHeight() * 0.1f);
                    float infoDist = 90f * density;
                    if (dy > dismissDist || (dy > 24f * density && velocityY > 1200f)) {
                        animateDismiss(view, dx, dy, velocityY);
                    } else {
                        boolean wantInfo = dy < -infoDist
                                || (dy < -24f * density && velocityY < -1800f);
                        animateBack(view);
                        if (wantInfo) showInfo();
                    }
                }
            };

    /** Тянем/анимируем всю страницу (фото + видео), а не только картинку. */
    private View pageOf(ZoomableImageView image) {
        return image.getParent() instanceof View ? (View) image.getParent() : image;
    }

    private int scrim(float alpha) {
        int a = Math.round(Math.max(0f, Math.min(1f, alpha)) * 255f);
        return (a << 24);
    }

    /** Пружинный возврат страницы на место. */
    private void animateBack(View view) {
        view.animate().cancel();
        view.animate()
                .translationX(0f).translationY(0f)
                .scaleX(1f).scaleY(1f)
                .setDuration(380)
                .setInterpolator(SPRING)
                .withEndAction(() -> root.setBackgroundColor(scrim(1f)))
                .start();
        if (view instanceof ViewGroup) {
            for (int i = 0; i < ((ViewGroup) view).getChildCount(); i++) {
                View c = ((ViewGroup) view).getChildAt(i);
                if (c instanceof ZoomableImageView) ((ZoomableImageView) c).setCornerRadius(0f);
            }
        } else if (view instanceof ZoomableImageView) {
            ((ZoomableImageView) view).setCornerRadius(0f);
        }
        root.setBackgroundColor(scrim(1f));
        if (barsVisible) dragBars(false);
    }

    /** Панели при свайпе вниз: уезжают за край с растворением и возвращаются при отмене. */
    private void dragBars(boolean hide) {
        if (barsDragHidden == hide) return;
        barsDragHidden = hide;
        float d = getResources().getDisplayMetrics().density;
        View[] bars = {topBar, bottomBar};
        for (int i = 0; i < bars.length; i++) {
            View bar = bars[i];
            int dir = i == 0 ? -1 : 1;
            float offset = bar.getHeight() > 0 ? dir * bar.getHeight() * 0.6f : dir * 56f * d;
            bar.animate().cancel();
            bar.animate()
                    .setStartDelay(0)
                    .alpha(hide ? 0f : 1f)
                    .translationY(hide ? offset : 0f)
                    .setDuration(hide ? 160 : 220)
                    .setInterpolator(EASE)
                    .setListener(null)
                    .start();
        }
    }

    /**
     * Закрытие жестом: если известна миниатюра, с которой открыли файл, и мы на той же
     * странице — картинка «возвращается» в неё; иначе улетает вниз.
     */
    private void animateDismiss(View view, float dx, float dy, float velocityY) {
        dismissing = true;
        long duration = 250;
        float targetX, targetY, targetScale, targetAlpha;
        int[] thumb = thumbRectFor(pager.getCurrentItem());
        ZoomableImageView zoom = zoomOf(view);
        Morph morph = thumb == null ? null : morphFor(view, zoom, thumb, false);
        boolean toThumb = morph != null;
        if (toThumb) {
            targetScale = morph.scale;
            targetX = morph.tx;
            targetY = morph.ty;
            targetAlpha = 1f;
            view.setPivotX(view.getWidth() / 2f);
            view.setPivotY(view.getHeight() / 2f);
            if (zoom != null) {
                // Кадр «сворачивается» в квадрат миниатюры со скруглёнными углами
                final float w = view.getWidth(), h = view.getHeight();
                final RectF cur = new RectF();
                android.animation.ValueAnimator clipAn =
                        android.animation.ValueAnimator.ofFloat(0f, 1f);
                clipAn.setDuration(duration);
                clipAn.setInterpolator(EASE);
                clipAn.addUpdateListener(a -> {
                    float v = (float) a.getAnimatedValue();
                    cur.set(morph.clip.left * v, morph.clip.top * v,
                            w + (morph.clip.right - w) * v, h + (morph.clip.bottom - h) * v);
                    zoom.setClipRect(cur, morph.radius * v);
                });
                clipAn.start();
            }
        } else {
            float h = root.getHeight();
            targetY = h + view.getHeight() * 0.5f;
            targetX = view.getTranslationX() + (dx > 0 ? 1 : -1) * 40f
                    * getResources().getDisplayMetrics().density;
            targetScale = 0.7f;
            targetAlpha = 0.5f;
            if (velocityY > 0f) {
                duration = Math.max(160, Math.min(320,
                        (long) ((targetY - view.getTranslationY()) / velocityY * 1000f)));
            }
        }
        topBar.animate().alpha(0f).setDuration(duration).setListener(null).start();
        bottomBar.animate().alpha(0f).setDuration(duration).setListener(null).start();
        android.animation.ValueAnimator bg = android.animation.ValueAnimator.ofFloat(
                alphaOf(root), 0f);
        bg.setDuration(duration);
        bg.addUpdateListener(a -> root.setBackgroundColor(scrim((float) a.getAnimatedValue())));
        bg.start();
        view.animate().cancel();
        view.animate()
                .translationY(targetY)
                .translationX(targetX)
                .scaleX(targetScale).scaleY(targetScale)
                .alpha(targetAlpha)
                .setDuration(duration)
                .setInterpolator(toThumb ? EASE : new AccelerateInterpolator(1.2f))
                .withEndAction(() -> {
                    if (toThumb) {
                        // Короткое растворение в самом конце, чтобы не было «скачка» к миниатюре
                        view.animate().alpha(0f).setDuration(70).withEndAction(() -> {
                            finish();
                            overridePendingTransition(0, 0);
                        }).start();
                    } else {
                        finish();
                        overridePendingTransition(0, 0);
                    }
                })
                .start();
    }

    private float alphaOf(View v) {
        android.graphics.drawable.Drawable d = v.getBackground();
        if (d instanceof android.graphics.drawable.ColorDrawable) {
            return ((android.graphics.drawable.ColorDrawable) d).getAlpha() / 255f;
        }
        return 1f;
    }

    // ---------- Системные панели ----------

    @Override
    public void onWindowFocusChanged(boolean hasFocus) {
        super.onWindowFocusChanged(hasFocus);
        if (hasFocus) applySystemBars();
    }
}
