package com.premiumlab.galleryx.util;

import android.annotation.SuppressLint;
import android.view.MotionEvent;
import android.view.View;
import android.view.animation.DecelerateInterpolator;
import android.view.animation.OvershootInterpolator;

import com.premiumlab.galleryx.R;

/**
 * «Пружинное» нажатие: при касании элемент слегка сжимается, при отпускании
 * возвращается с лёгким перелётом. Не перехватывает клики — только анимирует.
 */
public final class Press {

    private Press() {
    }

    /** Стандартное сжатие для кнопок-иконок. */
    public static void attach(View v) {
        attach(v, 0.88f);
    }

    @SuppressLint("ClickableViewAccessibility")
    public static void attach(View v, float scale) {
        if (v == null) return;
        v.setOnTouchListener((view, ev) -> {
            switch (ev.getActionMasked()) {
                case MotionEvent.ACTION_DOWN: {
                    // Масштаб покоя (выделенная ячейка сетки уже уменьшена) — вернёмся к нему
                    Object rest = view.getTag(R.id.press_rest);
                    float base = rest instanceof Float ? (Float) rest : 1f;
                    view.animate().cancel();
                    view.animate().scaleX(base * scale).scaleY(base * scale)
                            .setDuration(90)
                            .setInterpolator(new DecelerateInterpolator())
                            .start();
                    break;
                }
                case MotionEvent.ACTION_UP:
                case MotionEvent.ACTION_CANCEL: {
                    Object rest = view.getTag(R.id.press_rest);
                    float base = rest instanceof Float ? (Float) rest : 1f;
                    view.animate().cancel();
                    view.animate().scaleX(base).scaleY(base)
                            .setDuration(260)
                            .setInterpolator(new OvershootInterpolator(2.5f))
                            .start();
                    break;
                }
                default:
                    break;
            }
            return false;
        });
    }

    /** Нажатие для крупных карточек (сетка, альбомы). */
    public static void attachCard(View v) {
        attach(v, 0.96f);
    }

    /**
     * Задаёт масштаб покоя (например, 0.86 для выделенной ячейки) и плавно
     * переводит элемент к нему.
     */
    public static void setRestScale(View v, float rest, boolean animate) {
        if (v == null) return;
        Object prev = v.getTag(R.id.press_rest);
        float was = prev instanceof Float ? (Float) prev : 1f;
        v.setTag(R.id.press_rest, rest);
        if (was == rest && v.getScaleX() == rest) return;
        v.animate().cancel();
        if (animate) {
            v.animate().scaleX(rest).scaleY(rest).setDuration(260)
                    .setInterpolator(new OvershootInterpolator(1.5f)).start();
        } else {
            v.setScaleX(rest);
            v.setScaleY(rest);
        }
    }
}
