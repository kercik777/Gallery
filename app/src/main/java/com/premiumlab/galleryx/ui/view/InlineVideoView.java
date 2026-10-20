package com.premiumlab.galleryx.ui.view;

import android.content.Context;
import android.graphics.Matrix;
import android.graphics.SurfaceTexture;
import android.media.AudioAttributes;
import android.media.MediaPlayer;
import android.util.AttributeSet;
import android.view.Surface;
import android.view.TextureView;

import java.io.IOException;

/**
 * Лёгкий встроенный видеоплеер на TextureView для страниц просмотрщика.
 *
 * В отличие от VideoView (SurfaceView) не «дырявит» окно, поэтому страницу
 * с играющим видео можно свободно листать, тянуть вниз и анимировать.
 * Видео вписывается в границы view с сохранением пропорций.
 */
public class InlineVideoView extends TextureView implements TextureView.SurfaceTextureListener {

    public interface Listener {
        void onPrepared(int durationMs, int videoW, int videoH);

        void onPlayStateChanged(boolean playing);

        void onCompletion();

        void onError();
    }

    private MediaPlayer player;
    private Surface surface;
    private String path;
    private Listener listener;
    private boolean prepared = false;
    private boolean playWhenReady = false;
    private boolean released = true;
    private boolean muted = true;
    private int videoW, videoH;

    public InlineVideoView(Context context) {
        this(context, null);
    }

    public InlineVideoView(Context context, AttributeSet attrs) {
        super(context, attrs);
        setSurfaceTextureListener(this);
        setOpaque(false);
    }

    public void setListener(Listener l) {
        this.listener = l;
    }

    /** Назначает файл. Плеер создаётся лениво, когда появится поверхность. */
    public void setSource(String path) {
        if (path != null && path.equals(this.path) && !released) return;
        release();
        this.path = path;
        openIfPossible();
    }

    private void openIfPossible() {
        if (path == null || surface == null || !released) return;
        try {
            player = new MediaPlayer();
            released = false;
            prepared = false;
            player.setAudioAttributes(new AudioAttributes.Builder()
                    .setContentType(AudioAttributes.CONTENT_TYPE_MOVIE)
                    .setUsage(AudioAttributes.USAGE_MEDIA)
                    .build());
            player.setSurface(surface);
            player.setDataSource(path);
            player.setOnVideoSizeChangedListener((mp, w, h) -> {
                videoW = w;
                videoH = h;
                applyTransform();
            });
            player.setOnPreparedListener(mp -> {
                prepared = true;
                applyVolume();
                videoW = mp.getVideoWidth();
                videoH = mp.getVideoHeight();
                applyTransform();
                if (listener != null) listener.onPrepared(mp.getDuration(), videoW, videoH);
                if (playWhenReady) {
                    mp.start();
                    if (listener != null) listener.onPlayStateChanged(true);
                }
            });
            player.setOnCompletionListener(mp -> {
                if (listener != null) {
                    listener.onPlayStateChanged(false);
                    listener.onCompletion();
                }
            });
            player.setOnErrorListener((mp, what, extra) -> {
                if (listener != null) listener.onError();
                return true;
            });
            player.prepareAsync();
        } catch (IOException | IllegalStateException | IllegalArgumentException e) {
            release();
            if (listener != null) listener.onError();
        }
    }

    /** Старт (или продолжение) воспроизведения; до подготовки — отложенно. */
    public void play() {
        playWhenReady = true;
        if (player != null && prepared && !player.isPlaying()) {
            player.start();
            if (Math.abs(speed - 1f) > 0.01f) applySpeed();
            if (listener != null) listener.onPlayStateChanged(true);
        } else if (player == null) {
            openIfPossible();
        }
    }

    /** Без звука (по умолчанию) / со звуком. Применяется сразу и к следующим файлам. */
    public void setMuted(boolean muted) {
        this.muted = muted;
        applyVolume();
    }

    public boolean isMuted() {
        return muted;
    }

    private float volumeLevel = 1f;
    private float speed = 1f;

    /** Громкость 0..1 (для превью редактора; при «без звука» игнорируется). */
    public void setVolumeLevel(float level) {
        volumeLevel = Math.max(0f, Math.min(1f, level));
        applyVolume();
    }

    /** Скорость воспроизведения (применяется при старте и сразу, если уже играет). */
    public void setSpeed(float s) {
        speed = Math.max(0.25f, Math.min(4f, s));
        if (player != null && prepared && isPlaying()) applySpeed();
    }

    public float getSpeed() {
        return speed;
    }

    private void applySpeed() {
        if (player == null || !prepared) return;
        try {
            player.setPlaybackParams(player.getPlaybackParams().setSpeed(speed));
        } catch (Exception ignored) {
        }
    }

    private void applyVolume() {
        if (player == null || !prepared) return;
        try {
            float v = muted ? 0f : volumeLevel;
            player.setVolume(v, v);
        } catch (IllegalStateException ignored) {
        }
    }

    public void pause() {
        playWhenReady = false;
        if (player != null && prepared && player.isPlaying()) {
            player.pause();
            if (listener != null) listener.onPlayStateChanged(false);
        }
    }

    public int getVideoWidth() {
        return videoW;
    }

    public int getVideoHeight() {
        return videoH;
    }

    public boolean isPlaying() {
        try {
            return player != null && prepared && player.isPlaying();
        } catch (IllegalStateException e) {
            return false;
        }
    }

    public boolean isPrepared() {
        return prepared && player != null;
    }

    public int getPosition() {
        try {
            return player != null && prepared ? player.getCurrentPosition() : 0;
        } catch (IllegalStateException e) {
            return 0;
        }
    }

    public int getDuration() {
        try {
            return player != null && prepared ? player.getDuration() : 0;
        } catch (IllegalStateException e) {
            return 0;
        }
    }

    public void seekTo(int ms) {
        if (player != null && prepared) {
            try {
                if (android.os.Build.VERSION.SDK_INT >= 26) {
                    player.seekTo(ms, MediaPlayer.SEEK_CLOSEST);
                } else {
                    player.seekTo(ms);
                }
            } catch (IllegalStateException ignored) {
            }
        }
    }

    /** Полностью останавливает и освобождает плеер (страница ушла с экрана). */
    public void release() {
        playWhenReady = false;
        prepared = false;
        if (player != null) {
            try {
                player.reset();
            } catch (Exception ignored) {
            }
            try {
                player.release();
            } catch (Exception ignored) {
            }
            player = null;
        }
        path = null;
        released = true;
    }

    private final android.graphics.RectF displayRect = new android.graphics.RectF();
    private boolean hasDisplayRect = false;

    /**
     * Прямоугольник, в который надо вписать кадр (в координатах view) — его задаёт
     * ZoomableImageView при зуме/панораме, чтобы видео масштабировалось как фото.
     * null — обычное вписывание по центру.
     */
    public void setDisplayRect(android.graphics.RectF r) {
        if (r == null) {
            hasDisplayRect = false;
        } else {
            displayRect.set(r);
            hasDisplayRect = true;
        }
        applyTransform();
    }

    /** Вписывает кадр в view по центру с сохранением пропорций (letterbox). */
    private void applyTransform() {
        int vw = getWidth(), vh = getHeight();
        if (vw == 0 || vh == 0 || videoW == 0 || videoH == 0) return;
        if (hasDisplayRect && displayRect.width() > 0 && displayRect.height() > 0) {
            // Буфер растянут на весь view — переводим его в заданный прямоугольник
            Matrix m = new Matrix();
            m.setScale(displayRect.width() / vw, displayRect.height() / vh);
            m.postTranslate(displayRect.left, displayRect.top);
            setTransform(m);
            invalidate();
            return;
        }
        float sx = (float) videoW / vw;
        float sy = (float) videoH / vh;
        float scale = 1f / Math.max(sx, sy);
        Matrix m = new Matrix();
        m.setScale(sx * scale, sy * scale, vw / 2f, vh / 2f);
        setTransform(m);
        invalidate();
    }

    @Override
    protected void onSizeChanged(int w, int h, int oldw, int oldh) {
        super.onSizeChanged(w, h, oldw, oldh);
        applyTransform();
    }

    // ---------- SurfaceTextureListener ----------

    @Override
    public void onSurfaceTextureAvailable(SurfaceTexture st, int width, int height) {
        surface = new Surface(st);
        if (player != null && !released) {
            player.setSurface(surface);
        } else {
            openIfPossible();
        }
    }

    @Override
    public void onSurfaceTextureSizeChanged(SurfaceTexture st, int width, int height) {
        applyTransform();
    }

    @Override
    public boolean onSurfaceTextureDestroyed(SurfaceTexture st) {
        if (player != null) {
            try {
                player.setSurface(null);
            } catch (Exception ignored) {
            }
        }
        if (surface != null) {
            surface.release();
            surface = null;
        }
        return true;
    }

    @Override
    public void onSurfaceTextureUpdated(SurfaceTexture st) {
    }
}
