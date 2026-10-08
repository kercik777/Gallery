package com.premiumlab.galleryx.data;

/**
 * Модель альбома (папки) для списка альбомов.
 */
public class Album {

    public String name;
    public String path;
    public String coverPath;
    public int count;
    public boolean isUser;

    public Album(String name, String path, String coverPath, int count, boolean isUser) {
        this.name = name;
        this.path = path;
        this.coverPath = coverPath;
        this.count = count;
        this.isUser = isUser;
    }
}
