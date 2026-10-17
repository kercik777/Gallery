package com.premiumlab.galleryx.util;

import android.app.Activity;
import android.content.Context;
import android.content.res.TypedArray;
import android.util.TypedValue;

import com.premiumlab.galleryx.R;
import com.premiumlab.galleryx.data.Prefs;

/**
 * Оформление как в GorillaGallery: режим темы (Авто / Светлая / Тёмная / AMOLED) и акцентный
 * цвет. Акцент и поверхности заданы атрибутами темы (gxAccent, gxBg, …) и подменяются
 * оверлеем {@code ThemeOverlay.GX.Accent.*} перед {@code setContentView}.
 */
public final class Theming {

    private Theming() {
    }

    public static final class Accent {
        public final String key;
        /** Ресурс названия (локализуется). */
        public final int label;
        /** Цвет образца в палитре; 0 — «Авто» (белый в тёмной теме, чёрный в светлой). */
        public final int swatch;
        public final int style;

        Accent(String key, int label, int swatch, int style) {
            this.key = key;
            this.label = label;
            this.swatch = swatch;
            this.style = style;
        }
    }

    public static final Accent[] ACCENTS = {
            new Accent("adaptive", R.string.accent_adaptive, 0, R.style.ThemeOverlay_GX_Accent_Adaptive),
            new Accent("violet", R.string.accent_violet, 0xFF6C5CE7, R.style.ThemeOverlay_GX_Accent_Violet),
            new Accent("red", R.string.accent_red, 0xFFC62828, R.style.ThemeOverlay_GX_Accent_Red),
            new Accent("crimson", R.string.accent_crimson, 0xFFEC5464, R.style.ThemeOverlay_GX_Accent_Crimson),
            new Accent("rose", R.string.accent_rose, 0xFFD81B60, R.style.ThemeOverlay_GX_Accent_Rose),
            new Accent("purple", R.string.accent_purple, 0xFF8E24AA, R.style.ThemeOverlay_GX_Accent_Purple),
            new Accent("deep_purple", R.string.accent_deep_purple, 0xFF5E35B1, R.style.ThemeOverlay_GX_Accent_DeepPurple),
            new Accent("indigo", R.string.accent_indigo, 0xFF3949AB, R.style.ThemeOverlay_GX_Accent_Indigo),
            new Accent("blue", R.string.accent_blue, 0xFF1E88E5, R.style.ThemeOverlay_GX_Accent_Blue),
            new Accent("sky_blue", R.string.accent_sky_blue, 0xFF039BE5, R.style.ThemeOverlay_GX_Accent_SkyBlue),
            new Accent("cyan", R.string.accent_cyan, 0xFF00ACC1, R.style.ThemeOverlay_GX_Accent_Cyan),
            new Accent("electric_cyan", R.string.accent_electric_cyan, 0xFF00E5FF, R.style.ThemeOverlay_GX_Accent_ElectricCyan),
            new Accent("teal", R.string.accent_teal, 0xFF00897B, R.style.ThemeOverlay_GX_Accent_Teal),
            new Accent("green", R.string.accent_green, 0xFF43A047, R.style.ThemeOverlay_GX_Accent_Green),
            new Accent("light_green", R.string.accent_light_green, 0xFF7CB342, R.style.ThemeOverlay_GX_Accent_LightGreen),
            new Accent("lime", R.string.accent_lime, 0xFFC0CA33, R.style.ThemeOverlay_GX_Accent_Lime),
            new Accent("yellow", R.string.accent_yellow, 0xFFFDD835, R.style.ThemeOverlay_GX_Accent_Yellow),
            new Accent("amber", R.string.accent_amber, 0xFFFFB300, R.style.ThemeOverlay_GX_Accent_Amber),
            new Accent("orange", R.string.accent_orange, 0xFFFB8C00, R.style.ThemeOverlay_GX_Accent_Orange),
            new Accent("deep_orange", R.string.accent_deep_orange, 0xFFF4511E, R.style.ThemeOverlay_GX_Accent_DeepOrange),
            new Accent("blue_grey", R.string.accent_blue_grey, 0xFF546E7A, R.style.ThemeOverlay_GX_Accent_BlueGrey),
    };

    public static Accent current() {
        String key = Prefs.accent();
        for (Accent a : ACCENTS) if (a.key.equals(key)) return a;
        return ACCENTS[0];
    }

    /** Вызывать в начале {@code onCreate} каждой Activity, до {@code setContentView}. */
    public static void apply(Activity a) {
        a.getTheme().applyStyle(current().style, true);
        if (Prefs.theme() == Prefs.THEME_AMOLED) {
            a.getTheme().applyStyle(R.style.ThemeOverlay_GX_Amoled, true);
        }
    }

    /** Цвет атрибута темы контекста (например, {@code R.attr.gxAccent}). */
    public static int color(Context ctx, int attr) {
        TypedValue tv = new TypedValue();
        if (ctx.getTheme().resolveAttribute(attr, tv, true)) {
            if (tv.resourceId != 0) return ctx.getColor(tv.resourceId);
            return tv.data;
        }
        TypedArray ta = ctx.obtainStyledAttributes(new int[]{attr});
        try {
            return ta.getColor(0, 0xFF6C5CE7);
        } finally {
            ta.recycle();
        }
    }

    public static int accent(Context ctx) {
        return color(ctx, R.attr.gxAccent);
    }

    public static int onAccent(Context ctx) {
        return color(ctx, R.attr.gxOnAccent);
    }

    public static int surface(Context ctx) {
        return color(ctx, R.attr.gxSurface);
    }

    /** Истина, если тема сейчас тёмная (включая AMOLED). */
    public static boolean isDark(Context ctx) {
        return (ctx.getResources().getConfiguration().uiMode
                & android.content.res.Configuration.UI_MODE_NIGHT_MASK)
                == android.content.res.Configuration.UI_MODE_NIGHT_YES;
    }

    /** Цвет образца для палитры с учётом темы («Авто» — белый/чёрный). */
    public static int swatchColor(Context ctx, Accent a) {
        if (a.swatch != 0) return a.swatch;
        return isDark(ctx) ? 0xFFFFFFFF : 0xFF000000;
    }
}
