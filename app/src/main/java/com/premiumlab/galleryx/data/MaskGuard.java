package com.premiumlab.galleryx.data;

/**
 * Единая точка проверки «что сейчас нужно прятать».
 *
 * Режим маскировки больше не подменяет приложение пустой галереей:
 * галерея выглядит обычной, но пока она «закрыта» — скрываются
 * корневая папка (все «Мои папки» и файлы в них), сейф и пункт
 * настроек маскировки. После удержания заголовка «Галерея» 3 секунды
 * (и ввода PIN-кода, если он задан) всё снова становится видимым.
 */
public final class MaskGuard {

    private MaskGuard() {
    }

    /** true — маскировка включена и галерея сейчас «закрыта». */
    public static boolean hidden() {
        return Prefs.masking() && !SessionManager.isUnlocked();
    }

    /** true — путь лежит внутри корневой папки и его сейчас нужно прятать. */
    public static boolean isHiddenPath(String path) {
        if (path == null || !hidden()) return false;
        String root = Prefs.rootPath();
        return root != null && MediaEngine.isUnder(path, root);
    }
}
