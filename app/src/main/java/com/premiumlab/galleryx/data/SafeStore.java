package com.premiumlab.galleryx.data;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;

/**
 * Сейф: файлы переименовываются и переносятся в скрытую папку .GalleryX/safe,
 * полностью исчезая из галереи и системного MediaStore.
 */
public class SafeStore {

    public static class Entry {
        public String origPath;
        public String path;
        public String name;
        public long date;
        public long size;
        public boolean video;
        /** Папка сейфа; {@link #PERSONAL} — встроенная папка «Личный». */
        public String folder = PERSONAL;
    }

    /** Ключ встроенной папки «Личный» (есть и для фото, и для видео; не удаляется). */
    public static final String PERSONAL = "";

    private static final String FOLDERS_KEY = "safe_folders_json";

    private static volatile SafeStore instance;

    public static SafeStore get() {
        if (instance == null) {
            synchronized (SafeStore.class) {
                if (instance == null) instance = new SafeStore();
            }
        }
        return instance;
    }

    private SafeStore() {
    }

    /** Папка сейфа внутри корневой папки; null, если корень ещё не выбран. */
    public static File safeDir() {
        return AppDirs.safeDir();
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
            JSONArray arr = new JSONArray(Prefs.sp().getString("safe_json", "[]"));
            for (int i = 0; i < arr.length(); i++) {
                JSONObject o = arr.getJSONObject(i);
                Entry e = new Entry();
                e.origPath = o.optString("orig", "");
                e.path = o.optString("path", "");
                e.name = o.optString("name", "");
                e.date = o.optLong("date", 0);
                e.size = o.optLong("size", 0);
                e.video = o.optBoolean("video", false);
                e.folder = o.optString("folder", PERSONAL);
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
                o.put("folder", e.folder == null ? PERSONAL : e.folder);
                arr.put(o);
            } catch (Exception ignored) {
            }
        }
        Prefs.sp().edit().putString("safe_json", arr.toString()).apply();
        writeIndex(list, loadFolders());
    }

    // ---------- Индекс в самой папке сейфа (переживает переустановку) ----------

    private static final String INDEX_FILE = "index.json";

    /**
     * Зеркало записей и списка папок в <корень>/.GalleryX/safe/index.json.
     * Пути хранятся как имена файлов, поэтому индекс не зависит от расположения корня.
     */
    private void writeIndex(List<Entry> list, List<String[]> folders) {
        try {
            File dir = AppDirs.safeDir();
            if (dir == null) return;
            JSONArray arr = new JSONArray();
            for (Entry e : list) {
                JSONObject o = new JSONObject();
                o.put("file", new File(e.path).getName());
                o.put("orig", e.origPath == null ? "" : e.origPath);
                o.put("name", e.name == null ? "" : e.name);
                o.put("date", e.date);
                o.put("size", e.size);
                o.put("video", e.video);
                o.put("folder", folderOf(e));
                arr.put(o);
            }
            JSONArray fa = new JSONArray();
            for (String[] f : folders) {
                JSONObject o = new JSONObject();
                o.put("v", "1".equals(f[0]));
                o.put("n", f[1]);
                fa.put(o);
            }
            JSONObject root = new JSONObject();
            root.put("entries", arr);
            root.put("folders", fa);
            root.put("saved_at", System.currentTimeMillis());
            File tmp = new File(dir, "." + INDEX_FILE + ".tmp");
            try (java.io.FileOutputStream fos = new java.io.FileOutputStream(tmp)) {
                fos.write(root.toString().getBytes(java.nio.charset.StandardCharsets.UTF_8));
                fos.getFD().sync();
            }
            File target = new File(dir, INDEX_FILE);
            if (!tmp.renameTo(target)) {
                //noinspection ResultOfMethodCallIgnored
                tmp.delete();
            }
        } catch (Exception ignored) {
            // Индекс — страховка, основной список в настройках
        }
    }

    /**
     * Сверяет записи с содержимым папки сейфа. Файлы, которых нет в списке
     * (например, после переустановки приложения), возвращаются в сейф: сначала
     * по индексу index.json (с исходными именами и папками), а те, о которых
     * ничего не известно, — по содержимому файла (фото/видео определяется по
     * сигнатуре, папка «Личный»). Записи без файла удаляются.
     *
     * @return число восстановленных записей
     */
    public synchronized int syncFromDisk() {
        File dir = AppDirs.safeDir();
        if (dir == null || !dir.isDirectory()) return 0;
        List<Entry> list = load();
        java.util.HashSet<String> known = new java.util.HashSet<>();
        boolean changed = false;
        Iterator<Entry> it = list.iterator();
        while (it.hasNext()) {
            Entry e = it.next();
            if (!new File(e.path).exists()) {
                it.remove();
                changed = true;
            } else {
                known.add(new File(e.path).getName());
            }
        }
        List<String[]> folders = loadFolders();
        int restored = 0;

        // 1. Индекс из папки сейфа
        File idx = new File(dir, INDEX_FILE);
        if (idx.isFile()) {
            try {
                String json = new String(java.nio.file.Files.readAllBytes(idx.toPath()),
                        java.nio.charset.StandardCharsets.UTF_8);
                JSONObject root = new JSONObject(json);
                JSONArray arr = root.optJSONArray("entries");
                if (arr != null) {
                    for (int i = 0; i < arr.length(); i++) {
                        JSONObject o = arr.getJSONObject(i);
                        String fn = o.optString("file", "");
                        if (fn.isEmpty() || known.contains(fn)) continue;
                        File f = new File(dir, fn);
                        if (!f.isFile()) continue;
                        Entry e = new Entry();
                        e.path = f.getAbsolutePath();
                        e.origPath = o.optString("orig", "");
                        e.name = o.optString("name", "");
                        if (e.name.isEmpty()) e.name = guessName(f);
                        e.date = o.optLong("date", f.lastModified());
                        e.size = o.optLong("size", f.length());
                        e.video = o.optBoolean("video", false);
                        e.folder = o.optString("folder", PERSONAL);
                        list.add(e);
                        known.add(fn);
                        restored++;
                    }
                }
                JSONArray fa = root.optJSONArray("folders");
                if (fa != null) {
                    for (int i = 0; i < fa.length(); i++) {
                        JSONObject o = fa.getJSONObject(i);
                        String n = o.optString("n", "");
                        String v = o.optBoolean("v", false) ? "1" : "0";
                        if (n.isEmpty()) continue;
                        boolean has = false;
                        for (String[] f : folders) {
                            if (f[0].equals(v) && f[1].equals(n)) {
                                has = true;
                                break;
                            }
                        }
                        if (!has) {
                            folders.add(new String[]{v, n});
                            changed = true;
                        }
                    }
                }
            } catch (Exception ignored) {
            }
        }

        // 2. Файлы, о которых индекс не знает
        File[] files = dir.listFiles();
        if (files != null) {
            for (File f : files) {
                if (!f.isFile()) continue;
                String fn = f.getName();
                if (fn.startsWith(".") || fn.equals(INDEX_FILE) || known.contains(fn)) continue;
                if (!fn.endsWith(".bin")) continue;
                Entry e = new Entry();
                e.path = f.getAbsolutePath();
                e.origPath = "";
                e.video = sniffVideo(f);
                e.name = guessName(f);
                e.date = f.lastModified() > 0 ? f.lastModified() : System.currentTimeMillis();
                e.size = f.length();
                e.folder = PERSONAL;
                list.add(e);
                known.add(fn);
                restored++;
            }
        }

        if (restored > 0 || changed) {
            if (changed) saveFolders(folders);
            save(list);
        }
        return restored;
    }

    /** Имя для показа, если исходное неизвестно: по дате файла и сигнатуре. */
    private static String guessName(File f) {
        String ext = sniffExtension(f);
        String stamp = new java.text.SimpleDateFormat("yyyyMMdd_HHmmss", java.util.Locale.US)
                .format(new java.util.Date(f.lastModified() > 0
                        ? f.lastModified() : System.currentTimeMillis()));
        return (ext.equals("mp4") || ext.equals("mkv") || ext.equals("avi") || ext.equals("3gp")
                ? "VID_" : "IMG_") + stamp + "." + ext;
    }

    private static boolean sniffVideo(File f) {
        String ext = sniffExtension(f);
        return ext.equals("mp4") || ext.equals("mkv") || ext.equals("avi") || ext.equals("3gp");
    }

    /** Расширение по сигнатуре файла (имя в сейфе обезличено). */
    private static String sniffExtension(File f) {
        byte[] h = new byte[16];
        int n = 0;
        try (java.io.FileInputStream in = new java.io.FileInputStream(f)) {
            n = in.read(h);
        } catch (Exception ignored) {
        }
        if (n < 4) return "jpg";
        if ((h[0] & 0xFF) == 0xFF && (h[1] & 0xFF) == 0xD8) return "jpg";
        if ((h[0] & 0xFF) == 0x89 && h[1] == 'P' && h[2] == 'N' && h[3] == 'G') return "png";
        if (h[0] == 'G' && h[1] == 'I' && h[2] == 'F') return "gif";
        if (h[0] == 'B' && h[1] == 'M') return "bmp";
        if (n >= 12 && h[0] == 'R' && h[1] == 'I' && h[2] == 'F' && h[3] == 'F') {
            if (h[8] == 'W' && h[9] == 'E' && h[10] == 'B' && h[11] == 'P') return "webp";
            if (h[8] == 'A' && h[9] == 'V' && h[10] == 'I') return "avi";
        }
        if ((h[0] & 0xFF) == 0x1A && (h[1] & 0xFF) == 0x45 && (h[2] & 0xFF) == 0xDF
                && (h[3] & 0xFF) == 0xA3) {
            return "mkv";
        }
        if (n >= 12 && h[4] == 'f' && h[5] == 't' && h[6] == 'y' && h[7] == 'p') {
            String brand = new String(h, 8, 4, java.nio.charset.StandardCharsets.US_ASCII)
                    .toLowerCase(java.util.Locale.US);
            if (brand.startsWith("heic") || brand.startsWith("heix") || brand.startsWith("hevc")
                    || brand.startsWith("mif1") || brand.startsWith("msf1")
                    || brand.startsWith("avif")) {
                return "heic";
            }
            if (brand.startsWith("3gp") || brand.startsWith("3g2")) return "3gp";
            return "mp4";
        }
        return "jpg";
    }

    public synchronized void add(String origPath, File safeFile, String name, long size, boolean video) {
        List<Entry> list = load();
        Entry e = new Entry();
        e.origPath = origPath;
        e.path = safeFile.getAbsolutePath();
        e.name = name;
        e.date = System.currentTimeMillis();
        e.size = size;
        e.video = video;
        list.add(e);
        save(list);
    }

    public synchronized List<Entry> entries() {
        List<Entry> out = new ArrayList<>();
        for (Entry e : load()) {
            if (new File(e.path).exists()) out.add(e);
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

    public synchronized int count() {
        return entries().size();
    }

    // ---------- Папки сейфа ----------

    /** Записи одного вида (фото/видео). */
    public synchronized List<Entry> entries(boolean video) {
        List<Entry> out = new ArrayList<>();
        for (Entry e : entries()) if (e.video == video) out.add(e);
        return out;
    }

    /** Записи одной папки сейфа. */
    public synchronized List<Entry> entries(boolean video, String folder) {
        List<Entry> out = new ArrayList<>();
        for (Entry e : entries()) {
            if (e.video == video && folderOf(e).equals(folder)) out.add(e);
        }
        return out;
    }

    private static String folderOf(Entry e) {
        return e.folder == null ? PERSONAL : e.folder;
    }

    public synchronized int count(boolean video) {
        return entries(video).size();
    }

    /** Копия записи для скопированного внутри сейфа файла. */
    public synchronized void addCopy(Entry src, File newFile, String folder) {
        List<Entry> list = load();
        Entry e = new Entry();
        e.origPath = src.origPath;
        e.path = newFile.getAbsolutePath();
        e.name = src.name;
        e.date = System.currentTimeMillis();
        e.size = newFile.length();
        e.video = src.video;
        e.folder = folder;
        list.add(e);
        save(list);
    }

    /** Переносит записи (по путям файлов в сейфе) в другую папку сейфа. */
    public synchronized int moveToFolder(java.util.Collection<String> paths, String folder) {
        List<Entry> list = load();
        int n = 0;
        for (Entry e : list) {
            if (paths.contains(e.path) && !folderOf(e).equals(folder)) {
                e.folder = folder;
                n++;
            }
        }
        if (n > 0) save(list);
        return n;
    }

    private List<String[]> loadFolders() {
        List<String[]> out = new ArrayList<>();
        try {
            JSONArray arr = new JSONArray(Prefs.sp().getString(FOLDERS_KEY, "[]"));
            for (int i = 0; i < arr.length(); i++) {
                JSONObject o = arr.getJSONObject(i);
                String n = o.optString("n", "");
                if (!n.isEmpty()) out.add(new String[]{o.optBoolean("v", false) ? "1" : "0", n});
            }
        } catch (Exception ignored) {
        }
        return out;
    }

    private void saveFolders(List<String[]> list) {
        JSONArray arr = new JSONArray();
        for (String[] f : list) {
            JSONObject o = new JSONObject();
            try {
                o.put("v", "1".equals(f[0]));
                o.put("n", f[1]);
                arr.put(o);
            } catch (Exception ignored) {
            }
        }
        Prefs.sp().edit().putString(FOLDERS_KEY, arr.toString()).apply();
        writeIndex(load(), list);
    }

    /**
     * Пользовательские папки сейфа одного вида (без «Личный»), в порядке создания.
     * Папки, встречающиеся в записях, но потерянные в списке, добавляются в конец.
     */
    public synchronized List<String> folders(boolean video) {
        List<String> out = new ArrayList<>();
        String v = video ? "1" : "0";
        for (String[] f : loadFolders()) {
            if (f[0].equals(v) && !out.contains(f[1])) out.add(f[1]);
        }
        for (Entry e : entries(video)) {
            String f = folderOf(e);
            if (!f.equals(PERSONAL) && !out.contains(f)) out.add(f);
        }
        return out;
    }

    public synchronized boolean folderExists(boolean video, String name) {
        return name.equals(PERSONAL) || folders(video).contains(name);
    }

    /** @return false, если папка с таким именем уже есть. */
    public synchronized boolean addFolder(boolean video, String name) {
        if (folderExists(video, name)) return false;
        List<String[]> list = loadFolders();
        list.add(new String[]{video ? "1" : "0", name});
        saveFolders(list);
        return true;
    }

    /** Переименовывает пользовательскую папку вместе с её записями. */
    public synchronized boolean renameFolder(boolean video, String from, String to) {
        if (from.equals(PERSONAL) || folderExists(video, to)) return false;
        List<String[]> list = loadFolders();
        boolean found = false;
        for (String[] f : list) {
            if (f[0].equals(video ? "1" : "0") && f[1].equals(from)) {
                f[1] = to;
                found = true;
            }
        }
        if (!found) list.add(new String[]{video ? "1" : "0", to});
        saveFolders(list);
        List<Entry> entries = load();
        boolean changed = false;
        for (Entry e : entries) {
            if (e.video == video && folderOf(e).equals(from)) {
                e.folder = to;
                changed = true;
            }
        }
        if (changed) save(entries);
        return true;
    }

    /** Удаляет пользовательскую папку из списка (файлы должны быть удалены заранее). */
    public synchronized void removeFolder(boolean video, String name) {
        if (name.equals(PERSONAL)) return;
        List<String[]> list = loadFolders();
        Iterator<String[]> it = list.iterator();
        while (it.hasNext()) {
            String[] f = it.next();
            if (f[0].equals(video ? "1" : "0") && f[1].equals(name)) it.remove();
        }
        saveFolders(list);
        // Оставшиеся записи этой папки (если были) — в «Личный», чтобы ничего не потерять
        List<Entry> entries = load();
        boolean changed = false;
        for (Entry e : entries) {
            if (e.video == video && folderOf(e).equals(name)) {
                e.folder = PERSONAL;
                changed = true;
            }
        }
        if (changed) save(entries);
    }
}
