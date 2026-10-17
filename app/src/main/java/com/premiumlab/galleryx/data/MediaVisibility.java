package com.premiumlab.galleryx.data;

import android.content.Context;
import android.media.MediaScannerConnection;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Скрывает содержимое корневой папки от ДРУГИХ приложений, пока маскировка
 * «закрыта»: в корень кладётся файл {@code .nomedia}, после чего MediaStore
 * выкидывает из индекса все фото/видео корня — WhatsApp, Telegram и системные
 * выборщики их не видят. После открытия (удержание заголовка 3 с) флаг
 * удаляется, а файлы заново индексируются и снова доступны везде.
 *
 * Внутри нашего приложения корневые папки читаются напрямую с диска,
 * поэтому на них флаг не влияет.
 */
public final class MediaVisibility {

    private static final ExecutorService EXEC = Executors.newSingleThreadExecutor();
    private static final int BATCH = 400;
    private static final String KEY_RESCAN = "media_rescan_pending";

    private MediaVisibility() {
    }

    /** Приводит флаг в корне в соответствие с состоянием маскировки (идемпотентно). */
    public static void sync() {
        Context ctx = Prefs.ctx();
        String root = Prefs.rootPath();
        if (ctx == null || root == null) return;
        boolean hide = MaskGuard.hidden();
        File flag = new File(root, ".nomedia");
        if (flag.exists() == hide) {
            // Флаг уже на месте, но прошлая переиндексация могла не доработать
            // (приложение закрыли сразу после «открытия») — доводим её до конца
            if (!hide && Prefs.sp().getBoolean(KEY_RESCAN, false)) {
                EXEC.execute(() -> apply(ctx, new File(root), false));
            }
            return;
        }
        EXEC.execute(() -> apply(ctx, new File(root), hide));
    }

    /** Корень сменился: старую папку обязательно «рассекретить». */
    public static void release(String oldRoot) {
        Context ctx = Prefs.ctx();
        if (ctx == null || oldRoot == null) return;
        File flag = new File(oldRoot, ".nomedia");
        if (!flag.exists()) return;
        EXEC.execute(() -> apply(ctx, new File(oldRoot), false));
    }

    private static void apply(Context ctx, File root, boolean hide) {
        File flag = new File(root, ".nomedia");
        if (hide) {
            Prefs.sp().edit().remove(KEY_RESCAN).apply();
            try {
                //noinspection ResultOfMethodCallIgnored
                flag.createNewFile();
            } catch (IOException ignored) {
            }
            // Сканер, увидев .nomedia, убирает содержимое корня из индекса
            scan(ctx, new String[]{root.getAbsolutePath(), flag.getAbsolutePath()});
        } else {
            // Отмечаем «переиндексация в процессе»; снимем отметку, когда всё отправлено
            Prefs.sp().edit().putBoolean(KEY_RESCAN, true).apply();
            //noinspection ResultOfMethodCallIgnored
            flag.delete();
            // Возвращаем файлы в индекс: папку целиком и каждый файл отдельно
            // (на старых версиях сканер папки не рекурсивен)
            List<String> paths = new ArrayList<>();
            paths.add(root.getAbsolutePath());
            collect(root, paths, 0);
            for (int i = 0; i < paths.size(); i += BATCH) {
                int end = Math.min(paths.size(), i + BATCH);
                scan(ctx, paths.subList(i, end).toArray(new String[0]));
            }
            Prefs.sp().edit().remove(KEY_RESCAN).apply();
        }
        MediaEngine.invalidateAll();
    }

    private static void collect(File dir, List<String> out, int depth) {
        if (depth > 6) return;
        File[] files = dir.listFiles();
        if (files == null) return;
        for (File f : files) {
            if (f.getName().startsWith(".")) continue;
            if (f.isDirectory()) {
                out.add(f.getAbsolutePath());
                collect(f, out, depth + 1);
            } else if (MediaEngine.isMediaName(f.getName())) {
                out.add(f.getAbsolutePath());
            }
        }
    }

    private static void scan(Context ctx, String[] paths) {
        try {
            MediaScannerConnection.scanFile(ctx, paths, null, null);
        } catch (Exception ignored) {
        }
    }
}
