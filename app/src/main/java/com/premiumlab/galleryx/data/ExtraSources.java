package com.premiumlab.galleryx.data;

import android.content.Context;
import android.os.Environment;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * «Дополнительные источники»: фото и видео, которые лежат вне обычной галереи —
 * в скрытых папках (.имя, .nomedia), в данных приложений (Android/media:
 * WhatsApp, Telegram…), в Documents и т.п. Система их не индексирует или
 * галерея их не показывает как альбомы. Пользователь вручную выбирает, какие
 * из таких папок добавить в «Галерею» и «Альбомы».
 *
 * Включённые папки читаются напрямую с диска (File API) при каждой загрузке
 * галереи и прячутся вместе с корневой папкой, пока маскировка «закрыта».
 */
public final class ExtraSources {

    /** Категории для группировки на экране. */
    public static final int CAT_APPS = 0;     // Android/media и другие данные приложений
    public static final int CAT_HIDDEN = 1;   // скрытые папки: .имя или .nomedia
    public static final int CAT_OTHER = 2;    // прочие папки, не попавшие в галерею

    /** Защита от бесконечного обхода на очень больших хранилищах. */
    private static final int MAX_DIRS = 60000;
    private static final int MAX_DEPTH = 14;

    /** Файл кеша последнего сканирования (показываем мгновенно при входе). */
    private static final String CACHE_NAME = "extra_sources_cache.json";

    public static final class Source {
        public final String path;
        public final String name;
        /** Путь без префикса хранилища — для подписи. */
        public final String rel;
        public final int photos;
        public final int videos;
        public final String cover;
        public final long newest;
        public final boolean hidden;
        public final int category;
        public boolean enabled;

        Source(String path, String rel, int photos, int videos, String cover, long newest,
               boolean hidden, int category, boolean enabled) {
            this.path = path;
            this.name = new File(path).getName();
            this.rel = rel;
            this.photos = photos;
            this.videos = videos;
            this.cover = cover;
            this.newest = newest;
            this.hidden = hidden;
            this.category = category;
            this.enabled = enabled;
        }

        public int total() {
            return photos + videos;
        }

        JSONObject toJson() throws org.json.JSONException {
            JSONObject o = new JSONObject();
            o.put("path", path);
            o.put("rel", rel);
            o.put("photos", photos);
            o.put("videos", videos);
            o.put("cover", cover == null ? JSONObject.NULL : cover);
            o.put("newest", newest);
            o.put("hidden", hidden);
            o.put("cat", category);
            return o;
        }

        static Source fromJson(JSONObject o) throws org.json.JSONException {
            return new Source(
                    o.getString("path"), o.optString("rel", ""), o.optInt("photos"),
                    o.optInt("videos"), o.isNull("cover") ? null : o.optString("cover"),
                    o.optLong("newest"), o.optBoolean("hidden"), o.optInt("cat", CAT_OTHER),
                    false);
        }
    }

    private ExtraSources() {
    }

    /** Включённые папки, которые сейчас нужно подмешивать в галерею. */
    public static Set<String> active() {
        if (MaskGuard.hidden()) return Collections.emptySet();
        return Prefs.extraFolders();
    }

    public static boolean isEnabled(String folder) {
        return folder != null && Prefs.extraFolders().contains(folder);
    }

    public static void setEnabled(String folder, boolean on) {
        Set<String> set = Prefs.extraFolders();
        boolean changed = on ? set.add(folder) : set.remove(folder);
        if (changed) {
            Prefs.setExtraFolders(set);
            MediaEngine.invalidateAll();
        }
    }

    public static void setAll(List<Source> sources, boolean on) {
        Set<String> set = Prefs.extraFolders();
        for (Source s : sources) {
            if (on) set.add(s.path); else set.remove(s.path);
            s.enabled = on;
        }
        Prefs.setExtraFolders(set);
        MediaEngine.invalidateAll();
    }

    /**
     * Добавляет файлы включённых папок к общему списку (прямое содержимое,
     * без дубликатов с тем, что уже пришло из MediaStore).
     */
    static void appendActive(Set<String> known, List<MediaItem> out) {
        for (String folder : active()) {
            File dir = new File(folder);
            File[] files = dir.listFiles();
            if (files == null) continue;
            for (File f : files) {
                String name = f.getName();
                if (name.startsWith(".") || !f.isFile() || !MediaEngine.isMediaName(name)) continue;
                if (known.add(f.getAbsolutePath())) out.add(MediaItem.fromFile(f));
            }
        }
    }

    // ---------- Сканирование ----------

    /** Корни хранилищ: внутренняя память и карты памяти. */
    public static List<File> volumes(Context ctx) {
        LinkedHashSet<File> out = new LinkedHashSet<>();
        out.add(Environment.getExternalStorageDirectory());
        try {
            File[] dirs = ctx.getExternalFilesDirs(null);
            if (dirs != null) {
                for (File d : dirs) {
                    if (d == null) continue;
                    // …/storage/XXXX-XXXX/Android/data/<pkg>/files → поднимаемся до тома
                    File cur = d;
                    for (int i = 0; i < 4 && cur != null; i++) cur = cur.getParentFile();
                    if (cur != null && cur.isDirectory()) out.add(cur);
                }
            }
        } catch (Exception ignored) {
        }
        return new ArrayList<>(out);
    }

    /**
     * Полный обход хранилища. Возвращает папки с медиафайлами, которых
     * сейчас нет в галерее как альбомов (плюс все уже включённые — чтобы их
     * можно было выключить). Вызывать в фоне.
     *
     * @param progress необязательный приёмник прогресса (число просмотренных папок)
     */
    public static List<Source> scan(Context ctx, ProgressListener progress) {
        // Что галерея показывает и так (без дополнительных источников)
        Set<String> enabled = Prefs.extraFolders();
        Set<String> visibleAlbums = new HashSet<>();
        try {
            for (MediaItem it : MediaEngine.queryAllBase(ctx)) {
                if (it.folderPath != null) visibleAlbums.add(it.folderPath);
            }
        } catch (Exception ignored) {
        }
        String root = Prefs.rootPath();
        List<Source> out = new ArrayList<>();
        int seen = 0;
        for (File volume : volumes(ctx)) {
            String vol = volume.getAbsolutePath();
            String androidMedia = vol + "/Android";
            ArrayDeque<File> stack = new ArrayDeque<>();
            ArrayDeque<Integer> depths = new ArrayDeque<>();
            ArrayDeque<Boolean> hiddenFlags = new ArrayDeque<>();
            stack.push(volume);
            depths.push(0);
            hiddenFlags.push(Boolean.FALSE);
            while (!stack.isEmpty() && seen < MAX_DIRS) {
                File dir = stack.pop();
                int depth = depths.pop();
                boolean parentHidden = hiddenFlags.pop();
                String path = dir.getAbsolutePath();
                if (skipDir(dir, path, vol, root)) continue;
                File[] files = dir.listFiles();
                if (files == null) continue;
                seen++;
                if (progress != null && (seen & 63) == 0) progress.onProgress(seen, out.size());

                boolean hasNoMedia = false;
                int photos = 0, videos = 0;
                String cover = null;
                long newest = 0;
                List<File> subdirs = null;
                for (File f : files) {
                    String name = f.getName();
                    if (f.isDirectory()) {
                        if (depth < MAX_DEPTH) {
                            if (subdirs == null) subdirs = new ArrayList<>();
                            subdirs.add(f);
                        }
                        continue;
                    }
                    if (name.equals(".nomedia")) {
                        hasNoMedia = true;
                        continue;
                    }
                    if (name.startsWith(".") || !MediaEngine.isMediaName(name)) continue;
                    if (MediaEngine.isVideoName(name)) videos++; else photos++;
                    long m = f.lastModified();
                    if (cover == null || m > newest) {
                        newest = m;
                        cover = f.getAbsolutePath();
                    }
                }
                boolean hidden = parentHidden || hasNoMedia || dir.getName().startsWith(".");
                if (subdirs != null) {
                    for (File sd : subdirs) {
                        stack.push(sd);
                        depths.push(depth + 1);
                        hiddenFlags.push(hidden);
                    }
                }
                if (photos + videos == 0) continue;
                boolean inApps = path.startsWith(androidMedia + "/");
                boolean isEnabled = enabled.contains(path);
                // Уже видна как обычный альбом — предлагать нечего
                if (!isEnabled && !inApps && !hidden && visibleAlbums.contains(path)) continue;
                int cat = inApps ? CAT_APPS : hidden ? CAT_HIDDEN : CAT_OTHER;
                String rel = path.startsWith(vol + "/") ? path.substring(vol.length() + 1) : path;
                out.add(new Source(path, rel, photos, videos, cover, newest, hidden, cat, isEnabled));
            }
        }
        // Внутри категории — где больше файлов, те выше
        Collections.sort(out, (a, b) -> {
            if (a.category != b.category) return Integer.compare(a.category, b.category);
            if (a.total() != b.total()) return Integer.compare(b.total(), a.total());
            return a.rel.toLowerCase(Locale.ROOT).compareTo(b.rel.toLowerCase(Locale.ROOT));
        });
        return out;
    }

    public interface ProgressListener {
        void onProgress(int dirsSeen, int found);
    }

    /** Папки, в которые заходить не нужно. */
    private static boolean skipDir(File dir, String path, String vol, String root) {
        String name = dir.getName();
        if (path.equals(vol + "/Android/data") || path.equals(vol + "/Android/obb")) return true;
        if (name.equals(".thumbnails") || name.equals("LOST.DIR") || name.equals(".Trash")
                || name.startsWith(".trashed-") || name.equals(".cache") || name.equals("cache")) {
            return true;
        }
        if (AppDirs.isServicePath(path)) return true;           // корзина, сейф
        // Корневая папка галереи показывается своим механизмом («Мои папки»)
        if (root != null && MediaEngine.isUnder(path, root)) return true;
        if (LockStore.isLocked(path)) return true;
        return false;
    }

    // ---------- Кеш сканирования ----------

    /**
     * Сохраняет результат сканирования: при следующем входе список показывается
     * мгновенно, а свежее сканирование идёт в фоне. Флаги «включено» не храним —
     * при чтении они подтягиваются из настроек (ими можно щёлкать и во время скана).
     */
    public static void saveCache(Context ctx, List<Source> sources) {
        try {
            JSONArray arr = new JSONArray();
            for (Source s : sources) arr.put(s.toJson());
            File f = new File(ctx.getFilesDir(), CACHE_NAME);
            FileOutputStream fos = new FileOutputStream(f);
            try {
                fos.write(arr.toString().getBytes(StandardCharsets.UTF_8));
            } finally {
                try {
                    fos.close();
                } catch (Exception ignored) {
                }
            }
        } catch (Exception ignored) {
        }
    }

    /**
     * Читает кеш: null — кеша ещё нет (первый вход), пустой список —
     * при прошлом сканировании ничего дополнительного не нашлось.
     */
    public static List<Source> loadCache(Context ctx) {
        try {
            File f = new File(ctx.getFilesDir(), CACHE_NAME);
            if (!f.isFile()) return null;
            byte[] buf = new byte[(int) Math.min(f.length(), 4L * 1024 * 1024)];
            FileInputStream fis = new FileInputStream(f);
            int read = 0;
            try {
                while (read < buf.length) {
                    int n = fis.read(buf, read, buf.length - read);
                    if (n < 0) break;
                    read += n;
                }
            } finally {
                try {
                    fis.close();
                } catch (Exception ignored) {
                }
            }
            JSONArray arr = new JSONArray(new String(buf, 0, read, StandardCharsets.UTF_8));
            Set<String> enabled = Prefs.extraFolders();
            List<Source> out = new ArrayList<>(arr.length());
            for (int i = 0; i < arr.length(); i++) {
                Source s = Source.fromJson(arr.getJSONObject(i));
                s.enabled = enabled.contains(s.path);
                out.add(s);
            }
            return out;
        } catch (Exception e) {
            return null;
        }
    }

    /** Подпись «N фото · M видео». */
    public static String countsLabel(Context ctx, Source s) {
        StringBuilder sb = new StringBuilder();
        if (s.photos > 0) {
            sb.append(ctx.getResources().getQuantityString(
                    com.premiumlab.galleryx.R.plurals.photos_count, s.photos, s.photos));
        }
        if (s.videos > 0) {
            if (sb.length() > 0) sb.append(" · ");
            sb.append(ctx.getResources().getQuantityString(
                    com.premiumlab.galleryx.R.plurals.videos_count, s.videos, s.videos));
        }
        return sb.toString();
    }
}
