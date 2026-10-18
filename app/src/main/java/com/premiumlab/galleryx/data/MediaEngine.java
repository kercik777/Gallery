package com.premiumlab.galleryx.data;

import com.premiumlab.galleryx.R;

import org.json.JSONArray;
import org.json.JSONObject;

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
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.nio.charset.StandardCharsets;
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
    private static volatile boolean allCacheHidden;
    /**
     * Дисковый кеш списка (переживает перезапуск): холодный старт показывает
     * галерею мгновенно, свежий запрос догоняет в фоне — как Room-кеш
     * библиотеки у GorillaGallery. true — кеш протух за сессию (были файловые
     * операции), на холодном старте всегда false.
     */
    private static final String DISK_CACHE = "media_cache_v2.json";
    private static final long DISK_CACHE_MAX = 16L * 1024 * 1024;
    private static volatile boolean diskDirty = false;
    /** Штамп библиотеки на момент последнего полного запроса (для checkChanged). */
    private static volatile long stampMax = -1;
    private static volatile int stampCount = -1;
    private static volatile long stampIds = 0;
    private static volatile long stampRoot = -2;
    private static volatile long stampExtras = -2;

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
        // За сессию файлы менялись — дисковый кеш больше не показываем,
        // иначе только что удалённое на секунду «воскреснет»
        diskDirty = true;
    }

    /** Есть ли свежий кэш (чтобы не мигать индикатором загрузки). */
    public static boolean hasFreshCache() {
        return allCache != null
                && allCacheHidden == MaskGuard.hidden()
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
            // Холодный старт: мгновенно отдаём дисковый кеш прошлой сессии,
            // свежее догрузится следом. ТОЛЬКО при холодном старте: если
            // кэш в памяти уже есть, второй (дисковой) доставки быть не
            // должно — пара «снимок → свежий список» на каждом возвращении
            // на экран заставляла DiffUtil перетасовывать ячейки туда-сюда.
            if (!diskDirty && allCache == null) {
                List<MediaItem> disk = null;
                try {
                    disk = loadDiskCache(app);
                } catch (Throwable ignored) {
                }
                if (disk != null) {
                    // Кеш мог быть записан до фильтра данных приложений —
                    // чистим и его, чтобы чужие фото не мелькнули при старте
                    java.util.List<String> roots = new ArrayList<>();
                    for (File vol : ExtraSources.volumes(app)) {
                        roots.add(new File(vol, "Android").getAbsolutePath() + "/");
                    }
                    java.util.Set<String> extras = ExtraSources.active();
                    List<MediaItem> snap = new ArrayList<>();
                    for (MediaItem it : disk) {
                        String parent = new File(it.path).getParent();
                        if (parent != null && isAppDataOrHidden(it.path, parent, roots, extras)) {
                            continue;
                        }
                        snap.add(it);
                    }
                    List<MediaItem> clean = snap;
                    MAIN.post(() -> cb.onLoaded(clean));
                }
            }
            boolean hiddenNow = MaskGuard.hidden();
            List<MediaItem> result = queryAll(app);
            allCache = result;
            allCacheHidden = hiddenNow;
            allCacheAt = SystemClock.elapsedRealtime();
            updateStamp(app, result);
            try {
                saveDiskCache(app, result, hiddenNow);
            } catch (Throwable ignored) {
            }
            diskDirty = false;
            MAIN.post(() -> cb.onLoaded(result));
        });
    }

    /** Есть ли файл дискового кеша (дешёвая проверка для индикатора загрузки). */
    public static boolean hasDiskCache(Context ctx) {
        try {
            File f = new File(ctx.getFilesDir(), DISK_CACHE);
            return f.isFile() && f.length() > 0;
        } catch (Exception e) {
            return false;
        }
    }

    /**
     * Сохраняет список в дисковый кеш. Вызывать в фоне после свежего запроса.
     * Формат: {hidden, sort, extras[], items[[id,path,name,folder,mime,size,date,dur,video]…]}.
     */
    private static void saveDiskCache(Context ctx, List<MediaItem> items, boolean hidden) {
        try {
            JSONObject root = new JSONObject();
            root.put("hidden", hidden);
            root.put("sort", Prefs.sortMode());
            JSONArray ex = new JSONArray();
            for (String e : ExtraSources.active()) ex.put(e);
            root.put("extras", ex);
            JSONArray arr = new JSONArray();
            for (MediaItem it : items) {
                JSONArray r = new JSONArray();
                r.put(it.id);
                r.put(it.path);
                r.put(it.name);
                r.put(it.folderPath);
                r.put(it.mime);
                r.put(it.size);
                r.put(it.dateModified);
                r.put(it.duration);
                r.put(it.dateTaken);
                r.put(it.isVideo);
                arr.put(r);
            }
            root.put("items", arr);
            FileOutputStream fos = new FileOutputStream(new File(ctx.getFilesDir(), DISK_CACHE));
            try {
                fos.write(root.toString().getBytes(StandardCharsets.UTF_8));
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
     * Читает дисковый кеш: null — кеша нет или он не подходит под текущее
     * состояние (маскировка/допапки изменились). Пропавшие файлы отбрасываются,
     * порядок приводится к текущей сортировке. Вызывать в фоне.
     */
    private static List<MediaItem> loadDiskCache(Context ctx) {
        try {
            File f = new File(ctx.getFilesDir(), DISK_CACHE);
            if (!f.isFile() || f.length() <= 0 || f.length() > DISK_CACHE_MAX) return null;
            int len = (int) f.length();
            byte[] buf = new byte[len];
            FileInputStream fis = new FileInputStream(f);
            int read = 0;
            try {
                while (read < len) {
                    int n = fis.read(buf, read, len - read);
                    if (n < 0) break;
                    read += n;
                }
            } finally {
                try {
                    fis.close();
                } catch (Exception ignored) {
                }
            }
            if (read != len) return null;
            JSONObject root = new JSONObject(new String(buf, StandardCharsets.UTF_8));
            if (root.optBoolean("hidden") != MaskGuard.hidden()) return null;
            java.util.HashSet<String> storedEx = new java.util.HashSet<>();
            JSONArray ex = root.optJSONArray("extras");
            if (ex != null) {
                for (int i = 0; i < ex.length(); i++) storedEx.add(ex.optString(i));
            }
            if (!storedEx.equals(ExtraSources.active())) return null;
            JSONArray arr = root.optJSONArray("items");
            if (arr == null) return null;
            List<MediaItem> out = new ArrayList<>(arr.length());
            for (int i = 0; i < arr.length(); i++) {
                JSONArray r = arr.optJSONArray(i);
                if (r == null || r.length() < 9) continue;
                String path = r.optString(1, "");
                if (path.isEmpty() || !new File(path).exists()) continue;
                MediaItem it = new MediaItem();
                it.id = r.optLong(0);
                it.path = path;
                it.name = r.optString(2, "");
                it.folderPath = r.optString(3, "");
                it.mime = r.optString(4, "");
                it.size = r.optLong(5);
                it.dateModified = r.optLong(6);
                it.duration = r.optLong(7);
                it.isVideo = r.optBoolean(8);
                it.dateTaken = r.optLong(9);
                out.add(it);
            }
            // Всегда сортируем как свежий запрос: порядок снимка обязан
            // совпадать с порядком свежего списка до единого файла, иначе
            // вторая доставка «перемешает» сетку при холодном старте
            sort(out, Prefs.sortMode());
            return out;
        } catch (Exception e) {
            return null;
        }
    }

    // ---------- Штамп библиотеки: менялось ли что-то, пока нас не было ----------

    /**
     * Штамп считается ТОЛЬКО по строкам MediaStore (id != 0): файлы, найденные
     * напрямую на диске (корень, доп. источники), покрываются метками времени
     * папок — иначе сравнение «яблоки с апельсинами» всегда давало бы «изменилось».
     */
    private static void updateStamp(Context ctx, List<MediaItem> items) {
        long max = -1;
        int count = 0;
        long ids = 0;
        for (MediaItem it : items) {
            if (it.id == 0) continue;
            count++;
            if (it.dateModified > max) max = it.dateModified;
            ids += it.id;
        }
        stampMax = max;
        stampCount = count;
        stampIds = ids;
        stampRoot = rootMtime();
        stampExtras = extrasMtime();
    }

    /** Максимальная mtime прямых детей доп. папок (дешёвый сигнал их изменений). */
    private static long extrasMtime() {
        try {
            long m = -1;
            for (String dir : ExtraSources.active()) {
                long t = new File(dir).lastModified();
                if (t > m) m = t;
            }
            return m;
        } catch (Exception e) {
            return -1;
        }
    }

    private static long rootMtime() {
        try {
            String root = Prefs.rootPath();
            return root == null ? -1 : new File(root).lastModified();
        } catch (Exception e) {
            return -1;
        }
    }

    /**
     * Дешёвая проверка «библиотека изменилась?» (один проход по двум курсором
     * всего с двумя числовыми колонками — миллисекунды вместо секунд полного
     * запроса). Переименование/перемещение тоже ловится: у новой строки новый
     * _ID, сумма меняется. Вызывать в фоне через колбэк.
     */
    public static void checkChanged(Context ctx, Callback<Boolean> cb) {
        Context app = ctx.getApplicationContext();
        EXEC.execute(() -> {
            boolean changed = true;
            try {
                if (stampCount >= 0) {
                    long max = -1;
                    int count = 0;
                    long ids = 0;
                    ContentResolver cr = app.getContentResolver();
                    String[] proj = new String[]{BaseColumns._ID,
                            MediaStore.MediaColumns.DATE_MODIFIED};
                    android.net.Uri[] uris = new android.net.Uri[]{
                            MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
                            MediaStore.Video.Media.EXTERNAL_CONTENT_URI};
                    for (android.net.Uri uri : uris) {
                        Cursor c = null;
                        try {
                            c = cr.query(uri, proj, null, null, null);
                            if (c == null) continue;
                            int iId = c.getColumnIndex(BaseColumns._ID);
                            int iDate = c.getColumnIndex(MediaStore.MediaColumns.DATE_MODIFIED);
                            while (c.moveToNext()) {
                                count++;
                                if (iId >= 0) ids += c.getLong(iId);
                                if (iDate >= 0) {
                                    long ms = c.getLong(iDate) * 1000L;
                                    if (ms > max) max = ms;
                                }
                            }
                        } catch (Exception ignored) {
                        } finally {
                            if (c != null) {
                                try {
                                    c.close();
                                } catch (Exception ignored) {
                                }
                            }
                        }
                    }
                    // Новые/изменённые строки меняют max, переименования/
                    // перемещения — сумму _ID, удаления — count. Дисковые файлы
                    // (корень, доп. источники) ловятся метками времени папок.
                    changed = max != stampMax || ids != stampIds || count != stampCount
                            || rootMtime() != stampRoot || extrasMtime() != stampExtras;
                }
            } catch (Throwable t) {
                changed = true;
            }
            boolean result = changed;
            MAIN.post(() -> cb.onLoaded(result));
        });
    }

    public static List<MediaItem> queryAll(Context ctx) {
        List<MediaItem> out = queryAllBase(ctx);
        // Дополнительные источники: папки, включённые вручную в настройках
        // маскировки (скрытые папки, Android/media, Documents…). Пока
        // маскировка «закрыта», они, как и корневая папка, не подмешиваются.
        if (!ExtraSources.active().isEmpty()) {
            java.util.HashSet<String> known = new java.util.HashSet<>();
            for (MediaItem it : out) known.add(it.path);
            int before = out.size();
            ExtraSources.appendActive(known, out);
            if (out.size() != before) sort(out, Prefs.sortMode());
        }
        return out;
    }

    /** Обычная галерея без дополнительных источников (MediaStore + корневая папка). */
    static List<MediaItem> queryAllBase(Context ctx) {
        List<MediaItem> out = new ArrayList<>();
        AppDirs.cleanupEmptyDirs();
        try {
            queryTable(ctx, MediaStore.Images.Media.EXTERNAL_CONTENT_URI, false, out);
        } catch (Exception ignored) {
        }
        try {
            queryTable(ctx, MediaStore.Video.Media.EXTERNAL_CONTENT_URI, true, out);
        } catch (Exception ignored) {
        }
        // Корневая папка читается ещё и напрямую с диска: пока маскировка была
        // «закрыта», её файлы были выкинуты из MediaStore (.nomedia), а после
        // открытия индексация занимает время — в нашей галерее они нужны сразу.
        String root = Prefs.rootPath();
        if (root != null && !MaskGuard.hidden()) {
            File rootDir = new File(root);
            if (rootDir.isDirectory()) {
                java.util.HashSet<String> known = new java.util.HashSet<>();
                for (MediaItem it : out) known.add(it.path);
                addFromDisk(rootDir, known, out, 0);
            }
        }
        sort(out, Prefs.sortMode());
        return out;
    }

    private static void addFromDisk(File dir, java.util.HashSet<String> known,
                                    List<MediaItem> out, int depth) {
        if (depth > 6) return;
        File[] files = dir.listFiles();
        if (files == null) return;
        for (File f : files) {
            String name = f.getName();
            if (name.startsWith(".")) continue;
            if (f.isDirectory()) {
                addFromDisk(f, known, out, depth + 1);
            } else if (isMediaName(name) && known.add(f.getAbsolutePath())) {
                out.add(MediaItem.fromFile(f));
            }
        }
    }

    private static void queryTable(Context ctx, android.net.Uri uri, boolean video, List<MediaItem> out) {
        ContentResolver cr = ctx.getContentResolver();
        List<String> proj = new ArrayList<>();
        proj.add(BaseColumns._ID);
        proj.add(MediaStore.MediaColumns.DATA);
        proj.add(MediaStore.MediaColumns.DISPLAY_NAME);
        proj.add(MediaStore.MediaColumns.SIZE);
        proj.add(MediaStore.MediaColumns.DATE_MODIFIED);
        proj.add("datetaken"); // DATE_TAKEN: дата съёмки, а не записи файла
        proj.add(MediaStore.MediaColumns.MIME_TYPE);
        if (video && Build.VERSION.SDK_INT >= 29) {
            proj.add(MediaStore.MediaColumns.DURATION);
        }
        Cursor c = cr.query(uri, proj.toArray(new String[0]), null, null, null);
        if (c == null) return;
        try {
            int iId = c.getColumnIndex(BaseColumns._ID);
            int iPath = c.getColumnIndexOrThrow(MediaStore.MediaColumns.DATA);
            int iName = c.getColumnIndexOrThrow(MediaStore.MediaColumns.DISPLAY_NAME);
            int iSize = c.getColumnIndexOrThrow(MediaStore.MediaColumns.SIZE);
            int iDate = c.getColumnIndexOrThrow(MediaStore.MediaColumns.DATE_MODIFIED);
            int iTaken = c.getColumnIndex("datetaken");
            int iMime = c.getColumnIndexOrThrow(MediaStore.MediaColumns.MIME_TYPE);
            int iDur = video && Build.VERSION.SDK_INT >= 29
                    ? c.getColumnIndex(MediaStore.MediaColumns.DURATION) : -1;
            // Папки, которые в общую галерею НЕ должны попадать сами по себе:
            // данные приложений (Android/…) и скрытые каталоги. Они видны
            // только если пользователь сам включил их в «Дополнительных
            // источниках» (тогда файлы добавит ExtraSources.appendActive).
            // Раньше MediaStore отдавал, например, все фото Telegram из
            // Android/media, хотя источник был выключен.
            java.util.List<String> appDataRoots = new ArrayList<>();
            for (File vol : ExtraSources.volumes(ctx)) {
                appDataRoots.add(new File(vol, "Android").getAbsolutePath() + "/");
            }
            java.util.Set<String> extras = ExtraSources.active();
            while (c.moveToNext()) {
                String path = c.getString(iPath);
                if (path == null || path.isEmpty()) continue;
                File f = new File(path);
                String parent = f.getParent();
                if (parent == null) continue;
                // Исключаем служебные скрытые каталоги (корзина, сейф)
                if (AppDirs.isServicePath(parent)) continue;
                if (f.getName().startsWith(".")) continue;
                if (isAppDataOrHidden(path, parent, appDataRoots, extras)) continue;
                MediaItem item = new MediaItem();
                item.id = iId >= 0 ? c.getLong(iId) : 0;
                item.path = path;
                item.name = c.getString(iName);
                if (item.name == null || item.name.isEmpty()) item.name = f.getName();
                item.folderPath = parent;
                item.size = c.getLong(iSize);
                item.dateModified = c.getLong(iDate) * 1000L;
                item.dateTaken = iTaken >= 0 ? c.getLong(iTaken) : 0;
                item.mime = c.getString(iMime);
                item.isVideo = video;
                item.duration = iDur >= 0 ? c.getLong(iDur) : 0;
                out.add(item);
            }
        } finally {
            c.close();
        }
    }

    /**
     * true, если файл лежит в данных приложений (…/Android/…) или в скрытом
     * каталоге (сегмент пути с точкой) и при этом его папка не включена
     * вручную в дополнительных источниках.
     */
    private static boolean isAppDataOrHidden(String path, String parent,
                                             java.util.List<String> appDataRoots,
                                             java.util.Set<String> extras) {
        // Включённый источник (или любая папка внутри него) — показываем
        for (String ex : extras) {
            if (parent.equals(ex) || parent.startsWith(ex + "/")) return false;
        }
        for (String root : appDataRoots) {
            if (path.startsWith(root)) return true;
        }
        // Скрытый каталог в любом сегменте пути папки: /.hidden/…, /dir/.cache/…
        for (String seg : parent.split("/")) {
            if (seg.length() > 1 && seg.startsWith(".")) return true;
        }
        return false;
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
                // При равной дате — по пути: иначе при каждой перезагрузке
                // файлы одного дня «прыгали» друг относительно друга
                cmp = (a, b) -> {
                    int c = Long.compare(a.date(), b.date());
                    return c != 0 ? c : a.path.compareTo(b.path);
                };
                break;
            case Prefs.SORT_NAME_ASC:
                cmp = (a, b) -> {
                    int c = a.name.toLowerCase(Locale.US)
                            .compareTo(b.name.toLowerCase(Locale.US));
                    return c != 0 ? c : a.path.compareTo(b.path);
                };
                break;
            case Prefs.SORT_NAME_DESC:
                cmp = (a, b) -> {
                    int c = b.name.toLowerCase(Locale.US)
                            .compareTo(a.name.toLowerCase(Locale.US));
                    return c != 0 ? c : a.path.compareTo(b.path);
                };
                break;
            case Prefs.SORT_SIZE_DESC:
                cmp = (a, b) -> {
                    int c = Long.compare(b.size, a.size);
                    return c != 0 ? c : a.path.compareTo(b.path);
                };
                break;
            case Prefs.SORT_SIZE_ASC:
                cmp = (a, b) -> {
                    int c = Long.compare(a.size, b.size);
                    return c != 0 ? c : a.path.compareTo(b.path);
                };
                break;
            default:
                cmp = (a, b) -> {
                    int c = Long.compare(b.date(), a.date());
                    return c != 0 ? c : a.path.compareTo(b.path);
                };
        }
        Collections.sort(list, cmp);
    }

    // ---------- Альбомы ----------

    /**
     * Строит список альбомов: секция «Мои папки» (внутри корня) + «Папки устройства».
     * Возвращает список строк (заголовки секций) и объектов Album.
     */
    public static List<Object> buildAlbums(Context ctx) {
        return buildAlbumsFrom(ctx, queryAll(ctx));
    }

    /**
     * Строит строки альбомов по готовому списку (тот же queryAll, но уже
     * загруженный — из памяти или дискового кеша). Вызывать в фоне: здесь
     * обход папок через File API и прогрев индекса лиц.
     */
    public static List<Object> buildAlbumsFrom(Context ctx, List<MediaItem> all) {
        List<Object> rows = new ArrayList<>();
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
        java.util.Set<String> extras = ExtraSources.active();
        String androidPrefix = new File(Environment.getExternalStorageDirectory(), "Android")
                .getAbsolutePath();
        for (String folder : byFolder.keySet()) {
            if (rootPath != null && isUnder(folder, rootPath)) continue;
            if (AppDirs.isServicePath(folder)) continue;
            // Данные приложений (Android/media) — только если папка включена
            // как дополнительный источник
            if (folder.startsWith(androidPrefix) && !extras.contains(folder)) continue;
            List<MediaItem> l = byFolder.get(folder);
            if (l.isEmpty()) continue;
            File f = new File(folder);
            device.add(new Album(f.getName(), folder, l.get(0).path, l.size(), false));
        }
        Collections.sort(device, (a, b) -> a.name.toLowerCase(Locale.US)
                .compareTo(b.name.toLowerCase(Locale.US)));

        // Псевдо-альбом «Все» первым в «Папках устройства»: такой папки
        // на диске нет, это вид на всю галерею целиком (как вкладка
        // «Галерея», только из альбомов). Счётчик и обложка — по тем же
        // правилам видимости, что у общей ленты.
        {
            boolean anyLocked0 = LockStore.any();
            int allCount = 0;
            String allCover = null;
            for (MediaItem it : all) {
                if (rootHidden && MaskGuard.isHiddenPath(it.path)) continue;
                if (anyLocked0 && LockStore.isUnderLocked(it.path)) continue;
                if (allCover == null) allCover = it.path;
                allCount++;
            }
            if (allCount > 0) {
                device.add(0, new Album(ctx.getString(R.string.album_all),
                        Album.ALL_PATH, allCover, allCount, false));
            }
        }

        if (!user.isEmpty()) {
            rows.add(Boolean.TRUE); // маркер секции «Мои папки»
            rows.addAll(user);
        }
        if (!device.isEmpty()) {
            rows.add(Boolean.FALSE); // маркер секции «Папки устройства»
            rows.addAll(device);
        }

        // «Умные» альбомы: типы медиа и инструменты (считаем по уже загруженному списку)
        java.util.HashSet<String> favs = new java.util.HashSet<>(Prefs.favorites());
        SelfieIndex.warmUp(ctx);
        int photos = 0, videos = 0, selfies = 0, shots = 0, favCount = 0, edited = 0;
        boolean anyLocked = LockStore.any();
        for (MediaItem it : all) {
            if (rootHidden && MaskGuard.isHiddenPath(it.path)) continue;
            if (anyLocked && LockStore.isUnderLocked(it.path)) continue;
            if (it.isVideo) videos++; else photos++;
            if (SmartAlbum.isSelfie(it)) selfies++;
            if (SmartAlbum.isScreenshot(it)) shots++;
            if (favs.contains(it.path)) favCount++;
            if (SmartAlbum.isEdited(it)) edited++;
        }
        // Фоновый анализ лиц для «Селфи»; по мере готовности экран альбомов обновит счётчик
        SelfieIndex.ensureIndexed(ctx, all);
        SmartAlbum.Group types = new SmartAlbum.Group(R.string.smart_media_types);
        types.rows.add(new SmartAlbum(SmartAlbum.PHOTOS, R.drawable.ic_photo, 0xFF00C7BE,
                R.string.smart_photos, photos));
        types.rows.add(new SmartAlbum(SmartAlbum.VIDEOS, R.drawable.ic_play, 0xFF8FD14F,
                R.string.smart_videos, videos));
        types.rows.add(new SmartAlbum(SmartAlbum.SELFIES, R.drawable.ic_face, 0xFF0A84FF,
                R.string.smart_selfies, selfies));
        types.rows.add(new SmartAlbum(SmartAlbum.SCREENSHOTS, R.drawable.ic_screenshot,
                0xFF8E8E93, R.string.smart_screenshots, shots));
        types.rows.add(new SmartAlbum(SmartAlbum.FAVORITES, R.drawable.ic_heart, 0xFFFF2D55,
                R.string.smart_favorites, favCount));
        rows.add(types);

        SmartAlbum.Group utils = new SmartAlbum.Group(R.string.smart_utilities);
        utils.rows.add(new SmartAlbum(SmartAlbum.EDITED, R.drawable.ic_edit, 0xFF30D158,
                R.string.smart_edited, edited));
        if (!rootHidden) {
            utils.rows.add(new SmartAlbum(SmartAlbum.SAFE, R.drawable.ic_lock, 0xFF5E5CE6,
                    R.string.safe_title, -1));
        }
        int trash = 0;
        try {
            trash = TrashStore.get().entries().size();
        } catch (Exception ignored) {
        }
        utils.rows.add(new SmartAlbum(SmartAlbum.TRASH, R.drawable.ic_delete, 0xFFFF3B30,
                R.string.trash_title, trash));
        utils.rows.add(new SmartAlbum(SmartAlbum.RESIZER, R.drawable.ic_resize, 0xFFFF9F0A,
                R.string.resizer_title, -1));
        rows.add(utils);
        return rows;
    }

    /** Папки устройства (не из корня и не служебные) — для выбора назначения. */
    public static List<File> deviceFolders(Context ctx) {
        List<File> out = new ArrayList<>();
        java.util.HashSet<String> seen = new java.util.HashSet<>();
        String rootPath = Prefs.rootPath();
        String androidDir = new File(Environment.getExternalStorageDirectory(), "Android")
                .getAbsolutePath();
        java.util.Set<String> extras = ExtraSources.active();
        for (MediaItem it : queryAll(ctx)) {
            String folder = it.folderPath;
            if (folder == null || !seen.add(folder)) continue;
            if (rootPath != null && isUnder(folder, rootPath)) continue;
            if (AppDirs.isServicePath(folder)) continue;
            if (folder.startsWith(androidDir) && !extras.contains(folder)) continue;
            out.add(new File(folder));
        }
        Collections.sort(out, (a, b) -> a.getName().toLowerCase(Locale.US)
                .compareTo(b.getName().toLowerCase(Locale.US)));
        return out;
    }

    public static void loadAlbums(Context ctx, Callback<List<Object>> cb) {
        Context app = ctx.getApplicationContext();
        if (hasFreshCache()) {
            List<MediaItem> cached = allCache;
            EXEC.execute(() -> {
                List<Object> rows = buildAlbumsFrom(app, new ArrayList<>(cached));
                MAIN.post(() -> cb.onLoaded(rows));
            });
            return;
        }
        EXEC.execute(() -> {
            // Холодный старт: альбомы мгновенно из дискового кеша, свежее —
            // следом. Только при холодном старте (как в loadAll): пара
            // доставок на каждом возврате на экран меняла обложки и
            // счётчики прямо на глазах
            if (!diskDirty && allCache == null) {
                List<MediaItem> disk = null;
                try {
                    disk = loadDiskCache(app);
                } catch (Throwable ignored) {
                }
                if (disk != null) {
                    List<Object> rows = buildAlbumsFrom(app, disk);
                    MAIN.post(() -> cb.onLoaded(rows));
                }
            }
            boolean hiddenNow = MaskGuard.hidden();
            List<MediaItem> result = queryAll(app);
            allCache = result;
            allCacheHidden = hiddenNow;
            allCacheAt = SystemClock.elapsedRealtime();
            updateStamp(app, result);
            try {
                saveDiskCache(app, result, hiddenNow);
            } catch (Throwable ignored) {
            }
            diskDirty = false;
            List<Object> rows = buildAlbumsFrom(app, result);
            MAIN.post(() -> cb.onLoaded(rows));
        });
    }

    /**
     * Карточка папки: имя, обложка и число файлов (прямое содержимое).
     * ЛЁГКИЙ проход только по именам и датам файлов: раньше здесь для
     * каждого файла создавался MediaItem с чтением EXIF, и вход в папку
     * с подпапками заметно тормозил (экран загрузки висел на глазах).
     */
    public static Album albumFor(File dir, boolean isUser) {
        int count = 0;
        String cover = null;
        long newest = -1;
        File[] files = dir.listFiles();
        if (files != null) {
            for (File f : files) {
                if (f.isFile() && isMediaName(f.getName()) && !f.getName().startsWith(".")) {
                    count++;
                    long m = f.lastModified();
                    if (m > newest) {
                        newest = m;
                        cover = f.getAbsolutePath();
                    }
                }
            }
        }
        if (cover == null) {
            // Пустая папка с подпапками — обложка из первой непустой подпапки
            for (File sub : listSubdirs(dir)) {
                File[] inner = sub.listFiles();
                if (inner == null) continue;
                for (File f : inner) {
                    if (f.isFile() && isMediaName(f.getName())
                            && !f.getName().startsWith(".")) {
                        cover = f.getAbsolutePath();
                        break;
                    }
                }
                if (cover != null) break;
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
