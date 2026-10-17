package com.premiumlab.galleryx.ui.view;

import android.content.Context;
import android.content.res.ColorStateList;
import android.util.AttributeSet;
import android.view.Gravity;
import android.view.View;
import android.view.animation.OvershootInterpolator;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.annotation.DrawableRes;
import androidx.annotation.Nullable;
import androidx.annotation.StringRes;

import com.premiumlab.galleryx.R;
import com.premiumlab.galleryx.util.Press;

import java.util.ArrayList;
import java.util.List;

/**
 * Плавающая «стеклянная» панель вкладок: капсула с иконками и подписями,
 * под активной вкладкой — скользящий индикатор с пружинной анимацией.
 */
public class GlassNavBar extends FrameLayout {

    public interface Listener {
        void onTabSelected(int index);
    }

    private final View indicator;
    private final LinearLayout tabs;
    private final List<View> tabViews = new ArrayList<>();
    private final List<ImageView> icons = new ArrayList<>();
    private final List<TextView> labels = new ArrayList<>();
    private int selected = -1;
    private Listener listener;

    public GlassNavBar(Context context) {
        this(context, null);
    }

    public GlassNavBar(Context context, @Nullable AttributeSet attrs) {
        super(context, attrs);
        setBackgroundResource(R.drawable.bg_glass_pill);
        int pad = dp(4);
        setPadding(pad, pad, pad, pad);
        setClipToPadding(false);

        indicator = new View(context);
        indicator.setBackgroundResource(R.drawable.bg_nav_indicator);
        indicator.setVisibility(INVISIBLE);
        addView(indicator, new LayoutParams(0, LayoutParams.MATCH_PARENT));

        tabs = new LinearLayout(context);
        tabs.setOrientation(LinearLayout.HORIZONTAL);
        addView(tabs, new LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT));
    }

    public void setListener(Listener l) {
        this.listener = l;
    }

    /** Добавляет вкладку (иконка + подпись). Вызывать до первого показа. */
    public void addTab(@DrawableRes int icon, @StringRes int label) {
        Context ctx = getContext();
        LinearLayout tab = new LinearLayout(ctx);
        tab.setOrientation(LinearLayout.VERTICAL);
        tab.setGravity(Gravity.CENTER);
        tab.setMinimumWidth(dp(72));
        tab.setPadding(dp(14), 0, dp(14), 0);
        ImageView iv = new ImageView(ctx);
        iv.setImageResource(icon);
        tab.addView(iv, new LinearLayout.LayoutParams(dp(24), dp(24)));
        TextView tv = new TextView(ctx);
        tv.setText(label);
        tv.setTextSize(11f);
        tv.setMaxLines(1);
        tv.setTypeface(android.graphics.Typeface.create("sans-serif-medium",
                android.graphics.Typeface.NORMAL));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        lp.topMargin = dp(2);
        tab.addView(tv, lp);
        final int index = tabViews.size();
        tab.setOnClickListener(v -> {
            v.performHapticFeedback(android.view.HapticFeedbackConstants.KEYBOARD_TAP);
            if (selected != index) select(index, true);
            if (listener != null) listener.onTabSelected(index);
        });
        Press.attach(tab, 0.94f);
        tabs.addView(tab, new LinearLayout.LayoutParams(0, LayoutParams.MATCH_PARENT, 1f));
        tabViews.add(tab);
        icons.add(iv);
        labels.add(tv);
        tint(index);
    }

    public int getSelected() {
        return selected;
    }

    public void select(int index, boolean animate) {
        if (index < 0 || index >= tabViews.size()) return;
        int prev = selected;
        selected = index;
        if (prev >= 0) tint(prev);
        tint(index);
        if (getWidth() == 0) {
            post(() -> moveIndicator(false));
        } else {
            moveIndicator(animate && prev >= 0);
        }
    }

    private void tint(int i) {
        boolean on = i == selected;
        int color = getContext().getColor(on ? R.color.textPrimary : R.color.navIcon);
        icons.get(i).setImageTintList(ColorStateList.valueOf(color));
        labels.get(i).setTextColor(color);
        icons.get(i).setAlpha(on ? 1f : 0.85f);
    }

    private void moveIndicator(boolean animate) {
        if (selected < 0) return;
        View tab = tabViews.get(selected);
        if (tab.getWidth() == 0) {
            post(() -> moveIndicator(false));
            return;
        }
        LayoutParams lp = (LayoutParams) indicator.getLayoutParams();
        if (lp.width != tab.getWidth()) {
            lp.width = tab.getWidth();
            lp.gravity = Gravity.START | Gravity.CENTER_VERTICAL;
            indicator.setLayoutParams(lp);
        }
        float target = tab.getLeft();
        indicator.setVisibility(VISIBLE);
        indicator.animate().cancel();
        if (animate) {
            indicator.animate().translationX(target).setDuration(380)
                    .setInterpolator(new OvershootInterpolator(0.9f)).start();
        } else {
            indicator.setTranslationX(target);
        }
    }

    @Override
    protected void onLayout(boolean changed, int left, int top, int right, int bottom) {
        super.onLayout(changed, left, top, right, bottom);
        if (changed) moveIndicator(false);
    }

    private int dp(int v) {
        return Math.round(v * getResources().getDisplayMetrics().density);
    }
}
