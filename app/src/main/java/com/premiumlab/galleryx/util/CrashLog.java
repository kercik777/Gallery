package com.premiumlab.galleryx.util;

import android.content.Context;
import android.os.Build;
import android.util.Log;

import androidx.annotation.Nullable;

import java.io.File;
import java.io.FileOutputStream;
import java.io.OutputStreamWriter;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

/**
 * Журнал ошибок приложения.
 *
 * При необработанном исключении (падении) стек сохраняется в файл во
 * внутреннем хранилище приложения, после чего управление передаётся
 * системному обработчику. Также сюда пишутся «нефатальные» ошибки, которые
 * были перехвачены защитными блоками (например, в файловых операциях), —
 * чтобы по ним можно было найти причину. Пользователь может посмотреть,
 * скопировать и отправить журнал из настроек («О приложении»).
 */
public final class CrashLog {

    private static final String TAG = "GalleryX";
    private static final String FILE = "crash_log.txt";
    private static final int MAX_BYTES = 200 * 1024;

    private static volatile Context app;
    private static volatile boolean installed;

    private CrashLog() {
    }

    /** Ставит глобальный обработчик необработанных исключений. */
    public static void install(Context ctx) {
        app = ctx.getApplicationContext();
        if (installed) return;
        installed = true;
        final Thread.UncaughtExceptionHandler prev = Thread.getDefaultUncaughtExceptionHandler();
        Thread.setDefaultUncaughtExceptionHandler((t, e) -> {
            try {
                write("FATAL", t, e);
                sp(app).edit().putLong("fatal_at", System.currentTimeMillis()).commit();
            } catch (Throwable ignored) {
            }
            if (prev != null) {
                prev.uncaughtException(t, e);
            } else {
                android.os.Process.killProcess(android.os.Process.myPid());
                System.exit(10);
            }
        });
    }

    /** Нефатальная ошибка, перехваченная защитным блоком. */
    public static void record(String where, Throwable e) {
        Log.w(TAG, "non-fatal in " + where, e);
        try {
            write("ERROR " + where, Thread.currentThread(), e);
        } catch (Throwable ignored) {
        }
    }

    /** Есть ли сохранённый журнал. */
    public static boolean exists() {
        Context c = app;
        return c != null && file(c).length() > 0;
    }

    /** Текст журнала (последние записи — внизу) или null. */
    @Nullable
    public static String read() {
        Context c = app;
        if (c == null) return null;
        File f = file(c);
        if (!f.exists()) return null;
        try {
            byte[] b = Files.readAllBytes(f.toPath());
            return new String(b, StandardCharsets.UTF_8);
        } catch (Exception e) {
            return null;
        }
    }

    /** Дата последней записи в журнале (для подзаголовка в настройках). */
    public static long lastModified() {
        Context c = app;
        return c == null ? 0 : file(c).lastModified();
    }

    /** Было ли падение, о котором пользователю ещё не сообщали. */
    public static boolean hasUnseenFatal() {
        Context c = app;
        if (c == null) return false;
        android.content.SharedPreferences p = sp(c);
        return p.getLong("fatal_at", 0) > p.getLong("seen_at", 0) && exists();
    }

    /** Отмечает, что пользователь уведомлён о последнем падении. */
    public static void markSeen() {
        Context c = app;
        if (c != null) sp(c).edit().putLong("seen_at", System.currentTimeMillis()).apply();
    }

    public static void clear() {
        Context c = app;
        if (c != null) {
            //noinspection ResultOfMethodCallIgnored
            file(c).delete();
        }
    }

    // ---------- внутреннее ----------

    private static android.content.SharedPreferences sp(Context c) {
        return c.getSharedPreferences("crashlog", Context.MODE_PRIVATE);
    }

    private static File file(Context c) {
        return new File(c.getFilesDir(), FILE);
    }

    private static synchronized void write(String kind, Thread t, Throwable e) {
        Context c = app;
        if (c == null) return;
        StringWriter sw = new StringWriter();
        PrintWriter pw = new PrintWriter(sw);
        pw.println("==== " + kind + " ====");
        pw.println(new SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(new Date()));
        pw.println("app " + versionName(c) + " · Android " + Build.VERSION.RELEASE
                + " (API " + Build.VERSION.SDK_INT + ") · " + Build.MANUFACTURER + " " + Build.MODEL);
        pw.println("thread " + (t == null ? "?" : t.getName()));
        if (e != null) e.printStackTrace(pw);
        pw.println();
        pw.flush();

        File f = file(c);
        String prev = "";
        try {
            if (f.exists()) prev = new String(Files.readAllBytes(f.toPath()), StandardCharsets.UTF_8);
        } catch (Exception ignored) {
        }
        String all = prev + sw;
        // Храним хвост, чтобы файл не рос бесконечно
        if (all.length() > MAX_BYTES) all = all.substring(all.length() - MAX_BYTES);
        try (OutputStreamWriter w = new OutputStreamWriter(new FileOutputStream(f, false),
                StandardCharsets.UTF_8)) {
            w.write(all);
        } catch (Exception ignored) {
        }
    }

    private static String versionName(Context c) {
        try {
            return c.getPackageManager().getPackageInfo(c.getPackageName(), 0).versionName;
        } catch (Exception e) {
            return "?";
        }
    }
}
