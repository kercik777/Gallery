package com.premiumlab.galleryx.editor;

import android.graphics.RectF;

/** Состояние видеоредактора (снимок для undo/redo). */
public class VideoEditState {
    public int startMs = 0;
    public int endMs = 0;          // 0 — до конца
    public int rotation = 0;       // добавочный поворот по часовой: 0/90/180/270
    public boolean flipH = false;
    public boolean mute = false;
    public int volume = 100;       // 0..200 %
    public float speed = 1f;       // 0.5 / 1 / 1.5 / 2
    public int preset = 0;         // Filters.NAMES
    public int aspectIdx = 0;      // индекс пропорции (0 — оригинал)
    public RectF crop = null;      // нормализованный кадр в повернутом кадре, null — весь

    public VideoEditState copy() {
        VideoEditState s = new VideoEditState();
        s.startMs = startMs;
        s.endMs = endMs;
        s.rotation = rotation;
        s.flipH = flipH;
        s.mute = mute;
        s.volume = volume;
        s.speed = speed;
        s.preset = preset;
        s.aspectIdx = aspectIdx;
        s.crop = crop == null ? null : new RectF(crop);
        return s;
    }

    public boolean hasCrop() {
        return crop != null && !(crop.left <= 0.001f && crop.top <= 0.001f
                && crop.right >= 0.999f && crop.bottom >= 0.999f);
    }

    /** Нужно ли перекодирование (иначе достаточно быстрого ремукса). */
    public boolean needsTranscode() {
        return flipH || preset != 0 || hasCrop() || Math.abs(speed - 1f) > 0.01f
                || (!mute && volume != 100);
    }

    public boolean changed(int durationMs) {
        return startMs > 0 || (endMs > 0 && durationMs > 0 && endMs < durationMs - 100)
                || rotation != 0 || flipH || mute || volume != 100 || Math.abs(speed - 1f) > 0.01f
                || preset != 0 || hasCrop();
    }

    public boolean sameAs(VideoEditState o) {
        if (o == null) return false;
        boolean cropSame = (crop == null && o.crop == null)
                || (crop != null && o.crop != null && Math.abs(crop.left - o.crop.left) < 1e-4
                && Math.abs(crop.top - o.crop.top) < 1e-4 && Math.abs(crop.right - o.crop.right) < 1e-4
                && Math.abs(crop.bottom - o.crop.bottom) < 1e-4);
        return startMs == o.startMs && endMs == o.endMs && rotation == o.rotation && flipH == o.flipH
                && mute == o.mute && volume == o.volume && Math.abs(speed - o.speed) < 1e-4
                && preset == o.preset && aspectIdx == o.aspectIdx && cropSame;
    }
}
