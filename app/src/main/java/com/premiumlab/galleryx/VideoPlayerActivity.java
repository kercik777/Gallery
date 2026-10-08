package com.premiumlab.galleryx;

import android.annotation.SuppressLint;
import android.content.pm.ActivityInfo;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewConfiguration;
import android.view.WindowManager;
import android.widget.ImageView;
import android.widget.SeekBar;
import android.widget.TextView;
import android.widget.Toast;
import android.widget.VideoView;

import androidx.appcompat.app.AppCompatActivity;

import com.premiumlab.galleryx.data.MediaItem;
import com.premiumlab.galleryx.data.Prefs;
import com.premiumlab.galleryx.ui.Actions;
import com.premiumlab.galleryx.util.Fmt;

import java.io.File;
import java.util.Collections;

/**
 * Видеоплеер с кастомными премиум-управлением: play/pause, перемотка,
 * полный экран с поворотом, автоскрытие панелей.
 * Свайп вниз — закрыть, свайп вверх — свойства файла.
 */
public class VideoPlayerActivity extends AppCompatActivity {

    private VideoView videoView;
    private View topBar, bottomBar, btnPlayPause, progress;
    private TextView txtTitle, txtCur, txtDur;
    private SeekBar seek;
    private ImageView btnFullscreen;
    private String path, name;
    private int mode;
    private boolean controlsVisible = true;
    private boolean seeking = false;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private final Runnable hideControls = this::hideBars;
    private final Runnable progressTick = this::updateProgress;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        if (Prefs.flagSecure()) {
            getWindow().addFlags(WindowManager.LayoutParams.FLAG_SECURE);
        }
        setContentView(R.layout.activity_video_player);

        path = getIntent().getStringExtra("path");
        name = getIntent().getStringExtra("name");
        mode = getIntent().getIntExtra("mode", 0);
        if (path == null) {
            finish();
            return;
        }
        if (name == null) name = new File(path).getName();

        videoView = findViewById(R.id.videoView);
        topBar = findViewById(R.id.videoTopBar);
        bottomBar = findViewById(R.id.videoBottomBar);
        btnPlayPause = findViewById(R.id.btnPlayPause);
        progress = findViewById(R.id.progressVideo);
        txtTitle = findViewById(R.id.txtVTitle);
        txtCur = findViewById(R.id.txtCurPos);
        txtDur = findViewById(R.id.txtDurPos);
        seek = findViewById(R.id.seekVideo);
        btnFullscreen = findViewById(R.id.btnFullscreen);

        txtTitle.setText(name);
        findViewById(R.id.btnVBack).setOnClickListener(v -> close());
        setupGestures(findViewById(R.id.viewTapCatcher));

        setupPlayer();
        setupSeekbar();
        setupFullscreen();
        wireDelete();

        videoView.setVideoPath(path);
        videoView.start();
    }

    private void setupPlayer() {
        videoView.setOnPreparedListener(mp -> {
            progress.setVisibility(View.GONE);
            int duration = mp.getDuration();
            seek.setMax(Math.max(1, duration));
            txtDur.setText(Fmt.duration(duration));
            showBars();
            // Горизонтальные видео автоматически открываются в ландшафте
            if (mp.getVideoWidth() > mp.getVideoHeight()) {
                setRequestedOrientation(ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE);
            }
        });
        videoView.setOnCompletionListener(mp -> {
            ((ImageView) btnPlayPause).setImageResource(R.drawable.ic_play);
            btnPlayPause.setTag("paused");
            showBars();
        });
        videoView.setOnErrorListener((mp, what, extra) -> {
            progress.setVisibility(View.GONE);
            Toast.makeText(this, R.string.video_error, Toast.LENGTH_LONG).show();
            finish();
            return true;
        });

        btnPlayPause.setOnClickListener(v -> {
            if (videoView.isPlaying()) {
                videoView.pause();
                ((ImageView) btnPlayPause).setImageResource(R.drawable.ic_play);
                btnPlayPause.setTag("paused");
                showBars();
            } else {
                videoView.start();
                ((ImageView) btnPlayPause).setImageResource(R.drawable.ic_pause);
                btnPlayPause.setTag("playing");
                scheduleHide();
            }
        });

        // Перемотка на 10 секунд назад/вперёд
        findViewById(R.id.btnRewind).setOnClickListener(v -> skip(-10_000));
        findViewById(R.id.btnForward).setOnClickListener(v -> skip(10_000));
    }

    private void skip(int deltaMs) {
        if (videoView == null) return;
        int target = videoView.getCurrentPosition() + deltaMs;
        target = Math.max(0, Math.min(target, Math.max(0, videoView.getDuration() - 50)));
        videoView.seekTo(target);
        seek.setProgress(target);
        txtCur.setText(Fmt.duration(target));
        scheduleHide();
    }

    private void setupSeekbar() {
        seek.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override
            public void onProgressChanged(SeekBar seekBar, int value, boolean fromUser) {
                if (fromUser) {
                    txtCur.setText(Fmt.duration(value));
                }
            }

            @Override
            public void onStartTrackingTouch(SeekBar seekBar) {
                seeking = true;
                cancelHide();
            }

            @Override
            public void onStopTrackingTouch(SeekBar seekBar) {
                videoView.seekTo(seekBar.getProgress());
                seeking = false;
                scheduleHide();
            }
        });
    }

    private void setupFullscreen() {
        btnFullscreen.setOnClickListener(v -> {
            int orient = getRequestedOrientation();
            if (orient == ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
                    || orient == ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE) {
                setRequestedOrientation(ActivityInfo.SCREEN_ORIENTATION_PORTRAIT);
                btnFullscreen.setImageResource(R.drawable.ic_fullscreen);
            } else {
                setRequestedOrientation(ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE);
                btnFullscreen.setImageResource(R.drawable.ic_fullscreen_exit);
            }
        });
    }

    // ---------- Жесты: тап, свайп вниз (закрыть), свайп вверх (свойства) ----------

    @SuppressLint("ClickableViewAccessibility")
    private void setupGestures(View catcher) {
        final int slop = ViewConfiguration.get(this).getScaledTouchSlop();
        final float density = getResources().getDisplayMetrics().density;
        final View content = findViewById(R.id.videoRoot);
        catcher.setOnTouchListener(new View.OnTouchListener() {
            float downX, downY;
            boolean moved;

            @Override
            public boolean onTouch(View v, MotionEvent e) {
                switch (e.getActionMasked()) {
                    case MotionEvent.ACTION_DOWN:
                        downX = e.getX();
                        downY = e.getY();
                        moved = false;
                        return true;
                    case MotionEvent.ACTION_MOVE: {
                        float dy = e.getY() - downY;
                        float dx = e.getX() - downX;
                        if (Math.abs(dy) > slop || Math.abs(dx) > slop) moved = true;
                        if (dy > 0 && Math.abs(dy) > Math.abs(dx)) {
                            content.setTranslationY(dy * 0.5f);
                        }
                        return true;
                    }
                    case MotionEvent.ACTION_UP:
                    case MotionEvent.ACTION_CANCEL: {
                        float dy = e.getY() - downY;
                        float dx = e.getX() - downX;
                        boolean vertical = Math.abs(dy) > Math.abs(dx) * 1.3f;
                        if (!moved) {
                            toggleBars();
                        } else if (vertical && dy > 110f * density) {
                            close();
                            return true;
                        } else if (vertical && dy < -90f * density) {
                            showInfo();
                        }
                        content.animate().translationY(0f).setDuration(180).start();
                        return true;
                    }
                    default:
                        return false;
                }
            }
        });
    }

    private void showInfo() {
        MediaItem item = MediaItem.fromFile(new File(path));
        item.name = name;
        Actions.showInfo(this, item);
    }

    /** Закрытие с анимацией «уезжает вниз». */
    private void close() {
        setRequestedOrientation(ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED);
        finish();
        overridePendingTransition(0, R.anim.slide_out_down);
    }

    // ---------- Панели ----------

    private void toggleBars() {
        if (controlsVisible) hideBars();
        else showBars();
    }

    private void showBars() {
        controlsVisible = true;
        topBar.setVisibility(View.VISIBLE);
        bottomBar.setVisibility(View.VISIBLE);
        btnPlayPause.setVisibility(View.VISIBLE);
        findViewById(R.id.btnRewind).setVisibility(View.VISIBLE);
        findViewById(R.id.btnForward).setVisibility(View.VISIBLE);
        scheduleHide();
    }

    private void hideBars() {
        if (seeking || !videoView.isPlaying()) return;
        controlsVisible = false;
        topBar.setVisibility(View.GONE);
        bottomBar.setVisibility(View.GONE);
        btnPlayPause.setVisibility(View.GONE);
        findViewById(R.id.btnRewind).setVisibility(View.GONE);
        findViewById(R.id.btnForward).setVisibility(View.GONE);
    }

    private void scheduleHide() {
        cancelHide();
        handler.postDelayed(hideControls, 3500);
    }

    private void cancelHide() {
        handler.removeCallbacks(hideControls);
    }

    private void updateProgress() {
        if (videoView.isPlaying() && !seeking) {
            int pos = videoView.getCurrentPosition();
            seek.setProgress(pos);
            txtCur.setText(Fmt.duration(pos));
        }
        handler.postDelayed(progressTick, 500);
    }

    // ---------- Удаление ----------

    private void wireDelete() {
        findViewById(R.id.btnVDelete).setOnClickListener(v -> {
            MediaItem item = MediaItem.fromFile(new File(path));
            item.name = name;
            if (mode == 1 || mode == 2) {
                Actions.confirmDeleteForever(this, Collections.singletonList(item), this::finish);
            } else {
                Actions.confirmTrash(this, Collections.singletonList(item), this::finish);
            }
        });
    }

    @Override
    protected void onStart() {
        super.onStart();
        handler.post(progressTick);
    }

    @Override
    protected void onStop() {
        super.onStop();
        handler.removeCallbacksAndMessages(null);
        videoView.pause();
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        handler.removeCallbacksAndMessages(null);
    }

    @Override
    public void onBackPressed() {
        setRequestedOrientation(ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED);
        super.onBackPressed();
    }
}
