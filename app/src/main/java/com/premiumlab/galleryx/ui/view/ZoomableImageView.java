package com.premiumlab.galleryx.ui.view;

import android.annotation.SuppressLint;
import android.content.Context;
import android.graphics.Matrix;
import android.graphics.RectF;
import android.graphics.drawable.Drawable;
import android.util.AttributeSet;
import android.view.GestureDetector;
import android.view.MotionEvent;
import android.view.ScaleGestureDetector;
import android.view.ViewParent;
import android.view.animation.AccelerateDecelerateInterpolator;

import androidx.appcompat.widget.AppCompatImageView;

import android.animation.ValueAnimator;

/**
 * ImageView с зумом двумя пальцами, двойным тапом и панорамированием.
 *
 * Ключевая особенность: сначала строится базовая матрица вписывания
 * (fit-center) изображения в границы view, а пользовательский зум
 * накладывается поверх неё. Поэтому фотография всегда открывается
 * целиком, а не в пиксельном масштабе 1:1.
 *
 * При масштабе 1x не перехватывает горизонтальные свайпы (работает ViewPager2).
 */
public class ZoomableImageView extends AppCompatImageView {

    /** Максимальный зум относительно вписанного размера. */
    private static final float MAX_ZOOM = 6f;
    /** Зум двойным тапом относительно вписанного размера. */
    private static final float MID_ZOOM = 2.5f;
    private static final float ZOOM_EPS = 0.02f;

    public interface TapListener {
        void onSingleTap();
    }

    private final Matrix baseMatrix = new Matrix();
    private final Matrix suppMatrix = new Matrix();
    private final Matrix drawMatrix = new Matrix();
    private final float[] matrixValues = new float[9];
    private final RectF displayRect = new RectF();

    private ScaleGestureDetector scaleDetector;
    private GestureDetector gestureDetector;
    private TapListener tapListener;
    private ValueAnimator zoomAnimator;

    private float lastX = 0f, lastY = 0f;

    public ZoomableImageView(Context context) {
        super(context);
        init();
    }

    public ZoomableImageView(Context context, AttributeSet attrs) {
        super(context, attrs);
        init();
    }

    public ZoomableImageView(Context context, AttributeSet attrs, int defStyle) {
        super(context, attrs, defStyle);
        init();
    }

    public void setTapListener(TapListener l) {
        tapListener = l;
    }

    private void init() {
        setScaleType(ScaleType.MATRIX);
        setImageMatrix(new Matrix());
        setOnTouchListener((v, event) -> onTouchEventInternal(event));
    }

    /** Создаёт детекторы жестов (идемпотентно, вызывается при привязке страницы). */
    public void setupGestureDetectors(Context context) {
        if (scaleDetector != null && gestureDetector != null) return;
        if (scaleDetector == null) {
            scaleDetector = new ScaleGestureDetector(context,
                    new ScaleGestureDetector.SimpleOnScaleGestureListener() {
                        @Override
                        public boolean onScaleBegin(ScaleGestureDetector d) {
                            if (zoomAnimator != null) zoomAnimator.cancel();
                            return true;
                        }

                        @Override
                        public boolean onScale(ScaleGestureDetector d) {
                            float factor = d.getScaleFactor();
                            if (factor <= 0f) return true;
                            suppMatrix.postScale(factor, factor, d.getFocusX(), d.getFocusY());
                            clampScale();
                            apply();
                            return true;
                        }
                    });
        }
        if (gestureDetector == null) {
            gestureDetector = new GestureDetector(context,
                    new GestureDetector.SimpleOnGestureListener() {
                        @Override
                        public boolean onDoubleTap(MotionEvent e) {
                            animateDoubleTap(e.getX(), e.getY());
                            return true;
                        }

                        @Override
                        public boolean onSingleTapConfirmed(MotionEvent e) {
                            if (tapListener != null) tapListener.onSingleTap();
                            return true;
                        }
                    });
        }
    }

    // ---------- Базовое вписивание ----------

    @Override
    public void setImageDrawable(Drawable drawable) {
        super.setImageDrawable(drawable);
        baseMatrix.reset();
        suppMatrix.reset();
        fitBase();
        apply();
    }

    @Override
    protected void onSizeChanged(int w, int h, int oldw, int oldh) {
        super.onSizeChanged(w, h, oldw, oldh);
        fitBase();
        apply();
    }

    /** Строит базовую матрицу вписывания по центру (fit-center). */
    private void fitBase() {
        Drawable d = getDrawable();
        if (d == null || d.getIntrinsicWidth() <= 0 || d.getIntrinsicHeight() <= 0) return;
        if (getWidth() <= 0 || getHeight() <= 0) return;

        float dw = d.getIntrinsicWidth();
        float dh = d.getIntrinsicHeight();
        float vw = getWidth();
        float vh = getHeight();
        float baseScale = Math.min(vw / dw, vh / dh);

        baseMatrix.reset();
        baseMatrix.setScale(baseScale, baseScale);
        float tw = (vw - dw * baseScale) / 2f;
        float th = (vh - dh * baseScale) / 2f;
        baseMatrix.postTranslate(tw, th);
    }

    // ---------- Матрицы ----------

    private void apply() {
        drawMatrix.set(baseMatrix);
        drawMatrix.postConcat(suppMatrix);
        setImageMatrix(drawMatrix);
        invalidate();
    }

    /** Текущий пользовательский зум относительно вписанного размера. */
    private float currentZoom() {
        suppMatrix.getValues(matrixValues);
        return matrixValues[Matrix.MSCALE_X];
    }

    private void clampScale() {
        float zoom = currentZoom();
        if (zoom > MAX_ZOOM) {
            float correction = MAX_ZOOM / zoom;
            suppMatrix.postScale(correction, correction,
                    getWidth() / 2f, getHeight() / 2f);
        } else if (zoom < 1f) {
            float correction = 1f / zoom;
            suppMatrix.postScale(correction, correction,
                    getWidth() / 2f, getHeight() / 2f);
        }
        clampTranslation();
    }

    /** Не даёт утащить изображение за пределы экрана. */
    private void clampTranslation() {
        Drawable d = getDrawable();
        if (d == null || d.getIntrinsicWidth() <= 0 || getWidth() <= 0) return;

        displayRect.set(0, 0, d.getIntrinsicWidth(), d.getIntrinsicHeight());
        drawMatrix.set(baseMatrix);
        drawMatrix.postConcat(suppMatrix);
        drawMatrix.mapRect(displayRect);

        float vx = 0f, vy = 0f;
        float vw = getWidth();
        float vh = getHeight();

        if (displayRect.width() <= vw + 1f) {
            vx = (vw - displayRect.width()) / 2f - displayRect.left;
        } else if (displayRect.left > 0f) {
            vx = -displayRect.left;
        } else if (displayRect.right < vw) {
            vx = vw - displayRect.right;
        }

        if (displayRect.height() <= vh + 1f) {
            vy = (vh - displayRect.height()) / 2f - displayRect.top;
        } else if (displayRect.top > 0f) {
            vy = -displayRect.top;
        } else if (displayRect.bottom < vh) {
            vy = vh - displayRect.bottom;
        }

        if (vx != 0f || vy != 0f) suppMatrix.postTranslate(vx, vy);
    }

    // ---------- Касания ----------

    @SuppressLint("ClickableViewAccessibility")
    private boolean onTouchEventInternal(MotionEvent event) {
        if (gestureDetector != null) gestureDetector.onTouchEvent(event);
        if (scaleDetector != null) scaleDetector.onTouchEvent(event);

        switch (event.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
                lastX = event.getX();
                lastY = event.getY();
                if (zoomAnimator != null) zoomAnimator.cancel();
                return true;

            case MotionEvent.ACTION_MOVE:
                if (scaleDetector != null && scaleDetector.isInProgress()) {
                    // Зум обрабатывается в onScale (включая сдвиг фокуса)
                    ViewParent p = getParent();
                    if (p != null) p.requestDisallowInterceptTouchEvent(true);
                } else if (event.getPointerCount() == 1 && currentZoom() > 1f + ZOOM_EPS) {
                    float dx = event.getX() - lastX;
                    float dy = event.getY() - lastY;
                    lastX = event.getX();
                    lastY = event.getY();
                    suppMatrix.postTranslate(dx, dy);
                    clampTranslation();
                    apply();
                    ViewParent parent = getParent();
                    if (parent != null) parent.requestDisallowInterceptTouchEvent(true);
                } else {
                    lastX = event.getX();
                    lastY = event.getY();
                }
                return true;

            case MotionEvent.ACTION_UP:
            case MotionEvent.ACTION_CANCEL:
                if (currentZoom() <= 1f + ZOOM_EPS) {
                    reset();
                } else {
                    clampTranslation();
                    apply();
                }
                ViewParent parent = getParent();
                if (parent != null) parent.requestDisallowInterceptTouchEvent(false);
                return true;

            default:
                return false;
        }
    }

    // ---------- Двойной тап ----------

    private void animateDoubleTap(float focusX, float focusY) {
        if (zoomAnimator != null) zoomAnimator.cancel();

        float startZoom = currentZoom();
        float targetZoom;
        if (startZoom > 1f + ZOOM_EPS) {
            targetZoom = 1f;
        } else {
            targetZoom = MID_ZOOM;
        }

        suppMatrix.getValues(matrixValues);
        final float startTx = matrixValues[Matrix.MTRANS_X];
        final float startTy = matrixValues[Matrix.MTRANS_Y];

        // Целевая матрица: зум вокруг точки тапа
        Matrix target = new Matrix();
        target.postScale(targetZoom, targetZoom, focusX, focusY);
        float[] tv = new float[9];
        target.getValues(tv);

        ValueAnimator a = ValueAnimator.ofFloat(0f, 1f);
        a.setDuration(220);
        a.setInterpolator(new AccelerateDecelerateInterpolator());
        a.addUpdateListener(animation -> {
            float t = (float) animation.getAnimatedValue();
            float z = startZoom + (targetZoom - startZoom) * t;
            float tx = startTx + (tv[Matrix.MTRANS_X] - startTx) * t;
            float ty = startTy + (tv[Matrix.MTRANS_Y] - startTy) * t;
            suppMatrix.setScale(z, z);
            suppMatrix.postTranslate(tx, ty);
            apply();
        });
        a.addListener(new android.animation.AnimatorListenerAdapter() {
            @Override
            public void onAnimationEnd(android.animation.Animator anim) {
                clampTranslation();
                apply();
            }
        });
        zoomAnimator = a;
        a.start();
    }

    // ---------- Публичное API ----------

    /** Сброс зума (вызывается при привязке новой страницы). */
    public void reset() {
        if (zoomAnimator != null) zoomAnimator.cancel();
        suppMatrix.reset();
        apply();
    }

    public boolean isZoomed() {
        return currentZoom() > 1f + ZOOM_EPS;
    }
}
