package com.premiumlab.galleryx.data;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;

/**
 * Корзина: метаданные удалённых файлов, автоочистка через 30 дней.
 * Сами файлы хранятся в скрытой папке .GalleryX/trash.
 */
public class TrashStore {

    public static class Entry {
        public String origPath;
        public String path;
        public String name;
        public long date;
        public long size;
        public boolean video;
    }

    private static volatile TrashStore instance;

    public static TrashStore get() {
        if (instance == null) {
            synchronized (TrashStore.class) {
                if (instance == null) instance = new TrashStore();
            }
        }
        return instance;
    }

    private TrashStore() {
    }

    /** Папка корзины внутри корневой папки; null, если корень ещё не выбран. */
    public static File trashDir() {
        return AppDirs.trashDir();
    }

    /** Переписывает пути записей после переезда служебной папки. */
    public synchronized void rewritePrefix(String from, String to) {
        List<Entry> list = load();
        boolean changed = false;
        for (Entry e : list) {
            if (e.path.startsWith(from + "/")) {
                e.path = to + e.path.substring(from.length());
                changed = true;
            }
        }
        if (changed) save(list);
    }

    /**
     * Файл индекса корзины внутри самой папки корзины. Раньше метаданные
     * (даты удаления!) жили только в SharedPreferences, которые стираются
     * при удалении приложения: файлы в .GalleryX/trash оставались, а даты
     * терялись, и после переустановки все файлы «находились заново» со
     * свежими 30 днями — обратный отсчёт и автоудаление никогда не
     * срабатывали. Теперь индекс лежит рядом с файлами и переживает
     * переустановку (корень выбирается тот же). SharedPreferences
     * остались зеркалом на случай, пока корень не выбран.
     */
    private static java.io.File indexFile(boolean createDirs) {
        try {
            java.io.File dir = createDirs ? AppDirs.trashDir() : null;
            if (dir == null) {
                java.io.File hidden = AppDirs.hiddenRoot();
                if (hidden == null) return null;
                dir = new java.io.File(hidden, "trash");
            }
            return new java.io.File(dir, ".trash_index.json");
        } catch (Exception e) {
            return null;
        }
    }

    private static String readIndexFile() {
        try {
            java.io.File f = indexFile(false);
            if (f == null || !f.isFile() || f.length() <= 0 || f.length() > 16L * 1024 * 1024) {
                return null;
            }
            byte[] buf = new byte[(int) f.length()];
            java.io.FileInputStream in = new java.io.FileInputStream(f);
            try {
                int read = 0;
                while (read < buf.length) {
                    int n = in.read(buf, read, buf.length - read);
                    if (n < 0) break;
                    read += n;
                }
                if (read != buf.length) return null;
            } finally {
                in.close();
            }
            return new String(buf, java.nio.charset.StandardCharsets.UTF_8);
        } catch (Exception e) {
            return null;
        }
    }

    private synchronized List<Entry> load() {
        List<Entry> out = new ArrayList<>();
        try {
            String json = readIndexFile();
            if (json == null) {
                // Файла ещё нет (старая версия) — берём из SharedPreferences,
                // при первой же записи индекс переедет в файл
                json = Prefs.sp().getString("trash_json", "[]");
            }
            JSONArray arr = new JSONArray(json);
            for (int i = 0; i < arr.length(); i++) {
                JSONObject o = arr.getJSONObject(i);
                Entry e = new Entry();
                e.origPath = o.optString("orig", "");
                e.path = o.optString("path", "");
                e.name = o.optString("name", "");
                e.date = o.optLong("date", 0);
                e.size = o.optLong("size", 0);
                e.video = o.optBoolean("video", false);
                if (!e.path.isEmpty()) out.add(e);
            }
        } catch (Exception ignored) {
        }
        return out;
    }

    private synchronized void save(List<Entry> list) {
        JSONArray arr = new JSONArray();
        for (Entry e : list) {
            JSONObject o = new JSONObject();
            try {
                o.put("orig", e.origPath);
                o.put("path", e.path);
                o.put("name", e.name);
                o.put("date", e.date);
                o.put("size", e.size);
                o.put("video", e.video);
                arr.put(o);
            } catch (Exception ignored) {
            }
        }
        String json = arr.toString();
        // Основное хранилище — файл в папке корзины (переживает переустановку)
        try {
            java.io.File f = indexFile(true);
            if (f != null) {
                java.io.FileOutputStream out = new java.io.FileOutputStream(f);
                try {
                    out.write(json.getBytes(java.nio.charset.StandardCharsets.UTF_8));
                } finally {
                    out.close();
                }
            }
        } catch (Exception ignored) {
        }
        Prefs.sp().edit().putString("trash_json", json).apply();
    }

    public synchronized void add(String origPath, File trashedFile, String name, long size, boolean video) {
        List<Entry> list = load();
        Entry e = new Entry();
        e.origPath = origPath;
        e.path = trashedFile.getAbsolutePath();
        e.name = name;
        e.date = System.currentTimeMillis();
        e.size = size;
        e.video = video;
        list.add(e);
        save(list);
    }

    /** Папка, куда восстанавливаются файлы, положенные в корзину вручную. */
    public static final String RESTORED_DIR = "Restored";

    /** true — файл попал в корзину не через приложение (нет исходного пути). */
    public static boolean isManual(Entry e) {
        return e.origPath == null || e.origPath.isEmpty();
    }

    /**
     * Подхватывает файлы, которые пользователь положил в .GalleryX/trash сам
     * (через файловый менеджер): они появляются в корзине как обычные,
     * срок хранения отсчитывается с момента обнаружения.
     */
    private synchronized void syncExternal(List<Entry> list) {
        File dir = AppDirs.hiddenRoot();
        if (dir == null) return;
        List<File> files = new ArrayList<>();
        // Основная папка корзины и её «точечный» вариант, если кто-то создал его вручную
        for (String sub : new String[]{"trash", ".trash"}) {
            File[] arr = new File(dir, sub).listFiles();
            if (arr != null) java.util.Collections.addAll(files, arr);
        }
        if (files.isEmpty()) return;
        java.util.HashSet<String> known = new java.util.HashSet<>();
        for (Entry e : list) known.add(e.path);
        boolean changed = false;
        long now = System.currentTimeMillis();
        for (File f : files) {
            if (!f.isFile() || f.getName().startsWith(".")) continue;
            if (!MediaEngine.isMediaName(f.getName())) continue;
            if (known.contains(f.getAbsolutePath())) continue;
            Entry e = new Entry();
            e.origPath = "";
            e.path = f.getAbsolutePath();
            e.name = f.getName();
            e.date = now;
            e.size = f.length();
            e.video = MediaEngine.isVideoName(f.getName());
            list.add(e);
            changed = true;
        }
        if (changed) save(list);
    }

    public synchronized List<Entry> entries() {
        List<Entry> out = new ArrayList<>();
        long now = System.currentTimeMillis();
        List<Entry> list = load();
        syncExternal(list);
        for (Entry e : list) {
            if (new File(e.path).exists() && now - e.date < 30L * 24 * 3600 * 1000) {
                out.add(e);
            }
        }
        return out;
    }

    public synchronized void remove(Entry e) {
        List<Entry> list = load();
        Iterator<Entry> it = list.iterator();
        while (it.hasNext()) {
            if (it.next().path.equals(e.path)) it.remove();
        }
        save(list);
    }

    public synchronized void removeAll(List<Entry> toRemove) {
        List<Entry> list = load();
        Iterator<Entry> it = list.iterator();
        while (it.hasNext()) {
            Entry cur = it.next();
            for (Entry r : toRemove) {
                if (cur.path.equals(r.path)) {
                    it.remove();
                    break;
                }
            }
        }
        save(list);
    }

    /** Удаляет записи по путям файлов (например, после удаления навсегда). */
    public synchronized void removePaths(java.util.Collection<String> paths) {
        if (paths == null || paths.isEmpty()) return;
        List<Entry> list = load();
        boolean changed = false;
        Iterator<Entry> it = list.iterator();
        while (it.hasNext()) {
            if (paths.contains(it.next().path)) {
                it.remove();
                changed = true;
            }
        }
        if (changed) save(list);
    }

    /** Убирает записи, файлы которых уже не существуют (после массового удаления). */
    public synchronized void clearMissing() {
        List<Entry> list = load();
        boolean changed = false;
        Iterator<Entry> it = list.iterator();
        while (it.hasNext()) {
            if (!new File(it.next().path).exists()) {
                it.remove();
                changed = true;
            }
        }
        if (changed) save(list);
    }

    /** Полная очистка корзины: удаление файлов и метаданных. */
    public synchronized void clearAll() {
        List<Entry> list = load();
        for (Entry e : list) {
            //noinspection ResultOfMethodCallIgnored
            new File(e.path).delete();
        }
        save(new ArrayList<>());
    }

    /** Удаление записей старше N дней вместе с файлами. */
    public synchronized void cleanup(int days) {
        long limit = days * 24L * 3600 * 1000;
        long now = System.currentTimeMillis();
        List<Entry> list = load();
        Iterator<Entry> it = list.iterator();
        boolean changed = false;
        while (it.hasNext()) {
            Entry e = it.next();
            if (now - e.date >= limit || !new File(e.path).exists()) {
                //noinspection ResultOfMethodCallIgnored
                new File(e.path).delete();
                it.remove();
                changed = true;
            }
        }
        if (changed) save(list);
    }

    public synchronized int count() {
        return entries().size();
    }
}
