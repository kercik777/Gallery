package com.premiumlab.galleryx.resizer;

import java.util.List;

/**
 * Передача списка выбранных файлов между экранами без ограничений размера
 * Intent (в галерее могут быть тысячи путей).
 */
public final class ResizeSession {

    private ResizeSession() {
    }

    /** Пути, выбранные для обработки; читается и обнуляется получателем. */
    public static volatile List<String> paths;

    public static List<String> take() {
        List<String> p = paths;
        paths = null;
        return p;
    }
}
