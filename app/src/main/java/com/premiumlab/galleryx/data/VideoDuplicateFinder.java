package com.premiumlab.galleryx.data;

import android.content.Context;
import android.graphics.Bitmap;
import android.media.MediaMetadataRetriever;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;

import java.io.File;
import java.io.FileInputStream;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Поиск дубликатов видео — брат {@link DuplicateFinder} (фото не трогаем).
 *
 * Как и у фото, два уровня:
 * 1) Точные копии — одинаковый размер + SHA-256 всего файла.
 * 2) Похожие копии (пережатые мессенджерами, другой битрейт/разрешение) —
 *    отпечаток видео: кадры на 15%, 50% и 85% длительности, у каждого кадра
 *    тот же двойной 128-битный dHash, что у фото, плюс средняя яркость.
 *    Совпасть должны все кадры, длительность (±3%, не более ±1,5 с на
 *    коротких) и пропорции кадра.
 *
 * Отпечатки кешируются в dup_video_cache1.tsv — повторное сканирование
 * почти мгновенное. Автоматически ничего не удаляется: группы смотрит
 * и чистит сам пользователь (экран DuplicatesActivity в режиме видео).
 */
public final class VideoDuplicateFinder {

    public interface Listener {
        void onProgress(int done, int total);

        void onResult(List<DuplicateFinder.Group> groups);
    }

    /** Макс. суммарное расхождение бит кадра (два dHash по 64), чтобы кадры совпали. */
    private static final int MAX_FRAME_DISTANCE = 10;
    /** Максимальная разница средней яркости кадра (0–255). */
    private static final int MAX_AVG_DIFF = 18;
    /** Максимальная относительная разница пропорций кадра. */
    private static final double MAX_ASPECT_DIFF = 0.05;
    /** Допуск по длительности: 2% длительности, но не меньше 1 с и не больше 3 с. */
    private static final long DUR_TOL_MIN_MS = 1000;
    private static final long DUR_TOL_MAX_MS = 3000;
    /** Минимум «деталей» кадра — ниже кадр плоский, нужно полное совпадение. */
    private static final int MIN_DETAIL = 10;

    private static final String CACHE_FILE = "dup_video_cache1.tsv";

    private static final ExecutorService EXEC = Executors.newSingleThreadExecutor();
    private static final Handler MAIN = new Handler(Looper.getMainLooper());

    private volatile boolean cancelled;

    public void cancel() {
        cancelled = true;
    }

    /** Отпечаток видео: три кадра + размеры + длительность. */
    private static final class Print {
        final long[] dh = new long[3];
        final long[] dv = new long[3];
        final int[] avg = new int[3];
        int frames;          // сколько кадров удалось снять
        int w, h;
        long durMs;
        boolean valid;
    }

    public void scan(Context ctx, Listener l) {
        Context app = ctx.getApplicationContext();
        EXEC.execute(() -> {
            // ---------- Список видео ----------
            List<MediaItem> videos = new ArrayList<>();
            for (MediaItem it : MediaEngine.queryAll(app)) {
                if (!it.isVideo) continue;
                long len = new File(it.path).length();
                if (len <= 0) continue;
                it.size = len;
                videos.add(it);
            }
            if (cancelled) return;

            // ---------- Точные кандидаты по размеру ----------
            Map<Long, List<Integer>> bySize = new HashMap<>();
            for (int i = 0; i < videos.size(); i++) {
                List<Integer> g = bySize.get(videos.get(i).size);
                if (g == null) {
                    g = new ArrayList<>(2);
                    bySize.put(videos.get(i).size, g);
                }
                g.add(i);
            }
            List<Integer> shaTodo = new ArrayList<>();
            for (List<Integer> g : bySize.values()) {
                if (g.size() > 1) shaTodo.addAll(g);
            }

            // ---------- Кеш отпечатков ----------
            Map<String, String> cache = loadCache(app);
            Print[] prints = new Print[videos.size()];
            List<Integer> hashTodo = new ArrayList<>();
            for (int i = 0; i < videos.size(); i++) {
                Print p = fromCache(cache, videos.get(i));
                if (p != null) prints[i] = p;
                else hashTodo.add(i);
            }

            int total = shaTodo.size() + hashTodo.size();
            int totalF = total;
            MAIN.post(() -> l.onProgress(0, totalF));
            int done = 0;
            long lastPost = 0;

            // ---------- SHA-256 точных кандидатов ----------
            String[] sha = new String[videos.size()];
            for (int idx : shaTodo) {
                if (cancelled) return;
                sha[idx] = sha256(new File(videos.get(idx).path));
                done++;
                long now = SystemClock.elapsedRealtime();
                if (done == totalF || now - lastPost > 80) {
                    lastPost = now;
                    int d = done;
                    MAIN.post(() -> l.onProgress(d, totalF));
                }
            }

            // ---------- Отпечатки по кадрам ----------
            for (int idx : hashTodo) {
                if (cancelled) return;
                MediaItem it = videos.get(idx);
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
            if (cache.size() > 60000) {
                Map<String, String> pruned = new HashMap<>();
                for (MediaItem it : videos) {
                    String v = cache.get(cacheKey(it));
                    if (v != null) pruned.put(cacheKey(it), v);
                }
                cache = pruned;
            }
            saveCache(app, cache);
            if (cancelled) return;

            // Разрешение и длительность — в элементы (для подписи в списке)
            for (int i = 0; i < videos.size(); i++) {
                if (prints[i] != null && prints[i].valid) {
                    videos.get(i).width = prints[i].w;
                    videos.get(i).height = prints[i].h;
                    if (videos.get(i).duration <= 0) videos.get(i).duration = prints[i].durMs;
                }
            }

            // ---------- Группы (union-find) ----------
            int n = videos.size();
            int[] parent = new int[n];
            for (int i = 0; i < n; i++) parent[i] = i;

            Map<String, Integer> bySha = new HashMap<>();
            for (int i = 0; i < n; i++) {
                if (sha[i] == null) continue;
                String key = videos.get(i).size + ":" + sha[i];
                Integer first = bySha.get(key);
                if (first == null) bySha.put(key, i);
                else union(parent, first, i);
            }

            // Кеш звуковых отпечатков: считаются лениво, только для пар,
            // прошедших визуальную проверку (их мало)
            Map<String, AudioPrint> audioCache = new HashMap<>();

            // Кандидаты в похожие — по полосам первого кадра (как у фото)
            Map<Integer, List<Integer>> bands = new HashMap<>();
            for (int i = 0; i < n; i++) {
                Print p = prints[i];
                if (p == null || !p.valid) continue;
                for (int b = 0; b < 16; b++) {
                    long src = b < 8 ? p.dh[0] : p.dv[0];
                    int band = (int) ((src >>> ((b & 7) * 8)) & 0xFFL);
                    int key = (b << 8) | band;
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
                if (m < 2 || m > 1000) continue;
                for (int a = 0; a < m; a++) {
                    for (int b = a + 1; b < m; b++) {
                        int i = bucket.get(a), j = bucket.get(b);
                        if (find(parent, i) == find(parent, j)) continue;
                        if (!similar(prints[i], prints[j])) continue;
                        // Визуально похоже — но одинаковая сцена с разной
                        // речью (человек говорит разное на том же фоне)
                        // дубликатом НЕ является: решающее слово за звуком
                        AudioPrint aa = audioFor(audioCache, videos.get(i).path);
                        AudioPrint bb = audioFor(audioCache, videos.get(j).path);
                        if (aa.hasAudio && bb.hasAudio) {
                            if (audioSimilar(aa, bb)) union(parent, i, j);
                        } else if (similar(prints[i], prints[j], 5)) {
                            // Звука нет хотя бы у одного — верим только
                            // почти полному визуальному совпадению
                            union(parent, i, j);
                        }
                    }
                }
                if (cancelled) return;
            }

            // ---------- Сборка групп ----------
            Map<String, Integer> idxByPath = new HashMap<>();
            for (int i = 0; i < n; i++) idxByPath.put(videos.get(i).path, i);
            Map<Integer, DuplicateFinder.Group> byRoot = new HashMap<>();
            for (int i = 0; i < n; i++) {
                int root = find(parent, i);
                DuplicateFinder.Group g = byRoot.get(root);
                if (g == null) {
                    g = new DuplicateFinder.Group();
                    byRoot.put(root, g);
                }
                g.items.add(videos.get(i));
            }
            List<DuplicateFinder.Group> out = new ArrayList<>();
            for (DuplicateFinder.Group g : byRoot.values()) {
                if (g.items.size() < 2) continue;
                boolean exact = true;
                String firstSha = null;
                for (MediaItem it : g.items) {
                    Integer idx = idxByPath.get(it.path);
                    if (idx == null || sha[idx] == null) {
                        exact = false;
                        break;
                    }
                    if (firstSha == null) firstSha = sha[idx];
                    else if (!firstSha.equals(sha[idx]) || it.size != g.items.get(0).size) {
                        exact = false;
                        break;
                    }
                }
                g.exact = exact;
                // Лучший — первым: наибольший размер (битрейт), при равенстве — самый старый
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

    // ---------- Сравнение ----------

    private static boolean similar(Print a, Print b) {
        return similar(a, b, MAX_FRAME_DISTANCE);
    }

    private static boolean similar(Print a, Print b, int maxFrameDist) {
        if (a == null || b == null || !a.valid || !b.valid) return false;
        if (a.frames < 2 || b.frames < 2) return false;
        // Длительность: та же сцена не может стать заметно длиннее/короче
        if (a.durMs > 0 && b.durMs > 0) {
            long max = Math.max(a.durMs, b.durMs);
            long tol = Math.min(DUR_TOL_MAX_MS, Math.max(DUR_TOL_MIN_MS, max * 2 / 100));
            if (Math.abs(a.durMs - b.durMs) > tol) return false;
        }
        if (a.w > 0 && a.h > 0 && b.w > 0 && b.h > 0) {
            double ra = (double) a.w / a.h;
            double rb = (double) b.w / b.h;
            if (Math.abs(ra - rb) / Math.max(ra, rb) > MAX_ASPECT_DIFF) return false;
        }
        int frames = Math.min(a.frames, b.frames);
        for (int f = 0; f < frames; f++) {
            int dist = Long.bitCount(a.dh[f] ^ b.dh[f]) + Long.bitCount(a.dv[f] ^ b.dv[f]);
            int detailA = Long.bitCount(a.dh[f]) + Long.bitCount(a.dv[f]);
            int detailB = Long.bitCount(b.dh[f]) + Long.bitCount(b.dv[f]);
            if (detailA < MIN_DETAIL || detailB < MIN_DETAIL) {
                // Плоский кадр (чёрный экран, заставка) — только полное совпадение
                if (dist != 0 || Math.abs(a.avg[f] - b.avg[f]) > 8) return false;
            } else {
                if (dist > maxFrameDist) return false;
                if (Math.abs(a.avg[f] - b.avg[f]) > MAX_AVG_DIFF) return false;
            }
        }
        return true;
    }

    // ---------- Отпечаток ----------

    /** Снимает три кадра и считает по каждому двойной dHash. */
    private Print computePrint(String path) {
        Print p = new Print();
        MediaMetadataRetriever mmr = new MediaMetadataRetriever();
        try {
            mmr.setDataSource(path);
            String dur = mmr.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION);
            String w = mmr.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH);
            String h = mmr.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT);
            String rot = mmr.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_ROTATION);
            try {
                p.durMs = dur == null ? 0 : Long.parseLong(dur);
                p.w = w == null ? 0 : Integer.parseInt(w);
                p.h = h == null ? 0 : Integer.parseInt(h);
                if ("90".equals(rot) || "270".equals(rot)) {
                    int t = p.w;
                    p.w = p.h;
                    p.h = t;
                }
            } catch (NumberFormatException ignored) {
            }
            double[] fracs = {0.15, 0.5, 0.85};
            for (int f = 0; f < fracs.length; f++) {
                long us = p.durMs > 0
                        ? (long) (p.durMs * 1000L * fracs[f])
                        : (f + 1) * 1_000_000L;
                Bitmap frame = null;
                try {
                    frame = mmr.getFrameAtTime(us,
                            MediaMetadataRetriever.OPTION_CLOSEST_SYNC);
                } catch (Exception ignored) {
                }
                if (frame == null) continue;
                long[] fp = printFromBitmap(frame);
                frame.recycle();
                if (fp == null) continue;
                p.dh[p.frames] = fp[0];
                p.dv[p.frames] = fp[1];
                p.avg[p.frames] = (int) fp[2];
                p.frames++;
            }
            p.valid = p.frames >= 2;
        } catch (Throwable ignored) {
        } finally {
            try {
                mmr.release();
            } catch (Exception ignored) {
            }
        }
        return p;
    }

    /** Двойной dHash 9×9 — тот же алгоритм, что у фото. @return [dh, dv, avg] */
    private static long[] printFromBitmap(Bitmap bmp) {
        try {
            Bitmap small = Bitmap.createScaledBitmap(bmp, 9, 9, true);
            int[] px = new int[9 * 9];
            small.getPixels(px, 0, 9, 0, 0, 9, 9);
            if (small != bmp) small.recycle();
            int[] gray = new int[9 * 9];
            long sum = 0;
            for (int i = 0; i < px.length; i++) {
                int c = px[i];
                gray[i] = (((c >> 16) & 0xFF) * 299 + ((c >> 8) & 0xFF) * 587
                        + (c & 0xFF) * 114) / 1000;
                sum += gray[i];
            }
            long dh = 0, dv = 0;
            int bit = 0;
            for (int y = 0; y < 8; y++) {
                for (int x = 0; x < 8; x++) {
                    if (gray[y * 9 + x] > gray[y * 9 + x + 1]) dh |= 1L << bit;
                    bit++;
                }
            }
            bit = 0;
            for (int x = 0; x < 8; x++) {
                for (int y = 0; y < 8; y++) {
                    if (gray[y * 9 + x] > gray[(y + 1) * 9 + x]) dv |= 1L << bit;
                    bit++;
                }
            }
            return new long[]{dh, dv, sum / px.length};
        } catch (Throwable t) {
            return null;
        }
    }

    // ---------- Звуковой отпечаток ----------

    /**
     * Огибающая громкости звука: 64 отрезка по длительности, RMS в каждом,
     * нормировка к максимуму (0..255). Разная речь на одинаковом фоне даёт
     * заметно разную огибающую, пережатая копия того же ролика — почти ту же.
     */
    private static final class AudioPrint {
        final int[] env = new int[64];
        boolean hasAudio;
    }

    private static AudioPrint audioFor(Map<String, AudioPrint> cache, String path) {
        AudioPrint p = cache.get(path);
        if (p == null) {
            p = computeAudio(path);
            cache.put(path, p);
        }
        return p;
    }

    /** Средняя абсолютная разница огибающих и «похожесть» не тихих мест. */
    private static boolean audioSimilar(AudioPrint a, AudioPrint b) {
        long diff = 0;
        int loud = 0;
        long loudDiff = 0;
        for (int i = 0; i < 64; i++) {
            int d = Math.abs(a.env[i] - b.env[i]);
            diff += d;
            if (a.env[i] > 24 || b.env[i] > 24) {
                loud++;
                loudDiff += d;
            }
        }
        // Оба ролика почти беззвучны — звук ничего не решает, не блокируем
        if (loud < 6) return true;
        double mean = diff / 64.0;
        double loudMean = loudDiff / (double) loud;
        return mean <= 26 && loudMean <= 34;
    }

    /** Декодирует аудиодорожку в PCM и строит огибающую. Долго, но кандидатов мало. */
    private static AudioPrint computeAudio(String path) {
        AudioPrint out = new AudioPrint();
        android.media.MediaExtractor ex = new android.media.MediaExtractor();
        android.media.MediaCodec codec = null;
        try {
            ex.setDataSource(path);
            int track = -1;
            android.media.MediaFormat fmt = null;
            for (int i = 0; i < ex.getTrackCount(); i++) {
                android.media.MediaFormat f = ex.getTrackFormat(i);
                String mime = f.getString(android.media.MediaFormat.KEY_MIME);
                if (mime != null && mime.startsWith("audio/")) {
                    track = i;
                    fmt = f;
                    break;
                }
            }
            if (track < 0 || fmt == null) return out;
            ex.selectTrack(track);
            String mime = fmt.getString(android.media.MediaFormat.KEY_MIME);
            long durUs = fmt.containsKey(android.media.MediaFormat.KEY_DURATION)
                    ? fmt.getLong(android.media.MediaFormat.KEY_DURATION) : 0;
            codec = android.media.MediaCodec.createDecoderByType(mime);
            codec.configure(fmt, null, null, 0);
            codec.start();

            int rate = fmt.containsKey(android.media.MediaFormat.KEY_SAMPLE_RATE)
                    ? fmt.getInteger(android.media.MediaFormat.KEY_SAMPLE_RATE) : 44100;
            long totalSamples = durUs > 0 ? durUs * rate / 1_000_000L : 0;
            double[] sumSq = new double[64];
            long[] count = new long[64];
            long sampleIdx = 0;
            boolean inputEos = false;
            android.media.MediaCodec.BufferInfo info = new android.media.MediaCodec.BufferInfo();
            long deadline = SystemClock.elapsedRealtime() + 60_000; // страж на экзотике
            while (!cancelled && SystemClock.elapsedRealtime() < deadline) {
                if (!inputEos) {
                    int inIdx = codec.dequeueInputBuffer(10_000);
                    if (inIdx >= 0) {
                        java.nio.ByteBuffer inBuf = codec.getInputBuffer(inIdx);
                        int size = inBuf == null ? -1 : ex.readSampleData(inBuf, 0);
                        if (size < 0) {
                            codec.queueInputBuffer(inIdx, 0, 0, 0,
                                    android.media.MediaCodec.BUFFER_FLAG_END_OF_STREAM);
                            inputEos = true;
                        } else {
                            codec.queueInputBuffer(inIdx, 0, size, ex.getSampleTime(), 0);
                            ex.advance();
                        }
                    }
                }
                int outIdx = codec.dequeueOutputBuffer(info, 10_000);
                if (outIdx >= 0) {
                    java.nio.ByteBuffer buf = codec.getOutputBuffer(outIdx);
                    android.media.MediaFormat of = codec.getOutputFormat();
                    if (of.containsKey(android.media.MediaFormat.KEY_SAMPLE_RATE)) {
                        rate = of.getInteger(android.media.MediaFormat.KEY_SAMPLE_RATE);
                        if (totalSamples <= 0 && durUs > 0) {
                            totalSamples = durUs * rate / 1_000_000L;
                        }
                    }
                    int channels = of.containsKey(android.media.MediaFormat.KEY_CHANNEL_COUNT)
                            ? Math.max(1, of.getInteger(android.media.MediaFormat.KEY_CHANNEL_COUNT)) : 1;
                    if (buf != null && info.size > 0 && totalSamples > 0) {
                        buf.position(info.offset);
                        buf.limit(info.offset + info.size);
                        java.nio.ShortBuffer sb = buf.order(
                                java.nio.ByteOrder.LITTLE_ENDIAN).asShortBuffer();
                        int frames = sb.remaining() / channels;
                        for (int fr = 0; fr < frames; fr++) {
                            long acc = 0;
                            for (int ch = 0; ch < channels; ch++) acc += sb.get();
                            double v = acc / (double) channels / 32768.0;
                            int bucket = (int) Math.min(63, sampleIdx * 64 / totalSamples);
                            sumSq[bucket] += v * v;
                            count[bucket]++;
                            sampleIdx++;
                        }
                    }
                    codec.releaseOutputBuffer(outIdx, false);
                    if ((info.flags & android.media.MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) break;
                } else if (outIdx == android.media.MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                    android.media.MediaFormat of = codec.getOutputFormat();
                    if (of.containsKey(android.media.MediaFormat.KEY_SAMPLE_RATE)) {
                        rate = of.getInteger(android.media.MediaFormat.KEY_SAMPLE_RATE);
                        if (durUs > 0) totalSamples = durUs * rate / 1_000_000L;
                    }
                }
            }
            double max = 0;
            double[] rms = new double[64];
            for (int i = 0; i < 64; i++) {
                rms[i] = count[i] > 0 ? Math.sqrt(sumSq[i] / count[i]) : 0;
                if (rms[i] > max) max = rms[i];
            }
            if (max > 0.005) { // не полная тишина
                out.hasAudio = true;
                for (int i = 0; i < 64; i++) {
                    out.env[i] = (int) Math.min(255, rms[i] / max * 255);
                }
            }
        } catch (Throwable ignored) {
            out.hasAudio = false;
        } finally {
            try {
                if (codec != null) {
                    codec.stop();
                    codec.release();
                }
            } catch (Exception ignored) {
            }
            ex.release();
        }
        return out;
    }

    // ---------- SHA-256 / union-find ----------

    private static String sha256(File f) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            try (FileInputStream in = new FileInputStream(f)) {
                byte[] buf = new byte[128 * 1024];
                int n;
                while ((n = in.read(buf)) > 0) md.update(buf, 0, n);
            }
            StringBuilder sb = new StringBuilder(64);
            for (byte b : md.digest()) sb.append(String.format("%02x", b & 0xFF));
            return sb.toString();
        } catch (Exception e) {
            return null;
        }
    }

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

    // ---------- Кеш отпечатков (TSV) ----------

    private static String cacheKey(MediaItem it) {
        return it.path + "|" + it.size + "|" + it.dateModified;
    }

    private static Print fromCache(Map<String, String> cache, MediaItem it) {
        String v = cache.get(cacheKey(it));
        if (v == null) return null;
        try {
            String[] f = v.split(";");
            if (f.length < 6) return null;
            Print p = new Print();
            p.w = Integer.parseInt(f[0]);
            p.h = Integer.parseInt(f[1]);
            p.durMs = Long.parseLong(f[2]);
            p.frames = Integer.parseInt(f[3]);
            if (p.frames < 2 || p.frames > 3) return null;
            for (int i = 0; i < p.frames; i++) {
                String[] fp = f[4 + i].split(",");
                p.dh[i] = Long.parseUnsignedLong(fp[0], 16);
                p.dv[i] = Long.parseUnsignedLong(fp[1], 16);
                p.avg[i] = Integer.parseInt(fp[2]);
            }
            p.valid = true;
            return p;
        } catch (Exception e) {
            return null;
        }
    }

    private static void putCache(Map<String, String> cache, MediaItem it, Print p) {
        if (p == null || !p.valid) return;
        StringBuilder sb = new StringBuilder();
        sb.append(p.w).append(';').append(p.h).append(';').append(p.durMs)
                .append(';').append(p.frames);
        for (int i = 0; i < p.frames; i++) {
            sb.append(';')
                    .append(Long.toHexString(p.dh[i])).append(',')
                    .append(Long.toHexString(p.dv[i])).append(',')
                    .append(p.avg[i]);
        }
        cache.put(cacheKey(it), sb.toString());
    }

    private static Map<String, String> loadCache(Context ctx) {
        Map<String, String> out = new HashMap<>();
        try {
            File f = new File(ctx.getFilesDir(), CACHE_FILE);
            if (!f.isFile()) return out;
            try (java.io.BufferedReader r = new java.io.BufferedReader(
                    new java.io.FileReader(f))) {
                String line;
                while ((line = r.readLine()) != null) {
                    int t = line.lastIndexOf('\t');
                    if (t > 0) out.put(line.substring(0, t), line.substring(t + 1));
                }
            }
        } catch (Exception ignored) {
        }
        return out;
    }

    private static void saveCache(Context ctx, Map<String, String> cache) {
        try {
            File f = new File(ctx.getFilesDir(), CACHE_FILE);
            try (java.io.BufferedWriter w = new java.io.BufferedWriter(
                    new java.io.FileWriter(f))) {
                for (Map.Entry<String, String> e : cache.entrySet()) {
                    w.write(e.getKey());
                    w.write('\t');
                    w.write(e.getValue());
                    w.newLine();
                }
            }
        } catch (Exception ignored) {
        }
    }
}
