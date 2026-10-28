package com.premiumlab.galleryx.data;

import org.json.JSONArray;

import java.io.File;
import java.io.FileOutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;

/**
 * Заблокированные папки (альбомы). Файлы внутри них (и во вложенных папках)
 * не показываются в общих разделах — Галерея, Избранное, Видео — независимо
 * от маскировки; открыть такую папку можно только после ввода PIN-кода.
 * Список путей хранится в настройках и дублируется в файл
 * <корень>/.GalleryX/locked_folders.json — после переустановки приложения
 * достаточно выбрать ту же корневую папку, и блокировки подхватятся сами.
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
        writeMirror(l);
    }

    // ---------- Резервная копия в корневой папке ----------

    private static final String FILE = "locked_folders.json";

    private static File mirrorFile() {
        File hidden = AppDirs.hiddenRoot();
        return hidden == null ? null : new File(hidden, FILE);
    }

    /** Пишет список в <корень>/.GalleryX (атомарно, через временный файл). */
    private static void writeMirror(List<String> l) {
        try {
            File f = mirrorFile();
            if (f == null) return;
            File dir = f.getParentFile();
            if (dir != null && !dir.isDirectory() && !dir.mkdirs()) return;
            JSONArray arr = new JSONArray();
            for (String s : l) arr.put(s);
            File tmp = new File(dir, "." + FILE + ".tmp");
            try (FileOutputStream fos = new FileOutputStream(tmp)) {
                fos.write(arr.toString().getBytes(StandardCharsets.UTF_8));
                fos.getFD().sync();
            }
            if (!tmp.renameTo(f)) {
                //noinspection ResultOfMethodCallIgnored
                tmp.delete();
            }
        } catch (Exception ignored) {
            // Резервная копия — не критично
        }
    }

    /**
     * Корень выбран/сменён (вызывает AppDirs): объединяет список из настроек
     * со списком из файла в корне. Так блокировки переживают переустановку:
     * настройки пусты — папки подберутся из файла (только существующие).
     */
    public static void syncWithRootFile() {
        try {
            List<String> merged = list();
            boolean changed = false;
            File f = mirrorFile();
            if (f != null && f.isFile()) {
                String json = new String(Files.readAllBytes(f.toPath()),
                        StandardCharsets.UTF_8);
                JSONArray arr = new JSONArray(json);
                for (int i = 0; i < arr.length(); i++) {
                    String p = arr.optString(i, "");
                    if (!p.isEmpty() && !merged.contains(p)
                            && new File(p).isDirectory()) {
                        merged.add(p);
                        changed = true;
                    }
                }
            }
            if (changed) {
                save(merged);
            } else if (f != null && !f.isFile() && !merged.isEmpty()) {
                writeMirror(merged);
            }
        } catch (Exception ignored) {
        }
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

    /** Имя заблокированной папки для показа: «test» → «t**t», «ab» → «**». */
    public static String maskName(String name) {
        if (name == null || name.isEmpty()) return "***";
        int n = name.codePointCount(0, name.length());
        if (n <= 2) return "**";
        StringBuilder sb = new StringBuilder();
        sb.appendCodePoint(name.codePointAt(0));
        for (int i = 0; i < n - 2; i++) sb.append('*');
        sb.appendCodePoint(name.codePointBefore(name.length()));
        return sb.toString();
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
