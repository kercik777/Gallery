package com.premiumlab.galleryx.data;

import java.io.File;

/**
 * Модель медиафайла (фото или видео).
 */
public class MediaItem {

    /** Row id в MediaStore (0 — файл найден напрямую на диске, без строки). */
    public long id;
    public String path = "";
    public String name = "";
    public String folderPath = "";
    public String mime = "";
    public long size;
    public long dateModified;
    /** Дата съёмки (EXIF/MediaStore DATE_TAKEN), 0 — неизвестна. */
    public long dateTaken;
    public long duration;
    public boolean isVideo;
    /** Дополнительный бейдж (например, «27 дней» в корзине). */
    public String badge;
    /** Размеры кадра в пикселях (0 — неизвестны; заполняет поиск дубликатов). */
    public int width;
    public int height;

    public MediaItem() {
    }

    /**
     * Дата, по которой файл сортируется и группируется: дата СЪЁМКИ, если
     * она известна, иначе дата изменения файла. Иначе старое фото,
     * скачанное сегодня из облака, «всплывает» как сегодняшнее.
     */
    public long date() {
        return dateTaken > 0 ? dateTaken : dateModified;
    }

    public static MediaItem fromFile(File f) {
        MediaItem item = new MediaItem();
        item.path = f.getAbsolutePath();
        item.name = f.getName();
        item.folderPath = f.getParent();
        item.size = f.length();
        item.dateModified = f.lastModified();
        item.isVideo = MediaEngine.isVideoName(f.getName());
        item.mime = item.isVideo ? "video/*" : "image/*";
        if (!item.isVideo) item.dateTaken = exifTaken(f);
        return item;
    }

    /** Дата съёмки из EXIF (только JPEG), 0 если нет. Формат EXIF: yyyy:MM:dd HH:mm:ss. */
    private static long exifTaken(File f) {
        String n = f.getName().toLowerCase(java.util.Locale.US);
        if (!n.endsWith(".jpg") && !n.endsWith(".jpeg")) return 0;
        try {
            androidx.exifinterface.media.ExifInterface exif =
                    new androidx.exifinterface.media.ExifInterface(f.getAbsolutePath());
            String s = exif.getAttribute(
                    androidx.exifinterface.media.ExifInterface.TAG_DATETIME_ORIGINAL);
            if (s == null) {
                s = exif.getAttribute(androidx.exifinterface.media.ExifInterface.TAG_DATETIME);
            }
            if (s == null) return 0;
            java.text.SimpleDateFormat fmt = new java.text.SimpleDateFormat(
                    "yyyy:MM:dd HH:mm:ss", java.util.Locale.US);
            java.util.Date d = fmt.parse(s.trim());
            return d == null ? 0 : d.getTime();
        } catch (Throwable ignored) {
            return 0;
        }
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof MediaItem)) return false;
        return path.equals(((MediaItem) o).path);
    }

    @Override
    public int hashCode() {
        return path.hashCode();
    }
}
