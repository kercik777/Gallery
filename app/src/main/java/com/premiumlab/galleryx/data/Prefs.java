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
    public static final int THEME_AMOLED = 3;

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
        MediaVisibility.sync();
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
        setPinQuiet(hash, salt);
        // Копия в корневой папке — переживает переустановку приложения
        PinBackup.save();
    }

    /** То же без записи резервной копии (используется при восстановлении из неё). */
    public static void setPinQuiet(String hash, String salt) {
        sp.edit().putString("pin_hash", hash).putString("pin_salt", salt).apply();
    }

    public static void clearPin() {
        sp.edit().remove("pin_hash").remove("pin_salt").apply();
        PinBackup.clear();
    }

    /** Применять ли PIN к маскировке (сейф и блокировка папок защищены всегда). */
    public static boolean maskingUsesPin() {
        return sp.getBoolean("masking_use_pin", true);
    }

    public static void setMaskingUsesPin(boolean v) {
        sp.edit().putBoolean("masking_use_pin", v).apply();
        if (pinSet()) PinBackup.save();
    }

    /** PIN один на всё; для маскировки — только если включён пункт «PIN для маскировки». */
    public static boolean maskingPinRequired() {
        return pinSet() && maskingUsesPin();
    }

    public static int autoHide() {
        return sp.getInt("autohide_mode", AUTOHIDE_MANUAL);
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

    /** Ключ акцентного цвета (см. {@link com.premiumlab.galleryx.util.Theming#ACCENTS}). */
    public static String accent() {
        return sp.getString("accent", "adaptive");
    }

    public static void setAccent(String key) {
        sp.edit().putString("accent", key).apply();
    }

    /** Колонок в сетке медиа (2–5). */
    public static int columns() {
        return Math.max(2, Math.min(5, sp.getInt("grid_columns", 4)));
    }

    public static void setColumns(int c) {
        sp.edit().putInt("grid_columns", Math.max(2, Math.min(5, c))).apply();
    }

    /** Колонок в сетке альбомов (1–3). */
    public static int albumColumns() {
        return Math.max(1, Math.min(3, sp.getInt("album_columns", 3)));
    }

    public static void setAlbumColumns(int c) {
        sp.edit().putInt("album_columns", Math.max(1, Math.min(3, c))).apply();
    }

    /** Последняя открытая вкладка главного экрана (0 — галерея, 1 — альбомы, 2 — настройки). */
    public static int lastTab() {
        int v = sp.getInt("last_tab", 0);
        // Раньше вкладок было четыре (2 — избранное, 3 — настройки)
        if (sp.getInt("tabs_version", 1) < 2) {
            v = v == 3 ? 2 : (v == 2 ? 0 : v);
            sp.edit().putInt("tabs_version", 2).putInt("last_tab", v).apply();
        }
        return Math.max(0, Math.min(2, v));
    }

    public static void setLastTab(int index) {
        sp.edit().putInt("last_tab", index).putInt("tabs_version", 2).apply();
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

    // ----- Недавние запросы поиска (до 4, как в GorillaGallery) -----

    public static List<String> recentSearches() {
        List<String> out = new ArrayList<>();
        String raw = sp.getString("recent_searches", "");
        if (raw.isEmpty()) return out;
        for (String s : raw.split("\\|")) if (!s.trim().isEmpty()) out.add(s.trim());
        return out;
    }

    public static void addRecentSearch(String q) {
        if (q == null) return;
        String t = q.trim().replace("|", " ");
        if (t.isEmpty()) return;
        List<String> list = recentSearches();
        list.removeIf(x -> x.equalsIgnoreCase(t));
        list.add(0, t);
        while (list.size() > 4) list.remove(list.size() - 1);
        sp.edit().putString("recent_searches", String.join("|", list)).apply();
    }

    public static void clearRecentSearches() {
        sp.edit().remove("recent_searches").apply();
    }

    // ----- Дополнительные источники (папки вне обычной галереи) -----

    /** Папки, которые пользователь вручную включил в галерею (абсолютные пути). */
    public static java.util.Set<String> extraFolders() {
        java.util.LinkedHashSet<String> out = new java.util.LinkedHashSet<>();
        try {
            JSONArray arr = new JSONArray(sp.getString("extra_sources", "[]"));
            for (int i = 0; i < arr.length(); i++) out.add(arr.getString(i));
        } catch (Exception ignored) {
        }
        return out;
    }

    public static void setExtraFolders(java.util.Collection<String> paths) {
        JSONArray arr = new JSONArray();
        for (String s : new java.util.LinkedHashSet<>(paths)) arr.put(s);
        sp.edit().putString("extra_sources", arr.toString()).apply();
    }

    /** Когда последний раз открывали экран источников (для подсказки «новые папки»). */
    public static long extraSourcesSeenAt() {
        return sp.getLong("extra_sources_seen", 0L);
    }

    public static void setExtraSourcesSeenAt(long t) {
        sp.edit().putLong("extra_sources_seen", t).apply();
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

    // ----- Настройки в духе GorillaGallery -----

    public static final int GROUP_NONE = 0, GROUP_DAY = 1, GROUP_MONTH = 2, GROUP_YEAR = 3;

    /** Группировка ленты по дате: нет / день / месяц / год. */
    public static int groupBy() {
        return sp.getInt("group_by", GROUP_DAY);
    }

    public static void setGroupBy(int v) {
        sp.edit().putInt("group_by", v).apply();
    }

    /** Миниатюры высокого качества (декод в 2 раза крупнее ячейки). */
    public static boolean hqThumbs() {
        return sp.getBoolean("hq_thumbs", false);
    }

    public static void setHqThumbs(boolean v) {
        sp.edit().putBoolean("hq_thumbs", v).apply();
    }

    /** Зацикливать видео в просмотрщике. */
    public static boolean loopVideos() {
        return sp.getBoolean("loop_videos", false);
    }

    public static void setLoopVideos(boolean v) {
        sp.edit().putBoolean("loop_videos", v).apply();
    }

    /** Видео в просмотрщике стартует со звуком (по умолчанию — без). */
    public static boolean videoSoundDefault() {
        return sp.getBoolean("video_sound", false);
    }

    public static void setVideoSoundDefault(boolean v) {
        sp.edit().putBoolean("video_sound", v).apply();
    }

    /** Скрывать всплывающие уведомления и подтверждения. */
    public static boolean quiet() {
        return sp.getBoolean("quiet", false);
    }

    public static void setQuiet(boolean v) {
        sp.edit().putBoolean("quiet", v).apply();
    }

    /** Удалять в корзину (иначе — сразу навсегда). */
    public static boolean useTrash() {
        return sp.getBoolean("use_trash", true);
    }

    public static void setUseTrash(boolean v) {
        sp.edit().putBoolean("use_trash", v).apply();
    }

    /** Картинка в картинке для видео при сворачивании. */
    public static boolean pip() {
        return sp.getBoolean("pip", true);
    }

    public static void setPip(boolean v) {
        sp.edit().putBoolean("pip", v).apply();
    }

    /** Быстрая прокрутка (ползунок у края сетки). */
    public static boolean fastScroll() {
        return sp.getBoolean("fast_scroll", true);
    }

    public static void setFastScroll(boolean v) {
        sp.edit().putBoolean("fast_scroll", v).apply();
    }

    /** Разблокировка отпечатком / лицом вместо PIN. */
    public static boolean biometric() {
        return sp.getBoolean("biometric", false);
    }

    public static void setBiometric(boolean v) {
        sp.edit().putBoolean("biometric", v).apply();
    }

    /** Показывать «Сейф» в блоке «Инструменты» на экране альбомов. */
    public static boolean safeInAlbums() {
        return sp.getBoolean("safe_in_albums", true);
    }

    public static void setSafeInAlbums(boolean v) {
        sp.edit().putBoolean("safe_in_albums", v).apply();
    }

    /** Стиль ползунка плеера: 0 — тонкая линия, 1 — толстая капсула. */
    public static int sliderStyle() {
        return sp.getInt("slider_style", 0);
    }

    public static void setSliderStyle(int v) {
        sp.edit().putInt("slider_style", v).apply();
    }
}
