package com.premiumlab.galleryx.editor;

import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.ColorMatrixColorFilter;
import android.graphics.Matrix;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.PorterDuff;
import android.graphics.PorterDuffXfermode;
import android.graphics.RadialGradient;
import android.graphics.RectF;
import android.graphics.Shader;

import java.util.List;

/**
 * Отрисовка состояния редактора — один и тот же код для превью на экране и для
 * сохранения в полном разрешении.
 */
public final class EditRenderer {

    private EditRenderer() {
    }

    /** Матрица «исходник → ориентированное изображение» (поворот + отражение). */
    public static Matrix orientMatrix(EditState st, int w, int h) {
        Matrix m = new Matrix();
        m.postRotate(st.rotation);
        switch (st.rotation) {
            case 90:
                m.postTranslate(h, 0);
                break;
            case 180:
                m.postTranslate(w, h);
                break;
            case 270:
                m.postTranslate(0, w);
                break;
            default:
                break;
        }
        if (st.flipH) {
            m.postScale(-1f, 1f);
            m.postTranslate(st.orientedWidth(w, h), 0);
        }
        return m;
    }

    /** Кадр в пикселях ориентированного изображения. */
    public static RectF cropPx(EditState st, int ow, int oh) {
        RectF c = st.crop != null ? st.crop : new RectF(0, 0, 1, 1);
        return new RectF(c.left * ow, c.top * oh, c.right * ow, c.bottom * oh);
    }

    /** Полный рендер в новый bitmap размером кадра. */
    public static Bitmap render(Bitmap src, EditState st) {
        int ow = st.orientedWidth(src.getWidth(), src.getHeight());
        int oh = st.orientedHeight(src.getWidth(), src.getHeight());
        RectF crop = cropPx(st, ow, oh);
        int cw = Math.max(1, Math.round(crop.width()));
        int ch = Math.max(1, Math.round(crop.height()));
        Bitmap out = Bitmap.createBitmap(cw, ch, Bitmap.Config.ARGB_8888);
        Canvas c = new Canvas(out);
        c.drawColor(Color.BLACK);
        c.translate(-Math.round(crop.left), -Math.round(crop.top));
        drawOriented(c, src, st, ow, oh, false);
        return out;
    }

    /**
     * Рисует ориентированное изображение со всеми правками в координатах 0..ow × 0..oh.
     *
     * @param lightweight true для превью во время жеста: без слоя для ластика
     */
    public static void drawOriented(Canvas c, Bitmap src, EditState st, float ow, float oh,
                                    boolean lightweight) {
        Paint p = new Paint(Paint.FILTER_BITMAP_FLAG | Paint.ANTI_ALIAS_FLAG | Paint.DITHER_FLAG);
        p.setColorFilter(new ColorMatrixColorFilter(st.colorMatrix()));
        c.drawBitmap(src, orientMatrix(st, src.getWidth(), src.getHeight()), p);

        if (st.sharpness > 0f) {
            // Лёгкое «повышение резкости»: контрастная копия с малой прозрачностью
            Paint sp = new Paint(p);
            android.graphics.ColorMatrix cm = st.colorMatrix();
            cm.postConcat(Filters.contrast(0.35f * st.sharpness));
            sp.setColorFilter(new ColorMatrixColorFilter(cm));
            sp.setAlpha(Math.round(90 * st.sharpness));
            c.drawBitmap(src, orientMatrix(st, src.getWidth(), src.getHeight()), sp);
        }

        if (st.vignette != 0f) drawVignette(c, ow, oh, st.vignette);
        drawStrokes(c, st.strokes, ow, oh, lightweight);
        drawLabels(c, st.labels, ow, oh, -1);
    }

    public static void drawVignette(Canvas c, float ow, float oh, float v) {
        float r = (float) Math.hypot(ow / 2f, oh / 2f);
        int edge = v > 0
                ? Color.argb(Math.round(Math.min(1f, v) * 230), 0, 0, 0)
                : Color.argb(Math.round(Math.min(1f, -v) * 200), 255, 255, 255);
        Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
        p.setShader(new RadialGradient(ow / 2f, oh / 2f, r,
                new int[]{Color.TRANSPARENT, Color.TRANSPARENT, edge},
                new float[]{0f, 0.45f, 1f}, Shader.TileMode.CLAMP));
        c.drawRect(0, 0, ow, oh, p);
    }

    public static void drawStrokes(Canvas c, List<EditState.Stroke> strokes, float ow, float oh,
                                   boolean lightweight) {
        if (strokes.isEmpty()) return;
        boolean hasEraser = false;
        for (EditState.Stroke s : strokes) if (s.eraser) hasEraser = true;
        int layer = -1;
        if (hasEraser && !lightweight) layer = c.saveLayer(0, 0, ow, oh, null);
        Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
        p.setStyle(Paint.Style.STROKE);
        p.setStrokeCap(Paint.Cap.ROUND);
        p.setStrokeJoin(Paint.Join.ROUND);
        Path path = new Path();
        for (EditState.Stroke s : strokes) {
            if (s.points.isEmpty()) continue;
            p.setStrokeWidth(Math.max(1f, s.width * ow));
            if (s.eraser) {
                if (lightweight) continue;
                p.setColor(Color.BLACK);
                p.setXfermode(new PorterDuffXfermode(PorterDuff.Mode.CLEAR));
            } else {
                p.setXfermode(null);
                p.setColor(s.color);
                p.setAlpha(s.pencil ? 150 : 255);
            }
            buildPath(path, s.points, ow, oh);
            if (s.points.size() == 1) {
                float[] pt = s.points.get(0);
                c.drawPoint(pt[0] * ow, pt[1] * oh, p);
            } else {
                c.drawPath(path, p);
            }
        }
        if (layer >= 0) c.restoreToCount(layer);
    }

    private static void buildPath(Path path, List<float[]> pts, float ow, float oh) {
        path.reset();
        float[] p0 = pts.get(0);
        path.moveTo(p0[0] * ow, p0[1] * oh);
        if (pts.size() == 1) return;
        float px = p0[0] * ow, py = p0[1] * oh;
        for (int i = 1; i < pts.size(); i++) {
            float x = pts.get(i)[0] * ow, y = pts.get(i)[1] * oh;
            path.quadTo(px, py, (px + x) / 2f, (py + y) / 2f);
            px = x;
            py = y;
        }
        path.lineTo(px, py);
    }

    /** Рисует надписи; selected ≥ 0 — вокруг выбранной рисуется рамка. */
    public static void drawLabels(Canvas c, List<EditState.Label> labels, float ow, float oh,
                                  int selected) {
        if (labels.isEmpty()) return;
        Paint tp = new Paint(Paint.ANTI_ALIAS_FLAG);
        tp.setTypeface(android.graphics.Typeface.create("sans-serif-medium",
                android.graphics.Typeface.BOLD));
        Paint bp = new Paint(Paint.ANTI_ALIAS_FLAG);
        for (int i = 0; i < labels.size(); i++) {
            EditState.Label l = labels.get(i);
            if (l.text == null || l.text.isEmpty()) continue;
            float size = Math.max(4f, l.size * ow);
            tp.setTextSize(size);
            tp.setColor(l.color);
            RectF box = labelBox(l, ow, oh, tp);
            c.save();
            c.rotate(l.angle, l.x * ow, l.y * oh);
            if (l.background) {
                bp.setColor(luma(l.color) > 140 ? 0xB3000000 : 0xCCFFFFFF);
                c.drawRoundRect(box, size * 0.3f, size * 0.3f, bp);
            }
            if (i == selected) {
                Paint sp = new Paint(Paint.ANTI_ALIAS_FLAG);
                sp.setStyle(Paint.Style.STROKE);
                sp.setColor(0xFFFFFFFF);
                sp.setStrokeWidth(Math.max(1f, ow / 400f));
                sp.setPathEffect(new android.graphics.DashPathEffect(
                        new float[]{ow / 80f, ow / 120f}, 0));
                c.drawRoundRect(box, size * 0.3f, size * 0.3f, sp);
            }
            String[] lines = l.text.split("\n");
            float lineH = tp.getFontSpacing();
            float top = l.y * oh - lineH * lines.length / 2f;
            for (int k = 0; k < lines.length; k++) {
                float w = tp.measureText(lines[k]);
                c.drawText(lines[k], l.x * ow - w / 2f,
                        top + lineH * k - tp.ascent(), tp);
            }
            c.restore();
        }
    }

    /** Рамка надписи в ориентированных пикселях (без поворота). */
    public static RectF labelBox(EditState.Label l, float ow, float oh, Paint tp) {
        if (tp == null) {
            tp = new Paint(Paint.ANTI_ALIAS_FLAG);
            tp.setTypeface(android.graphics.Typeface.create("sans-serif-medium",
                    android.graphics.Typeface.BOLD));
            tp.setTextSize(Math.max(4f, l.size * ow));
        }
        String[] lines = l.text.split("\n");
        float w = 0f;
        for (String s : lines) w = Math.max(w, tp.measureText(s));
        float h = tp.getFontSpacing() * lines.length;
        float pad = tp.getTextSize() * 0.3f;
        return new RectF(l.x * ow - w / 2f - pad, l.y * oh - h / 2f - pad * 0.6f,
                l.x * ow + w / 2f + pad, l.y * oh + h / 2f + pad * 0.6f);
    }

    private static int luma(int c) {
        return (Color.red(c) * 299 + Color.green(c) * 587 + Color.blue(c) * 114) / 1000;
    }
}
