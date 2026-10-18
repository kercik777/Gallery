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
    private static final float MAX_ZOOM = 8f;
    /** Зум двойным тапом относительно вписанного размера. */
    private static final float MID_ZOOM = 2.5f;
    private static final float ZOOM_EPS = 0.02f;

    public interface TapListener {
        void onSingleTap();
    }

    /** Кручение двумя пальцами: картинка довернулась до ближайших 90°. */
    public interface RotateListener {
        /**
         * @param degreesClockwise на сколько повернуть файл (90/180/270)
         *        по часовой стрелке. Если слушатель не может применить
         *        поворот (видео, сейф, корзина…) — вернуть false, и
         *        предпросмотр красиво уедет обратно в исходное положение.
         */
        boolean onRotateSnapped(ZoomableImageView view, int degreesClockwise);
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
    private RotateListener rotateListener;
    /** Накопленный угол кручения двумя пальцами, градусы. */
    private float twistDeg = 0f;
    private float lastTwistAngle = 0f;
    private boolean twistActive = false;
    private ValueAnimator twistAnimator;
    /** Режим двух пальцев: 0 — нет, 1 — ещё неясно (кручение или зум), 2 — кручение, 3 — зум. */
    private int twoFingerMode = 0;
    private float spanStart = 0f;
    private float scaleAtTwistStart = 1f;
    private final Matrix matrixAtTwistStart = new Matrix();
    private ValueAnimator zoomAnimator;
    private VelocityTracker velocityTracker;

    private float lastX = 0f, lastY = 0f;
    private float downX = 0f, downY = 0f;
    private boolean dragging = false;
    private float downRawX, downRawY;
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

    public void setRotateListener(RotateListener l) {
        rotateListener = l;
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
        if (matrixListener != null) matrixListener.run();
    }

    private Runnable matrixListener;

    /** Уведомление о любом изменении матрицы (зум/панорама) — видео повторяет её. */
    public void setMatrixListener(Runnable l) {
        matrixListener = l;
    }

    /** Текущий пользовательский зум относительно вписанного размера. */
    private float currentZoom() {
        suppMatrix.getValues(matrixValues);
        // При повороте MSCALE_X = масштаб·cos(угол) — считаем честный масштаб
        return (float) Math.hypot(matrixValues[Matrix.MSCALE_X],
                matrixValues[Matrix.MSKEW_Y]);
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
            // Новое касание прерывает доворот/откат — иначе предпросмотр
            // могло «зависнуть» повёрнутым
            cancelTwistAnimation();
            downX = lastX = event.getX();
            downY = lastY = event.getY();
            // Свайп закрытия считаем в ЭКРАННЫХ координатах: сама view во время жеста
            // двигается и масштабируется, и локальные координаты «плывут» за ней
            // (отсюда дрожание). Экранные — стабильны.
            downRawX = event.getRawX();
            downRawY = event.getRawY();
            dragging = false;
            dragCancelled = false;
            if (velocityTracker == null) velocityTracker = VelocityTracker.obtain();
            else velocityTracker.clear();
        }
        if (velocityTracker != null) {
            MotionEvent raw = MotionEvent.obtain(event);
            raw.setLocation(event.getRawX(), event.getRawY());
            velocityTracker.addMovement(raw);
            raw.recycle();
        }

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
                // Начало двух пальцев: пока не ясно — кручение или зум.
                // Запоминаем угол, расстояние и матрицу, чтобы проигравшую
                // составляющую можно было чисто откатить.
                if (event.getPointerCount() == 2) {
                    cancelTwistAnimation();
                    lastTwistAngle = pointerAngle(event);
                    spanStart = pointerSpan(event);
                    scaleAtTwistStart = currentZoom();
                    matrixAtTwistStart.set(suppMatrix);
                    twistDeg = 0f;
                    twistActive = true;
                    twoFingerMode = 1;
                }
                return true;

            case MotionEvent.ACTION_POINTER_UP:
                // Один палец убрали — кручение завершено: доворот/откат
                if (twistActive) {
                    if (twoFingerMode == 2) finishTwist();
                    twistActive = false;
                    twoFingerMode = 0;
                }
                return true;

            case MotionEvent.ACTION_MOVE:
                // Два пальца: сначала определяем намерение — кручение или
                // зум, — и дальше ведём только его. Раньше детектор зума
                // работал параллельно, и медленное кручение «сильно
                // увеличивало» картинку.
                if (twistActive && event.getPointerCount() == 2) {
                    float ang = pointerAngle(event);
                    float delta = ang - lastTwistAngle;
                    // Нормализация скачка через ±180°
                    while (delta > 180f) delta -= 360f;
                    while (delta < -180f) delta += 360f;
                    lastTwistAngle = ang;
                    twistDeg += delta;
                    float spanFactor = spanStart > 0f
                            ? pointerSpan(event) / spanStart : 1f;
                    if (twoFingerMode == 1) {
                        if (Math.abs(twistDeg) >= 14f) {
                            // Победило кручение: откатываем успевший
                            // примениться зум и ставим чистый поворот
                            twoFingerMode = 2;
                            suppMatrix.set(matrixAtTwistStart);
                            suppMatrix.postRotate(twistDeg,
                                    getWidth() / 2f, getHeight() / 2f);
                            apply();
                        } else if (spanFactor > 1.22f || spanFactor < 0.82f) {
                            // Победил зум: поворот не применялся — просто
                            // дальше работает обычный детектор масштаба
                            twoFingerMode = 3;
                        }
                        disallowParentIntercept(true);
                        return true;
                    }
                    if (twoFingerMode == 2) {
                        if (delta != 0f) {
                            suppMatrix.postRotate(delta,
                                    getWidth() / 2f, getHeight() / 2f);
                        }
                        // Детектор зума параллельно мог изменить масштаб —
                        // возвращаем масштаб к исходному, оставляем поворот
                        float z = currentZoom();
                        if (z > 0f && Math.abs(z - scaleAtTwistStart) > 0.001f) {
                            suppMatrix.postScale(scaleAtTwistStart / z,
                                    scaleAtTwistStart / z,
                                    getWidth() / 2f, getHeight() / 2f);
                        }
                        apply();
                        disallowParentIntercept(true);
                        return true;
                    }
                    // twoFingerMode == 3 — обычный зум, идём дальше вниз
                }
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
                // Оба пальца убрали разом — кручение всё равно завершаем,
                // иначе предпросмотр оставалось повёрнутым навсегда
                if (twistActive) {
                    if (twoFingerMode == 2) finishTwist();
                    twistActive = false;
                    twoFingerMode = 0;
                }
                if (dragging) {
                    dragging = false;
                    float vy = 0f;
                    if (velocityTracker != null) {
                        velocityTracker.computeCurrentVelocity(1000);
                        vy = velocityTracker.getYVelocity();
                    }
                    if (dragListener != null) {
                        dragListener.onDragEnd(this, event.getRawX() - downRawX,
                                event.getRawY() - downRawY, vy);
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
        float totalDx = event.getRawX() - downRawX;
        float totalDy = event.getRawY() - downRawY;
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

    /** Расстояние между двумя пальцами, px. */
    private static float pointerSpan(MotionEvent e) {
        double dx = e.getX(1) - e.getX(0);
        double dy = e.getY(1) - e.getY(0);
        return (float) Math.hypot(dx, dy);
    }

    /** Угол линии между двумя пальцами, градусы (-180..180). */
    private static float pointerAngle(MotionEvent e) {
        double dx = e.getX(1) - e.getX(0);
        double dy = e.getY(1) - e.getY(0);
        return (float) Math.toDegrees(Math.atan2(dy, dx));
    }

    /**
     * Кручение отпустили: красиво доворачиваем до ближайших 90° и просим
     * слушателя применить поворот к файлу. Слабый жест (<30°) — откат.
     */
    private void finishTwist() {
        twistActive = false;
        float total = twistDeg;
        twistDeg = 0f;
        if (rotateListener == null) {
            animateRotationBy(-total, null);
            return;
        }
        int snapped = Math.round(total / 90f) * 90;
        int norm = ((snapped % 360) + 360) % 360;
        float rest = snapped - total;
        if (norm == 0) {
            // До полного круга/исходного не хватило — мягкий откат
            animateRotationBy(rest != 0f ? rest : -total, null);
            return;
        }
        animateRotationBy(rest, () -> {
            boolean applied = rotateListener.onRotateSnapped(this, norm);
            if (!applied) {
                // Повернуть файл нельзя — предпросмотр уезжает обратно
                animateRotationBy(-norm, null);
            }
        });
    }

    /** Плавный доворот/откат предпросмотра на угол delta (градусы). */
    private void animateRotationBy(float delta, Runnable end) {
        cancelTwistAnimation();
        if (delta == 0f) {
            if (end != null) end.run();
            return;
        }
        ValueAnimator a = ValueAnimator.ofFloat(0f, delta);
        a.setDuration(220);
        a.setInterpolator(new AccelerateDecelerateInterpolator());
        final float[] prev = {0f};
        a.addUpdateListener(anim -> {
            float v = (float) anim.getAnimatedValue();
            suppMatrix.postRotate(v - prev[0], getWidth() / 2f, getHeight() / 2f);
            prev[0] = v;
            apply();
        });
        a.addListener(new AnimatorListenerAdapter() {
            @Override
            public void onAnimationEnd(Animator anim) {
                if (twistAnimator == anim) twistAnimator = null;
                if (end != null) end.run();
            }
        });
        twistAnimator = a;
        a.start();
    }

    private void cancelTwistAnimation() {
        if (twistAnimator != null) {
            twistAnimator.cancel();
            twistAnimator = null;
        }
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
        cancelTwistAnimation();
        twistActive = false;
        twoFingerMode = 0;
        twistDeg = 0f;
        suppMatrix.reset();
        apply();
    }

    public boolean isZoomed() {
        return currentZoom() > 1f + ZOOM_EPS;
    }

    /** Прямоугольник, который картинка реально занимает на экране (в координатах view). */
    public boolean displayedRect(RectF out) {
        Drawable d = getDrawable();
        if (d == null || d.getIntrinsicWidth() <= 0 || getWidth() <= 0) return false;
        out.set(0, 0, d.getIntrinsicWidth(), d.getIntrinsicHeight());
        getImageMatrix().mapRect(out);
        return true;
    }

    /**
     * Скругление углов картинки (используется при жесте закрытия): 0 — без скругления.
     * Обрезка идёт по реальным границам картинки, а не по границам view.
     */
    public void setCornerRadius(float radius) {
        if (radius <= 0f && clipRect == null) {
            cornerRadius = 0f;
            if (getClipToOutline()) {
                setClipToOutline(false);
                setOutlineProvider(android.view.ViewOutlineProvider.BACKGROUND);
            }
            return;
        }
        cornerRadius = radius;
        ensureOutline();
        invalidateOutline();
    }

    /**
     * Обрезка произвольным прямоугольником со скруглением (в координатах view) — для
     * «перетекания» между квадратной миниатюрой сетки и полным кадром при открытии
     * и закрытии просмотрщика. {@code null} — снять обрезку.
     */
    public void setClipRect(RectF rect, float radius) {
        if (rect == null) {
            clipRect = null;
            setCornerRadius(cornerRadius);
            invalidateOutline();
            return;
        }
        if (clipRect == null) clipRect = new RectF();
        clipRect.set(rect);
        clipRadius = radius;
        ensureOutline();
        invalidateOutline();
    }

    private void ensureOutline() {
        if (getClipToOutline()) return;
        setOutlineProvider(new android.view.ViewOutlineProvider() {
            private final RectF r = new RectF();

            @Override
            public void getOutline(android.view.View v, android.graphics.Outline outline) {
                if (clipRect != null) {
                    outline.setRoundRect(Math.round(clipRect.left), Math.round(clipRect.top),
                            Math.round(clipRect.right), Math.round(clipRect.bottom),
                            Math.max(0f, clipRadius));
                    return;
                }
                if (!displayedRect(r)) {
                    outline.setRoundRect(0, 0, v.getWidth(), v.getHeight(), cornerRadius);
                    return;
                }
                outline.setRoundRect(Math.round(r.left), Math.round(r.top),
                        Math.round(r.right), Math.round(r.bottom), cornerRadius);
            }
        });
        setClipToOutline(true);
    }

    private float cornerRadius = 0f;
    private RectF clipRect;
    private float clipRadius;
}
