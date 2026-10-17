package com.premiumlab.galleryx.ui.view;

import android.animation.ValueAnimator;
import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.RectF;
import android.view.MotionEvent;
import android.view.View;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.premiumlab.galleryx.R;

/**
 * Быстрая прокрутка (как в GorillaGallery): капсула у правого края появляется при
 * прокрутке, её можно тянуть — рядом всплывает дата. Накладывается поверх RecyclerView.
 */
public class FastScroller extends View {

    public interface LabelProvider {
        String labelAt(int position);
    }

    private RecyclerView recycler;
    private LabelProvider labels;
    private final Paint thumbPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint strokePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint bubblePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint textPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF rect = new RectF();
    private final float d;
    private float thumbY = 0f;
    private float alpha = 0f;
    private boolean dragging = false;
    private float dragOffset = 0f;
    private String label;
    private ValueAnimator fade;
    private final Runnable hide = () -> fadeTo(0f);

    public FastScroller(Context ctx) {
        super(ctx);
        d = ctx.getResources().getDisplayMetrics().density;
        thumbPaint.setColor(com.premiumlab.galleryx.util.Theming.accent(ctx));
        strokePaint.setColor(0x66FFFFFF);
        strokePaint.setStyle(Paint.Style.STROKE);
        strokePaint.setStrokeWidth(d);
        bubblePaint.setColor(0xFFFFFFFF);
        bubblePaint.setShadowLayer(6f * d, 0f, 2f * d, 0x38000000);
        textPaint.setColor(0xFF1E1E1E);
        textPaint.setTextSize(14f * d);
        textPaint.setFakeBoldText(true);
        setLayerType(LAYER_TYPE_SOFTWARE, null);
        setWillNotDraw(false);
    }

    public void attach(RecyclerView rv, LabelProvider provider) {
        recycler = rv;
        labels = provider;
        rv.addOnScrollListener(new RecyclerView.OnScrollListener() {
            @Override
            public void onScrolled(@NonNull RecyclerView r, int dx, int dy) {
                if (dragging) return;
                if (!canScroll()) {
                    fadeTo(0f);
                    return;
                }
                thumbY = thumbFromScroll();
                // Появляется мгновенно и всегда полностью яркий, пока идёт прокрутка
                showNow();
                removeCallbacks(hide);
                postDelayed(hide, 1200);
                invalidate();
            }
        });
    }

    private boolean canScroll() {
        return recycler != null
                && recycler.computeVerticalScrollRange() > recycler.getHeight() * 2;
    }

    private float trackHeight() {
        // Нижний padding = плавающая панель навигации / системная панель
        return Math.max(1f, getHeight() - getPaddingBottom() - thumbH() - 2 * pad());
    }

    private float thumbH() {
        return 48f * d;
    }

    private float pad() {
        return 16f * d;
    }

    private float thumbFromScroll() {
        int range = recycler.computeVerticalScrollRange() - recycler.computeVerticalScrollExtent();
        if (range <= 0) return pad();
        float f = recycler.computeVerticalScrollOffset() / (float) range;
        return pad() + Math.max(0f, Math.min(1f, f)) * trackHeight();
    }

    private float fadeTarget = -1f;

    private void showNow() {
        if (fade != null) {
            fade.cancel();
            fade = null;
        }
        fadeTarget = 1f;
        if (alpha != 1f) {
            alpha = 1f;
            invalidate();
        }
    }

    private void fadeTo(float target) {
        if (fade != null && fade.isRunning() && fadeTarget == target) return;
        if (alpha == target) {
            fadeTarget = target;
            return;
        }
        if (fade != null) fade.cancel();
        fadeTarget = target;
        fade = ValueAnimator.ofFloat(alpha, target);
        fade.setDuration(target > alpha ? 120 : 260);
        fade.addUpdateListener(a -> {
            alpha = (float) a.getAnimatedValue();
            invalidate();
        });
        fade.start();
    }

    @Override
    public boolean onTouchEvent(MotionEvent e) {
        if (recycler == null || alpha <= 0.05f && e.getAction() == MotionEvent.ACTION_DOWN) return false;
        float x = e.getX();
        boolean onThumb = x > getWidth() - 48f * d
                && e.getY() > thumbY - 20f * d && e.getY() < thumbY + thumbH() + 20f * d;
        switch (e.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
                if (!onThumb || !canScroll()) return false;
                dragging = true;
                getParent().requestDisallowInterceptTouchEvent(true);
                removeCallbacks(hide);
                // Тянем от текущего положения капсулы — без скачка к пальцу
                dragOffset = e.getY() - (thumbY + thumbH() / 2f);
                if (Math.abs(dragOffset) > thumbH()) dragOffset = 0f;
                moveTo(e.getY() - dragOffset);
                return true;
            case MotionEvent.ACTION_MOVE:
                if (!dragging) return false;
                moveTo(e.getY() - dragOffset);
                return true;
            case MotionEvent.ACTION_UP:
            case MotionEvent.ACTION_CANCEL:
                if (!dragging) return false;
                dragging = false;
                label = null;
                postDelayed(hide, 900);
                invalidate();
                return true;
            default:
                return false;
        }
    }

    private void moveTo(float y) {
        float t = Math.max(0f, Math.min(1f, (y - pad() - thumbH() / 2f) / trackHeight()));
        thumbY = pad() + t * trackHeight();
        int count = recycler.getAdapter() == null ? 0 : recycler.getAdapter().getItemCount();
        if (count > 0 && recycler.getLayoutManager() instanceof LinearLayoutManager) {
            int pos = Math.round(t * (count - 1));
            ((LinearLayoutManager) recycler.getLayoutManager()).scrollToPositionWithOffset(pos, 0);
            label = labels != null ? labels.labelAt(pos) : null;
        }
        invalidate();
    }

    private final android.graphics.Path chevron = new android.graphics.Path();
    private final Paint chevronPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint shadowPaint = new Paint(Paint.ANTI_ALIAS_FLAG);

    @Override
    protected void onDraw(Canvas c) {
        if (alpha <= 0f) return;
        // Капсула как у Gorilla (стиль Aves): 20×48dp, радиус 10dp, тень, стрелки вверх/вниз
        float w = 20f * d;
        float right = getWidth() - 1f * d;
        float r = 10f * d;
        int a255 = Math.round(255 * alpha);
        rect.set(right - w, thumbY, right, thumbY + thumbH());
        shadowPaint.setColor(0x00000000);
        shadowPaint.setShadowLayer(4f * d, 0f, 2f * d, (Math.round(0x55 * alpha) << 24));
        c.drawRoundRect(rect, r, r, shadowPaint);
        thumbPaint.setAlpha(a255);
        c.drawRoundRect(rect, r, r, thumbPaint);
        int accent = thumbPaint.getColor();
        int luma = (android.graphics.Color.red(accent) * 299 + android.graphics.Color.green(accent) * 587
                + android.graphics.Color.blue(accent) * 114) / 1000;
        chevronPaint.setColor(luma > 128 ? 0xFF1E1E1E : 0xFFFFFFFF);
        chevronPaint.setAlpha(a255);
        chevronPaint.setStyle(Paint.Style.STROKE);
        chevronPaint.setStrokeWidth(1.6f * d);
        chevronPaint.setStrokeCap(Paint.Cap.ROUND);
        chevronPaint.setStrokeJoin(Paint.Join.ROUND);
        float cx = rect.centerX(), cy = rect.centerY();
        float aw = 7f * d, ah = 3.5f * d;
        float up = cy - 3.2f * d, down = cy + 3.2f * d;
        chevron.reset();
        chevron.moveTo(cx - aw / 2f, up + ah / 2f);
        chevron.lineTo(cx, up - ah / 2f);
        chevron.lineTo(cx + aw / 2f, up + ah / 2f);
        chevron.moveTo(cx - aw / 2f, down - ah / 2f);
        chevron.lineTo(cx, down + ah / 2f);
        chevron.lineTo(cx + aw / 2f, down - ah / 2f);
        c.drawPath(chevron, chevronPaint);

        if (dragging && label != null) {
            // Белый «пузырь» с датой слева от капсулы
            float tw = textPaint.measureText(label);
            float bh = 34f * d;
            float bw = tw + 28f * d;
            float by = thumbY + thumbH() / 2f - bh / 2f;
            by = Math.max(4f * d, Math.min(getHeight() - bh - 4f * d, by));
            rect.set(right - w - 16f * d - bw, by, right - w - 16f * d, by + bh);
            c.drawRoundRect(rect, 16f * d, 16f * d, bubblePaint);
            c.drawText(label, rect.left + 14f * d, rect.centerY() + textPaint.getTextSize() * 0.35f,
                    textPaint);
        }
    }
}
