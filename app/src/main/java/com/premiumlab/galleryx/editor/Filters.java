package com.premiumlab.galleryx.editor;

import android.graphics.ColorMatrix;

/**
 * Цветовые матрицы редактора: пресеты фильтров и элементарные коррекции.
 * Все коррекции принимают значение -1..1 (0 — без изменений), кроме saturation (0..2).
 */
public final class Filters {

    private Filters() {
    }

    /** Количество пресетов (имена — в ресурсах, см. {@link #names}). */
    public static final int COUNT = 12;

    /** Локализованные названия пресетов. */
    public static String[] names(android.content.Context ctx) {
        return ctx.getResources().getStringArray(com.premiumlab.galleryx.R.array.editor_filter_names);
    }

    public static ColorMatrix matrix(int preset) {
        ColorMatrix m = new ColorMatrix();
        switch (preset) {
            case 1: // Яркий
                m.postConcat(saturation(1.4f));
                m.postConcat(contrast(0.15f));
                break;
            case 2: // Холодный
                m.postConcat(warmth(-0.35f));
                break;
            case 3: // Тёплый
                m.postConcat(warmth(0.35f));
                break;
            case 4: // Выцветший
                m.postConcat(contrast(-0.2f));
                m.postConcat(brightness(0.08f));
                m.postConcat(saturation(0.85f));
                break;
            case 5: // Моно
                m.postConcat(saturation(0f));
                break;
            case 6: // Нуар
                m.postConcat(saturation(0f));
                m.postConcat(contrast(0.4f));
                m.postConcat(shadows(-0.3f));
                break;
            case 7: // Сепия
                m.postConcat(new ColorMatrix(new float[]{
                        0.393f, 0.769f, 0.189f, 0, 0,
                        0.349f, 0.686f, 0.168f, 0, 0,
                        0.272f, 0.534f, 0.131f, 0, 0,
                        0, 0, 0, 1, 0}));
                m.postConcat(contrast(0.1f));
                break;
            case 8: // Полароид
                m.postConcat(contrast(-0.1f));
                m.postConcat(shadows(0.2f));
                m.postConcat(warmth(0.2f));
                m.postConcat(new ColorMatrix(new float[]{
                        1, 0, 0, 0, 15,
                        0, 1, 0, 0, 0,
                        0, 0, 1, 0, 10,
                        0, 0, 0, 1, 0}));
                m.postConcat(saturation(0.8f));
                break;
            case 9: // Кино
                m.postConcat(contrast(0.2f));
                m.postConcat(saturation(1.1f));
                m.postConcat(new ColorMatrix(new float[]{
                        1.1f, 0, 0, 0, 5,
                        0, 1f, 0, 0, 0,
                        0, 0, 1.2f, 0, -10,
                        0, 0, 0, 1, 0}));
                break;
            case 10: // Пастель
                m.postConcat(contrast(-0.4f));
                m.postConcat(brightness(0.1f));
                m.postConcat(saturation(0.6f));
                m.postConcat(shadows(0.3f));
                break;
            case 11: // Панк
                m.postConcat(contrast(0.3f));
                m.postConcat(saturation(1.3f));
                m.postConcat(new ColorMatrix(new float[]{
                        1.2f, 0, 0, 0, 0,
                        0, 0.8f, 0, 0, 0,
                        0, 0, 1.3f, 0, 0,
                        0, 0, 0, 1, 0}));
                break;
            default:
                break;
        }
        return m;
    }

    public static ColorMatrix exposure(float e) {
        float s = 1f + e * 0.65f;
        return scale(s, s, s, 0f);
    }

    public static ColorMatrix brilliance(float b) {
        ColorMatrix m = new ColorMatrix();
        m.postConcat(contrast(b * 0.24f));
        m.postConcat(brightness(b * 0.16f));
        m.postConcat(saturation(1f + b * 0.12f));
        return m;
    }

    public static ColorMatrix brightness(float b) {
        float o = b * 100f;
        return scale(1f, 1f, 1f, o);
    }

    public static ColorMatrix contrast(float c) {
        float s = 1f + c;
        float t = (1f - s) * 127.5f;
        return scale(s, s, s, t);
    }

    public static ColorMatrix saturation(float s) {
        ColorMatrix m = new ColorMatrix();
        m.setSaturation(Math.max(0f, s));
        return m;
    }

    public static ColorMatrix highlights(float h) {
        float s = 1f + h * 0.34f;
        float t = (1f - s) * 192f;
        return scale(s, s, s, t);
    }

    public static ColorMatrix shadows(float v) {
        float s = 1f - v * 0.34f;
        float t = (1f - s) * 64f;
        return scale(s, s, s, t);
    }

    public static ColorMatrix vibrancy(float v) {
        return saturation(1f + v * 0.55f);
    }

    public static ColorMatrix warmth(float w) {
        return new ColorMatrix(new float[]{
                1f + w * 0.25f, 0, 0, 0, 0,
                0, 1, 0, 0, 0,
                0, 0, 1f - w * 0.25f, 0, 0,
                0, 0, 0, 1, 0});
    }

    private static ColorMatrix scale(float r, float g, float b, float offset) {
        return new ColorMatrix(new float[]{
                r, 0, 0, 0, offset,
                0, g, 0, 0, offset,
                0, 0, b, 0, offset,
                0, 0, 0, 1, 0});
    }
}
