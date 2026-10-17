package com.premiumlab.galleryx.editor;

import android.content.Context;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.ColorMatrixColorFilter;
import android.graphics.Paint;
import android.graphics.RectF;
import android.graphics.drawable.GradientDrawable;
import android.media.MediaCodec;
import android.media.MediaExtractor;
import android.media.MediaFormat;
import android.media.MediaMetadataRetriever;
import android.media.MediaMuxer;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.SeekBar;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.app.AppCompatActivity;

import com.google.android.material.dialog.MaterialAlertDialogBuilder;
import com.premiumlab.galleryx.R;
import com.premiumlab.galleryx.data.MediaEngine;
import com.premiumlab.galleryx.data.Prefs;
import com.premiumlab.galleryx.ui.view.InlineVideoView;
import com.premiumlab.galleryx.util.Fmt;
import com.premiumlab.galleryx.util.Press;
import com.premiumlab.galleryx.util.Scan;
import com.premiumlab.galleryx.util.Theming;
import com.premiumlab.galleryx.util.Toasts;

import java.io.File;
import java.nio.ByteBuffer;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Видеоредактор уровня GorillaGallery: режимы «Видео» (обрезка + скорость), «Звук»
 * (без звука, громкость), «Фильтры» (те же пресеты, что у фото — превью в реальном
 * времени), «Кадр» (пропорции, перетаскиваемая рамка, поворот, отражение). Undo/redo.
 * Экспорт: быстрый ремукс без перекодирования, если менялись только обрезка/поворот/звук,
 * иначе — Media3 Transformer с прогрессом и отменой.
 */
public class VideoEditorActivity extends AppCompatActivity {

    private static final ExecutorService IO = Executors.newSingleThreadExecutor();
    private static final int MODE_TRIM = 0, MODE_AUDIO = 1, MODE_FILTERS = 2, MODE_CROP = 3;
    private static final float[] SPEEDS = {0.5f, 0.75f, 1f, 1.25f, 1.5f, 2f};
    private String[] aspectNames;

    private final Handler main = new Handler(Looper.getMainLooper());

    private String path;
    private InlineVideoView video;
    private VideoCropOverlay cropOverlay;
    private FrameLayout videoBox;
    private ImageView btnPlay, btnUndo, btnRedo;
    private TextView txtRange, txtHint, txtVolume, txtProgress;
    private SeekBar seekVolume;
    private View progress, panelTrim, panelAudio, panelFilters, panelCrop;
    private ProgressBar progressBar;
    private TrimBar trim;
    private final List<View> modeTabs = new ArrayList<>();
    private final List<TextView> speedChips = new ArrayList<>();
    private final List<TextView> aspectChips = new ArrayList<>();
    private final List<View> filterTiles = new ArrayList<>();
    private TextView chipMute;

    private VideoEditState state = new VideoEditState();
    private final ArrayDeque<VideoEditState> undo = new ArrayDeque<>();
    private final ArrayDeque<VideoEditState> redo = new ArrayDeque<>();
    private int mode = MODE_TRIM;

    private int durationMs = 0;
    private boolean hasAudio = true;
    private int srcRotation = 0;
    private Bitmap frameThumb;
    private VideoExporter exporter;

    private final Runnable tick = new Runnable() {
        @Override
        public void run() {
            if (video.isPrepared()) {
                int pos = video.getPosition();
                trim.setPlayhead(pos);
                int end = endMs();
                if (video.isPlaying() && pos >= end - 40) {
                    video.pause();
                    video.seekTo(state.startMs);
                    trim.setPlayhead(state.startMs);
                }
            }
            if (video.isPlaying()) main.postDelayed(this, 60);
        }
    };

    private int endMs() {
        return state.endMs > 0 ? state.endMs : durationMs;
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        com.premiumlab.galleryx.util.BackCompat.install(this, this::handleBack);
        Theming.apply(this);
        if (Prefs.flagSecure()) getWindow().addFlags(WindowManager.LayoutParams.FLAG_SECURE);
        setContentView(R.layout.activity_video_editor);
        getWindow().setStatusBarColor(0xFF0B0B10);
        getWindow().setNavigationBarColor(0xFF141420);
        path = getIntent().getStringExtra(EditorActivity.EXTRA_PATH);
        if (path == null) {
            finish();
            return;
        }
        video = findViewById(R.id.veVideo);
        cropOverlay = findViewById(R.id.veCrop);
        videoBox = findViewById(R.id.veVideoBox);
        btnPlay = findViewById(R.id.btnVePlay);
        btnUndo = findViewById(R.id.btnVeUndo);
        btnRedo = findViewById(R.id.btnVeRedo);
        txtRange = findViewById(R.id.txtVeRange);
        txtHint = findViewById(R.id.txtVeHint);
        txtVolume = findViewById(R.id.txtVeVolume);
        txtProgress = findViewById(R.id.txtVeProgress);
        seekVolume = findViewById(R.id.seekVeVolume);
        progress = findViewById(R.id.layoutVeProgress);
        progressBar = findViewById(R.id.veProgressBar);
        panelTrim = findViewById(R.id.vePanelTrim);
        panelAudio = findViewById(R.id.vePanelAudio);
        panelFilters = findViewById(R.id.vePanelFilters);
        panelCrop = findViewById(R.id.vePanelCrop);

        trim = new TrimBar(this);
        ((FrameLayout) findViewById(R.id.veTrimBox)).addView(trim, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        trim.listener = new TrimListener() {
            @Override
            public void onRange(int s, int e, int seekTo) {
                // Живое превью без записи в историю
                state.startMs = s;
                state.endMs = e;
                if (video.isPrepared()) {
                    if (video.isPlaying()) video.pause();
                    video.seekTo(seekTo);
                }
                updateRangeText();
            }

            @Override
            public void onRangeCommitted(int s, int e, int oldS, int oldE) {
                if (s == oldS && e == oldE) return;
                VideoEditState prev = state.copy();
                prev.startMs = oldS;
                prev.endMs = oldE;
                pushHistory(prev);
            }
        };

        View close = findViewById(R.id.btnVeClose);
        Press.attach(close, 0.88f);
        close.setOnClickListener(v -> getOnBackPressedDispatcher().onBackPressed());
        View save = findViewById(R.id.btnVeSave);
        Press.attach(save, 0.88f);
        save.setOnClickListener(v -> askSave());
        Press.attach(btnUndo, 0.85f);
        Press.attach(btnRedo, 0.85f);
        btnUndo.setOnClickListener(v -> doUndo());
        btnRedo.setOnClickListener(v -> doRedo());
        btnPlay.setOnClickListener(v -> togglePlay());
        Press.attach(btnPlay, 0.9f);
        videoBox.setOnClickListener(v -> {
            if (mode != MODE_CROP) togglePlay();
        });
        findViewById(R.id.btnVeCancelExport).setOnClickListener(v -> cancelExport());

        buildModeTabs();
        buildSpeedRow();
        buildAudioPanel();
        buildCropPanel();

        cropOverlay.setListener(r -> {
            VideoEditState n = state.copy();
            n.crop = r;
            commit(n);
        });
        videoBox.addOnLayoutChangeListener((v, l, t, r, b, ol, ot, or, ob) -> {
            if (r - l != or - ol || b - t != ob - ot) main.post(this::layoutVideo);
        });

        video.setMuted(false);
        video.setListener(new InlineVideoView.Listener() {
            @Override
            public void onPrepared(int d, int w, int h) {
                durationMs = d;
                trim.setDuration(d, state.startMs, endMs());
                updateRangeText();
                layoutVideo();
            }

            @Override
            public void onPlayStateChanged(boolean playing) {
                btnPlay.setImageResource(playing ? R.drawable.ic_pause : R.drawable.ic_play);
                btnPlay.animate().alpha(playing ? 0f : 1f).setDuration(180).start();
                main.removeCallbacks(tick);
                if (playing) main.post(tick);
            }

            @Override
            public void onCompletion() {
                btnPlay.setImageResource(R.drawable.ic_play);
                btnPlay.animate().alpha(1f).setDuration(180).start();
                video.seekTo(state.startMs);
            }

            @Override
            public void onError() {
                Toasts.makeText(VideoEditorActivity.this, R.string.editor_load_failed,
                        Toast.LENGTH_SHORT).show();
            }
        });
        video.setSource(path);
        readMeta();
        setMode(MODE_TRIM);
        updateUndoRedo();
    }

    // ---------- Метаданные, миниатюры ----------

    private void readMeta() {
        IO.execute(() -> {
            boolean audio = false;
            int rot = 0;
            Bitmap thumb = null;
            List<Bitmap> strip = new ArrayList<>();
            try {
                MediaExtractor ex = new MediaExtractor();
                ex.setDataSource(path);
                for (int i = 0; i < ex.getTrackCount(); i++) {
                    String mime = ex.getTrackFormat(i).getString(MediaFormat.KEY_MIME);
                    if (mime != null && mime.startsWith("audio/")) audio = true;
                }
                ex.release();
            } catch (Exception ignored) {
            }
            try {
                MediaMetadataRetriever r = new MediaMetadataRetriever();
                r.setDataSource(path);
                String s = r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_ROTATION);
                if (s != null) rot = Integer.parseInt(s);
                String ds = r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION);
                long dur = ds == null ? 0 : Long.parseLong(ds);
                int n = 10;
                int th = dp(50);
                for (int i = 0; i < n; i++) {
                    long us = dur <= 0 ? 0 : (dur * 1000L * i / n + dur * 500L / n);
                    Bitmap f = r.getFrameAtTime(us, MediaMetadataRetriever.OPTION_CLOSEST_SYNC);
                    if (f == null) continue;
                    int tw = Math.max(1, Math.round(th * f.getWidth() / (float) f.getHeight()));
                    Bitmap sc = Bitmap.createScaledBitmap(f, tw, th, true);
                    if (sc != f) f.recycle();
                    strip.add(sc);
                    if (thumb == null) {
                        int s2 = dp(56);
                        thumb = Bitmap.createBitmap(s2, s2, Bitmap.Config.ARGB_8888);
                        Canvas c = new Canvas(thumb);
                        float scale = Math.max(s2 / (float) sc.getWidth(), s2 / (float) sc.getHeight());
                        float w = sc.getWidth() * scale, h = sc.getHeight() * scale;
                        c.drawBitmap(sc, null, new RectF((s2 - w) / 2f, (s2 - h) / 2f,
                                (s2 + w) / 2f, (s2 + h) / 2f), new Paint(Paint.FILTER_BITMAP_FLAG));
                    }
                }
                r.release();
            } catch (Exception ignored) {
            }
            final boolean fa = audio;
            final int fr = rot;
            final Bitmap ft = thumb;
            final List<Bitmap> fs = strip;
            main.post(() -> {
                if (isFinishing()) return;
                hasAudio = fa;
                srcRotation = fr;
                frameThumb = ft;
                trim.setFrames(fs);
                buildFilterPanel();
                if (!hasAudio) {
                    chipMute.setAlpha(0.4f);
                    seekVolume.setEnabled(false);
                    seekVolume.setAlpha(0.4f);
                }
            });
        });
    }

    // ---------- Режимы ----------

    private void buildModeTabs() {
        LinearLayout row = findViewById(R.id.rowVeModes);
        int[] icons = {R.drawable.ic_movie, R.drawable.ic_audio, R.drawable.ic_filter, R.drawable.ic_crop};
        String[] names = getResources().getStringArray(R.array.video_mode_names);
        for (int i = 0; i < icons.length; i++) {
            final int idx = i;
            LinearLayout tab = new LinearLayout(this);
            tab.setOrientation(LinearLayout.VERTICAL);
            tab.setGravity(Gravity.CENTER);
            tab.setLayoutParams(new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1f));
            ImageView img = new ImageView(this);
            img.setImageResource(icons[i]);
            img.setLayoutParams(new LinearLayout.LayoutParams(dp(24), dp(24)));
            TextView t = new TextView(this);
            t.setText(names[i]);
            t.setTextSize(11f);
            t.setPadding(0, dp(3), 0, 0);
            // Подпись строго по центру под иконкой: при крупном системном шрифте
            // текст шире вкладки и прижимался к левому краю — иконка казалась
            // смещённой вправо. Теперь подпись в одну строку, по центру, ужимается.
            t.setGravity(Gravity.CENTER_HORIZONTAL);
            t.setSingleLine(true);
            t.setIncludeFontPadding(false);
            t.setLayoutParams(new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
            androidx.core.widget.TextViewCompat.setAutoSizeTextTypeUniformWithConfiguration(
                    t, 8, 11, 1, android.util.TypedValue.COMPLEX_UNIT_SP);
            LinearLayout.LayoutParams ilp = new LinearLayout.LayoutParams(dp(24), dp(24));
            ilp.gravity = Gravity.CENTER_HORIZONTAL;
            img.setLayoutParams(ilp);
            tab.addView(img);
            tab.addView(t);
            tab.setOnClickListener(v -> setMode(idx));
            Press.attach(tab, 0.92f);
            row.addView(tab);
            modeTabs.add(tab);
        }
    }

    private void setMode(int m) {
        mode = m;
        panelTrim.setVisibility(m == MODE_TRIM ? View.VISIBLE : View.GONE);
        panelAudio.setVisibility(m == MODE_AUDIO ? View.VISIBLE : View.GONE);
        panelFilters.setVisibility(m == MODE_FILTERS ? View.VISIBLE : View.GONE);
        panelCrop.setVisibility(m == MODE_CROP ? View.VISIBLE : View.GONE);
        cropOverlay.setVisibility(m == MODE_CROP ? View.VISIBLE : View.GONE);
        if (m == MODE_CROP && video.isPlaying()) video.pause();
        btnPlay.setVisibility(m == MODE_CROP ? View.GONE : View.VISIBLE);
        for (int i = 0; i < modeTabs.size(); i++) {
            LinearLayout tab = (LinearLayout) modeTabs.get(i);
            int color = i == m ? Theming.accent(this) : 0xFF9999B0;
            ((ImageView) tab.getChildAt(0)).setColorFilter(color);
            ((TextView) tab.getChildAt(1)).setTextColor(color);
        }
        String hint;
        switch (m) {
            case MODE_AUDIO: hint = getString(R.string.video_hint_audio); break;
            case MODE_FILTERS: hint = getString(R.string.video_hint_filter); break;
            case MODE_CROP: hint = getString(R.string.video_hint_crop); break;
            default: hint = getString(R.string.video_hint_trim); break;
        }
        txtHint.setText(hint);
    }

    // ---------- История ----------

    private void commit(VideoEditState n) {
        if (n.sameAs(state)) return;
        pushHistory(state);
        state = n;
        applyState();
    }

    private void pushHistory(VideoEditState prev) {
        undo.push(prev);
        while (undo.size() > 50) undo.removeLast();
        redo.clear();
        updateUndoRedo();
    }

    private void doUndo() {
        if (undo.isEmpty()) return;
        redo.push(state);
        state = undo.pop();
        applyState();
        updateUndoRedo();
    }

    private void doRedo() {
        if (redo.isEmpty()) return;
        undo.push(state);
        state = redo.pop();
        applyState();
        updateUndoRedo();
    }

    private void updateUndoRedo() {
        btnUndo.setAlpha(undo.isEmpty() ? 0.3f : 1f);
        btnRedo.setAlpha(redo.isEmpty() ? 0.3f : 1f);
    }

    /** Приводит все панели и превью к текущему состоянию. */
    private void applyState() {
        trim.setDuration(Math.max(durationMs, 1), state.startMs, endMs());
        updateRangeText();
        for (int i = 0; i < speedChips.size(); i++) {
            styleChip(speedChips.get(i), Math.abs(SPEEDS[i] - state.speed) < 0.01f);
        }
        video.setSpeed(state.speed);
        video.setMuted(state.mute);
        video.setVolumeLevel(Math.min(1f, state.volume / 100f));
        if (chipMute != null) styleChip(chipMute, state.mute);
        seekVolume.setProgress(state.volume);
        txtVolume.setText(state.volume + "%");
        highlightFilter();
        applyFilterPreview();
        for (int i = 0; i < aspectChips.size(); i++) styleChip(aspectChips.get(i), i == state.aspectIdx);
        cropOverlay.setCrop(state.crop);
        layoutVideo();
    }

    // ---------- Видео: скорость ----------

    private void buildSpeedRow() {
        LinearLayout row = findViewById(R.id.rowVeSpeed);
        ImageView ic = new ImageView(this);
        ic.setImageResource(R.drawable.ic_speed);
        ic.setColorFilter(0xFF9999B0);
        LinearLayout.LayoutParams ip = new LinearLayout.LayoutParams(dp(18), dp(18));
        ip.setMarginEnd(dp(10));
        ic.setLayoutParams(ip);
        row.addView(ic);
        for (int i = 0; i < SPEEDS.length; i++) {
            final float sp = SPEEDS[i];
            TextView c = chip(speedLabel(sp));
            c.setOnClickListener(v -> {
                VideoEditState n = state.copy();
                n.speed = sp;
                commit(n);
            });
            row.addView(c);
            speedChips.add(c);
        }
    }

    private static String speedLabel(float s) {
        return (s == Math.floor(s) ? String.valueOf((int) s) : String.valueOf(s)) + "×";
    }

    // ---------- Звук ----------

    private void buildAudioPanel() {
        LinearLayout row = findViewById(R.id.rowVeAudio);
        chipMute = chip(getString(R.string.video_mute));
        chipMute.setOnClickListener(v -> {
            if (!hasAudio) {
                Toasts.makeText(this, R.string.video_no_audio_track, Toast.LENGTH_SHORT).show();
                return;
            }
            VideoEditState n = state.copy();
            n.mute = !n.mute;
            commit(n);
        });
        row.addView(chipMute);
        TextView reset = chip(getString(R.string.video_reset_volume));
        reset.setOnClickListener(v -> {
            VideoEditState n = state.copy();
            n.volume = 100;
            commit(n);
        });
        row.addView(reset);
        seekVolume.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            int startVal;

            @Override
            public void onProgressChanged(SeekBar sb, int p, boolean fromUser) {
                if (!fromUser) return;
                state.volume = p;
                txtVolume.setText(p + "%");
                video.setVolumeLevel(Math.min(1f, p / 100f));
            }

            @Override
            public void onStartTrackingTouch(SeekBar sb) {
                startVal = state.volume;
            }

            @Override
            public void onStopTrackingTouch(SeekBar sb) {
                if (startVal != state.volume) {
                    VideoEditState prev = state.copy();
                    prev.volume = startVal;
                    pushHistory(prev);
                }
            }
        });
    }

    // ---------- Фильтры ----------

    private void buildFilterPanel() {
        LinearLayout row = findViewById(R.id.rowVeFilters);
        row.removeAllViews();
        filterTiles.clear();
        String[] filterNames = Filters.names(this);
        for (int i = 0; i < filterNames.length; i++) {
            final int idx = i;
            LinearLayout tile = new LinearLayout(this);
            tile.setOrientation(LinearLayout.VERTICAL);
            tile.setGravity(Gravity.CENTER_HORIZONTAL);
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            lp.setMarginEnd(dp(10));
            tile.setLayoutParams(lp);
            ImageView img = new ImageView(this);
            img.setLayoutParams(new LinearLayout.LayoutParams(dp(64), dp(64)));
            img.setScaleType(ImageView.ScaleType.CENTER_CROP);
            img.setClipToOutline(true);
            if (frameThumb != null) img.setImageBitmap(frameThumb);
            if (i != 0) img.setColorFilter(new ColorMatrixColorFilter(Filters.matrix(i)));
            GradientDrawable bg = new GradientDrawable();
            bg.setCornerRadius(dp(14));
            bg.setColor(0xFF000000);
            img.setBackground(bg);
            TextView t = new TextView(this);
            t.setText(filterNames[i]);
            t.setTextSize(11f);
            t.setPadding(0, dp(4), 0, 0);
            tile.addView(img);
            tile.addView(t);
            tile.setOnClickListener(v -> {
                VideoEditState n = state.copy();
                n.preset = idx;
                commit(n);
            });
            Press.attach(tile, 0.92f);
            row.addView(tile);
            filterTiles.add(tile);
        }
        highlightFilter();
    }

    private void highlightFilter() {
        for (int i = 0; i < filterTiles.size(); i++) {
            LinearLayout tile = (LinearLayout) filterTiles.get(i);
            boolean on = i == state.preset;
            ImageView img = (ImageView) tile.getChildAt(0);
            GradientDrawable bg = (GradientDrawable) img.getBackground();
            bg.setStroke(dp(2), on ? Theming.accent(this) : 0x00000000);
            int p = on ? dp(2) : 0;
            img.setPadding(p, p, p, p);
            ((TextView) tile.getChildAt(1)).setTextColor(on ? Theming.accent(this) : 0xFF9999B0);
        }
    }

    /** Превью фильтра в реальном времени: цветовой фильтр аппаратного слоя TextureView. */
    private void applyFilterPreview() {
        if (state.preset == 0) {
            video.setLayerType(View.LAYER_TYPE_NONE, null);
        } else {
            Paint p = new Paint();
            p.setColorFilter(new ColorMatrixColorFilter(Filters.matrix(state.preset)));
            video.setLayerType(View.LAYER_TYPE_HARDWARE, p);
        }
    }

    // ---------- Кадр ----------

    private void buildCropPanel() {
        LinearLayout row = findViewById(R.id.rowVeAspects);
        aspectNames = getResources().getStringArray(R.array.video_aspect_names);
        for (int i = 0; i < aspectNames.length; i++) {
            final int idx = i;
            TextView c = chip(aspectNames[i]);
            c.setOnClickListener(v -> {
                VideoEditState n = state.copy();
                n.aspectIdx = idx;
                cropOverlay.setListener(null);
                cropOverlay.setAspect(aspectValue(idx));
                n.crop = idx == 0 ? null : cropOverlay.getCrop();
                cropOverlay.setListener(r -> {
                    VideoEditState s2 = state.copy();
                    s2.crop = r;
                    commit(s2);
                });
                commit(n);
            });
            row.addView(c);
            aspectChips.add(c);
        }
        View rotate = findViewById(R.id.btnVeRotate);
        View flip = findViewById(R.id.btnVeFlip);
        View reset = findViewById(R.id.btnVeCropReset);
        for (View v : new View[]{rotate, flip, reset}) Press.attach(v, 0.88f);
        rotate.setOnClickListener(v -> {
            VideoEditState n = state.copy();
            n.rotation = (n.rotation + 90) % 360;
            n.crop = null;
            n.aspectIdx = 0;
            commit(n);
        });
        flip.setOnClickListener(v -> {
            VideoEditState n = state.copy();
            n.flipH = !n.flipH;
            commit(n);
        });
        reset.setOnClickListener(v -> {
            VideoEditState n = state.copy();
            n.rotation = 0;
            n.flipH = false;
            n.crop = null;
            n.aspectIdx = 0;
            commit(n);
        });
    }

    private float aspectValue(int idx) {
        switch (idx) {
            case 2: return 1f;
            case 3: return 4f / 5f;
            case 4: return 9f / 16f;
            case 5: return 16f / 9f;
            case 6: return 4f / 3f;
            case 7: return 3f / 2f;
            default: return 0f;
        }
    }

    /**
     * Раскладывает превью: видео вписывается в бокс уже с учётом поворота и отражения,
     * рамка кадрирования получает прямоугольник, который реально занимает видео.
     */
    private void layoutVideo() {
        int bw = videoBox.getWidth(), bh = videoBox.getHeight();
        int vw = video.getVideoWidth(), vh = video.getVideoHeight();
        if (bw == 0 || bh == 0 || vw == 0 || vh == 0) return;
        boolean swap = state.rotation % 180 != 0;
        float cw = swap ? vh : vw, ch = swap ? vw : vh; // размеры повернутого кадра
        float scale = Math.min(bw / cw, bh / ch);
        float rw = cw * scale, rh = ch * scale;         // кадр на экране
        RectF shown = new RectF((bw - rw) / 2f, (bh - rh) / 2f, (bw + rw) / 2f, (bh + rh) / 2f);
        // До поворота view содержимое должно занимать прямоугольник с теми же размерами,
        // но «не повернутый» — тогда после rotation оно ляжет ровно в shown
        float pw = swap ? rh : rw, ph = swap ? rw : rh;
        video.setDisplayRect(new RectF((bw - pw) / 2f, (bh - ph) / 2f, (bw + pw) / 2f, (bh + ph) / 2f));
        video.setPivotX(bw / 2f);
        video.setPivotY(bh / 2f);
        video.animate().rotation(state.rotation).scaleX(state.flipH ? -1f : 1f).setDuration(200).start();
        cropOverlay.setVideoRect(shown);
    }

    // ---------- Воспроизведение ----------

    private void togglePlay() {
        if (!video.isPrepared()) return;
        if (video.isPlaying()) {
            video.pause();
        } else {
            int pos = video.getPosition();
            if (pos < state.startMs || pos >= endMs() - 40) video.seekTo(state.startMs);
            video.play();
        }
    }

    private void updateRangeText() {
        int e = endMs();
        long len = Math.round((e - state.startMs) / Math.max(0.1f, state.speed));
        txtRange.setText(String.format(Locale.US, "%s  –  %s   •   %s%s", Fmt.duration(state.startMs),
                Fmt.duration(e), Fmt.duration(len),
                Math.abs(state.speed - 1f) > 0.01f ? "  (" + speedLabel(state.speed) + ")" : ""));
    }

    @Override
    protected void onPause() {
        super.onPause();
        if (video.isPlaying()) video.pause();
    }

    @Override
    protected void onDestroy() {
        main.removeCallbacks(tick);
        if (exporter != null) exporter.cancel();
        video.release();
        super.onDestroy();
    }

    /** «Назад»: регистрируется через BackCompat (предиктивный жест, targetSdk 36+). */
    private boolean handleBack() {
        if (progress.getVisibility() == View.VISIBLE) {
            cancelExport();
            return true;
        }
        if (!state.changed(durationMs)) {
            return false;
        }
        new MaterialAlertDialogBuilder(this)
                .setTitle(R.string.editor_discard_title)
                .setMessage(R.string.editor_discard_msg)
                .setPositiveButton(R.string.editor_discard, (d, w) -> finish())
                .setNegativeButton(R.string.cancel, null)
                .show();
        return true;
    }

    // ---------- Сохранение ----------

    private void askSave() {
        if (!state.changed(durationMs)) {
            Toasts.makeText(this, R.string.editor_nothing, Toast.LENGTH_SHORT).show();
            return;
        }
        new MaterialAlertDialogBuilder(this)
                .setTitle(R.string.editor_save_title)
                .setMessage(state.needsTranscode() ? R.string.video_editor_transcode_note
                        : R.string.video_editor_fast_note)
                .setPositiveButton(R.string.editor_save_copy, (d, w) -> save(false))
                .setNeutralButton(R.string.editor_overwrite, (d, w) -> save(true))
                .setNegativeButton(R.string.cancel, null)
                .show();
    }

    private File tmpTarget;
    private File finalTarget;
    private boolean overwriteMode;

    private void save(boolean overwrite) {
        if (video.isPlaying()) video.pause();
        File src = new File(path);
        String name = src.getName();
        int dot = name.lastIndexOf('.');
        String base = dot > 0 ? name.substring(0, dot) : name;
        finalTarget = overwrite ? src : Fmt.uniqueFile(src.getParentFile(), base + "_edited.mp4");
        tmpTarget = new File(finalTarget.getParentFile(), "." + finalTarget.getName() + ".gxpart");
        overwriteMode = overwrite;
        progress.setVisibility(View.VISIBLE);
        progressBar.setIndeterminate(true);
        txtProgress.setText(R.string.video_editor_saving);
        final VideoEditState st = state.copy();
        final int dur = durationMs;
        if (!st.needsTranscode()) {
            // Быстрый путь: ремукс без перекодирования
            IO.execute(() -> {
                try {
                    remux(path, tmpTarget, st.startMs * 1000L, (st.endMs > 0 ? st.endMs : dur) * 1000L,
                            (srcRotation + st.rotation) % 360, st.mute || !hasAudio);
                    main.post(this::finishSave);
                } catch (Throwable t) {
                    main.post(() -> failSave(t));
                }
            });
            return;
        }
        progressBar.setIndeterminate(false);
        progressBar.setProgress(0);
        txtProgress.setText(getString(R.string.video_editor_saving_pct, 0));
        exporter = new VideoExporter();
        exporter.start(getApplicationContext(), path, tmpTarget, st, hasAudio, dur, new VideoExporter.Callback() {
            @Override
            public void onProgress(int percent) {
                progressBar.setProgress(percent);
                txtProgress.setText(getString(R.string.video_editor_saving_pct, percent));
            }

            @Override
            public void onDone(File out) {
                exporter = null;
                finishSave();
            }

            @Override
            public void onFailed(Throwable t) {
                exporter = null;
                failSave(t);
            }
        });
    }

    private void cancelExport() {
        if (exporter != null) {
            exporter.cancel();
            exporter = null;
        }
        if (tmpTarget != null) //noinspection ResultOfMethodCallIgnored
            tmpTarget.delete();
        progress.setVisibility(View.GONE);
    }

    private void finishSave() {
        IO.execute(() -> {
            String out = null;
            try {
                if (tmpTarget == null || !tmpTarget.exists() || tmpTarget.length() == 0) {
                    throw new IllegalStateException("empty");
                }
                if (overwriteMode) {
                    //noinspection ResultOfMethodCallIgnored
                    finalTarget.delete();
                }
                if (!tmpTarget.renameTo(finalTarget)) throw new IllegalStateException("rename");
                //noinspection ResultOfMethodCallIgnored
                finalTarget.setLastModified(System.currentTimeMillis());
                Scan.files(getApplicationContext(), finalTarget.getAbsolutePath());
                MediaEngine.invalidateAll();
                out = finalTarget.getAbsolutePath();
            } catch (Throwable t) {
                out = null;
            }
            final String fOut = out;
            main.post(() -> {
                progress.setVisibility(View.GONE);
                if (fOut == null) {
                    Toasts.makeText(this, R.string.video_editor_failed, Toast.LENGTH_SHORT).show();
                    return;
                }
                Toasts.makeText(this, R.string.editor_saved, Toast.LENGTH_SHORT).show();
                Intent data = new Intent();
                data.putExtra(EditorActivity.RESULT_PATH, fOut);
                data.putExtra("overwrite", overwriteMode);
                setResult(RESULT_OK, data);
                finish();
            });
        });
    }

    private void failSave(Throwable t) {
        if (tmpTarget != null) //noinspection ResultOfMethodCallIgnored
            tmpTarget.delete();
        progress.setVisibility(View.GONE);
        Toasts.makeText(this, R.string.video_editor_failed, Toast.LENGTH_SHORT).show();
    }

    /** Копирует дорожки без перекодирования в диапазоне [startUs, endUs]. */
    private static void remux(String src, File dst, long startUs, long endUs, int rotation,
                              boolean dropAudio) throws Exception {
        MediaExtractor ex = new MediaExtractor();
        ex.setDataSource(src);
        MediaMuxer mux = new MediaMuxer(dst.getAbsolutePath(), MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4);
        int count = ex.getTrackCount();
        int[] map = new int[count];
        boolean[] isVideo = new boolean[count];
        int maxSize = 1 << 20;
        boolean any = false;
        for (int i = 0; i < count; i++) {
            map[i] = -1;
            MediaFormat f = ex.getTrackFormat(i);
            String mime = f.getString(MediaFormat.KEY_MIME);
            if (mime == null) continue;
            boolean isA = mime.startsWith("audio/"), isV = mime.startsWith("video/");
            if (!isA && !isV) continue;
            if (isA && dropAudio) continue;
            if (f.containsKey(MediaFormat.KEY_MAX_INPUT_SIZE)) {
                maxSize = Math.max(maxSize, f.getInteger(MediaFormat.KEY_MAX_INPUT_SIZE));
            }
            map[i] = mux.addTrack(f);
            isVideo[i] = isV;
            ex.selectTrack(i);
            any = true;
        }
        if (!any) {
            mux.release();
            ex.release();
            throw new IllegalStateException("no tracks");
        }
        mux.setOrientationHint(rotation);
        mux.start();
        ex.seekTo(startUs, MediaExtractor.SEEK_TO_PREVIOUS_SYNC);
        long base = -1;
        ByteBuffer buf = ByteBuffer.allocateDirect(maxSize);
        MediaCodec.BufferInfo info = new MediaCodec.BufferInfo();
        while (true) {
            int size = ex.readSampleData(buf, 0);
            if (size < 0) break;
            long pts = ex.getSampleTime();
            int track = ex.getSampleTrackIndex();
            if (pts > endUs) {
                if (track >= 0 && isVideo[track]) break;
                if (!ex.advance()) break;
                continue;
            }
            if (base < 0) base = pts;
            if (track >= 0 && map[track] >= 0) {
                info.offset = 0;
                info.size = size;
                info.presentationTimeUs = Math.max(0, pts - base);
                info.flags = (ex.getSampleFlags() & MediaExtractor.SAMPLE_FLAG_SYNC) != 0
                        ? MediaCodec.BUFFER_FLAG_KEY_FRAME : 0;
                mux.writeSampleData(map[track], buf, info);
            }
            if (!ex.advance()) break;
        }
        try {
            mux.stop();
        } finally {
            mux.release();
            ex.release();
        }
    }

    // ---------- UI-помощники ----------

    private TextView chip(String text) {
        TextView t = new TextView(this);
        t.setText(text);
        t.setTextSize(13f);
        t.setMaxLines(1);
        t.setPadding(dp(14), dp(8), dp(14), dp(8));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.setMarginEnd(dp(8));
        t.setLayoutParams(lp);
        styleChip(t, false);
        Press.attach(t, 0.94f);
        return t;
    }

    private void styleChip(TextView t, boolean active) {
        int acc = Theming.accent(this);
        GradientDrawable bg = new GradientDrawable();
        bg.setCornerRadius(dp(999));
        bg.setColor(active ? (acc & 0x00FFFFFF) | 0x33000000 : 0x1AFFFFFF);
        bg.setStroke(dp(1), active ? (acc & 0x00FFFFFF) | 0x80000000 : 0x26FFFFFF);
        t.setBackground(bg);
        t.setTextColor(active ? acc : 0xFFE8E8F0);
    }

    private int dp(int v) {
        return Math.round(v * getResources().getDisplayMetrics().density);
    }

    // ---------- Плёнка обрезки ----------

    interface TrimListener {
        void onRange(int startMs, int endMs, int seekToMs);

        void onRangeCommitted(int startMs, int endMs, int oldStartMs, int oldEndMs);
    }

    /** Полоса обрезки в стиле iOS/Gorilla: кадры плёнки, жёлто-акцентная рамка, ручки, позиция. */
    static class TrimBar extends View {
        TrimListener listener;
        private int duration = 1, start = 0, end = 1, playhead = 0;
        private int downStart, downEnd;
        private final Paint track = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint dim = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint handle = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint grip = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint head = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint bmp = new Paint(Paint.FILTER_BITMAP_FLAG);
        private final float d;
        private int drag = 0; // 1 — левая, 2 — правая, 3 — позиция
        private List<Bitmap> frames = new ArrayList<>();
        private final android.graphics.Path clip = new android.graphics.Path();

        TrimBar(Context ctx) {
            super(ctx);
            d = ctx.getResources().getDisplayMetrics().density;
            track.setColor(0xFF1C1C28);
            dim.setColor(0x8A000000);
            int acc = Theming.accent(ctx);
            handle.setColor(acc);
            grip.setColor(Theming.isDark(ctx) ? 0xFF1E1E1E : 0xFF1E1E1E);
            grip.setStrokeWidth(2f * d);
            grip.setStrokeCap(Paint.Cap.ROUND);
            head.setColor(0xFFFFFFFF);
            head.setShadowLayer(3f * d, 0, 0, 0x80000000);
            setLayerType(LAYER_TYPE_SOFTWARE, null);
        }

        void setFrames(List<Bitmap> f) {
            frames = f == null ? new ArrayList<>() : f;
            invalidate();
        }

        void setDuration(int durationMs, int s, int e) {
            duration = Math.max(1, durationMs);
            start = Math.max(0, s);
            end = Math.min(duration, e <= 0 ? duration : e);
            invalidate();
        }

        void setPlayhead(int ms) {
            playhead = ms;
            invalidate();
        }

        private float hw() {
            return 16f * d;
        }

        private float xOf(int ms) {
            return hw() + (getWidth() - hw() * 2) * ms / (float) duration;
        }

        private int msOf(float x) {
            float f = (x - hw()) / (getWidth() - hw() * 2);
            return Math.round(Math.max(0f, Math.min(1f, f)) * duration);
        }

        @Override
        protected void onDraw(Canvas c) {
            float top = 8f * d, bottom = getHeight() - 8f * d;
            float r = 10f * d;
            RectF body = new RectF(hw(), top, getWidth() - hw(), bottom);
            c.drawRoundRect(body, r, r, track);
            // кадры плёнки
            if (!frames.isEmpty()) {
                c.save();
                clip.reset();
                clip.addRoundRect(body, r, r, android.graphics.Path.Direction.CW);
                c.clipPath(clip);
                float x = body.left;
                float h = body.height();
                int i = 0;
                while (x < body.right && i < 1000) {
                    Bitmap b = frames.get(i % frames.size());
                    float w = h * b.getWidth() / (float) b.getHeight();
                    c.drawBitmap(b, null, new RectF(x, body.top, x + w, body.bottom), bmp);
                    x += w;
                    i++;
                }
                c.restore();
            }
            float xs = xOf(start), xe = xOf(end);
            // затемнение вне отрезка
            c.drawRect(body.left, top, xs, bottom, dim);
            c.drawRect(xe, top, body.right, bottom, dim);
            // рамка выбранного отрезка
            float bw = 3f * d;
            c.drawRect(xs, top, xe, top + bw, handle);
            c.drawRect(xs, bottom - bw, xe, bottom, handle);
            RectF hl = new RectF(xs - hw(), top, xs, bottom);
            RectF hr = new RectF(xe, top, xe + hw(), bottom);
            c.drawRoundRect(hl, r, r, handle);
            c.drawRect(xs - r, top, xs, bottom, handle);
            c.drawRoundRect(hr, r, r, handle);
            c.drawRect(xe, top, xe + r, bottom, handle);
            // риски на ручках
            float cy = (top + bottom) / 2f;
            c.drawLine(xs - hw() / 2f, cy - 9f * d, xs - hw() / 2f, cy + 9f * d, grip);
            c.drawLine(xe + hw() / 2f, cy - 9f * d, xe + hw() / 2f, cy + 9f * d, grip);
            // позиция воспроизведения
            float xp = xOf(Math.max(start, Math.min(end, playhead)));
            c.drawRoundRect(xp - 1.5f * d, top - 4f * d, xp + 1.5f * d, bottom + 4f * d, 3f, 3f, head);
        }

        @Override
        public boolean onTouchEvent(MotionEvent e) {
            float x = e.getX();
            switch (e.getActionMasked()) {
                case MotionEvent.ACTION_DOWN: {
                    float xs = xOf(start), xe = xOf(end), t = 26f * d;
                    if (Math.abs(x - (xs - hw() / 2f)) < t) drag = 1;
                    else if (Math.abs(x - (xe + hw() / 2f)) < t) drag = 2;
                    else if (x > xs && x < xe) drag = 3;
                    else drag = 0;
                    downStart = start;
                    downEnd = end;
                    if (drag != 0) getParent().requestDisallowInterceptTouchEvent(true);
                    if (drag == 3) {
                        playhead = msOf(x);
                        if (listener != null) listener.onRange(start, end, playhead);
                        invalidate();
                    }
                    return drag != 0;
                }
                case MotionEvent.ACTION_MOVE: {
                    int ms = msOf(x);
                    int minLen = Math.min(duration, 500);
                    if (drag == 1) {
                        start = Math.max(0, Math.min(end - minLen, ms));
                        playhead = start;
                        if (listener != null) listener.onRange(start, end, start);
                    } else if (drag == 2) {
                        end = Math.min(duration, Math.max(start + minLen, ms));
                        playhead = end;
                        if (listener != null) listener.onRange(start, end, end);
                    } else if (drag == 3) {
                        playhead = Math.max(start, Math.min(end, ms));
                        if (listener != null) listener.onRange(start, end, playhead);
                    }
                    invalidate();
                    return true;
                }
                case MotionEvent.ACTION_UP:
                case MotionEvent.ACTION_CANCEL:
                    if ((drag == 1 || drag == 2) && listener != null) {
                        listener.onRangeCommitted(start, end, downStart, downEnd);
                    }
                    drag = 0;
                    return true;
                default:
                    return false;
            }
        }
    }
}
