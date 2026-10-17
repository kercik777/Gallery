package com.premiumlab.galleryx.resizer;

import android.graphics.Color;

/**
 * Настройки изменения размера / сжатия. Один набор применяется ко всем
 * выбранным фотографиям (пакетная обработка); размеры, зависящие от
 * конкретного снимка (проценты, «вписать в рамку», длинная сторона),
 * считаются для каждого файла отдельно в {@link ImageResizer#targetSize}.
 */
public final class ResizeOptions {

    // ---- Режим задания размера ----
    public static final int SIZE_PIXELS = 0;
    public static final int SIZE_PERCENT = 1;
    public static final int SIZE_LONG_SIDE = 2;
    public static final int SIZE_PRINT = 3;

    // ---- Как вписывать изображение в холст другой пропорции ----
    public static final int FIT_CROP = 0;     // обрезать по размеру
    public static final int FIT_INSIDE = 1;   // вписать целиком (с фоном)
    public static final int FIT_STRETCH = 2;  // растянуть

    // ---- Фон при «вписать целиком» ----
    public static final int PAD_BLUR = 0;
    public static final int PAD_COLOR = 1;

    // ---- Формат ----
    public static final int FMT_JPEG = 0;
    public static final int FMT_PNG = 1;
    public static final int FMT_WEBP = 2;

    // ---- Единицы печати ----
    public static final int UNIT_MM = 0;
    public static final int UNIT_CM = 1;
    public static final int UNIT_INCH = 2;

    public static final int MAX_SIDE = 16384;

    public int sizeMode = SIZE_PIXELS;

    /** Пиксели: рамка (при keepAspect) или точный размер. 0 — не задано. */
    public int width = 0;
    public int height = 0;
    public boolean keepAspect = true;

    /** Проценты от исходного (1..400). */
    public int percent = 100;

    /** Длинная сторона в пикселях (без увеличения маленьких снимков). */
    public int longSide = 1600;

    /** Печать: размеры в выбранных единицах и плотность. */
    public int printUnit = UNIT_MM;
    public float printWidth = 150f;
    public float printHeight = 100f;
    public int dpi = 300;

    /** Пропорции холста: 0 — как у исходника; иначе ширина/высота. */
    public float aspect = 0f;
    public int fitMode = FIT_CROP;
    public int padMode = PAD_BLUR;
    public int padColor = Color.WHITE;

    public int format = FMT_JPEG;
    /** Качество JPEG/WebP (1..100); для PNG не используется. */
    public int quality = 85;
    /** Целевой размер файла в байтах; 0 — без ограничения. */
    public long targetBytes = 0;
    /** Цвет, которым заливается прозрачность при сохранении в JPEG. */
    public int jpegBackground = Color.WHITE;

    /** Шаблон имени: {name} {w} {h} {n} {date}. */
    public String namePattern = "{name}_{w}x{h}";

    public ResizeOptions copy() {
        ResizeOptions o = new ResizeOptions();
        o.sizeMode = sizeMode;
        o.width = width;
        o.height = height;
        o.keepAspect = keepAspect;
        o.percent = percent;
        o.longSide = longSide;
        o.printUnit = printUnit;
        o.printWidth = printWidth;
        o.printHeight = printHeight;
        o.dpi = dpi;
        o.aspect = aspect;
        o.fitMode = fitMode;
        o.padMode = padMode;
        o.padColor = padColor;
        o.format = format;
        o.quality = quality;
        o.targetBytes = targetBytes;
        o.jpegBackground = jpegBackground;
        o.namePattern = namePattern;
        return o;
    }

    /** Расширение выходного файла. */
    public String extension() {
        switch (format) {
            case FMT_PNG:
                return ".png";
            case FMT_WEBP:
                return ".webp";
            default:
                return ".jpg";
        }
    }

    public String mime() {
        switch (format) {
            case FMT_PNG:
                return "image/png";
            case FMT_WEBP:
                return "image/webp";
            default:
                return "image/jpeg";
        }
    }

    /** Размер печати в пикселях по выбранным единицам и DPI. */
    public int printPixels(float value) {
        float inches;
        switch (printUnit) {
            case UNIT_CM:
                inches = value / 2.54f;
                break;
            case UNIT_INCH:
                inches = value;
                break;
            default:
                inches = value / 25.4f;
        }
        return Math.max(1, Math.round(inches * Math.max(1, dpi)));
    }

    /** Строка-«подпись» набора настроек — чтобы не сохранять дважды одно и то же. */
    public String signature() {
        return sizeMode + "|" + width + "x" + height + "|" + keepAspect + "|" + percent + "|"
                + longSide + "|" + printUnit + ":" + printWidth + "x" + printHeight + "@" + dpi
                + "|" + aspect + "|" + fitMode + "|" + padMode + "|" + padColor + "|" + format
                + "|" + quality + "|" + targetBytes + "|" + jpegBackground + "|" + namePattern;
    }
}
