package com.premiumlab.galleryx;

import android.animation.Animator;
import android.animation.AnimatorListenerAdapter;
import android.content.Intent;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.View;
import android.view.WindowManager;
import android.view.animation.AccelerateInterpolator;
import android.view.animation.DecelerateInterpolator;
import android.widget.ImageView;
import android.widget.SeekBar;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.app.AppCompatActivity;
import androidx.core.graphics.Insets;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowCompat;
import androidx.core.view.WindowInsetsCompat;
import androidx.core.view.WindowInsetsControllerCompat;
import androidx.recyclerview.widget.RecyclerView;
import androidx.viewpager2.widget.ViewPager2;

import com.premiumlab.galleryx.data.FavStore;
import com.premiumlab.galleryx.data.MaskGuard;
import com.premiumlab.galleryx.data.MediaItem;
import com.premiumlab.galleryx.data.Prefs;
import com.premiumlab.galleryx.data.SafeStore;
import com.premiumlab.galleryx.data.TrashStore;
import com.premiumlab.galleryx.ui.Actions;
import com.premiumlab.galleryx.ui.adapter.PhotoPagerAdapter;
import com.premiumlab.galleryx.ui.dialog.DestSheet;
import com.premiumlab.galleryx.ui.dialog.OpProgressDialog;
import com.premiumlab.galleryx.ui.view.ZoomableImageView;
import com.premiumlab.galleryx.util.FileOp;
import com.premiumlab.galleryx.util.Fmt;

import java.io.File;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Полноэкранный просмотрщик фото и видео (видео — кадр с кнопкой «play»).
 * Режимы: 0 — обычный, 1 — корзина, 2 — сейф.
 *
 * Жесты: тап — показать/скрыть панели, двойной тап — зум,
 * свайп вниз — закрыть («картинка улетает»), свайп вверх — свойства файла.
 */
public class PhotoViewerActivity extends AppCompatActivity {

    public static final int MODE_NORMAL = 0;
    public static final int MODE_TRASH = 1;
    public static final int MODE_SAFE = 2;

    private View root;
    private ViewPager2 pager;
    private PhotoPagerAdapter adapter;
    private View topBar, bottomBar;
    private TextView txtTitle, txtSub;
    private ImageView btnFav, btnMove, btnSafe, btnRestore, btnDelete, btnShare;
    private int mode = MODE_NORMAL;
    private boolean barsVisible = true;
    private boolean dismissing = false;

    /** Управление видео на текущей странице. */
    private View layoutVideoCtl;
    private ImageView btnVwPlay, btnVwMute;
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
    private int topBarPadTop, bottomBarPadBottom, bottomBarPadSide;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        if (Prefs.flagSecure()) {
            getWindow().addFlags(WindowManager.LayoutParams.FLAG_SECURE);
        }
        // Рисуем под системными панелями; отступы для своих панелей берём из insets
        WindowCompat.setDecorFitsSystemWindows(getWindow(), false);
        setContentView(R.layout.activity_photo_viewer);

        ArrayList<String> paths = getIntent().getStringArrayListExtra("paths");
        int index = getIntent().getIntExtra("index", 0);
        mode = getIntent().getIntExtra("mode", MODE_NORMAL);
        if (paths == null || paths.isEmpty()) {
            finish();
            return;
        }

        root = findViewById(R.id.viewerRoot);
        pager = findViewById(R.id.pager);
        topBar = findViewById(R.id.viewerTopBar);
        bottomBar = findViewById(R.id.viewerBottomBar);
        txtTitle = findViewById(R.id.txtViewerTitle);
        txtSub = findViewById(R.id.txtViewerSub);
        btnFav = findViewById(R.id.btnVwFavorite);
        btnShare = findViewById(R.id.btnVwShare);
        btnMove = findViewById(R.id.btnVwMove);
        btnSafe = findViewById(R.id.btnVwSafe);
        btnRestore = findViewById(R.id.btnVwRestore);
        btnDelete = findViewById(R.id.btnVwDelete);
        layoutVideoCtl = findViewById(R.id.layoutVideoCtl);
        btnVwPlay = findViewById(R.id.btnVwPlay);
        txtVwCur = findViewById(R.id.txtVwCur);
        txtVwDur = findViewById(R.id.txtVwDur);
        seekVw = findViewById(R.id.seekVw);

        topBarPadTop = topBar.getPaddingTop();
        bottomBarPadBottom = bottomBar.getPaddingBottom();
        bottomBarPadSide = bottomBar.getPaddingLeft();
        applyInsets();

        findViewById(R.id.btnViewerBack).setOnClickListener(v -> finish());

        adapter = new PhotoPagerAdapter(this, this::toggleBars, dragListener, videoListener);
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
        adapter.submit(paths);
        pager.setAdapter(adapter);
        pager.setCurrentItem(Math.min(index, paths.size() - 1), false);
        pager.setOffscreenPageLimit(1);
        pager.registerOnPageChangeCallback(new ViewPager2.OnPageChangeCallback() {
            @Override
            public void onPageSelected(int position) {
                updateToolbar(position);
                // Видео на новой странице стартует само; предыдущее — останавливается
                pager.post(() -> attachPage(position));
            }
        });
        pager.post(() -> attachPage(pager.getCurrentItem()));

        setupActions();
        updateToolbar(pager.getCurrentItem());
    }

    /** Панели не должны уходить под статус-бар и навигационную панель. */
    private void applyInsets() {
        ViewCompat.setOnApplyWindowInsetsListener(root, (v, insets) -> {
            Insets sb = insets.getInsets(WindowInsetsCompat.Type.systemBars()
                    | WindowInsetsCompat.Type.displayCutout());
            topBar.setPadding(sb.left, topBarPadTop + sb.top, sb.right,
                    topBar.getPaddingBottom());
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
                Toast.makeText(this, nowFav ? R.string.fav_added : R.string.fav_removed,
                        Toast.LENGTH_SHORT).show();
                updateFavIcon(it.path);
            });
            btnShare.setOnClickListener(v -> {
                MediaItem it = current();
                if (it != null) Actions.share(this, Collections.singletonList(it));
            });
            btnMove.setOnClickListener(v -> moveCurrent());
            btnSafe.setOnClickListener(v -> {
                MediaItem it = current();
                if (it == null) return;
                Actions.confirmSafe(this, Collections.singletonList(it), this::finish);
            });
            btnDelete.setOnClickListener(v -> {
                MediaItem it = current();
                if (it == null) return;
                Actions.confirmTrash(this, Collections.singletonList(it), this::finish);
            });
            // Пока галерея «закрыта» маскировкой — сейф не показываем
            if (MaskGuard.hidden()) {
                btnSafe.setVisibility(View.GONE);
            }
        } else {
            btnFav.setVisibility(View.GONE);
            btnMove.setVisibility(View.GONE);
            btnSafe.setVisibility(View.GONE);
            btnShare.setOnClickListener(v -> {
                MediaItem it = current();
                if (it != null) Actions.share(this, Collections.singletonList(it));
            });
            btnRestore.setVisibility(View.VISIBLE);
            btnRestore.setOnClickListener(v -> restoreCurrent());
            btnDelete.setOnClickListener(v -> {
                MediaItem it = current();
                if (it == null) return;
                Actions.confirmDeleteForever(this, Collections.singletonList(it),
                        this::finish);
            });
        }
        findViewById(R.id.btnVwInfo).setOnClickListener(v -> showInfo());
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
        if (currentPage != null && currentPage.isVideoPage()) {
            resumePlayback = currentPage.video.isPlaying();
            currentPage.pauseVideo();
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
        super.onDestroy();
    }

    private void showInfo() {
        MediaItem it = current();
        if (it != null) Actions.showInfo(this, decorateName(it));
    }

    private MediaItem current() {
        String path = adapter.getPath(pager.getCurrentItem());
        if (path == null) return null;
        return MediaItem.fromFile(new File(path));
    }

    /** В сейфе/корзине показывает оригинальное имя файла. */
    private MediaItem decorateName(MediaItem it) {
        if (mode == MODE_TRASH) {
            for (TrashStore.Entry e : TrashStore.get().entries()) {
                if (e.path.equals(it.path)) {
                    it.name = e.name;
                    break;
                }
            }
        } else if (mode == MODE_SAFE) {
            for (SafeStore.Entry e : SafeStore.get().entries()) {
                if (e.path.equals(it.path)) {
                    it.name = e.name;
                    break;
                }
            }
        }
        return it;
    }

    private void updateToolbar(int position) {
        MediaItem it = current();
        if (it == null) return;
        it = decorateName(it);
        txtTitle.setText(it.name);
        txtSub.setText(Fmt.dateFull(it.dateModified) + "  ·  " + Fmt.size(it.size));
        updateFavIcon(it.path);
    }

    private void updateFavIcon(String path) {
        if (mode != MODE_NORMAL) return;
        btnFav.setImageResource(FavStore.isFav(path)
                ? R.drawable.ic_star_filled : R.drawable.ic_star);
        btnFav.setColorFilter(0xFFFFC53D);
    }

    private void moveCurrent() {
        MediaItem it = current();
        if (it == null) return;
        DestSheet sheet = DestSheet.newInstance(false, it.folderPath);
        sheet.setListener(dir -> runCopyMove(Collections.singletonList(current()), dir));
        sheet.show(getSupportFragmentManager(), "dest");
    }

    private void runCopyMove(List<MediaItem> items, File dir) {
        if (items == null || items.isEmpty()) return;
        FileOp op = new FileOp(this);
        OpProgressDialog dlg = OpProgressDialog.show(this,
                getString(R.string.op_move), op::cancel);
        op.move(items, dir, dlg, (ok, fail, cancelled) -> {
            dlg.dismiss();
            Toast.makeText(this, Fmt.plural(this, R.plurals.result_moved, ok),
                    Toast.LENGTH_SHORT).show();
            finish();
        });
    }

    private void restoreCurrent() {
        String path = adapter.getPath(pager.getCurrentItem());
        if (path == null) return;
        OpProgressDialog dlg = OpProgressDialog.show(this,
                getString(R.string.op_restore), null);
        FileOp op = new FileOp(this);
        if (mode == MODE_TRASH) {
            List<TrashStore.Entry> entries = new ArrayList<>();
            for (TrashStore.Entry e : TrashStore.get().entries()) {
                if (e.path.equals(path)) entries.add(e);
            }
            op.restoreTrash(entries, dlg, (ok, fail, cancelled) -> {
                dlg.dismiss();
                Toast.makeText(this, Fmt.plural(this, R.plurals.result_restored, ok),
                        Toast.LENGTH_SHORT).show();
                finish();
            });
        } else {
            List<SafeStore.Entry> entries = new ArrayList<>();
            for (SafeStore.Entry e : SafeStore.get().entries()) {
                if (e.path.equals(path)) entries.add(e);
            }
            op.restoreSafe(entries, dlg, (ok, fail, cancelled) -> {
                dlg.dismiss();
                Toast.makeText(this, Fmt.plural(this, R.plurals.result_restored, ok),
                        Toast.LENGTH_SHORT).show();
                finish();
            });
        }
    }

    // ---------- Панели ----------

    private void toggleBars() {
        setBarsVisible(!barsVisible);
    }

    private void setBarsVisible(boolean visible) {
        if (barsVisible == visible) return;
        barsVisible = visible;
        if (currentPage != null) currentPage.setOverlayVisible(visible);
        float target = visible ? 1f : 0f;
        if (visible) {
            topBar.setVisibility(View.VISIBLE);
            bottomBar.setVisibility(View.VISIBLE);
        }
        topBar.animate().alpha(target).setDuration(200)
                .setListener(new AnimatorListenerAdapter() {
                    @Override
                    public void onAnimationEnd(Animator a) {
                        topBar.setVisibility(barsVisible ? View.VISIBLE : View.GONE);
                    }
                }).start();
        bottomBar.animate().alpha(target).setDuration(200)
                .setListener(new AnimatorListenerAdapter() {
                    @Override
                    public void onAnimationEnd(Animator a) {
                        bottomBar.setVisibility(barsVisible ? View.VISIBLE : View.GONE);
                    }
                }).start();
        applySystemBars();
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
                        // Тянем вниз: картинка уменьшается и уезжает, фон растворяется
                        float progress = Math.min(1f, dy / (root.getHeight() * 0.6f));
                        float scale = 1f - 0.3f * progress;
                        view.setTranslationX(dx * 0.6f);
                        view.setTranslationY(dy);
                        view.setScaleX(scale);
                        view.setScaleY(scale);
                        root.setBackgroundColor(scrim(1f - progress * 0.85f));
                        float barsAlpha = Math.max(0f, 1f - progress * 2.5f);
                        topBar.setAlpha(barsVisible ? barsAlpha : 0f);
                        bottomBar.setAlpha(barsVisible ? barsAlpha : 0f);
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
                    float dismissDist = 110f * density;
                    float infoDist = 90f * density;
                    if (dy > dismissDist || (dy > 24f * density && velocityY > 1800f)) {
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

    private void animateBack(View view) {
        view.animate().cancel();
        view.animate()
                .translationX(0f).translationY(0f)
                .scaleX(1f).scaleY(1f)
                .setDuration(220)
                .setInterpolator(new DecelerateInterpolator())
                .withEndAction(() -> root.setBackgroundColor(scrim(1f)))
                .start();
        root.setBackgroundColor(scrim(1f));
        if (barsVisible) {
            topBar.animate().alpha(1f).setDuration(200).setListener(null).start();
            bottomBar.animate().alpha(1f).setDuration(200).setListener(null).start();
        }
    }

    /** «Улетание» картинки вниз и закрытие экрана без стандартной анимации. */
    private void animateDismiss(View view, float dx, float dy, float velocityY) {
        dismissing = true;
        float h = root.getHeight();
        float targetY = h + view.getHeight() * 0.5f;
        float targetX = view.getTranslationX() + (dx > 0 ? 1 : -1) * 40f
                * getResources().getDisplayMetrics().density;
        long duration = 260;
        if (velocityY > 0f) {
            duration = Math.max(140, Math.min(260,
                    (long) ((targetY - view.getTranslationY()) / velocityY * 1000f)));
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
                .scaleX(0.6f).scaleY(0.6f)
                .alpha(0.6f)
                .setDuration(duration)
                .setInterpolator(new AccelerateInterpolator())
                .withEndAction(() -> {
                    finish();
                    overridePendingTransition(0, 0);
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
