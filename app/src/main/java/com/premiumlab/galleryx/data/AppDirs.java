package com.premiumlab.galleryx.data;

import android.os.Environment;

import java.io.File;
import java.io.IOException;

/**
 * Служебные папки приложения (корзина, сейф).
 *
 * Ничего не создаётся, пока пользователь не выбрал корневую папку.
 * После выбора всё служебное живёт внутри неё: {@code <корень>/.GalleryX/trash}
 * и {@code <корень>/.GalleryX/safe}. При смене корневой папки служебная папка
 * переезжает вместе с содержимым, а записи корзины/сейфа переписываются.
 */
public final class AppDirs {

    public static final String HIDDEN_NAME = ".GalleryX";

    private AppDirs() {
    }

    /** true — корневая папка выбрана, служебные папки можно использовать. */
    public static boolean ready() {
        return Prefs.rootPath() != null;
    }

    /** Скрытая служебная папка внутри корня или null, если корень не выбран. */
    public static File hiddenRoot() {
        String root = Prefs.rootPath();
        return root == null ? null : new File(root, HIDDEN_NAME);
    }

    /** Папка корзины (создаётся при первом обращении) или null без корня. */
    public static File trashDir() {
        return ensure("trash");
    }

    /** Папка сейфа (создаётся при первом обращении) или null без корня. */
    public static File safeDir() {
        return ensure("safe");
    }

    private static File ensure(String name) {
        File hidden = hiddenRoot();
        if (hidden == null) return null;
        File dir = new File(hidden, name);
        if (!dir.exists()) {
            //noinspection ResultOfMethodCallIgnored
            dir.mkdirs();
        }
        noMedia(hidden);
        noMedia(dir);
        return dir;
    }

    static void noMedia(File dir) {
        File f = new File(dir, ".nomedia");
        if (!f.exists()) {
            try {
                //noinspection ResultOfMethodCallIgnored
                f.createNewFile();
            } catch (IOException ignored) {
            }
        }
    }

    /** true — путь лежит внутри служебной папки (любой: старой или новой). */
    /**
     * Служебные альбомы, которые приложение создаёт само («Restored» при
     * восстановлении из корзины, «GalleryResizer» для копий из инструмента
     * изменения размера): если папка опустела — убираем, чтобы не висела пустым
     * альбомом. Папки с содержимым не трогаются.
     */
    public static void cleanupEmptyDirs() {
        String root = Prefs.rootPath();
        if (root != null) {
            deleteIfEmpty(new File(root, TrashStore.RESTORED_DIR));
            deleteIfEmpty(new File(root, RESIZER_DIR));
        }
        try {
            File pics = android.os.Environment.getExternalStoragePublicDirectory(
                    android.os.Environment.DIRECTORY_PICTURES);
            deleteIfEmpty(new File(pics, RESIZER_DIR));
        } catch (Exception ignored) {
        }
    }

    /** Имя папки копий инструмента «Изменить размер». */
    public static final String RESIZER_DIR = "GalleryResizer";

    private static void deleteIfEmpty(File dir) {
        try {
            if (!dir.isDirectory()) return;
            String[] names = dir.list();
            if (names == null) return;
            for (String n : names) {
                if (!".nomedia".equals(n)) return; // есть содержимое — оставляем
            }
            for (String n : names) {
                //noinspection ResultOfMethodCallIgnored
                new File(dir, n).delete();
            }
            //noinspection ResultOfMethodCallIgnored
            dir.delete();
        } catch (Exception ignored) {
        }
    }

    public static boolean isServicePath(String path) {
        return path != null && (path.contains("/" + HIDDEN_NAME + "/")
                || path.endsWith("/" + HIDDEN_NAME));
    }

    /** Служебная папка старых версий — в корне памяти телефона. */
    private static File legacyHidden() {
        return new File(Environment.getExternalStorageDirectory(), HIDDEN_NAME);
    }

    /**
     * Назначает новую корневую папку и переносит в неё служебные данные.
     * Вызывать вместо {@link Prefs#setRootPath(String)}.
     *
     * @param newRoot новый путь корневой папки
     */
    public static void setRoot(String newRoot) {
        String oldRoot = Prefs.rootPath();
        // База заметок лежит в служебной папке — закрываем перед переносом
        NoteStore.close();
        if (oldRoot != null && !oldRoot.equals(newRoot)) {
            // Старый корень больше не прячем от других приложений
            MediaVisibility.release(oldRoot);
        }
        File newHidden = new File(newRoot, HIDDEN_NAME);
        File oldHidden = oldRoot == null ? null : new File(oldRoot, HIDDEN_NAME);
        File legacy = legacyHidden();

        String from = null;
        if (oldHidden != null && oldHidden.isDirectory() && !oldHidden.equals(newHidden)) {
            if (!newHidden.exists() && oldHidden.renameTo(newHidden)) {
                from = oldHidden.getAbsolutePath();
            } else if (!newHidden.exists() && moveTree(oldHidden, newHidden)) {
                from = oldHidden.getAbsolutePath();
            }
        } else if (legacy.isDirectory() && !legacy.equals(newHidden)) {
            if (!newHidden.exists() && legacy.renameTo(newHidden)) {
                from = legacy.getAbsolutePath();
            } else if (!newHidden.exists() && moveTree(legacy, newHidden)) {
                from = legacy.getAbsolutePath();
            }
        }

        // Папку могли переименовать снаружи (например, переименован сам корень):
        // служебные файлы уже на месте, нужно лишь переписать пути в записях.
        if (from == null && oldHidden != null && !oldHidden.equals(newHidden)
                && newHidden.isDirectory()) {
            from = oldHidden.getAbsolutePath();
        }

        if (from != null) {
            String to = newHidden.getAbsolutePath();
            TrashStore.get().rewritePrefix(from, to);
            SafeStore.get().rewritePrefix(from, to);
            FavStore.rewritePrefix(from, to);
            LockStore.rewritePrefix(from, to);
        }
        if (oldRoot != null && !oldRoot.equals(newRoot)) {
            LockStore.rewritePrefix(oldRoot, newRoot);
            NoteStore.rewritePrefix(oldRoot, newRoot);
        }
        if (oldRoot != null && !oldRoot.equals(newRoot)) {
            // Избранное из старого корня больше не найдётся — ссылки остаются,
            // FavStore сам пропускает несуществующие файлы.
            MediaEngine.invalidateAll();
        }
        Prefs.setRootPath(newRoot);
        // Новый корень: флаг .nomedia по состоянию маскировки, база заметок — внутрь
        MediaVisibility.sync();
        NoteStore.warmUp();
        // PIN: свой — сохраняем в корень, нет своего — подхватываем из корня
        PinBackup.onRootChanged();
        // Заблокированные папки: объединяем настройки со списком из корня
        // (после переустановки блокировки восстанавливаются из файла)
        LockStore.syncWithRootFile();
        // Файлы сейфа из этого корня (например, после переустановки) — обратно в список
        SafeStore.get().syncFromDisk();
    }

    /** Перенос дерева через копирование (другой раздел памяти). */
    private static boolean moveTree(File src, File dst) {
        if (!dst.mkdirs() && !dst.isDirectory()) return false;
        File[] files = src.listFiles();
        boolean ok = true;
        if (files != null) {
            for (File f : files) {
                File t = new File(dst, f.getName());
                if (f.isDirectory()) {
                    ok &= moveTree(f, t);
                } else if (!f.renameTo(t)) {
                    ok &= copyAndDelete(f, t);
                }
            }
        }
        //noinspection ResultOfMethodCallIgnored
        src.delete();
        return ok;
    }

    private static boolean copyAndDelete(File src, File dst) {
        try (java.io.InputStream in = new java.io.FileInputStream(src);
             java.io.OutputStream out = new java.io.FileOutputStream(dst)) {
            byte[] buf = new byte[128 * 1024];
            int n;
            while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
            out.flush();
        } catch (IOException e) {
            //noinspection ResultOfMethodCallIgnored
            dst.delete();
            return false;
        }
        //noinspection ResultOfMethodCallIgnored
        src.delete();
        return true;
    }
}
