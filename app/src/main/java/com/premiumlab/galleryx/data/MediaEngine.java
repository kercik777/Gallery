package com.premiumlab.galleryx.data;

import android.content.ContentResolver;
import android.content.Context;
import android.database.Cursor;
import android.os.Build;
import android.os.Environment;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.provider.BaseColumns;
import android.provider.MediaStore;

import java.io.File;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Загрузка фото и видео со всего устройства (MediaStore) и из конкретных папок (File API).
 */
public final class MediaEngine {

    private static final ExecutorService EXEC = Executors.newSingleThreadExecutor();
    private static final Handler MAIN = new Handler(Looper.getMainLooper());

    /** Кэш общего списка медиа: мгновенные переключения между вкладками. */
    private static final long ALL_CACHE_TTL = 4000L;
    private static volatile List<MediaItem> allCache;
    private static volatile long allCacheAt;

    private static final List<String> IMG_EXT = Arrays.asList(
            "jpg", "jpeg", "png", "gif", "webp", "bmp", "heic", "heif", "dng", "tiff", "avif");
    private static final List<String> VID_EXT = Arrays.asList(
            "mp4", "mkv", "avi", "mov", "webm", "3gp", "m4v", "mpg", "mpeg", "ts", "flv", "wmv");

    public interface Callback<T> {
        void onLoaded(T data);
    }

    private MediaEngine() {
    }

    public static boolean isVideoName(String name) {
        return VID_EXT.contains(ext(name));
    }

    public static boolean isMediaName(String name) {
        String e = ext(name);
        return IMG_EXT.contains(e) || VID_EXT.contains(e);
    }

    private static String ext(String name) {
        if (name == null) return "";
        int dot = name.lastIndexOf('.');
        if (dot < 0 || dot == name.length() - 1) return "";
        return name.substring(dot + 1).toLowerCase(Locale.US);
    }

    /** Скрытая служебная директория приложения (внутри корня) или null без корня. */
    public static File hiddenRoot() {
        return AppDirs.hiddenRoot();
    }

    // ---------- Загрузка всей галереи (MediaStore) ----------

    /** Сбрасывает кэш списка (после файловых операций). */
    public static void invalidateAll() {
        allCache = null;
        allCacheAt = 0L;
    }

    /** Есть ли свежий кэш (чтобы не мигать индикатором загрузки). */
    public static boolean hasFreshCache() {
        return allCache != null
                && SystemClock.elapsedRealtime() - allCacheAt < ALL_CACHE_TTL;
    }

    public static void loadAll(Context ctx, Callback<List<MediaItem>> cb) {
        Context app = ctx.getApplicationContext();
        if (hasFreshCache()) {
            List<MediaItem> cached = allCache;
            MAIN.post(() -> cb.onLoaded(new ArrayList<>(cached)));
            return;
        }
        EXEC.execute(() -> {
            List<MediaItem> result = queryAll(app);
            allCache = result;
            allCacheAt = SystemClock.elapsedRealtime();
            MAIN.post(() -> cb.onLoaded(result));
        });
    }

    public static List<MediaItem> queryAll(Context ctx) {
        List<MediaItem> out = new ArrayList<>();
        try {
            queryTable(ctx, MediaStore.Images.Media.EXTERNAL_CONTENT_URI, false, out);
        } catch (Exception ignored) {
        }
        try {
            queryTable(ctx, MediaStore.Video.Media.EXTERNAL_CONTENT_URI, true, out);
        } catch (Exception ignored) {
        }
        sort(out, Prefs.sortMode());
        return out;
    }

    private static void queryTable(Context ctx, android.net.Uri uri, boolean video, List<MediaItem> out) {
        ContentResolver cr = ctx.getContentResolver();
        List<String> proj = new ArrayList<>();
        proj.add(BaseColumns._ID);
        proj.add(MediaStore.MediaColumns.DATA);
        proj.add(MediaStore.MediaColumns.DISPLAY_NAME);
        proj.add(MediaStore.MediaColumns.SIZE);
        proj.add(MediaStore.MediaColumns.DATE_MODIFIED);
        proj.add(MediaStore.MediaColumns.MIME_TYPE);
        if (video && Build.VERSION.SDK_INT >= 29) {
            proj.add(MediaStore.MediaColumns.DURATION);
        }
        Cursor c = cr.query(uri, proj.toArray(new String[0]), null, null, null);
        if (c == null) return;
        try {
            int iPath = c.getColumnIndexOrThrow(MediaStore.MediaColumns.DATA);
            int iName = c.getColumnIndexOrThrow(MediaStore.MediaColumns.DISPLAY_NAME);
            int iSize = c.getColumnIndexOrThrow(MediaStore.MediaColumns.SIZE);
            int iDate = c.getColumnIndexOrThrow(MediaStore.MediaColumns.DATE_MODIFIED);
            int iMime = c.getColumnIndexOrThrow(MediaStore.MediaColumns.MIME_TYPE);
            int iDur = video && Build.VERSION.SDK_INT >= 29
                    ? c.getColumnIndex(MediaStore.MediaColumns.DURATION) : -1;
            while (c.moveToNext()) {
                String path = c.getString(iPath);
                if (path == null || path.isEmpty()) continue;
                File f = new File(path);
                String parent = f.getParent();
                if (parent == null) continue;
                // Исключаем служебные скрытые каталоги (корзина, сейф)
                if (AppDirs.isServicePath(parent)) continue;
                if (f.getName().startsWith(".")) continue;
                MediaItem item = new MediaItem();
                item.path = path;
                item.name = c.getString(iName);
                if (item.name == null || item.name.isEmpty()) item.name = f.getName();
                item.folderPath = parent;
                item.size = c.getLong(iSize);
                item.dateModified = c.getLong(iDate) * 1000L;
                item.mime = c.getString(iMime);
                item.isVideo = video;
                item.duration = iDur >= 0 ? c.getLong(iDur) : 0;
                out.add(item);
            }
        } finally {
            c.close();
        }
    }

    // ---------- Содержимое конкретной папки (File API) ----------

    public static void loadFolder(File dir, Callback<List<MediaItem>> cb) {
        EXEC.execute(() -> {
            List<MediaItem> result = listFolder(dir);
            MAIN.post(() -> cb.onLoaded(result));
        });
    }

    public static List<MediaItem> listFolder(File dir) {
        List<MediaItem> out = new ArrayList<>();
        File[] files = dir.listFiles();
        if (files == null) return out;
        for (File f : files) {
            if (f.isFile() && isMediaName(f.getName()) && !f.getName().startsWith(".")) {
                out.add(MediaItem.fromFile(f));
            }
        }
        sort(out, Prefs.sortMode());
        return out;
    }

    /** Подпапки (для навигации внутри папки и выбора корня). */
    public static List<File> listSubdirs(File dir) {
        List<File> out = new ArrayList<>();
        File[] files = dir.listFiles();
        if (files == null) return out;
        for (File f : files) {
            if (f.isDirectory() && !f.getName().startsWith(".")) out.add(f);
        }
        Collections.sort(out, (a, b) -> a.getName().toLowerCase(Locale.US)
                .compareTo(b.getName().toLowerCase(Locale.US)));
        return out;
    }

    // ---------- Сортировка ----------

    public static void sort(List<MediaItem> list, int mode) {
        Comparator<MediaItem> cmp;
        switch (mode) {
            case Prefs.SORT_DATE_ASC:
                cmp = (a, b) -> Long.compare(a.dateModified, b.dateModified);
                break;
            case Prefs.SORT_NAME_ASC:
                cmp = (a, b) -> a.name.toLowerCase(Locale.US)
                        .compareTo(b.name.toLowerCase(Locale.US));
                break;
            case Prefs.SORT_NAME_DESC:
                cmp = (a, b) -> b.name.toLowerCase(Locale.US)
                        .compareTo(a.name.toLowerCase(Locale.US));
                break;
            case Prefs.SORT_SIZE_DESC:
                cmp = (a, b) -> Long.compare(b.size, a.size);
                break;
            case Prefs.SORT_SIZE_ASC:
                cmp = (a, b) -> Long.compare(a.size, b.size);
                break;
            default:
                cmp = (a, b) -> Long.compare(b.dateModified, a.dateModified);
        }
        Collections.sort(list, cmp);
    }

    // ---------- Альбомы ----------

    /**
     * Строит список альбомов: секция «Мои папки» (внутри корня) + «Папки устройства».
     * Возвращает список строк (заголовки секций) и объектов Album.
     */
    public static List<Object> buildAlbums(Context ctx) {
        List<Object> rows = new ArrayList<>();
        List<MediaItem> all = queryAll(ctx);
        HashMap<String, List<MediaItem>> byFolder = new HashMap<>();
        for (MediaItem it : all) {
            List<MediaItem> l = byFolder.get(it.folderPath);
            if (l == null) {
                l = new ArrayList<>();
                byFolder.put(it.folderPath, l);
            }
            l.add(it);
        }

        String rootPath = Prefs.rootPath();
        // Пока галерея «закрыта» маскировкой — корневой папки как будто нет
        boolean rootHidden = MaskGuard.hidden();

        // Мои папки (рекурсивно внутри корня)
        List<Album> user = new ArrayList<>();
        if (rootPath != null && !rootHidden) {
            File root = new File(rootPath);
            if (root.exists()) {
                // Сам корень альбомом не считается, вложенные папки второго
                // уровня — тоже: в «Альбомах» только прямые папки корня,
                // подпапки показываются внутри своей папки.
                for (File d : listSubdirs(root)) {
                    user.add(albumFor(d, true));
                }
                Collections.sort(user, (a, b) -> a.name.toLowerCase(Locale.US)
                        .compareTo(b.name.toLowerCase(Locale.US)));
            }
        }

        // Папки устройства
        List<Album> device = new ArrayList<>();
        for (String folder : byFolder.keySet()) {
            if (rootPath != null && isUnder(folder, rootPath)) continue;
            if (AppDirs.isServicePath(folder)) continue;
            if (folder.startsWith(new File(Environment.getExternalStorageDirectory(), "Android")
                    .getAbsolutePath())) continue;
            List<MediaItem> l = byFolder.get(folder);
            if (l.isEmpty()) continue;
            File f = new File(folder);
            device.add(new Album(f.getName(), folder, l.get(0).path, l.size(), false));
        }
        Collections.sort(device, (a, b) -> a.name.toLowerCase(Locale.US)
                .compareTo(b.name.toLowerCase(Locale.US)));

        if (!user.isEmpty()) {
            rows.add(Boolean.TRUE); // маркер секции «Мои папки»
            rows.addAll(user);
        }
        if (!device.isEmpty()) {
            rows.add(Boolean.FALSE); // маркер секции «Папки устройства»
            rows.addAll(device);
        }
        return rows;
    }

    /** Папки устройства (не из корня и не служебные) — для выбора назначения. */
    public static List<File> deviceFolders(Context ctx) {
        List<File> out = new ArrayList<>();
        java.util.HashSet<String> seen = new java.util.HashSet<>();
        String rootPath = Prefs.rootPath();
        String androidDir = new File(Environment.getExternalStorageDirectory(), "Android")
                .getAbsolutePath();
        for (MediaItem it : queryAll(ctx)) {
            String folder = it.folderPath;
            if (folder == null || !seen.add(folder)) continue;
            if (rootPath != null && isUnder(folder, rootPath)) continue;
            if (AppDirs.isServicePath(folder) || folder.startsWith(androidDir)) continue;
            out.add(new File(folder));
        }
        Collections.sort(out, (a, b) -> a.getName().toLowerCase(Locale.US)
                .compareTo(b.getName().toLowerCase(Locale.US)));
        return out;
    }

    public static void loadAlbums(Context ctx, Callback<List<Object>> cb) {
        Context app = ctx.getApplicationContext();
        EXEC.execute(() -> {
            List<Object> rows = buildAlbums(app);
            MAIN.post(() -> cb.onLoaded(rows));
        });
    }

    /** Карточка папки: имя, обложка и число файлов (прямое содержимое, свежее из File API). */
    public static Album albumFor(File dir, boolean isUser) {
        List<MediaItem> direct = directMedia(dir);
        int count = direct.size();
        String cover = count > 0 ? direct.get(0).path : null;
        if (cover == null) {
            // Пустая папка с подпапками — возьмём обложку из первой непустой подпапки
            for (File sub : listSubdirs(dir)) {
                List<MediaItem> inner = directMedia(sub);
                if (!inner.isEmpty()) {
                    cover = inner.get(0).path;
                    break;
                }
            }
        }
        return new Album(dir.getName(), dir.getAbsolutePath(), cover, count, isUser);
    }

    /** Карточки вложенных папок для экрана папки. */
    public static List<Album> subfolderAlbums(File dir, boolean isUser) {
        List<Album> out = new ArrayList<>();
        for (File d : listSubdirs(dir)) out.add(albumFor(d, isUser));
        return out;
    }

    /** Прямые медиафайлы папки без сортировки по дате (свежие из File API). */
    private static List<MediaItem> directMedia(File dir) {
        List<MediaItem> out = new ArrayList<>();
        File[] files = dir.listFiles();
        if (files == null) return out;
        for (File f : files) {
            if (f.isFile() && isMediaName(f.getName()) && !f.getName().startsWith(".")) {
                out.add(MediaItem.fromFile(f));
            }
        }
        Collections.sort(out, (a, b) -> Long.compare(b.dateModified, a.dateModified));
        return out;
    }

    /** Все подпапки внутри корня (рекурсивно), кроме скрытых. */
    public static List<File> userFoldersRecursive(File root) {
        List<File> out = new ArrayList<>();
        collectDirs(root, out, 0);
        return out;
    }

    private static void collectDirs(File dir, List<File> out, int depth) {
        if (depth > 5) return;
        File[] files = dir.listFiles();
        if (files == null) return;
        for (File f : files) {
            if (f.isDirectory() && !f.getName().startsWith(".")) {
                out.add(f);
                collectDirs(f, out, depth + 1);
            }
        }
    }

    public static boolean isUnder(String path, String rootPath) {
        return path != null && (path.equals(rootPath) || path.startsWith(rootPath + "/"));
    }
}
