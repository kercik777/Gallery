package com.premiumlab.galleryx.util;

import android.content.Context;

import com.premiumlab.galleryx.R;

import java.io.File;
import java.text.SimpleDateFormat;
import java.util.Calendar;
import java.util.Date;
import java.util.Locale;
import java.util.concurrent.TimeUnit;

/**
 * Утилиты форматирования: размеры, даты, длительности.
 */
public final class Fmt {

    private Fmt() {
    }

    /** Красивый размер файла: 12,4 МБ */
    public static String size(long bytes) {
        Context c = resCtx();
        if (bytes < 1024) return bytes + " " + c.getString(R.string.size_b);
        double kb = bytes / 1024.0;
        if (kb < 1024) return String.format(locale(), "%.1f %s", kb, c.getString(R.string.size_kb));
        double mb = kb / 1024.0;
        if (mb < 1024) return String.format(locale(), "%.1f %s", mb, c.getString(R.string.size_mb));
        double gb = mb / 1024.0;
        return String.format(locale(), "%.2f %s", gb, c.getString(R.string.size_gb));
    }

    /** Текущая локаль приложения (per-app language или системная). */
    public static Locale locale() {
        return Locale.getDefault();
    }

    /**
     * Контекст с ресурсами на текущем языке приложения. Application-контекст на Android < 13
     * не знает о языке, выбранном через AppCompat, поэтому строки берём через
     * конфигурацию с {@link Locale#getDefault()} (его AppCompat обновляет).
     */
    public static Context resCtx() {
        Context c = com.premiumlab.galleryx.data.Prefs.ctx();
        android.content.res.Configuration cfg =
                new android.content.res.Configuration(c.getResources().getConfiguration());
        cfg.setLocale(locale());
        return c.createConfigurationContext(cfg);
    }

    private static boolean ru() {
        return "ru".equals(locale().getLanguage());
    }

    /** Шаблон «день месяц год»: 8 октября 2026 / October 8, 2026. */
    private static String dayMonthYear() {
        return ru() ? "d MMMM yyyy" : "MMMM d, yyyy";
    }

    private static String dayMonth() {
        return ru() ? "d MMMM" : "MMMM d";
    }

    /** Полная дата: 8 октября 2026, 14:30 */
    public static String dateFull(long millis) {
        SimpleDateFormat sdf = new SimpleDateFormat(dayMonthYear() + ", HH:mm", locale());
        return sdf.format(new Date(millis));
    }

    /** День недели с заглавной буквы: Понедельник */
    public static String dayOfWeek(long millis) {
        SimpleDateFormat sdf = new SimpleDateFormat("EEEE", locale());
        String d = sdf.format(new Date(millis));
        return d.isEmpty() ? d : Character.toUpperCase(d.charAt(0)) + d.substring(1);
    }

    /** Короткая дата без года, если год текущий: 8 октября · 14:30 */
    public static String dateTimeShort(long millis) {
        Calendar now = Calendar.getInstance();
        Calendar then = Calendar.getInstance();
        then.setTimeInMillis(millis);
        String pattern = now.get(Calendar.YEAR) == then.get(Calendar.YEAR)
                ? dayMonth() : dayMonthYear();
        SimpleDateFormat sdf = new SimpleDateFormat(pattern, locale());
        return sdf.format(new Date(millis)) + " · " + timeShort(millis);
    }

    /** Время: 14:30 */
    public static String timeShort(long millis) {
        SimpleDateFormat sdf = new SimpleDateFormat("HH:mm", locale());
        return sdf.format(new Date(millis));
    }

    /** Ключ группировки по дате: Сегодня / Вчера / октябрь 2026 */
    public static String dateKey(Context ctx, long millis) {
        Calendar now = Calendar.getInstance();
        Calendar then = Calendar.getInstance();
        then.setTimeInMillis(millis);
        int group = com.premiumlab.galleryx.data.Prefs.groupBy();
        if (group == com.premiumlab.galleryx.data.Prefs.GROUP_YEAR) {
            return String.valueOf(then.get(Calendar.YEAR));
        }
        if (group == com.premiumlab.galleryx.data.Prefs.GROUP_MONTH) {
            String m = new SimpleDateFormat("LLLL yyyy", locale()).format(new Date(millis));
            return m.substring(0, 1).toUpperCase(locale()) + m.substring(1);
        }

        if (now.get(Calendar.YEAR) == then.get(Calendar.YEAR)
                && now.get(Calendar.DAY_OF_YEAR) == then.get(Calendar.DAY_OF_YEAR)) {
            return ctx.getString(R.string.today);
        }
        now.add(Calendar.DAY_OF_YEAR, -1);
        if (now.get(Calendar.YEAR) == then.get(Calendar.YEAR)
                && now.get(Calendar.DAY_OF_YEAR) == then.get(Calendar.DAY_OF_YEAR)) {
            return ctx.getString(R.string.yesterday);
        }
        SimpleDateFormat sdf = new SimpleDateFormat(dayMonthYear(), locale());
        return sdf.format(new Date(millis));
    }

    /** Длительность: 1:23 или 1:02:45 */
    public static String duration(long millis) {
        if (millis <= 0) return "";
        long totalSec = TimeUnit.MILLISECONDS.toSeconds(millis);
        long h = totalSec / 3600;
        long m = (totalSec % 3600) / 60;
        long s = totalSec % 60;
        if (h > 0) return String.format(Locale.US, "%d:%02d:%02d", h, m, s);
        return String.format(Locale.US, "%d:%02d", m, s);
    }

    /** Склонение: 1 элемент / 2 элемента / 5 элементов */
    public static String plural(Context ctx, int resId, int n) {
        return ctx.getResources().getQuantityString(resId, n, n);
    }

    /** Подбирает уникальное имя вида «фото (1).jpg», если файл уже существует. */
    public static File uniqueFile(File dir, String desiredName) {
        File f = new File(dir, desiredName);
        if (!f.exists()) return f;
        String base = desiredName;
        String ext = "";
        int dot = desiredName.lastIndexOf('.');
        if (dot > 0) {
            base = desiredName.substring(0, dot);
            ext = desiredName.substring(dot);
        }
        int i = 1;
        while (i < 1000) {
            f = new File(dir, base + " (" + i + ")" + ext);
            if (!f.exists()) return f;
            i++;
        }
        return new File(dir, base + "_" + System.currentTimeMillis() + ext);
    }

    /** Проверка корректности имени файла/папки. */
    public static boolean isValidName(String name) {
        if (name == null) return false;
        String n = name.trim();
        if (n.isEmpty() || n.length() > 64) return false;
        if (n.startsWith(".")) return false;
        String invalid = "\\/:*?\"<>|";
        for (char c : invalid.toCharArray()) {
            if (n.indexOf(c) >= 0) return false;
        }
        return true;
    }
}
