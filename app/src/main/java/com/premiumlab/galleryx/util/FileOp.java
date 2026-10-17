package com.premiumlab.galleryx.util;

import android.app.Activity;
import android.content.Context;
import android.os.Environment;
import android.os.Handler;
import android.os.Looper;

import com.premiumlab.galleryx.R;
import com.premiumlab.galleryx.data.FavStore;
import com.premiumlab.galleryx.data.NoteStore;
import com.premiumlab.galleryx.data.MediaItem;
import com.premiumlab.galleryx.data.SafeStore;
import com.premiumlab.galleryx.data.TrashStore;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.lang.ref.WeakReference;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Файловые операции: копирование, перемещение, корзина, сейф, удаление навсегда,
 * восстановление. Выполняются в фоне, с прогрессом и возможностью отмены.
 *
 * На время операции поднимается foreground-сервис {@link OpService}: процесс
 * не будет убит, если приложение свернуть или смахнуть из «недавних», а при
 * закрытом экране итог придёт уведомлением. Копирование идёт во временный
 * скрытый файл и только после полной записи переименовывается в целевой —
 * «половинки» файлов в галерее не появятся даже при внезапном завершении.
 */
public class FileOp {

    public interface Done {
        void onDone(int okCount, int failCount, boolean cancelled);
    }

    public interface Progress {
        void onProgress(int done, int total, String fileName, int percent);
    }

    /** Суффикс недописанной копии; такие файлы скрыты (имя с точки) и игнорируются сканером. */
    public static final String PART_SUFFIX = ".gxpart";

    private final Context ctx;
    private final WeakReference<Activity> host;
    private volatile boolean cancelled = false;
    private volatile boolean finished = false;
    private final ExecutorService exec = Executors.newSingleThreadExecutor();
    private final Handler main = new Handler(Looper.getMainLooper());
    private String title = "";

    public FileOp(Context context) {
        this.ctx = context.getApplicationContext();
        this.host = new WeakReference<>(context instanceof Activity ? (Activity) context : null);
    }

    /**
     * Запускает тело операции в фоне под защитой foreground-сервиса.
     * titleRes — название операции для уведомления («Копирование» и т. п.).
     */
    /**
     * Запускает тело операции в фоновом потоке. Любая неожиданная ошибка внутри
     * тела не роняет приложение: она пишется в журнал (CrashLog), а операция
     * завершается с результатом «не выполнено» — диалог закрывается, тост
     * показывается как обычно.
     */
    private void run(int titleRes, Progress p, Done d, int total, Runnable body) {
        title = ctx.getString(titleRes);
        finished = false;
        OpService.begin(ctx, title);
        exec.execute(() -> {
            try {
                body.run();
            } catch (Throwable t) {
                CrashLog.record("FileOp." + title, t);
                if (!finished) finish(p, d, 0, total, total);
            } finally {
                OpService.end(ctx);
            }
        });
    }

    /** true — экран, запустивший операцию, уже закрыт (итог покажем уведомлением). */
    private boolean hostGone() {
        Activity a = host.get();
        return a == null || a.isFinishing() || a.isDestroyed();
    }

    public void cancel() {
        cancelled = true;
    }

    // ---------- Копирование ----------

    public void copy(List<MediaItem> items, File destDir, Progress p, Done d) {
        run(R.string.op_copy, p, d, items.size(), () -> {
            int ok = 0, fail = 0;
            long totalBytes = 0;
            for (MediaItem it : items) totalBytes += it.size;
            long copiedBytes = 0;
            for (int i = 0; i < items.size(); i++) {
                if (cancelled) break;
                MediaItem it = items.get(i);
                postProgress(p, i, items.size(), it.name, percentOf(copiedBytes, totalBytes));
                File src = new File(it.path);
                if (!src.exists()) {
                    fail++;
                    continue;
                }
                File target = Fmt.uniqueFile(destDir, it.name);
                long done = copyFile(src, target, copiedBytes, totalBytes, p, i, items.size(), it.name);
                if (done >= 0) {
                    copiedBytes += src.length();
                    ok++;
                    Scan.files(ctx, target.getAbsolutePath());
                } else {
                    fail++;
                }
            }
            finish(p, d, ok, fail, items.size());
        });
    }

    // ---------- Перемещение ----------

    public void move(List<MediaItem> items, File destDir, Progress p, Done d) {
        run(R.string.op_move, p, d, items.size(), () -> {
            int ok = 0, fail = 0;
            for (int i = 0; i < items.size(); i++) {
                if (cancelled) break;
                MediaItem it = items.get(i);
                postProgress(p, i, items.size(), it.name, percentOf(i, items.size()));
                File src = new File(it.path);
                if (!src.exists()) {
                    fail++;
                    continue;
                }
                File target = Fmt.uniqueFile(destDir, it.name);
                if (moveFile(src, target)) {
                    FavStore.rewritePrefix(src.getAbsolutePath(), target.getAbsolutePath());
                    NoteStore.rewritePrefix(src.getAbsolutePath(), target.getAbsolutePath());
                    ok++;
                } else {
                    fail++;
                    continue;
                }
                Scan.files(ctx, src.getAbsolutePath(), target.getAbsolutePath());
            }
            finish(p, d, ok, fail, items.size());
        });
    }

    // ---------- Корзина ----------

    public void trash(List<MediaItem> items, Progress p, Done d) {
        run(R.string.op_trash, p, d, items.size(), () -> {
            int ok = 0, fail = 0;
            File dir = TrashStore.trashDir();
            if (dir == null) {
                failAll(p, d, items.size());
                return;
            }
            for (int i = 0; i < items.size(); i++) {
                if (cancelled) break;
                MediaItem it = items.get(i);
                postProgress(p, i, items.size(), it.name, percentOf(i, items.size()));
                File src = new File(it.path);
                if (!src.exists()) {
                    fail++;
                    continue;
                }
                File target = new File(dir, "t" + System.nanoTime() + "_" + it.name);
                if (moveFile(src, target)) {
                    TrashStore.get().add(src.getAbsolutePath(), target, it.name, it.size, it.isVideo);
                    FavStore.remove(it.path);
                    NoteStore.rewritePrefix(src.getAbsolutePath(), target.getAbsolutePath());
                    ok++;
                    Scan.files(ctx, src.getAbsolutePath());
                } else {
                    fail++;
                }
            }
            finish(p, d, ok, fail, items.size());
        });
    }

    // ---------- Сейф ----------

    public void safe(List<MediaItem> items, Progress p, Done d) {
        run(R.string.op_safe, p, d, items.size(), () -> {
            int ok = 0, fail = 0;
            File dir = SafeStore.safeDir();
            if (dir == null) {
                failAll(p, d, items.size());
                return;
            }
            for (int i = 0; i < items.size(); i++) {
                if (cancelled) break;
                MediaItem it = items.get(i);
                postProgress(p, i, items.size(), it.name, percentOf(i, items.size()));
                File src = new File(it.path);
                if (!src.exists()) {
                    fail++;
                    continue;
                }
                File target = new File(dir, "s" + System.nanoTime() + ".bin");
                if (moveFile(src, target)) {
                    SafeStore.get().add(src.getAbsolutePath(), target, it.name, it.size, it.isVideo);
                    FavStore.remove(it.path);
                    NoteStore.rewritePrefix(src.getAbsolutePath(), target.getAbsolutePath());
                    ok++;
                    Scan.files(ctx, src.getAbsolutePath());
                } else {
                    fail++;
                }
            }
            finish(p, d, ok, fail, items.size());
        });
    }

    /** Копирует файлы внутри сейфа в другую папку сейфа (физическая копия + новая запись). */
    public void safeCopy(List<SafeStore.Entry> entries, String folder, Progress p, Done d) {
        run(R.string.op_copy, p, d, entries.size(), () -> {
            int ok = 0, fail = 0;
            File dir = SafeStore.safeDir();
            if (dir == null) {
                failAll(p, d, entries.size());
                return;
            }
            for (int i = 0; i < entries.size(); i++) {
                if (cancelled) break;
                SafeStore.Entry e = entries.get(i);
                postProgress(p, i, entries.size(), e.name, percentOf(i, entries.size()));
                File src = new File(e.path);
                if (!src.exists()) {
                    fail++;
                    continue;
                }
                File target = new File(dir, "s" + System.nanoTime() + ".bin");
                if (copyFile(src, target, -1, -1, null, 0, 0, null) >= 0) {
                    SafeStore.get().addCopy(e, target, folder);
                    ok++;
                } else {
                    fail++;
                }
            }
            finish(p, d, ok, fail, entries.size());
        });
    }

    // ---------- Удаление навсегда ----------

    public void deleteForever(List<MediaItem> items, Progress p, Done d) {
        run(R.string.op_delete, p, d, items.size(), () -> {
            int ok = 0, fail = 0;
            List<String> deletedPaths = new ArrayList<>();
            for (int i = 0; i < items.size(); i++) {
                if (cancelled) break;
                MediaItem it = items.get(i);
                postProgress(p, i, items.size(), it.name, percentOf(i, items.size()));
                File f = new File(it.path);
                //noinspection ResultOfMethodCallIgnored
                boolean deleted = f.delete() || !f.exists();
                if (deleted) {
                    ok++;
                    deletedPaths.add(it.path);
                    NoteStore.remove(it.path);
                    FavStore.remove(it.path);
                    Scan.files(ctx, it.path);
                } else {
                    fail++;
                }
            }
            // Записи сейфа/корзины об удалённых файлах больше не нужны
            SafeStore.get().removePaths(deletedPaths);
            TrashStore.get().removePaths(deletedPaths);
            finish(p, d, ok, fail, items.size());
        });
    }

    // ---------- Восстановление из корзины ----------

    public void restoreTrash(List<TrashStore.Entry> entries, Progress p, Done d) {
        run(R.string.op_restore, p, d, entries.size(), () -> {
            int ok = 0, fail = 0;
            for (int i = 0; i < entries.size(); i++) {
                if (cancelled) break;
                TrashStore.Entry e = entries.get(i);
                postProgress(p, i, entries.size(), e.name, percentOf(i, entries.size()));
                File src = new File(e.path);
                File destDir;
                String origName;
                if (TrashStore.isManual(e)) {
                    // Положено в корзину вручную — возвращаем в <корень>/Restored
                    String root = com.premiumlab.galleryx.data.Prefs.rootPath();
                    destDir = root == null ? null : new File(root, TrashStore.RESTORED_DIR);
                    origName = e.name;
                } else {
                    destDir = new File(e.origPath).getParentFile();
                    origName = new File(e.origPath).getName();
                }
                if (src.exists() && destDir != null) {
                    //noinspection ResultOfMethodCallIgnored
                    destDir.mkdirs();
                    File target = Fmt.uniqueFile(destDir, origName);
                    if (moveFile(src, target)) {
                        ok++;
                        TrashStore.get().remove(e);
                        NoteStore.rewritePrefix(src.getAbsolutePath(), target.getAbsolutePath());
                        Scan.files(ctx, target.getAbsolutePath());
                    } else {
                        fail++;
                    }
                } else {
                    fail++;
                    TrashStore.get().remove(e);
                }
            }
            com.premiumlab.galleryx.data.AppDirs.cleanupEmptyDirs();
            finish(p, d, ok, fail, entries.size());
        });
    }

    // ---------- Восстановление из сейфа ----------

    public void restoreSafe(List<SafeStore.Entry> entries, Progress p, Done d) {
        run(R.string.op_restore, p, d, entries.size(), () -> {
            int ok = 0, fail = 0;
            for (int i = 0; i < entries.size(); i++) {
                if (cancelled) break;
                SafeStore.Entry e = entries.get(i);
                postProgress(p, i, entries.size(), e.name, percentOf(i, entries.size()));
                File src = new File(e.path);
                File destDir;
                if (e.origPath == null || e.origPath.isEmpty()) {
                    // Запись восстановлена по файлу (после переустановки) — исходный
                    // путь неизвестен, возвращаем в <корень>/Restored
                    String root = com.premiumlab.galleryx.data.Prefs.rootPath();
                    destDir = root == null ? null : new File(root, TrashStore.RESTORED_DIR);
                } else {
                    destDir = new File(e.origPath).getParentFile();
                }
                if (src.exists() && destDir != null) {
                    //noinspection ResultOfMethodCallIgnored
                    destDir.mkdirs();
                    File target = Fmt.uniqueFile(destDir, e.name);
                    if (moveFile(src, target)) {
                        ok++;
                        SafeStore.get().remove(e);
                        NoteStore.rewritePrefix(src.getAbsolutePath(), target.getAbsolutePath());
                        Scan.files(ctx, target.getAbsolutePath());
                    } else {
                        fail++;
                    }
                } else {
                    fail++;
                    SafeStore.get().remove(e);
                }
            }
            finish(p, d, ok, fail, entries.size());
        });
    }

    // ---------- Операции с папками (альбомами) ----------

    /**
     * Копирует папки целиком (со всеми медиафайлами и подпапками) внутрь destDir.
     * Папка-источник становится подпапкой назначения: A → dest/A.
     */
    public void copyDirs(List<File> dirs, File destDir, Progress p, Done d) {
        run(R.string.op_copy, p, d, dirs.size(), () -> {
            int ok = 0, fail = 0;
            List<File[]> jobs = new ArrayList<>(); // [src, target]
            long totalBytes = 0;
            for (File dir : dirs) {
                if (!dir.isDirectory() || isSameOrInside(destDir, dir)) {
                    fail++;
                    continue;
                }
                File targetRoot = uniqueDir(destDir, dir.getName());
                List<File> files = new ArrayList<>();
                collectMedia(dir, files, 0);
                for (File f : files) {
                    jobs.add(new File[]{f, new File(targetRoot,
                            relativePath(dir, f))});
                    totalBytes += f.length();
                }
                if (files.isEmpty()) {
                    //noinspection ResultOfMethodCallIgnored
                    targetRoot.mkdirs();
                    ok++;
                }
            }
            long copiedBytes = 0;
            List<String> scan = new ArrayList<>();
            for (int i = 0; i < jobs.size(); i++) {
                if (cancelled) break;
                File src = jobs.get(i)[0];
                File target = jobs.get(i)[1];
                File parent = target.getParentFile();
                if (parent != null) {
                    //noinspection ResultOfMethodCallIgnored
                    parent.mkdirs();
                }
                target = Fmt.uniqueFile(parent == null ? destDir : parent, target.getName());
                postProgress(p, i, jobs.size(), src.getName(),
                        percentOf(copiedBytes, totalBytes));
                long done = copyFile(src, target, copiedBytes, totalBytes, p, i,
                        jobs.size(), src.getName());
                if (done >= 0) {
                    copiedBytes += src.length();
                    ok++;
                    scan.add(target.getAbsolutePath());
                } else {
                    fail++;
                }
            }
            if (!scan.isEmpty()) Scan.files(ctx, scan.toArray(new String[0]));
            finish(p, d, ok, fail, Math.max(1, jobs.size()));
        });
    }

    /**
     * Перемещает папки целиком внутрь destDir (A → dest/A).
     * На одном разделе — мгновенный rename, иначе — копирование и удаление.
     */
    public void moveDirs(List<File> dirs, File destDir, Progress p, Done d) {
        run(R.string.op_move, p, d, dirs.size(), () -> {
            int ok = 0, fail = 0;
            List<String> scan = new ArrayList<>();
            // Файлы собираем заранее: межраздельный перенос большой папки
            // показывает пофайловый прогресс, а не висит на 0%
            List<File> valid = new ArrayList<>();
            List<List<File>> perDir = new ArrayList<>();
            for (File dir : dirs) {
                if (!dir.isDirectory() || isSameOrInside(destDir, dir)) {
                    fail++;
                    continue;
                }
                List<File> files = new ArrayList<>();
                collectMedia(dir, files, 0);
                valid.add(dir);
                perDir.add(files);
            }
            int totalFiles = 0;
            for (List<File> l : perDir) totalFiles += l.size();
            int grand = Math.max(1, totalFiles);
            int done = 0;
            for (int i = 0; i < valid.size(); i++) {
                if (cancelled) break;
                File dir = valid.get(i);
                List<File> files = perDir.get(i);
                postProgress(p, done, grand, dir.getName(), percentOf(done, grand));
                File target = uniqueDir(destDir, dir.getName());
                for (File f : files) scan.add(f.getAbsolutePath());

                if (dir.renameTo(target)) {
                    for (File f : files) {
                        scan.add(new File(target, relativePath(dir, f)).getAbsolutePath());
                    }
                    FavStore.rewritePrefix(dir.getAbsolutePath(), target.getAbsolutePath());
                    NoteStore.rewritePrefix(dir.getAbsolutePath(), target.getAbsolutePath());
                    ok++;
                    done += files.size();
                    continue;
                }
                // Разные разделы: копируем файл за файлом, затем удаляем источник
                boolean allOk = true;
                for (File f : files) {
                    if (cancelled) {
                        allOk = false;
                        break;
                    }
                    postProgress(p, done, grand, f.getName(), percentOf(done, grand));
                    done++;
                    File t = new File(target, relativePath(dir, f));
                    File parent = t.getParentFile();
                    if (parent != null) {
                        //noinspection ResultOfMethodCallIgnored
                        parent.mkdirs();
                    }
                    if (copyFile(f, t, -1, -1, null, 0, 0, null) >= 0) {
                        //noinspection ResultOfMethodCallIgnored
                        f.delete();
                        scan.add(t.getAbsolutePath());
                    } else {
                        allOk = false;
                    }
                }
                if (allOk) {
                    deleteTree(dir);
                    ok++;
                } else {
                    fail++;
                }
            }
            if (!scan.isEmpty()) Scan.files(ctx, scan.toArray(new String[0]));
            finish(p, d, ok, fail, dirs.size());
        });
    }

    /** Удаляет папки: все медиафайлы — в корзину, затем удаляется пустое дерево. */
    public void trashDirs(List<File> dirs, Progress p, Done d) {
        run(R.string.op_trash, p, d, dirs.size(), () -> {
            int ok = 0, fail = 0;
            File trashDir = TrashStore.trashDir();
            if (trashDir == null) {
                failAll(p, d, dirs.size());
                return;
            }
            // Файлы собираем заранее по всем папкам: прогресс идёт пофайлово,
            // иначе одна большая папка висит на 0% до самого конца
            List<File> targets = new ArrayList<>();
            List<List<File>> perDir = new ArrayList<>();
            for (File dir : dirs) {
                if (!dir.exists()) {
                    fail++;
                    continue;
                }
                List<File> files = new ArrayList<>();
                collectMedia(dir, files, 0);
                targets.add(dir);
                perDir.add(files);
            }
            int totalFiles = 0;
            for (List<File> l : perDir) totalFiles += l.size();
            int grand = Math.max(1, totalFiles);
            List<String> scan = new ArrayList<>();
            int done = 0;
            for (int i = 0; i < targets.size(); i++) {
                if (cancelled) break;
                File dir = targets.get(i);
                boolean allOk = true;
                for (File f : perDir.get(i)) {
                    if (cancelled) {
                        allOk = false;
                        break;
                    }
                    postProgress(p, done, grand, f.getName(), percentOf(done, grand));
                    File target = new File(trashDir, "t" + System.nanoTime() + "_" + f.getName());
                    if (moveFile(f, target)) {
                        TrashStore.get().add(f.getAbsolutePath(), target, f.getName(),
                                target.length(), com.premiumlab.galleryx.data.MediaEngine
                                        .isVideoName(f.getName()));
                        FavStore.remove(f.getAbsolutePath());
                        NoteStore.rewritePrefix(f.getAbsolutePath(), target.getAbsolutePath());
                        scan.add(f.getAbsolutePath());
                    } else {
                        allOk = false;
                    }
                    done++;
                }
                if (cancelled) {
                    fail++;
                    continue;
                }
                if (allOk) {
                    deleteTree(dir);
                    ok++;
                } else {
                    fail++;
                }
            }
            if (!scan.isEmpty()) Scan.files(ctx, scan.toArray(new String[0]));
            finish(p, d, ok, fail, dirs.size());
        });
    }

    /** true, если candidate совпадает с dir или лежит внутри него. */
    private static boolean isSameOrInside(File candidate, File dir) {
        String c = candidate.getAbsolutePath();
        String d = dir.getAbsolutePath();
        return c.equals(d) || c.startsWith(d + "/");
    }

    private static String relativePath(File base, File f) {
        String b = base.getAbsolutePath();
        String p = f.getAbsolutePath();
        if (p.startsWith(b + "/")) return p.substring(b.length() + 1);
        return f.getName();
    }

    /** Свободное имя папки внутри parent: name, name (2), name (3)… */
    private static File uniqueDir(File parent, String name) {
        File f = new File(parent, name);
        int n = 2;
        while (f.exists()) {
            f = new File(parent, name + " (" + n + ")");
            n++;
        }
        return f;
    }

    private static void collectMedia(File dir, List<File> out, int depth) {
        if (depth > 8) return;
        File[] arr = dir.listFiles();
        if (arr == null) return;
        for (File f : arr) {
            if (f.getName().startsWith(".")) continue;
            if (f.isDirectory()) collectMedia(f, out, depth + 1);
            else out.add(f);
        }
    }

    private static void deleteTree(File dir) {
        File[] arr = dir.listFiles();
        if (arr != null) {
            for (File f : arr) {
                if (f.isDirectory()) deleteTree(f);
                //noinspection ResultOfMethodCallIgnored
                f.delete();
            }
        }
        //noinspection ResultOfMethodCallIgnored
        dir.delete();
    }

    // ---------- Вспомогательные ----------

    private static final int BUF = 128 * 1024;

    /**
     * Копирует файл потоками. Возвращает число скопированных байт или -1 при ошибке.
     * При переданном прогрессе обновляет его в процессе копирования.
     */
    private long copyFile(File src, File target, long copiedBytes, long totalBytes,
                          Progress p, int index, int total, String name) {
        InputStream in = null;
        OutputStream out = null;
        // Пишем во временный скрытый файл рядом с целевым: пока копия не дописана,
        // ни наша галерея, ни другие приложения её не увидят
        File tmp = new File(target.getParentFile(), "." + target.getName() + PART_SUFFIX);
        try {
            in = new FileInputStream(src);
            out = new FileOutputStream(tmp);
            byte[] buf = new byte[BUF];
            long done = 0;
            int n;
            while ((n = in.read(buf)) > 0) {
                if (cancelled) {
                    out.close();
                    out = null;
                    //noinspection ResultOfMethodCallIgnored
                    tmp.delete();
                    return -1;
                }
                out.write(buf, 0, n);
                done += n;
                if (p != null && totalBytes > 0) {
                    int percent = (int) ((copiedBytes + done) * 100 / totalBytes);
                    postProgress(p, index, total, name, percent);
                }
            }
            out.flush();
            out.close();
            out = null;
            if (!tmp.renameTo(target)) {
                //noinspection ResultOfMethodCallIgnored
                tmp.delete();
                return -1;
            }
            return done;
        } catch (IOException e) {
            //noinspection ResultOfMethodCallIgnored
            tmp.delete();
            return -1;
        } finally {
            try {
                if (in != null) in.close();
            } catch (IOException ignored) {
            }
            try {
                if (out != null) out.close();
            } catch (IOException ignored) {
            }
        }
    }

    /** Перемещает файл: сначала rename, при неудаче (другой том) — копирование и удаление. */
    private boolean moveFile(File src, File target) {
        if (src.renameTo(target)) return true;
        if (copyFile(src, target, -1, -1, null, 0, 0, null) >= 0) {
            //noinspection ResultOfMethodCallIgnored
            src.delete();
            return true;
        }
        return false;
    }

    /** Завершает операцию с ошибкой «корневая папка не выбрана». */
    private void failAll(Progress p, Done d, int total) {
        finish(p, d, 0, total, total);
    }

    private static int percentOf(long part, long total) {
        if (total <= 0) return 0;
        return (int) Math.min(100, part * 100 / total);
    }

    private void postProgress(Progress p, int done, int total, String name, int percent) {
        OpService.progress(name, percent);
        if (p == null) return;
        main.post(() -> {
            try {
                p.onProgress(done, total, name, percent);
            } catch (Throwable t) {
                CrashLog.record("FileOp.progress", t);
            }
        });
    }

    private void finish(Progress p, Done d, int ok, int fail, int total) {
        finished = true;
        boolean cancelledNow = cancelled && ok + fail < total;
        main.post(() -> {
            if (hostGone()) {
                // Экран закрыт: диалог и тост показывать некому — сообщаем уведомлением
                try {
                    OpService.notifyDone(ctx, title,
                            ctx.getString(R.string.op_notif_done, ok, total));
                } catch (Throwable t) {
                    CrashLog.record("FileOp.notifyDone", t);
                }
                return;
            }
            // Колбэки экрана защищены: ошибка в них не должна ронять приложение
            try {
                if (p != null) p.onProgress(total, total, "", 100);
            } catch (Throwable t) {
                CrashLog.record("FileOp.progress", t);
            }
            try {
                if (d != null) d.onDone(ok, fail, cancelledNow);
            } catch (Throwable t) {
                CrashLog.record("FileOp.done", t);
            }
        });
    }

    /** Корень внешнего хранилища (для служебных нужд). */
    @SuppressWarnings("unused")
    private static File externalRoot() {
        return Environment.getExternalStorageDirectory();
    }
}
