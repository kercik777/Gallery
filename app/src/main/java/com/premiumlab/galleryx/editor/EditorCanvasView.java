package com.premiumlab.galleryx.editor;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Matrix;
import android.graphics.Paint;
import android.graphics.RectF;
import android.util.AttributeSet;
import android.view.MotionEvent;
import android.view.View;

/**
 * Холст редактора: показывает превью с правками, принимает жесты кадрирования,
 * рисования и перетаскивания надписей.
 */
public class EditorCanvasView extends View {

    public static final int MODE_VIEW = 0, MODE_CROP = 1, MODE_DOODLE = 2, MODE_TEXT = 3;

    public interface Listener {
        /** Завершён штрих / перемещение / изменение кадра — можно записать в историю. */
        void onEditCommitted();

        void onLabelSelected(int index);

        void onLabelDoubleTap(int index);
    }

    private Bitmap preview;
    private EditState state = new EditState();
    private int mode = MODE_VIEW;
    private Listener listener;

    // Рисование
    private int brushColor = 0xFFFFFFFF;
    private float brushWidth = 0.012f;
    private boolean eraser = false;
    private boolean pencil = false;
    private EditState.Stroke current;

    // Кадр
    private RectF cropEdit; // редактируемый кадр (нормализованный)
    private float cropAspect = 0f; // 0 — свободно
    private int dragZone = ZONE_NONE;
    private float lastX, lastY;
    private static final int ZONE_NONE = 0, ZONE_MOVE = 1, ZONE_L = 2, ZONE_T = 4, ZONE_R = 8,
            ZONE_B = 16;

    // Надписи
    private int selectedLabel = -1;
    private long lastTapTime = 0;
    private boolean dragging = false;

    // Показ оригинала при удержании
    private boolean showOriginal = false;

    private final Matrix fit = new Matrix();
    private final Matrix inv = new Matrix();
    private final Paint dimPaint = new Paint();
    private final Paint gridPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint handlePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final float d;

    public EditorCanvasView(Context ctx) {
        this(ctx, null);
    }

    public EditorCanvasView(Context ctx, AttributeSet attrs) {
        super(ctx, attrs);
        d = ctx.getResources().getDisplayMetrics().density;
        dimPaint.setColor(0x99000000);
        gridPaint.setColor(0x99FFFFFF);
        gridPaint.setStyle(Paint.Style.STROKE);
        gridPaint.setStrokeWidth(d);
        handlePaint.setColor(Color.WHITE);
        handlePaint.setStyle(Paint.Style.STROKE);
        handlePaint.setStrokeWidth(3f * d);
        handlePaint.setStrokeCap(Paint.Cap.ROUND);
    }

    // ---------- API ----------

    public void setPreview(Bitmap b) {
        preview = b;
        invalidate();
    }

    public void setState(EditState s) {
        state = s;
        if (mode == MODE_CROP) cropEdit = s.crop != null ? new RectF(s.crop) : new RectF(0, 0, 1, 1);
        invalidate();
    }

    public EditState getState() {
        return state;
    }

    public void setListener(Listener l) {
        listener = l;
    }

    public void setMode(int m) {
        if (mode == MODE_CROP && m != MODE_CROP) cropEdit = null;
        mode = m;
        if (m == MODE_CROP) {
            cropEdit = state.crop != null ? new RectF(state.crop) : new RectF(0, 0, 1, 1);
        }
        if (m != MODE_TEXT) selectedLabel = -1;
        invalidate();
    }

    public int getMode() {
        return mode;
    }

    public void setBrush(int color, float width, boolean eraser, boolean pencil) {
        brushColor = color;
        brushWidth = width;
        this.eraser = eraser;
        this.pencil = pencil;
    }

    public void setShowOriginal(boolean v) {
        showOriginal = v;
        invalidate();
    }

    /** Пропорция кадра (w/h), 0 — свободно. Применяется к текущему кадру сразу. */
    public void setCropAspect(float aspect) {
        cropAspect = aspect;
        if (cropEdit == null || preview == null) return;
        if (aspect > 0f) {
            float ow = oriented()[0], oh = oriented()[1];
            // Вписываем максимальный прямоугольник нужной пропорции по центру текущего кадра
            float cw = cropEdit.width() * ow, ch = cropEdit.height() * oh;
            float nw, nh;
            if (cw / ch > aspect) {
                nh = ch;
                nw = ch * aspect;
            } else {
                nw = cw;
                nh = cw / aspect;
            }
            float cx = cropEdit.centerX() * ow, cy = cropEdit.centerY() * oh;
            cropEdit.set((cx - nw / 2f) / ow, (cy - nh / 2f) / oh, (cx + nw / 2f) / ow, (cy + nh / 2f) / oh);
            clampCrop();
        }
        invalidate();
    }

    public void resetCrop() {
        cropEdit = new RectF(0, 0, 1, 1);
        invalidate();
    }

    /** Текущий кадр из режима кадрирования (null — весь кадр). */
    public RectF cropResult() {
        if (cropEdit == null) return null;
        RectF r = new RectF(cropEdit);
        if (r.left <= 0.001f && r.top <= 0.001f && r.right >= 0.999f && r.bottom >= 0.999f) return null;
        return r;
    }

    public int getSelectedLabel() {
        return selectedLabel;
    }

    public void setSelectedLabel(int i) {
        selectedLabel = i;
        invalidate();
    }

    // ---------- Геометрия ----------

    private float[] oriented() {
        if (preview == null) return new float[]{1, 1};
        return new float[]{state.orientedWidth(preview.getWidth(), preview.getHeight()),
                state.orientedHeight(preview.getWidth(), preview.getHeight())};
    }

    /** Область ориентированного изображения, которую показываем (кадр или всё). */
    private RectF visibleRect() {
        float[] o = oriented();
        if (mode == MODE_CROP) return new RectF(0, 0, o[0], o[1]);
        return EditRenderer.cropPx(state, (int) o[0], (int) o[1]);
    }

    private void computeFit() {
        RectF vis = visibleRect();
        float pad = 12f * d;
        float vw = getWidth() - pad * 2, vh = getHeight() - pad * 2;
        float s = Math.min(vw / vis.width(), vh / vis.height());
        float tx = pad + (vw - vis.width() * s) / 2f - vis.left * s;
        float ty = pad + (vh - vis.height() * s) / 2f - vis.top * s;
        fit.reset();
        fit.postScale(s, s);
        fit.postTranslate(tx, ty);
        fit.invert(inv);
    }

    private float[] toImage(float x, float y) {
        float[] p = {x, y};
        inv.mapPoints(p);
        float[] o = oriented();
        return new float[]{p[0] / o[0], p[1] / o[1]};
    }

    private float scale() {
        float[] v = new float[9];
        fit.getValues(v);
        return v[Matrix.MSCALE_X];
    }

    // ---------- Рисование ----------

    @Override
    protected void onDraw(Canvas c) {
        super.onDraw(c);
        if (preview == null || getWidth() == 0) return;
        computeFit();
        float[] o = oriented();
        RectF vis = visibleRect();
        c.save();
        c.concat(fit);
        c.clipRect(vis);
        if (showOriginal) {
            EditState plain = new EditState();
            plain.rotation = state.rotation;
            plain.flipH = state.flipH;
            plain.crop = state.crop;
            EditRenderer.drawOriented(c, preview, plain, o[0], o[1], true);
        } else {
            Paint p = new Paint(Paint.FILTER_BITMAP_FLAG | Paint.ANTI_ALIAS_FLAG);
            p.setColorFilter(new android.graphics.ColorMatrixColorFilter(state.colorMatrix()));
            c.drawBitmap(preview, EditRenderer.orientMatrix(state, preview.getWidth(),
                    preview.getHeight()), p);
            if (state.sharpness > 0f) {
                android.graphics.ColorMatrix cm = state.colorMatrix();
                cm.postConcat(Filters.contrast(0.35f * state.sharpness));
                p.setColorFilter(new android.graphics.ColorMatrixColorFilter(cm));
                p.setAlpha(Math.round(90 * state.sharpness));
                c.drawBitmap(preview, EditRenderer.orientMatrix(state, preview.getWidth(),
                        preview.getHeight()), p);
            }
            if (state.vignette != 0f) EditRenderer.drawVignette(c, o[0], o[1], state.vignette);
            EditRenderer.drawStrokes(c, state.strokes, o[0], o[1], false);
            if (current != null) {
                java.util.List<EditState.Stroke> one = new java.util.ArrayList<>();
                one.add(current);
                EditRenderer.drawStrokes(c, one, o[0], o[1], true);
            }
            EditRenderer.drawLabels(c, state.labels, o[0], o[1],
                    mode == MODE_TEXT ? selectedLabel : -1);
        }
        c.restore();

        if (mode == MODE_CROP && cropEdit != null) drawCropOverlay(c, o[0], o[1]);
    }

    private void drawCropOverlay(Canvas c, float ow, float oh) {
        RectF r = new RectF(cropEdit.left * ow, cropEdit.top * oh, cropEdit.right * ow,
                cropEdit.bottom * oh);
        fit.mapRect(r);
        RectF full = new RectF(0, 0, ow, oh);
        fit.mapRect(full);
        // Затемнение вне кадра
        c.drawRect(full.left, full.top, full.right, r.top, dimPaint);
        c.drawRect(full.left, r.bottom, full.right, full.bottom, dimPaint);
        c.drawRect(full.left, r.top, r.left, r.bottom, dimPaint);
        c.drawRect(r.right, r.top, full.right, r.bottom, dimPaint);
        // Сетка третей
        for (int i = 1; i < 3; i++) {
            float x = r.left + r.width() * i / 3f;
            float y = r.top + r.height() * i / 3f;
            c.drawLine(x, r.top, x, r.bottom, gridPaint);
            c.drawLine(r.left, y, r.right, y, gridPaint);
        }
        gridPaint.setStrokeWidth(1.5f * d);
        c.drawRect(r, gridPaint);
        gridPaint.setStrokeWidth(d);
        // Уголки
        float h = 22f * d;
        c.drawLine(r.left, r.top, r.left + h, r.top, handlePaint);
        c.drawLine(r.left, r.top, r.left, r.top + h, handlePaint);
        c.drawLine(r.right, r.top, r.right - h, r.top, handlePaint);
        c.drawLine(r.right, r.top, r.right, r.top + h, handlePaint);
        c.drawLine(r.left, r.bottom, r.left + h, r.bottom, handlePaint);
        c.drawLine(r.left, r.bottom, r.left, r.bottom - h, handlePaint);
        c.drawLine(r.right, r.bottom, r.right - h, r.bottom, handlePaint);
        c.drawLine(r.right, r.bottom, r.right, r.bottom - h, handlePaint);
    }

    // ---------- Жесты ----------

    @Override
    public boolean onTouchEvent(MotionEvent e) {
        if (preview == null) return false;
        computeFit();
        switch (mode) {
            case MODE_CROP:
                return touchCrop(e);
            case MODE_DOODLE:
                return touchDoodle(e);
            case MODE_TEXT:
                return touchText(e);
            default:
                // Удержание — показать оригинал
                if (e.getActionMasked() == MotionEvent.ACTION_DOWN) {
                    setShowOriginal(true);
                    return true;
                }
                if (e.getActionMasked() == MotionEvent.ACTION_UP
                        || e.getActionMasked() == MotionEvent.ACTION_CANCEL) {
                    setShowOriginal(false);
                }
                return true;
        }
    }

    private boolean touchDoodle(MotionEvent e) {
        float[] p = toImage(e.getX(), e.getY());
        switch (e.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
                getParent().requestDisallowInterceptTouchEvent(true);
                current = new EditState.Stroke();
                current.color = brushColor;
                current.width = brushWidth;
                current.eraser = eraser;
                current.pencil = pencil;
                current.points.add(p);
                invalidate();
                return true;
            case MotionEvent.ACTION_MOVE:
                if (current == null) return false;
                for (int i = 0; i < e.getHistorySize(); i++) {
                    current.points.add(toImage(e.getHistoricalX(i), e.getHistoricalY(i)));
                }
                current.points.add(p);
                invalidate();
                return true;
            case MotionEvent.ACTION_UP:
            case MotionEvent.ACTION_CANCEL:
                if (current != null) {
                    state.strokes.add(current);
                    current = null;
                    invalidate();
                    if (listener != null) listener.onEditCommitted();
                }
                return true;
            default:
                return false;
        }
    }

    private boolean touchText(MotionEvent e) {
        float[] o = oriented();
        float[] img = toImage(e.getX(), e.getY());
        float px = img[0] * o[0], py = img[1] * o[1];
        switch (e.getActionMasked()) {
            case MotionEvent.ACTION_DOWN: {
                int hit = -1;
                for (int i = state.labels.size() - 1; i >= 0; i--) {
                    EditState.Label l = state.labels.get(i);
                    RectF box = EditRenderer.labelBox(l, o[0], o[1], null);
                    // Учитываем поворот надписи: переводим точку в её систему
                    Matrix m = new Matrix();
                    m.setRotate(-l.angle, l.x * o[0], l.y * o[1]);
                    float[] pt = {px, py};
                    m.mapPoints(pt);
                    box.inset(-12f * d / scale(), -12f * d / scale());
                    if (box.contains(pt[0], pt[1])) {
                        hit = i;
                        break;
                    }
                }
                long now = System.currentTimeMillis();
                if (hit >= 0 && hit == selectedLabel && now - lastTapTime < 350) {
                    if (listener != null) listener.onLabelDoubleTap(hit);
                }
                lastTapTime = now;
                selectedLabel = hit;
                dragging = hit >= 0;
                if (dragging) getParent().requestDisallowInterceptTouchEvent(true);
                lastX = e.getX();
                lastY = e.getY();
                if (listener != null) listener.onLabelSelected(hit);
                invalidate();
                return true;
            }
            case MotionEvent.ACTION_MOVE:
                if (!dragging || selectedLabel < 0) return false;
                EditState.Label l = state.labels.get(selectedLabel);
                float s = scale();
                l.x += (e.getX() - lastX) / s / o[0];
                l.y += (e.getY() - lastY) / s / o[1];
                l.x = Math.max(0f, Math.min(1f, l.x));
                l.y = Math.max(0f, Math.min(1f, l.y));
                lastX = e.getX();
                lastY = e.getY();
                invalidate();
                return true;
            case MotionEvent.ACTION_UP:
            case MotionEvent.ACTION_CANCEL:
                if (dragging) {
                    dragging = false;
                    if (listener != null) listener.onEditCommitted();
                }
                return true;
            default:
                return false;
        }
    }

    private boolean touchCrop(MotionEvent e) {
        if (cropEdit == null) return false;
        float[] o = oriented();
        RectF r = new RectF(cropEdit.left * o[0], cropEdit.top * o[1], cropEdit.right * o[0],
                cropEdit.bottom * o[1]);
        fit.mapRect(r);
        float x = e.getX(), y = e.getY();
        switch (e.getActionMasked()) {
            case MotionEvent.ACTION_DOWN: {
                float t = 28f * d;
                dragZone = ZONE_NONE;
                if (Math.abs(x - r.left) < t && y > r.top - t && y < r.bottom + t) dragZone |= ZONE_L;
                if (Math.abs(x - r.right) < t && y > r.top - t && y < r.bottom + t) dragZone |= ZONE_R;
                if (Math.abs(y - r.top) < t && x > r.left - t && x < r.right + t) dragZone |= ZONE_T;
                if (Math.abs(y - r.bottom) < t && x > r.left - t && x < r.right + t) dragZone |= ZONE_B;
                if (dragZone == ZONE_NONE && r.contains(x, y)) dragZone = ZONE_MOVE;
                if (dragZone != ZONE_NONE) getParent().requestDisallowInterceptTouchEvent(true);
                lastX = x;
                lastY = y;
                return dragZone != ZONE_NONE;
            }
            case MotionEvent.ACTION_MOVE: {
                if (dragZone == ZONE_NONE) return false;
                float s = scale();
                float dx = (x - lastX) / s / o[0];
                float dy = (y - lastY) / s / o[1];
                lastX = x;
                lastY = y;
                float minW = 48f * d / s / o[0], minH = 48f * d / s / o[1];
                if (dragZone == ZONE_MOVE) {
                    dx = Math.max(-cropEdit.left, Math.min(1f - cropEdit.right, dx));
                    dy = Math.max(-cropEdit.top, Math.min(1f - cropEdit.bottom, dy));
                    cropEdit.offset(dx, dy);
                } else {
                    if ((dragZone & ZONE_L) != 0) cropEdit.left = Math.min(cropEdit.right - minW, Math.max(0f, cropEdit.left + dx));
                    if ((dragZone & ZONE_R) != 0) cropEdit.right = Math.max(cropEdit.left + minW, Math.min(1f, cropEdit.right + dx));
                    if ((dragZone & ZONE_T) != 0) cropEdit.top = Math.min(cropEdit.bottom - minH, Math.max(0f, cropEdit.top + dy));
                    if ((dragZone & ZONE_B) != 0) cropEdit.bottom = Math.max(cropEdit.top + minH, Math.min(1f, cropEdit.bottom + dy));
                    if (cropAspect > 0f) applyAspect(o[0], o[1]);
                }
                invalidate();
                return true;
            }
            case MotionEvent.ACTION_UP:
            case MotionEvent.ACTION_CANCEL:
                if (dragZone != ZONE_NONE) {
                    dragZone = ZONE_NONE;
                    if (listener != null) listener.onEditCommitted();
                }
                return true;
            default:
                return false;
        }
    }

    /** Подгоняет кадр под пропорцию, двигая сторону, противоположную той, что тянут. */
    private void applyAspect(float ow, float oh) {
        float cw = cropEdit.width() * ow, ch = cropEdit.height() * oh;
        boolean horizontal = (dragZone & (ZONE_L | ZONE_R)) != 0 && (dragZone & (ZONE_T | ZONE_B)) == 0;
        if (horizontal) {
            float nh = cw / cropAspect;
            float cy = cropEdit.centerY() * oh;
            cropEdit.top = (cy - nh / 2f) / oh;
            cropEdit.bottom = (cy + nh / 2f) / oh;
        } else {
            float nw = ch * cropAspect;
            if ((dragZone & ZONE_L) != 0) cropEdit.left = cropEdit.right - nw / ow;
            else cropEdit.right = cropEdit.left + nw / ow;
        }
        clampCrop();
    }

    private void clampCrop() {
        float[] o = oriented();
        if (cropEdit.left < 0f) cropEdit.offset(-cropEdit.left, 0f);
        if (cropEdit.top < 0f) cropEdit.offset(0f, -cropEdit.top);
        if (cropEdit.right > 1f) cropEdit.offset(1f - cropEdit.right, 0f);
        if (cropEdit.bottom > 1f) cropEdit.offset(0f, 1f - cropEdit.bottom);
        if (cropEdit.left < 0f || cropEdit.top < 0f || cropEdit.right > 1f || cropEdit.bottom > 1f) {
            // Не влезает — уменьшаем, сохраняя пропорцию
            float w = Math.min(1f, cropEdit.width()), h = Math.min(1f, cropEdit.height());
            if (cropAspect > 0f) {
                float pw = w * o[0], ph = h * o[1];
                if (pw / ph > cropAspect) pw = ph * cropAspect; else ph = pw / cropAspect;
                w = pw / o[0];
                h = ph / o[1];
            }
            cropEdit.set((1f - w) / 2f, (1f - h) / 2f, (1f + w) / 2f, (1f + h) / 2f);
        }
    }
}
