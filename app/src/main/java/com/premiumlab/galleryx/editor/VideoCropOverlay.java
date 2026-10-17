package com.premiumlab.galleryx.editor;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RectF;
import android.util.AttributeSet;
import android.view.MotionEvent;
import android.view.View;

/**
 * Рамка кадрирования поверх видео: затемнение вне кадра, сетка третей, угловые маркеры.
 * Перетаскивание внутри — сдвиг, за углы/края — изменение размера (с учётом пропорции).
 * Работает в нормализованных координатах (0..1) относительно прямоугольника видео.
 */
public class VideoCropOverlay extends View {

    public interface Listener {
        void onCropChanged(RectF normalized);
    }

    private final Paint dim = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint frame = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint grid = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint corner = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF videoRect = new RectF();
    private final RectF crop = new RectF(0, 0, 1, 1); // нормализованный
    private final RectF tmp = new RectF();
    private final Path path = new Path();
    private final float d;
    private float aspect = 0f; // 0 — свободно
    private Listener listener;
    private int drag = 0; // 0 нет, 1 сдвиг, 2..5 углы (lt, rt, rb, lb), 6..9 края (l,t,r,b)
    private float lastX, lastY;

    public VideoCropOverlay(Context c) {
        this(c, null);
    }

    public VideoCropOverlay(Context c, AttributeSet a) {
        super(c, a);
        d = c.getResources().getDisplayMetrics().density;
        dim.setColor(0x99000000);
        frame.setStyle(Paint.Style.STROKE);
        frame.setStrokeWidth(1.5f * d);
        frame.setColor(Color.WHITE);
        grid.setStyle(Paint.Style.STROKE);
        grid.setStrokeWidth(d * 0.8f);
        grid.setColor(0x66FFFFFF);
        corner.setStyle(Paint.Style.STROKE);
        corner.setStrokeWidth(3f * d);
        corner.setStrokeCap(Paint.Cap.ROUND);
        corner.setColor(Color.WHITE);
    }

    public void setListener(Listener l) {
        listener = l;
    }

    /** Прямоугольник, который реально занимает видео на экране (координаты этой view). */
    public void setVideoRect(RectF r) {
        videoRect.set(r);
        invalidate();
    }

    public void setCrop(RectF normalized) {
        if (normalized == null) crop.set(0, 0, 1, 1);
        else crop.set(normalized);
        invalidate();
    }

    public RectF getCrop() {
        return new RectF(crop);
    }

    public boolean isFull() {
        return crop.left <= 0.001f && crop.top <= 0.001f && crop.right >= 0.999f && crop.bottom >= 0.999f;
    }

    /** Пропорция w/h (0 — свободно). Кадр пересчитывается по центру максимального размера. */
    public void setAspect(float a) {
        aspect = a;
        if (a > 0 && videoRect.width() > 0) {
            float vw = videoRect.width(), vh = videoRect.height();
            float w = vw, h = w / a;
            if (h > vh) {
                h = vh;
                w = h * a;
            }
            float nw = w / vw, nh = h / vh;
            crop.set((1 - nw) / 2f, (1 - nh) / 2f, (1 + nw) / 2f, (1 + nh) / 2f);
        } else if (a == 0) {
            crop.set(0, 0, 1, 1);
        }
        invalidate();
        if (listener != null) listener.onCropChanged(new RectF(crop));
    }

    private void screenRect(RectF out) {
        out.set(videoRect.left + crop.left * videoRect.width(),
                videoRect.top + crop.top * videoRect.height(),
                videoRect.left + crop.right * videoRect.width(),
                videoRect.top + crop.bottom * videoRect.height());
    }

    @Override
    protected void onDraw(Canvas c) {
        if (videoRect.width() <= 0) return;
        screenRect(tmp);
        // затемнение вне кадра
        path.reset();
        path.addRect(videoRect, Path.Direction.CW);
        path.addRect(tmp, Path.Direction.CCW);
        c.drawPath(path, dim);
        // сетка третей
        for (int i = 1; i < 3; i++) {
            float x = tmp.left + tmp.width() * i / 3f;
            float y = tmp.top + tmp.height() * i / 3f;
            c.drawLine(x, tmp.top, x, tmp.bottom, grid);
            c.drawLine(tmp.left, y, tmp.right, y, grid);
        }
        c.drawRect(tmp, frame);
        // уголки
        float l = Math.min(18f * d, Math.min(tmp.width(), tmp.height()) / 3f);
        float o = 1.5f * d;
        c.drawLine(tmp.left - o, tmp.top, tmp.left + l, tmp.top, corner);
        c.drawLine(tmp.left, tmp.top - o, tmp.left, tmp.top + l, corner);
        c.drawLine(tmp.right + o, tmp.top, tmp.right - l, tmp.top, corner);
        c.drawLine(tmp.right, tmp.top - o, tmp.right, tmp.top + l, corner);
        c.drawLine(tmp.right + o, tmp.bottom, tmp.right - l, tmp.bottom, corner);
        c.drawLine(tmp.right, tmp.bottom + o, tmp.right, tmp.bottom - l, corner);
        c.drawLine(tmp.left - o, tmp.bottom, tmp.left + l, tmp.bottom, corner);
        c.drawLine(tmp.left, tmp.bottom + o, tmp.left, tmp.bottom - l, corner);
    }

    @Override
    public boolean onTouchEvent(MotionEvent e) {
        if (videoRect.width() <= 0) return false;
        float x = e.getX(), y = e.getY();
        switch (e.getActionMasked()) {
            case MotionEvent.ACTION_DOWN: {
                screenRect(tmp);
                float t = 28f * d;
                drag = 0;
                if (near(x, y, tmp.left, tmp.top, t)) drag = 2;
                else if (near(x, y, tmp.right, tmp.top, t)) drag = 3;
                else if (near(x, y, tmp.right, tmp.bottom, t)) drag = 4;
                else if (near(x, y, tmp.left, tmp.bottom, t)) drag = 5;
                else if (Math.abs(x - tmp.left) < t && y > tmp.top && y < tmp.bottom) drag = 6;
                else if (Math.abs(y - tmp.top) < t && x > tmp.left && x < tmp.right) drag = 7;
                else if (Math.abs(x - tmp.right) < t && y > tmp.top && y < tmp.bottom) drag = 8;
                else if (Math.abs(y - tmp.bottom) < t && x > tmp.left && x < tmp.right) drag = 9;
                else if (tmp.contains(x, y)) drag = 1;
                lastX = x;
                lastY = y;
                if (drag != 0) getParent().requestDisallowInterceptTouchEvent(true);
                return drag != 0;
            }
            case MotionEvent.ACTION_MOVE: {
                if (drag == 0) return false;
                float dx = (x - lastX) / videoRect.width();
                float dy = (y - lastY) / videoRect.height();
                lastX = x;
                lastY = y;
                applyDrag(dx, dy);
                invalidate();
                return true;
            }
            case MotionEvent.ACTION_UP:
            case MotionEvent.ACTION_CANCEL:
                if (drag != 0 && listener != null) listener.onCropChanged(new RectF(crop));
                drag = 0;
                return true;
            default:
                return false;
        }
    }

    private boolean near(float x, float y, float px, float py, float t) {
        return Math.abs(x - px) < t && Math.abs(y - py) < t;
    }

    private void applyDrag(float dx, float dy) {
        float minW = Math.max(0.08f, 40f * d / videoRect.width());
        float minH = Math.max(0.08f, 40f * d / videoRect.height());
        if (drag == 1) {
            float nx = Math.max(0f, Math.min(1f - crop.width(), crop.left + dx));
            float ny = Math.max(0f, Math.min(1f - crop.height(), crop.top + dy));
            crop.offsetTo(nx, ny);
            return;
        }
        RectF r = new RectF(crop);
        boolean left = drag == 2 || drag == 5 || drag == 6;
        boolean right = drag == 3 || drag == 4 || drag == 8;
        boolean top = drag == 2 || drag == 3 || drag == 7;
        boolean bottom = drag == 4 || drag == 5 || drag == 9;
        if (left) r.left = Math.max(0f, Math.min(r.right - minW, r.left + dx));
        if (right) r.right = Math.min(1f, Math.max(r.left + minW, r.right + dx));
        if (top) r.top = Math.max(0f, Math.min(r.bottom - minH, r.top + dy));
        if (bottom) r.bottom = Math.min(1f, Math.max(r.top + minH, r.bottom + dy));
        if (aspect > 0) {
            // Пропорция в экранных пикселях: w_px / h_px = aspect
            float vw = videoRect.width(), vh = videoRect.height();
            float wpx = r.width() * vw, hpx = r.height() * vh;
            if (left || right) {
                hpx = wpx / aspect;
            } else {
                wpx = hpx * aspect;
            }
            float nw = wpx / vw, nh = hpx / vh;
            if (nw > 1f) {
                nw = 1f;
                nh = (vw / aspect) / vh;
            }
            if (nh > 1f) {
                nh = 1f;
                nw = (vh * aspect) / vw;
            }
            // фиксируем противоположный угол/край
            float ax = left ? r.right : r.left;
            float ay = top ? r.bottom : r.top;
            if (!left && !right) ax = r.centerX() - nw / 2f;
            if (!top && !bottom) ay = r.centerY() - nh / 2f;
            float nl = left ? ax - nw : ax;
            float nt = top ? ay - nh : ay;
            r.set(nl, nt, nl + nw, nt + nh);
            // в пределах видео
            if (r.left < 0) r.offset(-r.left, 0);
            if (r.top < 0) r.offset(0, -r.top);
            if (r.right > 1) r.offset(1 - r.right, 0);
            if (r.bottom > 1) r.offset(0, 1 - r.bottom);
        }
        crop.set(r);
    }
}
