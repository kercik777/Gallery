package com.premiumlab.galleryx.util;

import android.content.Context;
import android.widget.Toast;

import com.premiumlab.galleryx.data.Prefs;

/**
 * Обёртка над Toast: при включённой настройке «Скрывать уведомления»
 * возвращает «пустой» тост, у которого show() ничего не делает.
 */
public final class Toasts {

    private Toasts() {
    }

    public static Toast makeText(Context ctx, int resId, int duration) {
        if (Prefs.quiet()) return silent(ctx);
        return Toast.makeText(ctx, resId, duration);
    }

    public static Toast makeText(Context ctx, CharSequence text, int duration) {
        if (Prefs.quiet()) return silent(ctx);
        return Toast.makeText(ctx, text, duration);
    }

    private static Toast silent(Context ctx) {
        return new Toast(ctx.getApplicationContext()) {
            @Override
            public void show() {
                // тихий режим
            }
        };
    }
}
