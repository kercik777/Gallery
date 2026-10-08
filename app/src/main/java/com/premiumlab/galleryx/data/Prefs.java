package com.premiumlab.galleryx.data;

import android.content.Context;
import android.content.SharedPreferences;

import org.json.JSONArray;

import java.util.ArrayList;
import java.util.List;

/**
 * Единая точка доступа к настройкам приложения.
 */
public class Prefs {

    // Режимы автоскрытия
    public static final int AUTOHIDE_MINIMIZE = 0;
    public static final int AUTOHIDE_1MIN = 1;
    public static final int AUTOHIDE_5MIN = 2;
    public static final int AUTOHIDE_MANUAL = 3;

    // Темы
    public static final int THEME_SYSTEM = 0;
    public static final int THEME_LIGHT = 1;
    public static final int THEME_DARK = 2;

    // Сортировка
    public static final int SORT_DATE_DESC = 0;
    public static final int SORT_DATE_ASC = 1;
    public static final int SORT_NAME_ASC = 2;
    public static final int SORT_NAME_DESC = 3;
    public static final int SORT_SIZE_DESC = 4;
    public static final int SORT_SIZE_ASC = 5;

    private static SharedPreferences sp;
    private static Context appCtx;

    private Prefs() {
    }

    public static void init(Context context) {
        appCtx = context.getApplicationContext();
        sp = appCtx.getSharedPreferences("galleryx_prefs", Context.MODE_PRIVATE);
    }

    public static Context ctx() {
        return appCtx;
    }

    public static SharedPreferences sp() {
        return sp;
    }

    // ----- Маскировка -----

    public static boolean masking() {
        return sp.getBoolean("masking_enabled", false);
    }

    public static void setMasking(boolean v) {
        sp.edit().putBoolean("masking_enabled", v).apply();
    }

    public static boolean pinSet() {
        return sp.getString("pin_hash", null) != null;
    }

    public static String pinHash() {
        return sp.getString("pin_hash", null);
    }

    public static String pinSalt() {
        return sp.getString("pin_salt", null);
    }

    public static void setPin(String hash, String salt) {
        sp.edit().putString("pin_hash", hash).putString("pin_salt", salt).apply();
    }

    public static void clearPin() {
        sp.edit().remove("pin_hash").remove("pin_salt").remove("pin_for_masking").apply();
    }

    /** PIN-код требуется также для открытия скрытых папок (маскировка). */
    public static boolean pinForMasking() {
        return sp.getBoolean("pin_for_masking", false);
    }

    public static void setPinForMasking(boolean v) {
        sp.edit().putBoolean("pin_for_masking", v).apply();
    }

    /** Нужно ли запрашивать PIN при снятии маскировки. */
    public static boolean maskingPinRequired() {
        return pinSet() && pinForMasking();
    }

    public static int autoHide() {
        return sp.getInt("autohide_mode", AUTOHIDE_MINIMIZE);
    }

    public static void setAutoHide(int mode) {
        sp.edit().putInt("autohide_mode", mode).apply();
    }

    public static boolean flagSecure() {
        return sp.getBoolean("flag_secure", false);
    }

    public static void setFlagSecure(boolean v) {
        sp.edit().putBoolean("flag_secure", v).apply();
    }

    // ----- Оформление -----

    public static int theme() {
        return sp.getInt("theme_mode", THEME_SYSTEM);
    }

    public static void setTheme(int mode) {
        sp.edit().putInt("theme_mode", mode).apply();
    }

    public static int columns() {
        return sp.getInt("grid_columns", 3);
    }

    public static void setColumns(int c) {
        sp.edit().putInt("grid_columns", Math.max(2, Math.min(6, c))).apply();
    }

    public static int sortMode() {
        return sp.getInt("sort_mode", SORT_DATE_DESC);
    }

    public static void setSortMode(int mode) {
        sp.edit().putInt("sort_mode", mode).apply();
    }

    // ----- Корневая папка -----

    public static String rootPath() {
        return sp.getString("root_path", null);
    }

    public static void setRootPath(String path) {
        sp.edit().putString("root_path", path).apply();
    }

    // ----- Избранное -----

    public static List<String> favorites() {
        List<String> out = new ArrayList<>();
        try {
            JSONArray arr = new JSONArray(sp.getString("favorites", "[]"));
            for (int i = 0; i < arr.length(); i++) out.add(arr.getString(i));
        } catch (Exception ignored) {
        }
        return out;
    }

    public static void saveFavorites(List<String> list) {
        JSONArray arr = new JSONArray();
        for (String s : list) arr.put(s);
        sp.edit().putString("favorites", arr.toString()).apply();
    }
}
