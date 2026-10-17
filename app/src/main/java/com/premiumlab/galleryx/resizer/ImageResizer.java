package com.premiumlab.galleryx.resizer;

import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Matrix;
import android.graphics.Paint;
import android.os.Build;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.exifinterface.media.ExifInterface;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

/**
 * Движок изменения размера: чтение с учётом EXIF-поворота и без загрузки
 * полного кадра (inSampleSize), вписывание/обрезка/растягивание в холст нужной
 * пропорции, фон (размытие или цвет), кодирование в JPEG/PNG/WebP и подбор
 * качества (а при необходимости — разрешения) под целевой размер файла.
 *
 * Всё выполняется локально. Оригинал никогда не изменяется.
 */
public final class ImageResizer {

    private ImageResizer() {
    }

    // ---------- Ошибки ----------

    public static final class ResizeException extends IOException {
        public static final int UNSUPPORTED = 1;   // не изображение / повреждён
        public static final int TOO_LARGE = 2;     // результат не поместится в память
        public static final int OUT_OF_MEMORY = 3;
        public static final int WRITE_FAILED = 4;
        public static final int NO_SPACE = 5;
        public static final int CANCELLED = 6;

        public final int code;

        public ResizeException(int code, String msg) {
            super(msg);
            this.code = code;
        }
    }

    /** Флаг отмены для длинных операций. */
    public interface Cancel {
        boolean isCancelled();
    }

    // ---------- Описание исходника ----------

    public static final class Source {
        public String path;
        public String name;
        /** Размер «как видит пользователь» — уже с учётом EXIF-поворота. */
        public int width, height;
        public long bytes;
        public String mime;
        public boolean hasAlpha;
        int orientation = ExifInterface.ORIENTATION_NORMAL;
        /** Сырые размеры файла (до поворота). */
        int rawWidth, rawHeight;

        public boolean isPortrait() {
            return height > width;
        }
    }

    /** Читает размеры, ориентацию, MIME и наличие прозрачности, не декодируя кадр целиком. */
    @NonNull
    public static Source probe(@NonNull String path) throws ResizeException {
        File f = new File(path);
        if (!f.isFile() || !f.canRead()) {
            throw new ResizeException(ResizeException.UNSUPPORTED, "unreadable");
        }
        BitmapFactory.Options o = new BitmapFactory.Options();
        o.inJustDecodeBounds = true;
        BitmapFactory.decodeFile(path, o);
        if (o.outWidth <= 0 || o.outHeight <= 0) {
            throw new ResizeException(ResizeException.UNSUPPORTED, "not an image");
        }
        Source s = new Source();
        s.path = path;
        s.name = f.getName();
        s.bytes = f.length();
        s.mime = o.outMimeType == null ? "image/*" : o.outMimeType;
        s.rawWidth = o.outWidth;
        s.rawHeight = o.outHeight;
        try {
            s.orientation = new ExifInterface(path).getAttributeInt(
                    ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL);
        } catch (Exception ignored) {
        }
        boolean swapped = isSwapped(s.orientation);
        s.width = swapped ? s.rawHeight : s.rawWidth;
        s.height = swapped ? s.rawWidth : s.rawHeight;
        s.hasAlpha = detectAlpha(path, s.mime, s.rawWidth, s.rawHeight);
        return s;
    }

    private static boolean isSwapped(int orientation) {
        switch (orientation) {
            case ExifInterface.ORIENTATION_ROTATE_90:
            case ExifInterface.ORIENTATION_ROTATE_270:
            case ExifInterface.ORIENTATION_TRANSPOSE:
            case ExifInterface.ORIENTATION_TRANSVERSE:
                return true;
            default:
                return false;
        }
    }

    /** Прозрачность ищем только у форматов, которые её поддерживают, по уменьшенной копии. */
    private static boolean detectAlpha(String path, String mime, int w, int h) {
        String m = mime.toLowerCase(Locale.US);
        if (!(m.contains("png") || m.contains("webp") || m.contains("gif"))) return false;
        try {
            BitmapFactory.Options d = new BitmapFactory.Options();
            int sample = 1;
            while (w / (sample * 2) >= 96 && h / (sample * 2) >= 96) sample *= 2;
            d.inSampleSize = sample;
            d.inPreferredConfig = Bitmap.Config.ARGB_8888;
            Bitmap b = BitmapFactory.decodeFile(path, d);
            if (b == null) return false;
            boolean alpha = false;
            if (b.hasAlpha()) {
                int bw = b.getWidth(), bh = b.getHeight();
                int[] row = new int[bw];
                outer:
                for (int y = 0; y < bh; y++) {
                    b.getPixels(row, 0, bw, 0, y, bw, 1);
                    for (int x = 0; x < bw; x++) {
                        if ((row[x] >>> 24) < 0xFF) {
                            alpha = true;
                            break outer;
                        }
                    }
                }
            }
            b.recycle();
            return alpha;
        } catch (Throwable t) {
            return false;
        }
    }

    // ---------- Расчёт размера результата ----------

    /** Пропорция холста (ширина/высота) для данного исходника. */
    public static double canvasAspect(Source s, ResizeOptions o) {
        return o.aspect > 0 ? o.aspect : (double) s.width / s.height;
    }

    /**
     * Размер холста после «стадии пропорций» в полном разрешении исходника:
     * при обрезке — наибольший прямоугольник нужной пропорции внутри кадра,
     * при вписывании — наименьший прямоугольник нужной пропорции вокруг кадра.
     */
    private static double[] baseCanvas(Source s, ResizeOptions o) {
        double sw = s.width, sh = s.height;
        if (o.aspect <= 0) return new double[]{sw, sh};
        double asp = o.aspect;
        boolean wider = sw / sh > asp;
        if (o.fitMode == ResizeOptions.FIT_INSIDE) {
            return wider ? new double[]{sw, sw / asp} : new double[]{sh * asp, sh};
        }
        // обрезка и растягивание — не больше исходника
        return wider ? new double[]{sh * asp, sh} : new double[]{sw, sw / asp};
    }

    /** Итоговые ширина и высота результата для исходника s. */
    @NonNull
    public static int[] targetSize(@NonNull Source s, @NonNull ResizeOptions o) {
        double[] base = baseCanvas(s, o);
        double cw = base[0], ch = base[1];
        double tw, th;
        switch (o.sizeMode) {
            case ResizeOptions.SIZE_PERCENT: {
                double p = clamp(o.percent, 1, 400) / 100.0;
                tw = cw * p;
                th = ch * p;
                break;
            }
            case ResizeOptions.SIZE_LONG_SIDE: {
                double f = Math.max(1, o.longSide) / Math.max(cw, ch);
                if (f > 1) f = 1; // маленькие снимки не увеличиваем
                tw = cw * f;
                th = ch * f;
                break;
            }
            case ResizeOptions.SIZE_PRINT:
            case ResizeOptions.SIZE_PIXELS:
            default: {
                int w, h;
                if (o.sizeMode == ResizeOptions.SIZE_PRINT) {
                    w = o.printPixels(o.printWidth);
                    h = o.printPixels(o.printHeight);
                } else {
                    w = o.width;
                    h = o.height;
                }
                if (w <= 0 && h <= 0) {
                    tw = cw;
                    th = ch;
                } else if (o.keepAspect) {
                    double bw = w, bh = h;
                    if (w > 0 && h > 0) {
                        // Рамка ориентируется по снимку: 1280×720 для портрета — 720×1280
                        boolean boxPortrait = bh > bw;
                        boolean imgPortrait = ch > cw;
                        if (boxPortrait != imgPortrait) {
                            double t = bw;
                            bw = bh;
                            bh = t;
                        }
                        double f = Math.min(bw / cw, bh / ch);
                        tw = cw * f;
                        th = ch * f;
                    } else if (w > 0) {
                        tw = w;
                        th = w * ch / cw;
                    } else {
                        th = h;
                        tw = h * cw / ch;
                    }
                } else {
                    tw = w > 0 ? w : h * cw / ch;
                    th = h > 0 ? h : w * ch / cw;
                }
            }
        }
        int iw = (int) Math.max(1, Math.min(ResizeOptions.MAX_SIDE, Math.round(tw)));
        int ih = (int) Math.max(1, Math.min(ResizeOptions.MAX_SIDE, Math.round(th)));
        return new int[]{iw, ih};
    }

    private static double clamp(double v, double lo, double hi) {
        return Math.max(lo, Math.min(hi, v));
    }

    // ---------- Результат ----------

    public static final class Result {
        public byte[] data;
        public int width, height;
        public int quality;
        /** Целевой размер файла был задан, но достичь его не удалось. */
        public boolean targetMissed;
        /** Итог собран по уменьшенной копии (предпросмотр), байты — оценка. */
        public boolean preview;

        public long bytes() {
            return data == null ? 0 : data.length;
        }
    }

    /**
     * Обрабатывает исходник по настройкам.
     *
     * @param previewMaxSide 0 — полный размер с подбором под целевой размер файла;
     *                       &gt;0 — быстрый уменьшенный вариант для предпросмотра
     *                       (без подбора размера файла).
     */
    @NonNull
    public static Result render(@NonNull Source s, @NonNull ResizeOptions o, int previewMaxSide,
                                @Nullable Cancel cancel) throws ResizeException {
        int[] t = targetSize(s, o);
        int tw = t[0], th = t[1];
        boolean preview = previewMaxSide > 0;
        if (preview && Math.max(tw, th) > previewMaxSide) {
            double f = previewMaxSide / (double) Math.max(tw, th);
            tw = Math.max(1, (int) Math.round(tw * f));
            th = Math.max(1, (int) Math.round(th * f));
        }
        long budget = memoryBudget();
        if ((long) tw * th * 4L > budget) {
            throw new ResizeException(ResizeException.TOO_LARGE, "output too large");
        }

        Bitmap src = null;
        Bitmap cur = null;
        try {
            src = decodeForTarget(s, o, tw, th, budget);
            if (src == null) throw new ResizeException(ResizeException.UNSUPPORTED, "decode");
            checkCancel(cancel);
            Matrix exif = exifMatrix(s.orientation, src.getWidth(), src.getHeight());
            int uw = isSwapped(s.orientation) ? src.getHeight() : src.getWidth();
            int uh = isSwapped(s.orientation) ? src.getWidth() : src.getHeight();

            Result r = new Result();
            r.preview = preview;
            int q = clampInt(o.quality, 1, 100);
            int curW = tw, curH = th;
            cur = draw(src, exif, uw, uh, curW, curH, s, o);
            byte[] data = encode(cur, o, q);
            checkCancel(cancel);

            if (!preview && o.targetBytes > 0 && data.length > o.targetBytes) {
                // 1. Подбор качества (JPEG/WebP) двоичным поиском
                if (o.format != ResizeOptions.FMT_PNG) {
                    int lo = 10, hi = q;
                    byte[] best = null;
                    int bestQ = q;
                    while (lo <= hi) {
                        checkCancel(cancel);
                        int mid = (lo + hi) / 2;
                        byte[] d = encode(cur, o, mid);
                        if (d.length <= o.targetBytes) {
                            best = d;
                            bestQ = mid;
                            lo = mid + 1;
                        } else {
                            hi = mid - 1;
                        }
                    }
                    if (best != null) {
                        data = best;
                        q = bestQ;
                    } else {
                        q = 10;
                        data = encode(cur, o, q);
                    }
                }
                // 2. Если и так много — уменьшаем разрешение
                int guard = 0;
                while (data.length > o.targetBytes && guard++ < 8
                        && Math.min(curW, curH) > 16) {
                    checkCancel(cancel);
                    double f = Math.sqrt((double) o.targetBytes / data.length) * 0.92;
                    if (o.format != ResizeOptions.FMT_PNG && q < 70) {
                        // Лучше меньше пикселей с приличным качеством, чем «кубики» при q=10
                        q = 70;
                        f *= 0.75;
                    }
                    if (f > 0.97) f = 0.97;
                    curW = Math.max(16, (int) Math.round(curW * f));
                    curH = Math.max(16, (int) Math.round(curH * f));
                    cur.recycle();
                    cur = draw(src, exif, uw, uh, curW, curH, s, o);
                    data = encode(cur, o, o.format == ResizeOptions.FMT_PNG ? 100 : q);
                }
                r.targetMissed = data.length > o.targetBytes;
            }
            r.data = data;
            r.width = curW;
            r.height = curH;
            r.quality = o.format == ResizeOptions.FMT_PNG ? 100 : q;
            return r;
        } catch (OutOfMemoryError e) {
            throw new ResizeException(ResizeException.OUT_OF_MEMORY, "oom");
        } finally {
            if (cur != null) cur.recycle();
            if (src != null) src.recycle();
        }
    }

    private static void checkCancel(@Nullable Cancel c) throws ResizeException {
        if (c != null && c.isCancelled()) {
            throw new ResizeException(ResizeException.CANCELLED, "cancelled");
        }
    }

    private static int clampInt(int v, int lo, int hi) {
        return Math.max(lo, Math.min(hi, v));
    }

    /** Сколько байт можно отвести под один битмап, не рискуя OutOfMemoryError. */
    private static long memoryBudget() {
        Runtime rt = Runtime.getRuntime();
        long free = rt.maxMemory() - (rt.totalMemory() - rt.freeMemory());
        return Math.max(24L * 1024 * 1024, (long) (free * 0.38));
    }

    /**
     * Декодирует исходник с таким inSampleSize, чтобы его хватило для холста
     * tw×th (с запасом по качеству), но он не вышел за бюджет памяти.
     */
    @Nullable
    private static Bitmap decodeForTarget(Source s, ResizeOptions o, int tw, int th, long budget) {
        double need;
        double sw = s.width, sh = s.height;
        switch (o.fitMode) {
            case ResizeOptions.FIT_INSIDE:
                need = Math.min(tw / sw, th / sh);
                break;
            case ResizeOptions.FIT_STRETCH:
            case ResizeOptions.FIT_CROP:
            default:
                need = Math.max(tw / sw, th / sh);
        }
        if (need > 1) need = 1;
        int sample = 1;
        // Оставляем минимум двукратный запас над нужным масштабом
        while (sw / (sample * 2) >= sw * need * 2 && sh / (sample * 2) >= sh * need * 2) {
            sample *= 2;
        }
        while ((long) (s.rawWidth / sample) * (s.rawHeight / sample) * 4L > budget) {
            sample *= 2;
        }
        BitmapFactory.Options d = new BitmapFactory.Options();
        d.inSampleSize = sample;
        d.inPreferredConfig = Bitmap.Config.ARGB_8888;
        return BitmapFactory.decodeFile(s.path, d);
    }

    /** Матрица EXIF-поворота: сырые координаты декодированного кадра → «как видит пользователь». */
    private static Matrix exifMatrix(int orientation, int rw, int rh) {
        Matrix m = new Matrix();
        switch (orientation) {
            case ExifInterface.ORIENTATION_ROTATE_90:
                m.postRotate(90);
                m.postTranslate(rh, 0);
                break;
            case ExifInterface.ORIENTATION_ROTATE_180:
                m.postRotate(180);
                m.postTranslate(rw, rh);
                break;
            case ExifInterface.ORIENTATION_ROTATE_270:
                m.postRotate(270);
                m.postTranslate(0, rw);
                break;
            case ExifInterface.ORIENTATION_FLIP_HORIZONTAL:
                m.postScale(-1, 1);
                m.postTranslate(rw, 0);
                break;
            case ExifInterface.ORIENTATION_FLIP_VERTICAL:
                m.postScale(1, -1);
                m.postTranslate(0, rh);
                break;
            case ExifInterface.ORIENTATION_TRANSPOSE:
                m.postRotate(90);
                m.postScale(-1, 1);
                break;
            case ExifInterface.ORIENTATION_TRANSVERSE:
                m.postRotate(270);
                m.postScale(-1, 1);
                m.postTranslate(rh, rw);
                break;
            default:
                break;
        }
        return m;
    }

    /**
     * Рисует исходник на холсте tw×th: обрезка / вписывание (с фоном) / растягивание,
     * заливка прозрачности для JPEG.
     */
    private static Bitmap draw(Bitmap src, Matrix exif, int uw, int uh, int tw, int th,
                               Source s, ResizeOptions o) {
        Bitmap out = Bitmap.createBitmap(tw, th, Bitmap.Config.ARGB_8888);
        Canvas c = new Canvas(out);
        Paint p = new Paint(Paint.FILTER_BITMAP_FLAG | Paint.ANTI_ALIAS_FLAG | Paint.DITHER_FLAG);

        double sx = (double) tw / uw, sy = (double) th / uh;
        float scaleX, scaleY;
        switch (o.fitMode) {
            case ResizeOptions.FIT_INSIDE:
                scaleX = scaleY = (float) Math.min(sx, sy);
                break;
            case ResizeOptions.FIT_STRETCH:
                scaleX = (float) sx;
                scaleY = (float) sy;
                break;
            case ResizeOptions.FIT_CROP:
            default:
                scaleX = scaleY = (float) Math.max(sx, sy);
        }
        float dw = uw * scaleX, dh = uh * scaleY;
        boolean uncovered = dw < tw - 0.5f || dh < th - 0.5f;

        // Фон: JPEG не умеет прозрачность — всегда заливаем; поля при вписывании —
        // цветом или размытым кадром
        if (o.format == ResizeOptions.FMT_JPEG) {
            c.drawColor(o.jpegBackground | 0xFF000000);
        }
        if (uncovered) {
            if (o.padMode == ResizeOptions.PAD_COLOR) {
                c.drawColor(o.padColor | 0xFF000000);
            } else {
                drawBlurBackground(c, src, exif, uw, uh, tw, th, o);
            }
        }

        Matrix m = new Matrix(exif);
        m.postScale(scaleX, scaleY);
        m.postTranslate((tw - dw) / 2f, (th - dh) / 2f);
        c.drawBitmap(src, m, p);
        return out;
    }

    /** Размытый фон: кадр уменьшается до нескольких десятков пикселей и растягивается на холст. */
    private static void drawBlurBackground(Canvas c, Bitmap src, Matrix exif, int uw, int uh,
                                           int tw, int th, ResizeOptions o) {
        int smallW = Math.max(2, Math.min(28, tw / 40));
        int smallH = Math.max(2, Math.round(smallW * (float) th / tw));
        Bitmap small = Bitmap.createBitmap(smallW, smallH, Bitmap.Config.ARGB_8888);
        Canvas sc = new Canvas(small);
        if (o.format == ResizeOptions.FMT_JPEG) sc.drawColor(o.jpegBackground | 0xFF000000);
        Paint p = new Paint(Paint.FILTER_BITMAP_FLAG | Paint.ANTI_ALIAS_FLAG);
        float f = Math.max((float) smallW / uw, (float) smallH / uh);
        Matrix m = new Matrix(exif);
        m.postScale(f, f);
        m.postTranslate((smallW - uw * f) / 2f, (smallH - uh * f) / 2f);
        sc.drawBitmap(src, m, p);
        // Второй проход ещё сильнее сглаживает
        Bitmap tiny = Bitmap.createScaledBitmap(small, Math.max(2, smallW / 2),
                Math.max(2, smallH / 2), true);
        Matrix up = new Matrix();
        up.postScale((float) tw / tiny.getWidth(), (float) th / tiny.getHeight());
        c.drawBitmap(tiny, up, p);
        // Лёгкое затемнение, чтобы фон не спорил с кадром
        c.drawColor(0x33000000);
        small.recycle();
        tiny.recycle();
    }

    private static byte[] encode(Bitmap b, ResizeOptions o, int quality) {
        {
            ByteArrayOutputStream bos = new ByteArrayOutputStream(Math.max(64 * 1024,
                    b.getWidth() * b.getHeight() / 4));
            Bitmap.CompressFormat fmt;
            int q = clampInt(quality, 1, 100);
            switch (o.format) {
                case ResizeOptions.FMT_PNG:
                    fmt = Bitmap.CompressFormat.PNG;
                    q = 100;
                    break;
                case ResizeOptions.FMT_WEBP:
                    if (Build.VERSION.SDK_INT >= 30) {
                        fmt = q >= 100 ? Bitmap.CompressFormat.WEBP_LOSSLESS
                                : Bitmap.CompressFormat.WEBP_LOSSY;
                    } else {
                        //noinspection deprecation
                        fmt = Bitmap.CompressFormat.WEBP;
                    }
                    break;
                default:
                    fmt = Bitmap.CompressFormat.JPEG;
            }
            b.compress(fmt, q, bos);
            return bos.toByteArray();
        }
    }

    // ---------- Сохранение ----------

    /** Имя файла по шаблону ({name} {w} {h} {n} {date}) без расширения. */
    public static String buildName(String pattern, String srcName, int w, int h, int n) {
        String base = srcName;
        int dot = base.lastIndexOf('.');
        if (dot > 0) base = base.substring(0, dot);
        String pat = pattern == null || pattern.trim().isEmpty() ? "{name}_{w}x{h}" : pattern.trim();
        String date = new SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(new Date());
        String out = pat.replace("{name}", base)
                .replace("{w}", String.valueOf(w))
                .replace("{h}", String.valueOf(h))
                .replace("{n}", String.valueOf(n))
                .replace("{date}", date);
        // Недопустимые символы имени файла
        out = out.replaceAll("[\\\\/:*?\"<>|\\x00-\\x1F]", "_").trim();
        if (out.isEmpty() || out.equals(".") || out.equals("..")) out = base;
        if (out.length() > 120) out = out.substring(0, 120);
        return out;
    }

    /**
     * Записывает результат в target (через временный файл), переносит дату съёмки,
     * ставит нормальную ориентацию и, для печати, DPI.
     */
    public static void write(@NonNull Result r, @NonNull File target, @NonNull Source s,
                             @NonNull ResizeOptions o) throws ResizeException {
        File dir = target.getParentFile();
        if (dir == null || (!dir.isDirectory() && !dir.mkdirs())) {
            throw new ResizeException(ResizeException.WRITE_FAILED, "mkdirs");
        }
        if (dir.getUsableSpace() > 0 && dir.getUsableSpace() < r.bytes() + 4L * 1024 * 1024) {
            throw new ResizeException(ResizeException.NO_SPACE, "no space");
        }
        File tmp = new File(dir, "." + target.getName() + ".gxpart");
        try (FileOutputStream fos = new FileOutputStream(tmp)) {
            fos.write(r.data);
            fos.getFD().sync();
        } catch (IOException e) {
            //noinspection ResultOfMethodCallIgnored
            tmp.delete();
            String msg = String.valueOf(e.getMessage()).toLowerCase(Locale.US);
            throw new ResizeException(msg.contains("enospc") || msg.contains("no space")
                    ? ResizeException.NO_SPACE : ResizeException.WRITE_FAILED, msg);
        }
        if (!tmp.renameTo(target)) {
            //noinspection ResultOfMethodCallIgnored
            tmp.delete();
            throw new ResizeException(ResizeException.WRITE_FAILED, "rename");
        }
        if (o.format == ResizeOptions.FMT_JPEG) {
            try {
                ExifInterface in = new ExifInterface(s.path);
                ExifInterface out = new ExifInterface(target.getAbsolutePath());
                String[] tags = {ExifInterface.TAG_DATETIME, ExifInterface.TAG_DATETIME_ORIGINAL,
                        ExifInterface.TAG_DATETIME_DIGITIZED, ExifInterface.TAG_MAKE,
                        ExifInterface.TAG_MODEL};
                for (String t : tags) {
                    String v = in.getAttribute(t);
                    if (v != null) out.setAttribute(t, v);
                }
                out.setAttribute(ExifInterface.TAG_ORIENTATION,
                        String.valueOf(ExifInterface.ORIENTATION_NORMAL));
                out.setAttribute(ExifInterface.TAG_IMAGE_WIDTH, String.valueOf(r.width));
                out.setAttribute(ExifInterface.TAG_IMAGE_LENGTH, String.valueOf(r.height));
                if (o.sizeMode == ResizeOptions.SIZE_PRINT && o.dpi > 0) {
                    out.setAttribute(ExifInterface.TAG_X_RESOLUTION, o.dpi + "/1");
                    out.setAttribute(ExifInterface.TAG_Y_RESOLUTION, o.dpi + "/1");
                    out.setAttribute(ExifInterface.TAG_RESOLUTION_UNIT,
                            String.valueOf(ExifInterface.RESOLUTION_UNIT_INCHES));
                }
                out.saveAttributes();
            } catch (Exception ignored) {
                // Метаданные — не критично
            }
        }
        //noinspection ResultOfMethodCallIgnored
        target.setLastModified(System.currentTimeMillis());
    }

    /** Декодирует байты результата в битмап для показа (не больше maxSide). */
    @Nullable
    public static Bitmap decodeForDisplay(byte[] data, int maxSide) {
        if (data == null) return null;
        try {
            BitmapFactory.Options o = new BitmapFactory.Options();
            o.inJustDecodeBounds = true;
            BitmapFactory.decodeByteArray(data, 0, data.length, o);
            int sample = 1;
            while (o.outWidth / (sample * 2) >= maxSide || o.outHeight / (sample * 2) >= maxSide) {
                sample *= 2;
            }
            BitmapFactory.Options d = new BitmapFactory.Options();
            d.inSampleSize = sample;
            return BitmapFactory.decodeByteArray(data, 0, data.length, d);
        } catch (Throwable t) {
            return null;
        }
    }

    /** Непрозрачный цвет из #RRGGBB / RRGGBB; null, если строка не цвет. */
    @Nullable
    public static Integer parseColor(String s) {
        if (s == null) return null;
        String t = s.trim();
        if (t.isEmpty()) return null;
        if (!t.startsWith("#")) t = "#" + t;
        if (t.length() != 7) return null;
        try {
            return Color.parseColor(t) | 0xFF000000;
        } catch (Exception e) {
            return null;
        }
    }
}
