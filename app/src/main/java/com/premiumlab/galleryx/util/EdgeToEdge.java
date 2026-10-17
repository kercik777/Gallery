package com.premiumlab.galleryx.util;

import android.app.Activity;
import android.graphics.Color;
import android.os.Build;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;

import androidx.core.graphics.Insets;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowCompat;
import androidx.core.view.WindowInsetsCompat;

import com.premiumlab.galleryx.ui.EdgeToEdgeHost;

/**
 * Прозрачные системные панели во всём приложении: окно рисуется под строкой состояния
 * и панелью навигации. Обычные экраны получают отступы сверху и снизу; экраны-
 * {@link EdgeToEdgeHost} — только сверху, а снизу сами приподнимают плавающие
 * элементы и добавляют отступ прокручиваемому контенту (см. {@link #padBottom},
 * {@link #growBottom}).
 */
public final class EdgeToEdge {

    private EdgeToEdge() {
    }

    /** Вызывается из {@code App.onActivityCreated} (до {@code setContentView}). */
    public static void setup(Activity a) {
        Window w = a.getWindow();
        WindowCompat.setDecorFitsSystemWindows(w, false);
        w.setStatusBarColor(Color.TRANSPARENT);
        w.setNavigationBarColor(Color.TRANSPARENT);
        if (Build.VERSION.SDK_INT >= 29) {
            w.setNavigationBarContrastEnforced(false);
            w.setStatusBarContrastEnforced(false);
        }
        final boolean ownBottom = a instanceof EdgeToEdgeHost;
        View content = a.findViewById(android.R.id.content);
        // Фон окна — из уже применённого оверлея темы (под прозрачными панелями виден
        // именно он). Полупрозрачные окна (просмотрщик, редакторы) не трогаем.
        try {
            android.util.TypedValue tv = new android.util.TypedValue();
            boolean translucent = a.getTheme().resolveAttribute(
                    android.R.attr.windowIsTranslucent, tv, true) && tv.data != 0;
            if (!translucent) {
                w.setBackgroundDrawable(new android.graphics.drawable.ColorDrawable(
                        Theming.color(a, com.premiumlab.galleryx.R.attr.gxBg)));
            }
        } catch (Exception ignored) {
        }
        if (content == null) return;
        ViewCompat.setOnApplyWindowInsetsListener(content, (v, insets) -> {
            Insets sb = insets.getInsets(WindowInsetsCompat.Type.systemBars()
                    | WindowInsetsCompat.Type.displayCutout());
            v.setPadding(sb.left, sb.top, sb.right, ownBottom ? 0 : sb.bottom);
            // Экраны с собственной нижней раскладкой получают инсеты дальше по дереву
            return ownBottom ? insets : WindowInsetsCompat.CONSUMED;
        });
    }

    /** Нижний инсет панели навигации (0, если ещё не известен). */
    public static int bottomInset(View v) {
        WindowInsetsCompat ins = ViewCompat.getRootWindowInsets(v);
        return ins == null ? 0 : ins.getInsets(WindowInsetsCompat.Type.systemBars()).bottom;
    }

    /** Добавляет инсет панели навигации к нижнему padding (базовое значение запоминается). */
    public static void padBottom(View v) {
        if (v == null) return;
        final int basePad = v.getPaddingBottom();
        ViewCompat.setOnApplyWindowInsetsListener(v, (view, insets) -> {
            int b = insets.getInsets(WindowInsetsCompat.Type.systemBars()).bottom;
            view.setPadding(view.getPaddingLeft(), view.getPaddingTop(), view.getPaddingRight(),
                    basePad + b);
            return insets;
        });
        ViewCompat.requestApplyInsets(v);
    }

    /**
     * Для фиксированной нижней зоны (панель вкладок, капсула действий): высота растёт
     * на инсет, а сам инсет уходит в нижний padding — содержимое остаётся прежнего
     * размера и приподнимается над панелью навигации.
     */
    public static void growBottom(View v) {
        if (v == null) return;
        final int baseHeight = v.getLayoutParams() != null ? v.getLayoutParams().height : 0;
        final int basePad = v.getPaddingBottom();
        ViewCompat.setOnApplyWindowInsetsListener(v, (view, insets) -> {
            int b = insets.getInsets(WindowInsetsCompat.Type.systemBars()).bottom;
            ViewGroup.LayoutParams lp = view.getLayoutParams();
            if (lp != null && baseHeight > 0 && lp.height != baseHeight + b) {
                lp.height = baseHeight + b;
                view.setLayoutParams(lp);
            }
            view.setPadding(view.getPaddingLeft(), view.getPaddingTop(), view.getPaddingRight(),
                    basePad + b);
            return insets;
        });
        ViewCompat.requestApplyInsets(v);
    }
}
