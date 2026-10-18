package com.premiumlab.galleryx.util;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Matrix;

import androidx.exifinterface.media.ExifInterface;

import java.io.File;
import java.io.FileOutputStream;
import java.io.OutputStream;

/**
 * Поворот фотографии прямо в файле (жест «кручение двумя пальцами»
 * в просмотрщике). Пиксели поворачиваются на 90/180/270°, EXIF-дата
 * съёмки и прочие метаданные сохраняются, ориентация EXIF сбрасывается
 * в обычную — чтобы и другие приложения видели картинку повёрнутой.
 * Вызывать в фоновом потоке.
 */
public final class ImageRotator {

    /**
     * Все EXIF-теги, какие есть в исходнике. Раньше был ручной список —
     * и в нём не оказалось ISOSpeedRatings, поэтому после поворота
     * пропадал ISO (а могли и другие поля). Теперь копируем каждый
     * объявленный в ExifInterface тег, кроме служебных: ориентацию
     * ставим обычную, размеры — новые, миниатюру EXIF не тащим
     * (она осталась бы непровёрнутой).
     */
    private static void copyAllTags(ExifInterface from, ExifInterface to,
                                    int newW, int newH) {
        for (java.lang.reflect.Field f : ExifInterface.class.getFields()) {
            if (!f.getName().startsWith("TAG_")) continue;
            String tag;
            try {
                Object v = f.get(null);
                if (!(v instanceof String)) continue;
                tag = (String) v;
            } catch (Exception e) {
                continue;
            }
            if (tag.equals(ExifInterface.TAG_ORIENTATION)
                    || tag.equals(ExifInterface.TAG_PIXEL_X_DIMENSION)
                    || tag.equals(ExifInterface.TAG_PIXEL_Y_DIMENSION)
                    || tag.contains("THUMBNAIL")
                    || tag.contains("INTERCHANGE")) {
                continue;
            }
            try {
                String value = from.getAttribute(tag);
                if (value != null) to.setAttribute(tag, value);
            } catch (Exception ignored) {
            }
        }
        to.setAttribute(ExifInterface.TAG_ORIENTATION,
                String.valueOf(ExifInterface.ORIENTATION_NORMAL));
        to.setAttribute(ExifInterface.TAG_PIXEL_X_DIMENSION, String.valueOf(newW));
        to.setAttribute(ExifInterface.TAG_PIXEL_Y_DIMENSION, String.valueOf(newH));
    }

    private ImageRotator() {
    }

    /**
     * Поворачивает файл на degrees по часовой стрелке (90/180/270).
     *
     * @return true — файл переписан повёрнутым.
     */
    public static boolean rotate(Context ctx, File file, int degrees) {
        int deg = ((degrees % 360) + 360) % 360;
        if (deg == 0 || !file.isFile()) return false;
        Bitmap bmp = null;
        Bitmap rotated = null;
        File tmp = null;
        try {
            // Старые метаданные — до перезаписи файла
            ExifInterface oldExif = null;
            try {
                oldExif = new ExifInterface(file.getAbsolutePath());
            } catch (Throwable ignored) {
            }

            bmp = BitmapFactory.decodeFile(file.getAbsolutePath());
            if (bmp == null) return false;
            Matrix m = new Matrix();
            m.postRotate(deg);
            rotated = Bitmap.createBitmap(bmp, 0, 0, bmp.getWidth(), bmp.getHeight(), m, true);
            if (rotated == null) return false;

            String name = file.getName().toLowerCase(java.util.Locale.US);
            Bitmap.CompressFormat fmt;
            if (name.endsWith(".png")) fmt = Bitmap.CompressFormat.PNG;
            else if (name.endsWith(".webp")) fmt = Bitmap.CompressFormat.WEBP;
            else fmt = Bitmap.CompressFormat.JPEG;

            tmp = new File(file.getParentFile(), "." + file.getName() + ".gxrot");
            try (OutputStream out = new FileOutputStream(tmp)) {
                if (!rotated.compress(fmt, 92, out)) return false;
            }

            // Метаданные обратно: ВСЕ теги исходника (ISO, диафрагма,
            // выдержка, GPS…) + обычная ориентация и новые размеры
            if (fmt == Bitmap.CompressFormat.JPEG && oldExif != null) {
                try {
                    ExifInterface exif = new ExifInterface(tmp.getAbsolutePath());
                    copyAllTags(oldExif, exif, rotated.getWidth(), rotated.getHeight());
                    exif.saveAttributes();
                } catch (Throwable ignored) {
                }
            }

            // Атомарная замена: оригинал удаляем только когда новый готов
            //noinspection ResultOfMethodCallIgnored
            if (!file.delete()) return false;
            if (!tmp.renameTo(file)) {
                //noinspection ResultOfMethodCallIgnored
                tmp.renameTo(file); // последняя попытка
                if (!file.exists()) return false;
            }
            tmp = null;
            // Чтобы MediaStore и другие приложения подхватили поворот
            Scan.files(ctx, file.getAbsolutePath());
            return true;
        } catch (Throwable t) {
            return false;
        } finally {
            if (tmp != null) {
                //noinspection ResultOfMethodCallIgnored
                tmp.delete();
            }
            if (rotated != null && rotated != bmp) rotated.recycle();
            if (bmp != null) bmp.recycle();
        }
    }
}
