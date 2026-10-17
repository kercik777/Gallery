package com.premiumlab.galleryx.editor;

import android.content.Context;
import android.graphics.Color;
import android.graphics.ColorMatrix;
import android.graphics.RectF;
import android.net.Uri;
import android.os.Handler;
import android.os.Looper;
import android.util.Pair;

import androidx.media3.common.C;
import androidx.media3.common.Effect;
import androidx.media3.common.MediaItem;
import androidx.media3.common.audio.AudioProcessor;
import androidx.media3.common.audio.ChannelMixingAudioProcessor;
import androidx.media3.common.audio.ChannelMixingMatrix;
import androidx.media3.common.audio.SpeedProvider;
import androidx.media3.effect.Crop;
import androidx.media3.effect.ScaleAndRotateTransformation;
import androidx.media3.effect.SingleColorLut;
import androidx.media3.effect.SpeedChangeEffect;
import androidx.media3.transformer.Composition;
import androidx.media3.transformer.EditedMediaItem;
import androidx.media3.transformer.Effects;
import androidx.media3.transformer.ExportException;
import androidx.media3.transformer.ExportResult;
import androidx.media3.transformer.ProgressHolder;
import androidx.media3.transformer.Transformer;

import java.io.File;
import java.util.ArrayList;
import java.util.List;

/**
 * Экспорт видео с перекодированием через Media3 Transformer: обрезка, поворот,
 * отражение, кадрирование, LUT-фильтр, скорость, громкость / без звука.
 */
public final class VideoExporter {

    public interface Callback {
        void onProgress(int percent);

        void onDone(File out);

        void onFailed(Throwable t);
    }

    private final Handler main = new Handler(Looper.getMainLooper());
    private Transformer transformer;
    private final ProgressHolder holder = new ProgressHolder();
    private boolean cancelled = false;

    private final Runnable poll = new Runnable() {
        @Override
        public void run() {
            if (transformer == null) return;
            int state = transformer.getProgress(holder);
            if (state == Transformer.PROGRESS_STATE_AVAILABLE && callback != null) {
                callback.onProgress(holder.progress);
            }
            main.postDelayed(this, 300);
        }
    };
    private Callback callback;

    @androidx.annotation.OptIn(markerClass = androidx.media3.common.util.UnstableApi.class)
    public void start(Context ctx, String src, File out, VideoEditState st, boolean hasAudio,
                      int durationMs, Callback cb) {
        callback = cb;
        cancelled = false;
        MediaItem.Builder mi = new MediaItem.Builder().setUri(Uri.fromFile(new File(src)));
        int end = st.endMs > 0 ? st.endMs : durationMs;
        if (st.startMs > 0 || (durationMs > 0 && end < durationMs - 100)) {
            mi.setClippingConfiguration(new MediaItem.ClippingConfiguration.Builder()
                    .setStartPositionMs(st.startMs)
                    .setEndPositionMs(end)
                    .build());
        }
        List<Effect> video = new ArrayList<>();
        List<AudioProcessor> audio = new ArrayList<>();

        // 1. Поворот (по часовой у нас → против часовой у Media3) и отражение
        if (st.rotation != 0 || st.flipH) {
            ScaleAndRotateTransformation.Builder b = new ScaleAndRotateTransformation.Builder();
            if (st.flipH) b.setScale(-1f, 1f);
            if (st.rotation != 0) b.setRotationDegrees((360 - st.rotation) % 360);
            video.add(b.build());
        }
        // 2. Кадрирование (нормализованный кадр → NDC, ось Y вверх)
        if (st.hasCrop()) {
            RectF c = st.crop;
            float left = c.left * 2f - 1f, right = c.right * 2f - 1f;
            float top = 1f - c.top * 2f, bottom = 1f - c.bottom * 2f;
            video.add(new Crop(left, right, bottom, top));
        }
        // 3. Фильтр — точная 3D-LUT из той же цветовой матрицы, что и превью
        if (st.preset != 0) {
            video.add(SingleColorLut.createFromCube(lutCube(Filters.matrix(st.preset), 33)));
        }
        // 4. Скорость
        boolean removeAudio = st.mute || !hasAudio;
        if (Math.abs(st.speed - 1f) > 0.01f) {
            if (removeAudio) {
                video.add(new SpeedChangeEffect(st.speed));
            } else {
                final float sp = st.speed;
                Pair<AudioProcessor, Effect> pair = Effects.createExperimentalSpeedChangingEffect(
                        new SpeedProvider() {
                            @Override
                            public float getSpeed(long timeUs) {
                                return sp;
                            }

                            @Override
                            public long getNextSpeedChangeTimeUs(long timeUs) {
                                return C.TIME_UNSET;
                            }
                        });
                audio.add(pair.first);
                video.add(pair.second);
            }
        }
        // 5. Громкость
        if (!removeAudio && st.volume != 100) {
            float v = st.volume / 100f;
            ChannelMixingAudioProcessor mix = new ChannelMixingAudioProcessor();
            for (int ch = 1; ch <= 8; ch++) {
                try {
                    mix.putChannelMixingMatrix(ChannelMixingMatrix.create(ch, ch).scaleBy(v));
                } catch (Exception ignored) {
                }
            }
            audio.add(mix);
        }

        EditedMediaItem item = new EditedMediaItem.Builder(mi.build())
                .setRemoveAudio(removeAudio)
                .setEffects(new Effects(audio, video))
                .build();
        transformer = new Transformer.Builder(ctx)
                .addListener(new Transformer.Listener() {
                    @Override
                    public void onCompleted(Composition composition, ExportResult exportResult) {
                        main.removeCallbacks(poll);
                        transformer = null;
                        if (callback != null && !cancelled) callback.onDone(out);
                    }

                    @Override
                    public void onError(Composition composition, ExportResult exportResult,
                                        ExportException exportException) {
                        main.removeCallbacks(poll);
                        transformer = null;
                        if (callback != null && !cancelled) callback.onFailed(exportException);
                    }
                })
                .build();
        transformer.start(item, out.getAbsolutePath());
        main.postDelayed(poll, 300);
    }

    public void cancel() {
        cancelled = true;
        main.removeCallbacks(poll);
        if (transformer != null) {
            try {
                transformer.cancel();
            } catch (Exception ignored) {
            }
            transformer = null;
        }
    }

    /** Куб N×N×N: cube[r][g][b] = цвет после применения матрицы к (r,g,b). */
    static int[][][] lutCube(ColorMatrix m, int n) {
        float[] a = m.getArray();
        int[][][] cube = new int[n][n][n];
        for (int r = 0; r < n; r++) {
            float fr = r * 255f / (n - 1);
            for (int g = 0; g < n; g++) {
                float fg = g * 255f / (n - 1);
                for (int b = 0; b < n; b++) {
                    float fb = b * 255f / (n - 1);
                    int or = clamp(a[0] * fr + a[1] * fg + a[2] * fb + a[4]);
                    int og = clamp(a[5] * fr + a[6] * fg + a[7] * fb + a[9]);
                    int ob = clamp(a[10] * fr + a[11] * fg + a[12] * fb + a[14]);
                    cube[r][g][b] = Color.argb(255, or, og, ob);
                }
            }
        }
        return cube;
    }

    private static int clamp(float v) {
        return v < 0 ? 0 : v > 255 ? 255 : Math.round(v);
    }
}
