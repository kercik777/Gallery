package com.premiumlab.galleryx.data;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * «Умные» альбомы вкладки «Альбомы»: типы медиа (Фото, Видео, Селфи, Скриншоты,
 * Избранное) и инструменты (Отредактированные, Сейф, Корзина).
 * Строка списка с иконкой, цветом и счётчиком; группа строк рисуется одной карточкой.
 */
public class SmartAlbum {

    public static final int PHOTOS = 1;
    public static final int VIDEOS = 2;
    public static final int SELFIES = 3;
    public static final int SCREENSHOTS = 4;
    public static final int FAVORITES = 5;
    public static final int EDITED = 6;
    public static final int SAFE = 7;
    public static final int TRASH = 8;
    /** Инструмент «Изменить размер» (открывает экран обработки, не список). */
    public static final int RESIZER = 9;

    public final int kind;
    public final int icon;
    public final int color;
    public final int label;
    /** -1 — счётчик не показывать (сейф). */
    public int count;

    public SmartAlbum(int kind, int icon, int color, int label, int count) {
        this.kind = kind;
        this.icon = icon;
        this.color = color;
        this.label = label;
        this.count = count;
    }

    /** Группа строк: заголовок секции + карточка. */
    public static class Group {
        public final int title;
        public final List<SmartAlbum> rows = new ArrayList<>();

        public Group(int title) {
            this.title = title;
        }
    }

    // ---------- Эвристики отбора ----------

    /** Скриншот: папка Screenshots/Скриншоты или имя файла начинается с Screenshot. */
    public static boolean isScreenshot(MediaItem it) {
        if (it.isVideo) return false;
        String p = it.path.toLowerCase(Locale.US);
        String n = it.name.toLowerCase(Locale.US);
        return p.contains("/screenshots/") || p.contains("/скриншоты/")
                || p.contains("/screenshot/") || n.startsWith("screenshot")
                || n.startsWith("скриншот") || n.startsWith("снимок экрана");
    }

    /** Селфи: папки Selfie/Селфи/Portrait и файлы фронтальной камеры (по имени). */
    public static boolean isSelfie(MediaItem it) {
        if (it.isVideo) return false;
        if (SelfieIndex.isSelfie(it.path)) return true;
        String p = it.path.toLowerCase(Locale.US);
        String n = it.name.toLowerCase(Locale.US);
        return p.contains("/selfie") || p.contains("/селфи") || p.contains("/portrait/")
                || n.contains("selfie") || n.contains("селфи") || n.contains("_front")
                || n.contains("front_") || n.startsWith("img_front");
    }

    /** Отредактированные: копии, сохранённые нашим редактором, и типичные суффиксы. */
    public static boolean isEdited(MediaItem it) {
        String n = it.name.toLowerCase(Locale.US);
        int dot = n.lastIndexOf('.');
        String base = dot > 0 ? n.substring(0, dot) : n;
        return base.contains("_edited") || base.contains("-edited") || base.endsWith("_edit")
                || base.contains("_edited_") || base.endsWith("(edited)")
                || it.path.toLowerCase(Locale.US).contains("/edited/");
    }

    public static boolean matches(int kind, MediaItem it, java.util.Set<String> favs) {
        switch (kind) {
            case PHOTOS:
                return !it.isVideo;
            case VIDEOS:
                return it.isVideo;
            case SELFIES:
                return isSelfie(it);
            case SCREENSHOTS:
                return isScreenshot(it);
            case FAVORITES:
                return favs != null && favs.contains(it.path);
            case EDITED:
                return isEdited(it);
            default:
                return false;
        }
    }
}
