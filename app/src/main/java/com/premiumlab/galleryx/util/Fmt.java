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
        if (bytes < 1024) return bytes + " Б";
        double kb = bytes / 1024.0;
        if (kb < 1024) return String.format(Locale.getDefault(), "%.1f КБ", kb);
        double mb = kb / 1024.0;
        if (mb < 1024) return String.format(Locale.getDefault(), "%.1f МБ", mb);
        double gb = mb / 1024.0;
        return String.format(Locale.getDefault(), "%.2f ГБ", gb);
    }

    /** Полная дата: 8 октября 2026, 14:30 */
    public static String dateFull(long millis) {
        SimpleDateFormat sdf = new SimpleDateFormat("d MMMM yyyy, HH:mm", new Locale("ru"));
        return sdf.format(new Date(millis));
    }

    /** Ключ группировки по дате: Сегодня / Вчера / октябрь 2026 */
    public static String dateKey(Context ctx, long millis) {
        Calendar now = Calendar.getInstance();
        Calendar then = Calendar.getInstance();
        then.setTimeInMillis(millis);

        if (now.get(Calendar.YEAR) == then.get(Calendar.YEAR)
                && now.get(Calendar.DAY_OF_YEAR) == then.get(Calendar.DAY_OF_YEAR)) {
            return ctx.getString(R.string.today);
        }
        now.add(Calendar.DAY_OF_YEAR, -1);
        if (now.get(Calendar.YEAR) == then.get(Calendar.YEAR)
                && now.get(Calendar.DAY_OF_YEAR) == then.get(Calendar.DAY_OF_YEAR)) {
            return ctx.getString(R.string.yesterday);
        }
        SimpleDateFormat sdf = new SimpleDateFormat("d MMMM yyyy", new Locale("ru"));
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
