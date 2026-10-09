package com.premiumlab.galleryx.data;

import org.json.JSONArray;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;

/**
 * Заблокированные папки (альбомы). Файлы внутри них (и во вложенных папках)
 * не показываются в общих разделах — Галерея, Избранное, Видео — независимо
 * от маскировки; открыть такую папку можно только после ввода PIN-кода.
 * Список путей хранится в настройках.
 */
public final class LockStore {

    private static final String KEY = "locked_dirs";
    private static volatile List<String> cache;

    private LockStore() {
    }

    private static List<String> list() {
        List<String> c = cache;
        if (c != null) return new ArrayList<>(c);
        List<String> out = new ArrayList<>();
        try {
            JSONArray arr = new JSONArray(Prefs.sp().getString(KEY, "[]"));
            for (int i = 0; i < arr.length(); i++) out.add(arr.getString(i));
        } catch (Exception ignored) {
        }
        cache = new ArrayList<>(out);
        return out;
    }

    private static void save(List<String> l) {
        JSONArray arr = new JSONArray();
        for (String s : l) arr.put(s);
        Prefs.sp().edit().putString(KEY, arr.toString()).apply();
        cache = new ArrayList<>(l);
    }

    public static boolean any() {
        return !list().isEmpty();
    }

    public static boolean isLocked(String dirPath) {
        return dirPath != null && list().contains(dirPath);
    }

    /** true — файл/папка лежит в заблокированной папке (на любой глубине) или сам ею является. */
    public static boolean isUnderLocked(String path) {
        if (path == null) return false;
        List<String> l = list();
        if (l.isEmpty()) return false;
        for (String d : l) {
            if (path.equals(d) || path.startsWith(d + "/")) return true;
        }
        return false;
    }

    public static void lock(Collection<String> dirs) {
        List<String> l = list();
        for (String d : dirs) if (d != null && !l.contains(d)) l.add(d);
        save(l);
    }

    public static void unlock(Collection<String> dirs) {
        List<String> l = list();
        l.removeAll(dirs);
        save(l);
    }

    /** Переписывает пути после переименования/перемещения папки. */
    public static void rewritePrefix(String from, String to) {
        List<String> l = list();
        boolean changed = false;
        for (int i = 0; i < l.size(); i++) {
            String p = l.get(i);
            if (p.equals(from) || p.startsWith(from + "/")) {
                l.set(i, to + p.substring(from.length()));
                changed = true;
            }
        }
        if (changed) save(l);
    }

    /** Снимает блокировку с удалённых папок. */
    public static void removeUnder(String dirPath) {
        List<String> l = list();
        boolean changed = false;
        for (int i = l.size() - 1; i >= 0; i--) {
            String p = l.get(i);
            if (p.equals(dirPath) || p.startsWith(dirPath + "/")) {
                l.remove(i);
                changed = true;
            }
        }
        if (changed) save(l);
    }
}
