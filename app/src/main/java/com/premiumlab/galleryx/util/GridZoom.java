package com.premiumlab.galleryx.util;

import android.graphics.Rect;
import android.util.SparseArray;
import android.view.MotionEvent;
import android.view.ScaleGestureDetector;
import android.view.View;
import android.view.animation.Interpolator;

import androidx.annotation.NonNull;
import androidx.core.view.OneShotPreDrawListener;

import androidx.recyclerview.widget.GridLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

/**
 * Щипок по сетке как в GorillaGallery: число колонок меняется прямо во время жеста при
 * пересечении порога, а ячейки «перетекают» на новые места с пружинной анимацией
 * (старые плавно переезжают и меняют размер, новые появляются с лёгким масштабом).
 */
public final class GridZoom {

    private GridZoom() {
    }

    public interface Listener {
        void onColumnsChanged(int columns);
    }

    private static final Interpolator EASE = new android.view.animation.DecelerateInterpolator(1.6f);
    private static final Interpolator SPRING = new android.view.animation.OvershootInterpolator(0.55f);
    private static final long DURATION = 340L;

    /** Вешает детектор щипка на RecyclerView; min/max — границы числа колонок. */
    public static void attach(@NonNull RecyclerView rv, @NonNull GridLayoutManager lm,
                              int min, int max, @NonNull Listener listener) {
        ScaleGestureDetector detector = new ScaleGestureDetector(rv.getContext(),
                new ScaleGestureDetector.SimpleOnScaleGestureListener() {
                    float zoom = 1f;

                    @Override
                    public boolean onScaleBegin(@NonNull ScaleGestureDetector d) {
                        zoom = 1f;
                        return true;
                    }

                    @Override
                    public boolean onScale(@NonNull ScaleGestureDetector d) {
                        zoom *= d.getScaleFactor();
                        int cols = lm.getSpanCount();
                        if (zoom > 1.18f && cols > min) {
                            change(rv, lm, cols - 1, listener);
                            zoom = 1f;
                        } else if (zoom < 0.84f && cols < max) {
                            change(rv, lm, cols + 1, listener);
                            zoom = 1f;
                        }
                        return true;
                    }
                });
        detector.setQuickScaleEnabled(false);
        rv.addOnItemTouchListener(new RecyclerView.SimpleOnItemTouchListener() {
            @Override
            public boolean onInterceptTouchEvent(@NonNull RecyclerView r, @NonNull MotionEvent e) {
                detector.onTouchEvent(e);
                return detector.isInProgress();
            }

            @Override
            public void onTouchEvent(@NonNull RecyclerView r, @NonNull MotionEvent e) {
                detector.onTouchEvent(e);
            }
        });
    }

    /** Меняет число колонок с анимацией перетекания ячеек. */
    public static void change(@NonNull RecyclerView rv, @NonNull GridLayoutManager lm, int columns,
                              Listener listener) {
        if (columns == lm.getSpanCount() || rv.getAdapter() == null) return;
        // 1. Запоминаем, где сейчас стоят видимые ячейки
        SparseArray<Rect> before = new SparseArray<>();
        for (int i = 0; i < rv.getChildCount(); i++) {
            View ch = rv.getChildAt(i);
            int pos = rv.getChildAdapterPosition(ch);
            if (pos == RecyclerView.NO_POSITION) continue;
            ch.animate().cancel();
            before.put(pos, new Rect(ch.getLeft(), ch.getTop(), ch.getRight(), ch.getBottom()));
        }
        int anchor = lm.findFirstVisibleItemPosition();
        View anchorView = anchor >= 0 ? lm.findViewByPosition(anchor) : null;
        int anchorOffset = anchorView != null ? anchorView.getTop() - rv.getPaddingTop() : 0;
        // 2. Применяем новую сетку
        if (listener != null) listener.onColumnsChanged(columns);
        lm.setSpanCount(columns);
        rv.getAdapter().notifyDataSetChanged();
        if (anchor > 0) lm.scrollToPositionWithOffset(anchor, anchorOffset);
        // 3. После раскладки — анимируем от старых позиций к новым
        OneShotPreDrawListener.add(rv, () -> {
            for (int i = 0; i < rv.getChildCount(); i++) {
                View ch = rv.getChildAt(i);
                int pos = rv.getChildAdapterPosition(ch);
                int w = ch.getWidth(), h = ch.getHeight();
                if (w == 0 || h == 0) continue;
                Rect old = pos == RecyclerView.NO_POSITION ? null : before.get(pos);
                if (old != null && old.width() > 0 && old.height() > 0) {
                    float sx = old.width() / (float) w;
                    float sy = old.height() / (float) h;
                    ch.setPivotX(0f);
                    ch.setPivotY(0f);
                    ch.setTranslationX(old.left - ch.getLeft());
                    ch.setTranslationY(old.top - ch.getTop());
                    ch.setScaleX(sx);
                    ch.setScaleY(sy);
                    ch.setAlpha(1f);
                    ch.animate().translationX(0f).translationY(0f).scaleX(1f).scaleY(1f)
                            .setDuration(DURATION).setInterpolator(SPRING)
                            .withEndAction(() -> resetTransform(ch)).start();
                } else {
                    ch.setPivotX(w / 2f);
                    ch.setPivotY(h / 2f);
                    ch.setTranslationX(0f);
                    ch.setTranslationY(0f);
                    ch.setScaleX(0.86f);
                    ch.setScaleY(0.86f);
                    ch.setAlpha(0f);
                    ch.animate().alpha(1f).scaleX(1f).scaleY(1f)
                            .setDuration(DURATION).setInterpolator(EASE)
                            .withEndAction(() -> resetTransform(ch)).start();
                }
            }
        });
    }

    private static void resetTransform(View v) {
        v.setTranslationX(0f);
        v.setTranslationY(0f);
        // Учитываем «масштаб покоя» выделенной ячейки (Press.setRestScale)
        Object rest = v.getTag(com.premiumlab.galleryx.R.id.press_rest);
        float s = rest instanceof Float ? (Float) rest : 1f;
        v.setScaleX(s);
        v.setScaleY(s);
        v.setAlpha(1f);
        v.setPivotX(v.getWidth() / 2f);
        v.setPivotY(v.getHeight() / 2f);
    }
}
