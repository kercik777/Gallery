package com.premiumlab.galleryx.data;

import java.io.File;

/**
 * Модель медиафайла (фото или видео).
 */
public class MediaItem {

    public String path = "";
    public String name = "";
    public String folderPath = "";
    public String mime = "";
    public long size;
    public long dateModified;
    public long duration;
    public boolean isVideo;
    /** Дополнительный бейдж (например, «27 дней» в корзине). */
    public String badge;

    public MediaItem() {
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
        return item;
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
