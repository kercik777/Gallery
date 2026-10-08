package com.premiumlab.galleryx.ui.view;

import android.animation.Animator;
import android.animation.AnimatorListenerAdapter;
import android.animation.ValueAnimator;
import android.annotation.SuppressLint;
import android.content.Context;
import android.graphics.Matrix;
import android.graphics.RectF;
import android.graphics.drawable.Drawable;
import android.util.AttributeSet;
import android.view.GestureDetector;
import android.view.MotionEvent;
import android.view.ScaleGestureDetector;
import android.view.VelocityTracker;
import android.view.ViewConfiguration;
import android.view.ViewParent;
import android.view.animation.AccelerateDecelerateInterpolator;

import androidx.appcompat.widget.AppCompatImageView;

/**
 * ImageView с зумом двумя пальцами, двойным тапом, панорамированием
 * и вертикальным свайпом (закрыть / показать свойства).
 *
 * Сначала строится базовая матрица вписывания (fit-center), пользовательский
 * зум накладывается поверх неё — фото всегда открывается целиком.
 *
 * При масштабе 1x горизонтальные свайпы отдаются ViewPager2, а вертикальные
 * уходят в {@link DragListener} (просмотрщик рисует «улетание» картинки).
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

    /** Вертикальное перетаскивание невзумленной картинки. */
    public interface DragListener {
        void onDragStart(ZoomableImageView view);

        /** dx/dy — суммарное смещение от точки касания. */
        void onDrag(ZoomableImageView view, float dx, float dy);

        /** velocityY — скорость по вертикали (px/s) в момент отпускания. */
        void onDragEnd(ZoomableImageView view, float dx, float dy, float velocityY);
    }

    private final Matrix baseMatrix = new Matrix();
    private final Matrix suppMatrix = new Matrix();
    private final Matrix drawMatrix = new Matrix();
    private final float[] matrixValues = new float[9];
    private final RectF displayRect = new RectF();

    private ScaleGestureDetector scaleDetector;
    private GestureDetector gestureDetector;
    private TapListener tapListener;
    private DragListener dragListener;
    private ValueAnimator zoomAnimator;
    private VelocityTracker velocityTracker;

    private float lastX = 0f, lastY = 0f;
    private float downX = 0f, downY = 0f;
    private boolean dragging = false;
    private boolean dragCancelled = false;
    private int touchSlop;

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

    public void setDragListener(DragListener l) {
        dragListener = l;
    }

    private void init() {
        setScaleType(ScaleType.MATRIX);
        setImageMatrix(new Matrix());
        touchSlop = ViewConfiguration.get(getContext()).getScaledTouchSlop();
        setOnTouchListener((v, event) -> onTouchEventInternal(event));
    }

    /** Создаёт детекторы жестов (идемпотентно, вызывается при привязке страницы). */
    public void setupGestureDetectors(Context context) {
        if (scaleDetector == null) {
            scaleDetector = new ScaleGestureDetector(context,
                    new ScaleGestureDetector.SimpleOnScaleGestureListener() {
                        @Override
                        public boolean onScaleBegin(ScaleGestureDetector d) {
                            cancelZoomAnimation();
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
            // «Быстрый зум» (двойной тап + протяжка) конфликтует с обычным
            // двойным тапом — отключаем.
            scaleDetector.setQuickScaleEnabled(false);
        }
        if (gestureDetector == null) {
            gestureDetector = new GestureDetector(context,
                    new GestureDetector.SimpleOnGestureListener() {
                        @Override
                        public boolean onDown(MotionEvent e) {
                            return true;
                        }

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

    // ---------- Базовое вписывание ----------

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
        float[] fix = translationFix(suppMatrix);
        if (fix[0] != 0f || fix[1] != 0f) suppMatrix.postTranslate(fix[0], fix[1]);
    }

    /**
     * Считает поправку сдвига (dx, dy), которую нужно добавить к матрице,
     * чтобы изображение не уезжало за края и центрировалось, если меньше view.
     */
    private float[] translationFix(Matrix supp) {
        float[] out = new float[]{0f, 0f};
        Drawable d = getDrawable();
        if (d == null || d.getIntrinsicWidth() <= 0 || getWidth() <= 0) return out;

        displayRect.set(0, 0, d.getIntrinsicWidth(), d.getIntrinsicHeight());
        Matrix m = new Matrix(baseMatrix);
        m.postConcat(supp);
        m.mapRect(displayRect);

        float vw = getWidth();
        float vh = getHeight();

        if (displayRect.width() <= vw + 1f) {
            out[0] = (vw - displayRect.width()) / 2f - displayRect.left;
        } else if (displayRect.left > 0f) {
            out[0] = -displayRect.left;
        } else if (displayRect.right < vw) {
            out[0] = vw - displayRect.right;
        }

        if (displayRect.height() <= vh + 1f) {
            out[1] = (vh - displayRect.height()) / 2f - displayRect.top;
        } else if (displayRect.top > 0f) {
            out[1] = -displayRect.top;
        } else if (displayRect.bottom < vh) {
            out[1] = vh - displayRect.bottom;
        }
        return out;
    }

    // ---------- Касания ----------

    @SuppressLint("ClickableViewAccessibility")
    private boolean onTouchEventInternal(MotionEvent event) {
        int action = event.getActionMasked();

        // Анимацию зума прерываем ДО передачи события детекторам: иначе
        // двойной тап (срабатывает на втором ACTION_DOWN) был бы тут же отменён.
        if (action == MotionEvent.ACTION_DOWN) {
            cancelZoomAnimation();
            downX = lastX = event.getX();
            downY = lastY = event.getY();
            dragging = false;
            dragCancelled = false;
            if (velocityTracker == null) velocityTracker = VelocityTracker.obtain();
            else velocityTracker.clear();
        }
        if (velocityTracker != null) velocityTracker.addMovement(event);

        if (gestureDetector != null) gestureDetector.onTouchEvent(event);
        if (scaleDetector != null) scaleDetector.onTouchEvent(event);

        switch (action) {
            case MotionEvent.ACTION_DOWN:
                return true;

            case MotionEvent.ACTION_POINTER_DOWN:
                // Второй палец — это зум, а не свайп
                if (dragging) {
                    dragging = false;
                    if (dragListener != null) dragListener.onDragEnd(this, 0f, 0f, 0f);
                }
                dragCancelled = true;
                return true;

            case MotionEvent.ACTION_MOVE:
                if (scaleDetector != null && scaleDetector.isInProgress()) {
                    disallowParentIntercept(true);
                } else if (event.getPointerCount() == 1 && isZoomed()) {
                    float dx = event.getX() - lastX;
                    float dy = event.getY() - lastY;
                    lastX = event.getX();
                    lastY = event.getY();
                    suppMatrix.postTranslate(dx, dy);
                    clampTranslation();
                    apply();
                    disallowParentIntercept(true);
                } else if (event.getPointerCount() == 1) {
                    handleVerticalDrag(event);
                } else {
                    lastX = event.getX();
                    lastY = event.getY();
                }
                return true;

            case MotionEvent.ACTION_UP:
            case MotionEvent.ACTION_CANCEL:
                if (dragging) {
                    dragging = false;
                    float vy = 0f;
                    if (velocityTracker != null) {
                        velocityTracker.computeCurrentVelocity(1000);
                        vy = velocityTracker.getYVelocity();
                    }
                    if (dragListener != null) {
                        dragListener.onDragEnd(this, event.getX() - downX,
                                event.getY() - downY, vy);
                    }
                } else if (zoomAnimator == null || !zoomAnimator.isRunning()) {
                    // Во время анимации двойного тапа матрицу не трогаем
                    if (!isZoomed()) {
                        suppMatrix.reset();
                        apply();
                    } else {
                        clampTranslation();
                        apply();
                    }
                }
                if (velocityTracker != null) {
                    velocityTracker.recycle();
                    velocityTracker = null;
                }
                disallowParentIntercept(false);
                return true;

            default:
                return false;
        }
    }

    /** Вертикальный свайп при масштабе 1x: вниз — закрыть, вверх — свойства. */
    private void handleVerticalDrag(MotionEvent event) {
        float totalDx = event.getX() - downX;
        float totalDy = event.getY() - downY;
        lastX = event.getX();
        lastY = event.getY();
        if (dragListener == null || dragCancelled) return;

        if (!dragging) {
            if (Math.abs(totalDy) > touchSlop && Math.abs(totalDy) > Math.abs(totalDx) * 1.3f) {
                dragging = true;
                disallowParentIntercept(true);
                dragListener.onDragStart(this);
            } else if (Math.abs(totalDx) > touchSlop) {
                // Пользователь листает страницы — вертикальный жест больше не начинаем
                dragCancelled = true;
                return;
            }
        }
        if (dragging) dragListener.onDrag(this, totalDx, totalDy);
    }

    private void disallowParentIntercept(boolean disallow) {
        ViewParent p = getParent();
        if (p != null) p.requestDisallowInterceptTouchEvent(disallow);
    }

    private void cancelZoomAnimation() {
        if (zoomAnimator != null) {
            zoomAnimator.cancel();
            zoomAnimator = null;
        }
    }

    // ---------- Двойной тап ----------

    private void animateDoubleTap(float focusX, float focusY) {
        cancelZoomAnimation();
        if (getDrawable() == null) return;

        final float startZoom = currentZoom();
        final float targetZoom = startZoom > 1f + ZOOM_EPS ? 1f : MID_ZOOM;

        suppMatrix.getValues(matrixValues);
        final float startTx = matrixValues[Matrix.MTRANS_X];
        final float startTy = matrixValues[Matrix.MTRANS_Y];

        // Целевая матрица: зум вокруг точки тапа, сразу с поправкой на края —
        // тогда в конце анимации картинка не «дёргается».
        Matrix target = new Matrix();
        target.postScale(targetZoom, targetZoom, focusX, focusY);
        float[] fix = translationFix(target);
        target.postTranslate(fix[0], fix[1]);
        float[] tv = new float[9];
        target.getValues(tv);
        final float endTx = tv[Matrix.MTRANS_X];
        final float endTy = tv[Matrix.MTRANS_Y];

        ValueAnimator a = ValueAnimator.ofFloat(0f, 1f);
        a.setDuration(240);
        a.setInterpolator(new AccelerateDecelerateInterpolator());
        a.addUpdateListener(animation -> {
            float t = (float) animation.getAnimatedValue();
            float z = startZoom + (targetZoom - startZoom) * t;
            float tx = startTx + (endTx - startTx) * t;
            float ty = startTy + (endTy - startTy) * t;
            suppMatrix.setScale(z, z);
            suppMatrix.postTranslate(tx, ty);
            apply();
        });
        a.addListener(new AnimatorListenerAdapter() {
            @Override
            public void onAnimationEnd(Animator anim) {
                if (zoomAnimator == anim) zoomAnimator = null;
                if (targetZoom <= 1f) suppMatrix.reset();
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
        cancelZoomAnimation();
        suppMatrix.reset();
        apply();
    }

    public boolean isZoomed() {
        return currentZoom() > 1f + ZOOM_EPS;
    }
}
