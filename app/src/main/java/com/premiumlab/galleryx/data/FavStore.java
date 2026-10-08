package com.premiumlab.galleryx.data;

import java.io.File;
import java.util.ArrayList;
import java.util.List;

/**
 * Избранное: список путей к файлам, сохраняется в настройках.
 */
public final class FavStore {

    private FavStore() {
    }

    public static boolean isFav(String path) {
        return Prefs.favorites().contains(path);
    }

    /** Переключает статус избранного. Возвращает true, если файл добавлен. */
    public static boolean toggle(String path) {
        List<String> list = Prefs.favorites();
        if (list.contains(path)) {
            list.remove(path);
            Prefs.saveFavorites(list);
            return false;
        }
        list.add(0, path);
        Prefs.saveFavorites(list);
        return true;
    }

    public static void remove(String path) {
        List<String> list = Prefs.favorites();
        list.remove(path);
        Prefs.saveFavorites(list);
    }

    public static void removeAll(List<String> paths) {
        List<String> list = Prefs.favorites();
        list.removeAll(paths);
        Prefs.saveFavorites(list);
    }

    public static List<MediaItem> items() {
        List<MediaItem> out = new ArrayList<>();
        for (String p : Prefs.favorites()) {
            File f = new File(p);
            if (f.exists() && f.isFile()) {
                out.add(MediaItem.fromFile(f));
            }
        }
        MediaEngine.sort(out, Prefs.sortMode());
        return out;
    }

    public static List<String> paths() {
        return new ArrayList<>(Prefs.favorites());
    }

    public static int count() {
        int n = 0;
        for (String p : Prefs.favorites()) {
            if (new File(p).exists()) n++;
        }
        return n;
    }
}
