package com.premiumlab.galleryx.data;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Matrix;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;

import androidx.exifinterface.media.ExifInterface;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileReader;
import java.io.FileWriter;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Поиск дубликатов фото: точные копии и похожие изображения.
 *
 * ТОЧНЫЕ КОПИИ — одинаковые байт-в-байт. Ищутся в два шага: группировка
 * по размеру файла, затем SHA-256 только для кандидатов. Ложных
 * срабатываний нет.
 *
 * ПОХОЖИЕ — то же изображение, но пересохранённое в другом разрешении или
 * качестве (сжатое мессенджером, уменьшенная копия и т. п.). Ищутся
 * перцептивным хешем (dHash): картинка уменьшается до 9×8 пикселей
 * в оттенках серого, из перепадов яркости строится 64-битный отпечаток
 * «как картинка выглядит». Отпечатки, отличающиеся не больше чем на
 * {@link #MAX_DISTANCE} бит, считаются одним изображением. Дополнительно
 * сверяются средняя яркость и пропорции кадра — это отсекает случайные
 * совпадения (например, однотонные картинки разного цвета).
 *
 * Отпечатки кешируются на диске (путь + размер + дата изменения),
 * поэтому повторное сканирование почти мгновенное. Вся работа в фоне,
 * прогресс и результат приходят на главный поток.
 */
public final class DuplicateFinder {

    public interface Listener {
        /** Прогресс: проверено done из total файлов (главный поток). */
        void onProgress(int done, int total);

        /** Итог: группы дубликатов, отсортированы по «зря занятому» месту. */
        void onResult(List<Group> groups);
    }

    /** Группа дубликатов (2+ файла). Первый в списке — лучший (его оставляют). */
    public static final class Group {
        /** true — все файлы группы одинаковы байт-в-байт. */
        public boolean exact;
        /** Размер лучшего (первого) файла. */
        public long size;
        public final List<MediaItem> items = new ArrayList<>();

        /** Сколько байт занято лишними копиями (всё, кроме лучшего файла). */
        public long wasted() {
            long sum = 0, max = 0;
            for (MediaItem it : items) {
                sum += it.size;
                if (it.size > max) max = it.size;
            }
            return Math.max(0, sum - max);
        }
    }

    /** Максимум отличающихся бит dHash, чтобы считать изображения одинаковыми. */
    private static final int MAX_DISTANCE = 3;
    /** Максимальная разница средней яркости (0–255). */
    private static final int MAX_AVG_DIFF = 16;
    /** Максимальная относительная разница пропорций кадра. */
    private static final double MAX_ASPECT_DIFF = 0.05;
    /** Файл кеша отпечатков в filesDir. */
    private static final String CACHE_FILE = "dup_hash_cache.tsv";

    private static final ExecutorService EXEC = Executors.newSingleThreadExecutor();
    private static final Handler MAIN = new Handler(Looper.getMainLooper());

    private volatile boolean cancelled;

    /** Останавливает сканирование; результат уже не придёт. */
    public void cancel() {
        cancelled = true;
    }

    /** Отпечаток одного фото. */
    private static final class Print {
        long dhash;
        int avg;        // средняя яркость 0..255
        int w, h;       // размеры оригинала
        boolean valid;
    }

    /**
     * Запускает сканирование в фоне. Список фото берётся как в галерее;
     * при «закрытой» маскировке скрытое, как и везде, не участвует.
     */
    public void scan(Context ctx, Listener l) {
        Context app = ctx.getApplicationContext();
        EXEC.execute(() -> {
            // ---------- Список фото ----------
            List<MediaItem> photos = new ArrayList<>();
            for (MediaItem it : MediaEngine.queryAll(app)) {
                if (it.isVideo) continue;
                long len = new File(it.path).length();
                if (len <= 0) continue;
                it.size = len;
                photos.add(it);
            }
            if (cancelled) return;

            // ---------- Шаг 1: кандидаты в точные копии (по размеру) ----------
            Map<Long, List<Integer>> bySize = new HashMap<>();
            for (int i = 0; i < photos.size(); i++) {
                MediaItem it = photos.get(i);
                List<Integer> g = bySize.get(it.size);
                if (g == null) {
                    g = new ArrayList<>(2);
                    bySize.put(it.size, g);
                }
                g.add(i);
            }
            List<Integer> shaTodo = new ArrayList<>();
            for (List<Integer> g : bySize.values()) {
                if (g.size() > 1) shaTodo.addAll(g);
            }

            // ---------- Кеш отпечатков: что уже посчитано раньше ----------
            Map<String, String> cache = loadCache(app);
            Print[] prints = new Print[photos.size()];
            List<Integer> dhashTodo = new ArrayList<>();
            for (int i = 0; i < photos.size(); i++) {
                MediaItem it = photos.get(i);
                Print p = fromCache(cache, it);
                if (p != null) prints[i] = p;
                else dhashTodo.add(i);
            }

            int total = shaTodo.size() + dhashTodo.size();
            int totalF = total;
            MAIN.post(() -> l.onProgress(0, totalF));
            int done = 0;
            long lastPost = 0;

            // ---------- Шаг 2: SHA-256 для кандидатов в точные копии ----------
            String[] sha = new String[photos.size()];
            for (int idx : shaTodo) {
                if (cancelled) return;
                sha[idx] = sha256(new File(photos.get(idx).path));
                done++;
                long now = SystemClock.elapsedRealtime();
                if (done == totalF || now - lastPost > 80) {
                    lastPost = now;
                    int d = done;
                    MAIN.post(() -> l.onProgress(d, totalF));
                }
            }

            // ---------- Шаг 3: dHash для фото без кеша ----------
            for (int idx : dhashTodo) {
                if (cancelled) return;
                MediaItem it = photos.get(idx);
                Print p = computePrint(it.path);
                prints[idx] = p;
                putCache(cache, it, p);
                done++;
                long now = SystemClock.elapsedRealtime();
                if (done == totalF || now - lastPost > 80) {
                    lastPost = now;
                    int d = done;
                    MAIN.post(() -> l.onProgress(d, totalF));
                }
            }
            // Кеш не растёт бесконечно: при переполнении оставляем только
            // отпечатки файлов, которые существуют сейчас
            if (cache.size() > 60000) {
                Map<String, String> pruned = new HashMap<>();
                for (MediaItem it : photos) {
                    String k = cacheKey(it);
                    String v = cache.get(k);
                    if (v != null) pruned.put(k, v);
                }
                cache = pruned;
            }
            saveCache(app, cache);
            if (cancelled) return;

            // Размеры кадра — в элементы (для строки «3000×4000 · 1,1 МБ»)
            for (int i = 0; i < photos.size(); i++) {
                if (prints[i] != null && prints[i].valid) {
                    photos.get(i).width = prints[i].w;
                    photos.get(i).height = prints[i].h;
                }
            }

            // ---------- Шаг 4: объединение в группы (union-find) ----------
            int n = photos.size();
            int[] parent = new int[n];
            for (int i = 0; i < n; i++) parent[i] = i;

            // Точные копии: одинаковый размер + одинаковый SHA-256
            Map<String, Integer> bySha = new HashMap<>();
            for (int i = 0; i < n; i++) {
                if (sha[i] == null) continue;
                String key = photos.get(i).size + ":" + sha[i];
                Integer first = bySha.get(key);
                if (first == null) bySha.put(key, i);
                else union(parent, first, i);
            }

            // Похожие: близкий dHash. Чтобы не сравнивать всех со всеми,
            // 64-битный отпечаток режется на 4 полосы по 16 бит: при
            // расхождении ≤3 бит хотя бы одна полоса совпадает целиком —
            // сравниваются только файлы с общей полосой.
            Map<Long, List<Integer>> bands = new HashMap<>();
            for (int i = 0; i < n; i++) {
                Print p = prints[i];
                if (p == null || !p.valid) continue;
                for (int b = 0; b < 4; b++) {
                    long band = (p.dhash >>> (b * 16)) & 0xFFFFL;
                    long key = ((long) b << 16) | band;
                    List<Integer> bucket = bands.get(key);
                    if (bucket == null) {
                        bucket = new ArrayList<>(2);
                        bands.put(key, bucket);
                    }
                    bucket.add(i);
                }
            }
            for (List<Integer> bucket : bands.values()) {
                int m = bucket.size();
                if (m < 2 || m > 1000) continue;   // гигантские вёдра — вырожденный случай
                for (int a = 0; a < m; a++) {
                    for (int b = a + 1; b < m; b++) {
                        int i = bucket.get(a), j = bucket.get(b);
                        if (find(parent, i) == find(parent, j)) continue;
                        if (similar(prints[i], prints[j])) union(parent, i, j);
                    }
                }
                if (cancelled) return;
            }

            // ---------- Шаг 5: сборка групп ----------
            Map<String, Integer> idxByPath = new HashMap<>();
            for (int i = 0; i < n; i++) idxByPath.put(photos.get(i).path, i);
            Map<Integer, Group> byRoot = new HashMap<>();
            for (int i = 0; i < n; i++) {
                int root = find(parent, i);
                Group g = byRoot.get(root);
                if (g == null) {
                    g = new Group();
                    byRoot.put(root, g);
                }
                g.items.add(photos.get(i));
            }
            List<Group> out = new ArrayList<>();
            for (Group g : byRoot.values()) {
                if (g.items.size() < 2) continue;
                // Точная ли группа: у всех одинаковый размер и SHA совпал
                boolean exact = true;
                String firstSha = null;
                for (MediaItem it : g.items) {
                    Integer idx = idxByPath.get(it.path);
                    if (idx == null || sha[idx] == null) {
                        exact = false;
                        break;
                    }
                    if (firstSha == null) firstSha = sha[idx];
                    else if (!firstSha.equals(sha[idx])
                            || it.size != g.items.get(0).size) {
                        exact = false;
                        break;
                    }
                }
                g.exact = exact;
                // Лучший файл — первым: наибольший размер (качество),
                // при равенстве — самый старый
                Collections.sort(g.items, (a, b) -> {
                    if (a.size != b.size) return Long.compare(b.size, a.size);
                    return Long.compare(a.dateModified, b.dateModified);
                });
                g.size = g.items.get(0).size;
                out.add(g);
            }
            Collections.sort(out, (a, b) -> Long.compare(b.wasted(), a.wasted()));
            if (cancelled) return;
            MAIN.post(() -> l.onResult(out));
        });
    }

    // ---------- Сравнение отпечатков ----------

    /** true — изображения выглядят одинаково (dHash + яркость + пропорции). */
    private static boolean similar(Print a, Print b) {
        if (a == null || b == null || !a.valid || !b.valid) return false;
        if (Long.bitCount(a.dhash ^ b.dhash) > MAX_DISTANCE) return false;
        if (Math.abs(a.avg - b.avg) > MAX_AVG_DIFF) return false;
        if (a.w > 0 && a.h > 0 && b.w > 0 && b.h > 0) {
            double ra = (double) a.w / a.h;
            double rb = (double) b.w / b.h;
            if (Math.abs(ra - rb) / Math.max(ra, rb) > MAX_ASPECT_DIFF) return false;
        }
        return true;
    }

    // ---------- Union-Find ----------

    private static int find(int[] parent, int i) {
        while (parent[i] != i) {
            parent[i] = parent[parent[i]];
            i = parent[i];
        }
        return i;
    }

    private static void union(int[] parent, int a, int b) {
        int ra = find(parent, a), rb = find(parent, b);
        if (ra != rb) parent[rb] = ra;
    }

    // ---------- dHash ----------

    /** Считает отпечаток изображения: dHash 64 бита + средняя яркость + размеры. */
    private Print computePrint(String path) {
        Print p = new Print();
        try {
            BitmapFactory.Options opts = new BitmapFactory.Options();
            opts.inJustDecodeBounds = true;
            BitmapFactory.decodeFile(path, opts);
            if (opts.outWidth <= 0 || opts.outHeight <= 0) return p;
            p.w = opts.outWidth;
            p.h = opts.outHeight;

            // Декод с уменьшением до ~64 px по меньшей стороне — быстро и достаточно
            int sample = 1;
            while (Math.min(opts.outWidth, opts.outHeight) / (sample * 2) >= 64) sample *= 2;
            BitmapFactory.Options dec = new BitmapFactory.Options();
            dec.inSampleSize = sample;
            dec.inPreferredConfig = Bitmap.Config.RGB_565;
            Bitmap bmp = BitmapFactory.decodeFile(path, dec);
            if (bmp == null) return p;

            // Поворот по EXIF: иначе копия с «запечённым» поворотом не совпадёт
            int rotation = exifRotation(path);
            if (rotation != 0) {
                Matrix m = new Matrix();
                m.postRotate(rotation);
                Bitmap rotated = Bitmap.createBitmap(bmp, 0, 0,
                        bmp.getWidth(), bmp.getHeight(), m, false);
                if (rotated != bmp) bmp.recycle();
                bmp = rotated;
                if (rotation == 90 || rotation == 270) {
                    int t = p.w;
                    p.w = p.h;
                    p.h = t;
                }
            }

            // 9×8 в оттенках серого
            Bitmap small = Bitmap.createScaledBitmap(bmp, 9, 8, true);
            if (small != bmp) bmp.recycle();
            int[] px = new int[9 * 8];
            small.getPixels(px, 0, 9, 0, 0, 9, 8);
            small.recycle();
            int[] gray = new int[9 * 8];
            long sum = 0;
            for (int i = 0; i < px.length; i++) {
                int c = px[i];
                gray[i] = (((c >> 16) & 0xFF) * 299 + ((c >> 8) & 0xFF) * 587
                        + (c & 0xFF) * 114) / 1000;
                sum += gray[i];
            }
            p.avg = (int) (sum / px.length);

            // Бит = ярче ли пиксель соседа справа
            long hash = 0;
            int bit = 0;
            for (int y = 0; y < 8; y++) {
                for (int x = 0; x < 8; x++) {
                    if (gray[y * 9 + x] > gray[y * 9 + x + 1]) hash |= 1L << bit;
                    bit++;
                }
            }
            p.dhash = hash;
            p.valid = true;
        } catch (Throwable ignored) {
        }
        return p;
    }

    /** Угол поворота из EXIF (0/90/180/270). */
    private static int exifRotation(String path) {
        try {
            ExifInterface exif = new ExifInterface(path);
            switch (exif.getAttributeInt(ExifInterface.TAG_ORIENTATION,
                    ExifInterface.ORIENTATION_NORMAL)) {
                case ExifInterface.ORIENTATION_ROTATE_90:
                    return 90;
                case ExifInterface.ORIENTATION_ROTATE_180:
                    return 180;
                case ExifInterface.ORIENTATION_ROTATE_270:
                    return 270;
                default:
                    return 0;
            }
        } catch (Exception e) {
            return 0;
        }
    }

    // ---------- Кеш отпечатков ----------

    private static String cacheKey(MediaItem it) {
        return it.path + "\u0001" + it.size + "\u0001" + it.dateModified;
    }

    private static Print fromCache(Map<String, String> cache, MediaItem it) {
        String v = cache.get(cacheKey(it));
        if (v == null) return null;
        try {
            String[] parts = v.split(",");
            Print p = new Print();
            p.valid = "1".equals(parts[0]);
            p.dhash = Long.parseLong(parts[1], 16);
            p.avg = Integer.parseInt(parts[2]);
            p.w = Integer.parseInt(parts[3]);
            p.h = Integer.parseInt(parts[4]);
            return p;
        } catch (Exception e) {
            return null;
        }
    }

    private static void putCache(Map<String, String> cache, MediaItem it, Print p) {
        cache.put(cacheKey(it), (p.valid ? "1" : "0") + ","
                + Long.toHexString(p.dhash) + "," + p.avg + "," + p.w + "," + p.h);
    }

    private static Map<String, String> loadCache(Context ctx) {
        Map<String, String> out = new HashMap<>();
        File f = new File(ctx.getFilesDir(), CACHE_FILE);
        if (!f.isFile()) return out;
        try (BufferedReader r = new BufferedReader(new FileReader(f))) {
            String line;
            while ((line = r.readLine()) != null) {
                int tab = line.lastIndexOf('\t');
                if (tab > 0) out.put(line.substring(0, tab), line.substring(tab + 1));
            }
        } catch (Exception ignored) {
        }
        return out;
    }

    private static void saveCache(Context ctx, Map<String, String> cache) {
        try (FileWriter w = new FileWriter(new File(ctx.getFilesDir(), CACHE_FILE))) {
            for (Map.Entry<String, String> e : cache.entrySet()) {
                w.write(e.getKey());
                w.write('\t');
                w.write(e.getValue());
                w.write('\n');
            }
        } catch (Exception ignored) {
        }
    }

    // ---------- SHA-256 ----------

    /** SHA-256 содержимого файла (hex) или null при ошибке чтения/отмене. */
    private String sha256(File f) {
        try (FileInputStream in = new FileInputStream(f)) {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] buf = new byte[256 * 1024];
            int n;
            while ((n = in.read(buf)) > 0) {
                if (cancelled) return null;
                md.update(buf, 0, n);
            }
            byte[] d = md.digest();
            StringBuilder sb = new StringBuilder(d.length * 2);
            for (byte b : d) {
                sb.append(Character.forDigit((b >> 4) & 0xF, 16));
                sb.append(Character.forDigit(b & 0xF, 16));
            }
            return sb.toString();
        } catch (Exception e) {
            return null;
        }
    }
}
