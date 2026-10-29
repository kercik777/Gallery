package com.premiumlab.galleryx.data;

import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import android.os.Handler;
import android.os.Looper;

import java.io.File;
import java.util.HashSet;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Заметки к файлам — база SQLite.
 *
 * База лежит в корневой папке: {@code <корень>/.GalleryX/galleryx.db}, поэтому
 * переезжает вместе с корнем и копируется на компьютер вместе со всеми папками.
 * Пока корень не выбран, используется внутренняя база приложения; при выборе
 * корня её записи переносятся в корневую.
 *
 * Все обращения к диску — в фоновом потоке; на главном потоке держится лишь
 * множество путей с заметками (для значков в сетке и просмотрщике).
 */
public final class NoteStore {

    public static final class Note {
        public final String path;
        public final String title;
        public final String body;
        public final long updated;

        Note(String path, String title, String body, long updated) {
            this.path = path;
            this.title = title == null ? "" : title;
            this.body = body == null ? "" : body;
            this.updated = updated;
        }

        public boolean isEmpty() {
            return title.trim().isEmpty() && body.trim().isEmpty();
        }
    }

    public interface Callback<T> {
        void onResult(T value);
    }

    private static final String DB_NAME = "galleryx.db";
    private static final String INTERNAL_NAME = "galleryx_notes.db";
    private static final ExecutorService EXEC = Executors.newSingleThreadExecutor();
    private static final Handler MAIN = new Handler(Looper.getMainLooper());

    private static final Object LOCK = new Object();
    private static SQLiteDatabase db;
    private static File dbFile;
    private static final Set<String> withNotes = new HashSet<>();
    private static volatile boolean indexLoaded = false;

    private NoteStore() {
    }

    // ---------- Файл базы ----------

    /** Где должна лежать база сейчас. */
    public static File targetFile() {
        File hidden = AppDirs.hiddenRoot();
        if (hidden != null) return new File(hidden, DB_NAME);
        Context ctx = Prefs.ctx();
        return ctx.getDatabasePath(INTERNAL_NAME);
    }

    /**
     * Открывает базу. Никогда не бросает: если внешнее хранилище временно
     * недоступно (отмонтировано, «Transport endpoint is not connected»,
     * папку .GalleryX создать не удалось) — возвращает null, а следующий
     * вызов попробует снова. Вызывающие методы и так сидят в try/catch,
     * а null они переживают как обычную ошибку одного вызова.
     */
    private static SQLiteDatabase open() {
        synchronized (LOCK) {
            File target = targetFile();
            if (db != null && db.isOpen() && target.equals(dbFile)) return db;
            if (db != null) {
                try {
                    db.close();
                } catch (Exception ignored) {
                }
                db = null;
                dbFile = null;
            }
            SQLiteDatabase d = null;
            try {
                File parent = target.getParentFile();
                if (parent != null && !parent.exists()) {
                    //noinspection ResultOfMethodCallIgnored
                    parent.mkdirs();
                }
                if (parent != null && !parent.isDirectory()) return null;
                if (parent != null && AppDirs.HIDDEN_NAME.equals(parent.getName())) {
                    AppDirs.noMedia(parent);
                }
                d = SQLiteDatabase.openOrCreateDatabase(target, null);
                d.execSQL("CREATE TABLE IF NOT EXISTS notes ("
                        + "path TEXT PRIMARY KEY, "
                        + "title TEXT, "
                        + "body TEXT, "
                        + "updated INTEGER)");
                migrateInternal(d, target);
                reloadIndex(d);
                db = d;
                dbFile = target;
                return d;
            } catch (Exception e) {
                if (d != null) {
                    try {
                        d.close();
                    } catch (Exception ignored) {
                    }
                }
                db = null;
                dbFile = null;
                return null;
            }
        }
    }

    /** Корень выбран — переносим заметки из внутренней базы в корневую. */
    private static void migrateInternal(SQLiteDatabase target, File targetFile) {
        Context ctx = Prefs.ctx();
        if (ctx == null) return;
        File internal = ctx.getDatabasePath(INTERNAL_NAME);
        if (!internal.exists() || internal.equals(targetFile)) return;
        SQLiteDatabase src = null;
        try {
            src = SQLiteDatabase.openDatabase(internal.getAbsolutePath(), null,
                    SQLiteDatabase.OPEN_READONLY);
            Cursor c = src.rawQuery("SELECT path, title, body, updated FROM notes", null);
            target.beginTransaction();
            try {
                while (c.moveToNext()) {
                    ContentValues cv = new ContentValues();
                    cv.put("path", c.getString(0));
                    cv.put("title", c.getString(1));
                    cv.put("body", c.getString(2));
                    cv.put("updated", c.getLong(3));
                    target.insertWithOnConflict("notes", null, cv,
                            SQLiteDatabase.CONFLICT_IGNORE);
                }
                target.setTransactionSuccessful();
            } finally {
                target.endTransaction();
                c.close();
            }
        } catch (Exception ignored) {
            return;
        } finally {
            if (src != null) src.close();
        }
        //noinspection ResultOfMethodCallIgnored
        internal.delete();
        //noinspection ResultOfMethodCallIgnored
        new File(internal.getAbsolutePath() + "-journal").delete();
    }

    private static void reloadIndex(SQLiteDatabase d) {
        Set<String> set = new HashSet<>();
        try (Cursor c = d.rawQuery("SELECT path FROM notes WHERE "
                + "length(trim(coalesce(title,''))) > 0 OR length(trim(coalesce(body,''))) > 0", null)) {
            while (c.moveToNext()) set.add(c.getString(0));
        } catch (Exception ignored) {
        }
        synchronized (withNotes) {
            withNotes.clear();
            withNotes.addAll(set);
        }
        indexLoaded = true;
    }

    /** Прогрев в фоне (при старте и после выбора корня). */
    public static void warmUp() {
        EXEC.execute(() -> {
            try {
                open();
            } catch (Throwable ignored) {
                // Хранилище могло быть временно недоступно — прогрев
                // повторится при следующем обращении, краш недопустим
            }
        });
    }

    /** Закрыть базу перед переносом служебной папки. */
    public static void close() {
        synchronized (LOCK) {
            if (db != null) {
                try {
                    db.close();
                } catch (Exception ignored) {
                }
                db = null;
                dbFile = null;
            }
        }
    }

    // ---------- Быстрые проверки (главный поток) ----------

    public static boolean has(String path) {
        if (path == null) return false;
        if (!indexLoaded) warmUp();
        synchronized (withNotes) {
            return withNotes.contains(path);
        }
    }

    // ---------- Чтение / запись (фон) ----------

    public static void load(String path, Callback<Note> cb) {
        EXEC.execute(() -> {
            Note n = null;
            try {
                SQLiteDatabase d = open();
                try (Cursor c = d.rawQuery(
                        "SELECT title, body, updated FROM notes WHERE path = ?",
                        new String[]{path})) {
                    if (c.moveToFirst()) {
                        n = new Note(path, c.getString(0), c.getString(1), c.getLong(2));
                    }
                }
            } catch (Exception ignored) {
            }
            Note result = n;
            MAIN.post(() -> cb.onResult(result));
        });
    }

    public static void save(String path, String title, String body, Callback<Boolean> cb) {
        EXEC.execute(() -> {
            boolean ok;
            boolean empty = (title == null || title.trim().isEmpty())
                    && (body == null || body.trim().isEmpty());
            try {
                SQLiteDatabase d = open();
                if (empty) {
                    d.delete("notes", "path = ?", new String[]{path});
                } else {
                    ContentValues cv = new ContentValues();
                    cv.put("path", path);
                    cv.put("title", title);
                    cv.put("body", body);
                    cv.put("updated", System.currentTimeMillis());
                    d.insertWithOnConflict("notes", null, cv, SQLiteDatabase.CONFLICT_REPLACE);
                }
                ok = true;
            } catch (Exception e) {
                ok = false;
            }
            if (ok) {
                synchronized (withNotes) {
                    if (empty) withNotes.remove(path);
                    else withNotes.add(path);
                }
            }
            boolean result = ok;
            if (cb != null) MAIN.post(() -> cb.onResult(result));
        });
    }

    public static void delete(String path, Callback<Boolean> cb) {
        save(path, "", "", cb);
    }

    /** Файл удалён навсегда — заметка больше не нужна. */
    public static void remove(String path) {
        if (path == null) return;
        EXEC.execute(() -> {
            try {
                open().delete("notes", "path = ?", new String[]{path});
            } catch (Exception ignored) {
            }
            synchronized (withNotes) {
                withNotes.remove(path);
            }
        });
    }

    /** Переписывает пути после переименования/перемещения файла или папки. */
    public static void rewritePrefix(String from, String to) {
        if (from == null || to == null || from.equals(to)) return;
        EXEC.execute(() -> {
            try {
                SQLiteDatabase d = open();
                d.beginTransaction();
                try {
                    // Сам файл/папка
                    d.execSQL("UPDATE OR REPLACE notes SET path = ? WHERE path = ?",
                            new Object[]{to, from});
                    // Всё, что внутри папки
                    d.execSQL("UPDATE OR REPLACE notes SET path = ? || substr(path, ?) "
                                    + "WHERE substr(path, 1, ?) = ?",
                            new Object[]{to, from.length() + 1, from.length() + 1, from + "/"});
                    d.setTransactionSuccessful();
                } finally {
                    d.endTransaction();
                }
                reloadIndex(d);
            } catch (Exception ignored) {
            }
        });
    }
}
