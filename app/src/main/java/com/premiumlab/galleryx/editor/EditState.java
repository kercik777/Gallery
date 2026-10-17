package com.premiumlab.galleryx.editor;

import android.graphics.ColorMatrix;
import android.graphics.RectF;

import java.util.ArrayList;
import java.util.List;

/**
 * Состояние редактора фото: коррекции света/цвета, фильтр, поворот/отражение,
 * кадрирование, рисунки и надписи. Неизменяемых копий нет — для undo/redo
 * используется {@link #copy()}.
 * <p>
 * Все координаты (кадр, штрихи, надписи) нормализованы в 0..1 относительно
 * «ориентированного» изображения (после поворота и отражения, до кадрирования).
 */
public final class EditState {

    // ---- Коррекции, все в диапазоне -1..1 (0 — без изменений) ----
    public float exposure, brilliance, brightness, highlights, shadows, contrast,
            saturation, vibrancy, warmth, vignette, sharpness;

    /** Индекс фильтра из {@link Filters}. */
    public int preset = 0;

    /** Поворот по часовой: 0, 90, 180, 270. */
    public int rotation = 0;
    public boolean flipH = false;

    /** Кадр (нормализованный) в ориентированном пространстве; null — без кадрирования. */
    public RectF crop = null;

    public final List<Stroke> strokes = new ArrayList<>();
    public final List<Label> labels = new ArrayList<>();

    /** Штрих рисунка: нормализованные точки, цвет, толщина (доля ширины), ластик, мягкость. */
    public static final class Stroke {
        public final List<float[]> points = new ArrayList<>();
        public int color;
        public float width;
        public boolean eraser;
        public boolean pencil;

        public Stroke copy() {
            Stroke s = new Stroke();
            for (float[] p : points) s.points.add(new float[]{p[0], p[1]});
            s.color = color;
            s.width = width;
            s.eraser = eraser;
            s.pencil = pencil;
            return s;
        }
    }

    /** Надпись: текст, цвет, центр (нормализованный), размер (доля ширины), поворот. */
    public static final class Label {
        public String text = "";
        public int color = 0xFFFFFFFF;
        public float x = 0.5f, y = 0.5f;
        public float size = 0.08f;
        public float angle = 0f;
        public boolean background = false;

        public Label copy() {
            Label l = new Label();
            l.text = text;
            l.color = color;
            l.x = x;
            l.y = y;
            l.size = size;
            l.angle = angle;
            l.background = background;
            return l;
        }
    }

    public EditState copy() {
        EditState e = new EditState();
        e.exposure = exposure;
        e.brilliance = brilliance;
        e.brightness = brightness;
        e.highlights = highlights;
        e.shadows = shadows;
        e.contrast = contrast;
        e.saturation = saturation;
        e.vibrancy = vibrancy;
        e.warmth = warmth;
        e.vignette = vignette;
        e.sharpness = sharpness;
        e.preset = preset;
        e.rotation = rotation;
        e.flipH = flipH;
        e.crop = crop == null ? null : new RectF(crop);
        for (Stroke s : strokes) e.strokes.add(s.copy());
        for (Label l : labels) e.labels.add(l.copy());
        return e;
    }

    public boolean isIdentity() {
        return exposure == 0 && brilliance == 0 && brightness == 0 && highlights == 0
                && shadows == 0 && contrast == 0 && saturation == 0 && vibrancy == 0
                && warmth == 0 && vignette == 0 && sharpness == 0 && preset == 0
                && rotation == 0 && !flipH && crop == null && strokes.isEmpty()
                && labels.isEmpty();
    }

    public float get(int option) {
        switch (option) {
            case OPT_EXPOSURE: return exposure;
            case OPT_BRILLIANCE: return brilliance;
            case OPT_BRIGHTNESS: return brightness;
            case OPT_HIGHLIGHTS: return highlights;
            case OPT_SHADOWS: return shadows;
            case OPT_CONTRAST: return contrast;
            case OPT_SATURATION: return saturation;
            case OPT_VIBRANCY: return vibrancy;
            case OPT_WARMTH: return warmth;
            case OPT_VIGNETTE: return vignette;
            case OPT_SHARPNESS: return sharpness;
            default: return 0f;
        }
    }

    public void set(int option, float v) {
        switch (option) {
            case OPT_EXPOSURE: exposure = v; break;
            case OPT_BRILLIANCE: brilliance = v; break;
            case OPT_BRIGHTNESS: brightness = v; break;
            case OPT_HIGHLIGHTS: highlights = v; break;
            case OPT_SHADOWS: shadows = v; break;
            case OPT_CONTRAST: contrast = v; break;
            case OPT_SATURATION: saturation = v; break;
            case OPT_VIBRANCY: vibrancy = v; break;
            case OPT_WARMTH: warmth = v; break;
            case OPT_VIGNETTE: vignette = v; break;
            case OPT_SHARPNESS: sharpness = v; break;
            default: break;
        }
    }

    public static final int OPT_EXPOSURE = 0, OPT_BRILLIANCE = 1, OPT_BRIGHTNESS = 2,
            OPT_HIGHLIGHTS = 3, OPT_SHADOWS = 4, OPT_CONTRAST = 5, OPT_SATURATION = 6,
            OPT_VIBRANCY = 7, OPT_WARMTH = 8, OPT_VIGNETTE = 9, OPT_SHARPNESS = 10;

    /** Итоговая цветовая матрица: фильтр + все коррекции. */
    public ColorMatrix colorMatrix() {
        ColorMatrix m = new ColorMatrix();
        m.postConcat(Filters.matrix(preset));
        m.postConcat(Filters.exposure(exposure));
        m.postConcat(Filters.brilliance(brilliance));
        m.postConcat(Filters.brightness(brightness));
        m.postConcat(Filters.highlights(highlights));
        m.postConcat(Filters.shadows(shadows));
        m.postConcat(Filters.contrast(contrast));
        m.postConcat(Filters.saturation(1f + saturation));
        m.postConcat(Filters.vibrancy(vibrancy));
        m.postConcat(Filters.warmth(warmth));
        return m;
    }

    // ---- Геометрия ----

    /** Поворот на 90° по часовой: пересчитываем кадр, штрихи и надписи. */
    public void rotateCw() {
        rotation = (rotation + 90) % 360;
        if (crop != null) {
            // (x, y) → (1 - y, x)
            crop = new RectF(1f - crop.bottom, crop.left, 1f - crop.top, crop.right);
        }
        for (Stroke s : strokes) {
            for (float[] p : s.points) {
                float x = p[0], y = p[1];
                p[0] = 1f - y;
                p[1] = x;
            }
        }
        for (Label l : labels) {
            float x = l.x, y = l.y;
            l.x = 1f - y;
            l.y = x;
            l.angle += 90f;
        }
    }

    /** Отражение по горизонтали. */
    public void flipHorizontal() {
        flipH = !flipH;
        if (crop != null) crop = new RectF(1f - crop.right, crop.top, 1f - crop.left, crop.bottom);
        for (Stroke s : strokes) for (float[] p : s.points) p[0] = 1f - p[0];
        for (Label l : labels) {
            l.x = 1f - l.x;
            l.angle = -l.angle;
        }
    }

    /** Размер ориентированного изображения для исходника w×h. */
    public int orientedWidth(int w, int h) {
        return rotation % 180 == 0 ? w : h;
    }

    public int orientedHeight(int w, int h) {
        return rotation % 180 == 0 ? h : w;
    }
}
