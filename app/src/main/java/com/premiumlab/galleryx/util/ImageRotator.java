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

    /** EXIF-теги, которые переносим в повёрнутый файл. */
    private static final String[] KEEP_TAGS = {
            ExifInterface.TAG_DATETIME, ExifInterface.TAG_DATETIME_ORIGINAL,
            ExifInterface.TAG_DATETIME_DIGITIZED, ExifInterface.TAG_MAKE,
            ExifInterface.TAG_MODEL, ExifInterface.TAG_SOFTWARE,
            ExifInterface.TAG_IMAGE_DESCRIPTION, ExifInterface.TAG_ARTIST,
            ExifInterface.TAG_COPYRIGHT, ExifInterface.TAG_GPS_LATITUDE,
            ExifInterface.TAG_GPS_LATITUDE_REF, ExifInterface.TAG_GPS_LONGITUDE,
            ExifInterface.TAG_GPS_LONGITUDE_REF, ExifInterface.TAG_GPS_ALTITUDE,
            ExifInterface.TAG_GPS_ALTITUDE_REF, ExifInterface.TAG_F_NUMBER,
            ExifInterface.TAG_EXPOSURE_TIME, ExifInterface.TAG_ISO_SPEED,
            ExifInterface.TAG_FOCAL_LENGTH, ExifInterface.TAG_FLASH,
            ExifInterface.TAG_WHITE_BALANCE, ExifInterface.TAG_MAKE,
    };

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
            String[] saved = null;
            try {
                ExifInterface old = new ExifInterface(file.getAbsolutePath());
                saved = new String[KEEP_TAGS.length];
                for (int i = 0; i < KEEP_TAGS.length; i++) {
                    saved[i] = old.getAttribute(KEEP_TAGS[i]);
                }
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

            // Метаданные обратно + обычная ориентация и новые размеры
            if (fmt == Bitmap.CompressFormat.JPEG && saved != null) {
                try {
                    ExifInterface exif = new ExifInterface(tmp.getAbsolutePath());
                    for (int i = 0; i < KEEP_TAGS.length; i++) {
                        if (saved[i] != null) exif.setAttribute(KEEP_TAGS[i], saved[i]);
                    }
                    exif.setAttribute(ExifInterface.TAG_ORIENTATION,
                            String.valueOf(ExifInterface.ORIENTATION_NORMAL));
                    exif.setAttribute(ExifInterface.TAG_PIXEL_X_DIMENSION,
                            String.valueOf(rotated.getWidth()));
                    exif.setAttribute(ExifInterface.TAG_PIXEL_Y_DIMENSION,
                            String.valueOf(rotated.getHeight()));
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
