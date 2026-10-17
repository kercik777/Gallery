package com.premiumlab.galleryx.data;

import android.content.Context;
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
 * Поиск ТОЧНЫХ дубликатов фото.
 *
 * Имя файла не имеет значения: сравнивается содержимое. Алгоритм в два шага,
 * чтобы не читать всю память телефона:
 *   1) все фото группируются по размеру файла — у настоящих копий размер
 *      всегда совпадает, поэтому одиночные размеры отсеиваются мгновенно;
 *   2) только для кандидатов (2+ файла одинакового размера) считается
 *      контрольная сумма SHA-256 всего содержимого. Совпала сумма —
 *      файлы байт-в-байт одинаковы.
 *
 * Ложных срабатываний нет: разные картинки в одну группу не попадают.
 * Сканирование идёт в фоне, прогресс и результат приходят на главный поток.
 */
public final class DuplicateFinder {

    public interface Listener {
        /** Прогресс хеширования: проверено done из total кандидатов (главный поток). */
        void onProgress(int done, int total);

        /** Итог: группы одинаковых файлов, отсортированы по «зря занятому» месту. */
        void onResult(List<Group> groups);
    }

    /** Группа одинаковых файлов (2 и больше). Первый в списке — самый старый. */
    public static final class Group {
        /** Размер одного файла (у всех в группе он совпадает). */
        public long size;
        public final List<MediaItem> items = new ArrayList<>();

        /** Сколько байт занято лишними копиями (всё, кроме одного файла). */
        public long wasted() {
            return size * Math.max(0, items.size() - 1);
        }
    }

    private static final ExecutorService EXEC = Executors.newSingleThreadExecutor();
    private static final Handler MAIN = new Handler(Looper.getMainLooper());

    private volatile boolean cancelled;

    /** Останавливает сканирование; результат уже не придёт. */
    public void cancel() {
        cancelled = true;
    }

    /**
     * Запускает сканирование в фоне. Список фото берётся как в галерее
     * (MediaStore + корневая папка + включённые дополнительные источники);
     * при «закрытой» маскировке скрытое, как и везде, не участвует.
     */
    public void scan(Context ctx, Listener l) {
        Context app = ctx.getApplicationContext();
        EXEC.execute(() -> {
            // Шаг 1: все фото устройства, группировка по размеру файла
            List<MediaItem> all = MediaEngine.queryAll(app);
            Map<Long, List<MediaItem>> bySize = new HashMap<>();
            for (MediaItem it : all) {
                if (it.isVideo || cancelled) continue;
                File f = new File(it.path);
                long len = f.length();
                if (len <= 0) continue;              // файла уже нет или он пуст
                it.size = len;                        // уточняем размер с диска
                List<MediaItem> g = bySize.get(len);
                if (g == null) {
                    g = new ArrayList<>(2);
                    bySize.put(len, g);
                }
                g.add(it);
            }
            if (cancelled) return;

            int total = 0;
            for (List<MediaItem> g : bySize.values()) {
                if (g.size() > 1) total += g.size();
            }
            int totalF = total;
            MAIN.post(() -> l.onProgress(0, totalF));

            // Шаг 2: контрольная сумма только для кандидатов
            Map<String, Group> byHash = new HashMap<>();
            int done = 0;
            long lastPost = 0;
            for (List<MediaItem> sizeGroup : bySize.values()) {
                if (sizeGroup.size() < 2) continue;
                for (MediaItem it : sizeGroup) {
                    if (cancelled) return;
                    String h = hash(new File(it.path));
                    done++;
                    long now = SystemClock.elapsedRealtime();
                    if (h != null) {
                        String key = it.size + ":" + h;
                        Group grp = byHash.get(key);
                        if (grp == null) {
                            grp = new Group();
                            grp.size = it.size;
                            byHash.put(key, grp);
                        }
                        grp.items.add(it);
                    }
                    // Прогресс не чаще ~12 раз в секунду, чтобы не заспамить UI
                    if (done == totalF || now - lastPost > 80) {
                        lastPost = now;
                        int d = done;
                        MAIN.post(() -> l.onProgress(d, totalF));
                    }
                }
            }
            if (cancelled) return;

            List<Group> out = new ArrayList<>();
            for (Group g : byHash.values()) {
                if (g.items.size() < 2) continue;
                // Самый старый — первым: обычно именно его оставляют
                Collections.sort(g.items, (a, b) -> Long.compare(a.dateModified, b.dateModified));
                out.add(g);
            }
            // Сверху группы, где лишние копии занимают больше всего места
            Collections.sort(out, (a, b) -> Long.compare(b.wasted(), a.wasted()));
            MAIN.post(() -> l.onResult(out));
        });
    }

    /** SHA-256 содержимого файла (hex) или null при ошибке чтения/отмене. */
    private String hash(File f) {
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
