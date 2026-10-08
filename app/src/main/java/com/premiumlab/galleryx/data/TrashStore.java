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

    private synchronized List<Entry> load() {
        List<Entry> out = new ArrayList<>();
        try {
            JSONArray arr = new JSONArray(Prefs.sp().getString("trash_json", "[]"));
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
        Prefs.sp().edit().putString("trash_json", arr.toString()).apply();
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

    public synchronized List<Entry> entries() {
        List<Entry> out = new ArrayList<>();
        long now = System.currentTimeMillis();
        for (Entry e : load()) {
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
