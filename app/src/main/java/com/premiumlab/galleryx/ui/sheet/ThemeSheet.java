package com.premiumlab.galleryx.ui.sheet;

import android.app.Dialog;
import android.graphics.drawable.GradientDrawable;
import android.os.Bundle;
import android.view.Gravity;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.annotation.NonNull;

import com.google.android.material.bottomsheet.BottomSheetDialog;
import com.google.android.material.bottomsheet.BottomSheetDialogFragment;
import com.premiumlab.galleryx.App;
import com.premiumlab.galleryx.R;
import com.premiumlab.galleryx.data.Prefs;
import com.premiumlab.galleryx.util.Press;
import com.premiumlab.galleryx.util.Theming;

/**
 * Нижний лист «Тема оформления» как в GorillaGallery: сегменты Авто / Светлая / Тёмная / AMOLED
 * и палитра акцентных цветов (5 в ряд). Применяется сразу — экран пересоздаётся.
 */
public class ThemeSheet extends BottomSheetDialogFragment {

    public interface Listener {
        void onThemePicked();
    }

    private Listener listener;
    private LinearLayout rowModes, gridAccents;
    private TextView txtAccentName;
    private float d;

    public void setListener(Listener listener) {
        this.listener = listener;
    }

    @NonNull
    @Override
    public Dialog onCreateDialog(Bundle savedInstanceState) {
        BottomSheetDialog dialog = (BottomSheetDialog) super.onCreateDialog(savedInstanceState);
        View v = LayoutInflater.from(getContext()).inflate(R.layout.sheet_theme, null, false);
        dialog.setContentView(v);
        d = getResources().getDisplayMetrics().density;
        rowModes = v.findViewById(R.id.rowThemeModes);
        gridAccents = v.findViewById(R.id.gridAccents);
        txtAccentName = v.findViewById(R.id.txtAccentName);
        buildModes();
        buildAccents();
        return dialog;
    }

    private void buildModes() {
        rowModes.removeAllViews();
        int[] modes = {Prefs.THEME_SYSTEM, Prefs.THEME_LIGHT, Prefs.THEME_DARK, Prefs.THEME_AMOLED};
        int[] icons = {R.drawable.ic_auto, R.drawable.ic_light, R.drawable.ic_dark, R.drawable.ic_contrast};
        int[] labels = {R.string.settings_theme_system_short, R.string.settings_theme_light,
                R.string.settings_theme_dark, R.string.settings_theme_amoled};
        int current = Prefs.theme();
        int accent = Theming.accent(requireContext());
        int onAccent = Theming.onAccent(requireContext());
        for (int i = 0; i < modes.length; i++) {
            final int mode = modes[i];
            boolean sel = mode == current;
            LinearLayout seg = new LinearLayout(requireContext());
            seg.setOrientation(LinearLayout.HORIZONTAL);
            seg.setGravity(Gravity.CENTER);
            seg.setPadding(0, dp(10), 0, dp(10));
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
            if (i > 0) lp.setMarginStart(dp(4));
            seg.setLayoutParams(lp);
            GradientDrawable bg = new GradientDrawable();
            bg.setCornerRadius(dp(18));
            bg.setColor(sel ? accent : 0x00000000);
            seg.setBackground(bg);
            ImageView img = new ImageView(requireContext());
            img.setImageResource(icons[i]);
            img.setLayoutParams(new LinearLayout.LayoutParams(dp(14), dp(14)));
            int fg = sel ? onAccent : requireContext().getColor(R.color.textSecondary);
            img.setColorFilter(fg);
            TextView t = new TextView(requireContext());
            t.setText(labels[i]);
            t.setTextSize(11f);
            t.setMaxLines(1);
            t.setTextColor(fg);
            t.setTypeface(null, android.graphics.Typeface.BOLD);
            LinearLayout.LayoutParams tlp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT);
            tlp.setMarginStart(dp(3));
            t.setLayoutParams(tlp);
            seg.addView(img);
            seg.addView(t);
            Press.attach(seg, 0.95f);
            seg.setOnClickListener(x -> {
                if (Prefs.theme() == mode) return;
                Prefs.setTheme(mode);
                apply();
            });
            rowModes.addView(seg);
        }
    }

    private void buildAccents() {
        gridAccents.removeAllViews();
        gridAccents.setClipChildren(false);
        gridAccents.setClipToPadding(false);
        Theming.Accent cur = Theming.current();
        txtAccentName.setText(cur.label);
        LinearLayout row = null;
        for (int i = 0; i < Theming.ACCENTS.length; i++) {
            if (i % 5 == 0) {
                row = new LinearLayout(requireContext());
                row.setOrientation(LinearLayout.HORIZONTAL);
                LinearLayout.LayoutParams rlp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                        ViewGroup.LayoutParams.WRAP_CONTENT);
                if (i > 0) rlp.topMargin = dp(8);
                row.setLayoutParams(rlp);
                gridAccents.addView(row);
            }
            final Theming.Accent a = Theming.ACCENTS[i];
            boolean sel = a.key.equals(cur.key);
            android.widget.FrameLayout cell = new android.widget.FrameLayout(requireContext());
            cell.setLayoutParams(new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
            // Запас под увеличение выбранного кружка (scale 1.08) — иначе его обрежет
            cell.setPadding(dp(4), dp(4), dp(4), dp(4));
            cell.setClipChildren(false);
            cell.setClipToPadding(false);
            row.setClipChildren(false);
            row.setClipToPadding(false);
            View dot = new View(requireContext());
            int s = dp(44);
            android.widget.FrameLayout.LayoutParams dlp = new android.widget.FrameLayout.LayoutParams(s, s);
            dlp.gravity = Gravity.CENTER;
            dot.setLayoutParams(dlp);
            GradientDrawable bg = new GradientDrawable();
            bg.setShape(GradientDrawable.OVAL);
            bg.setColor(Theming.swatchColor(requireContext(), a));
            bg.setStroke(sel ? dp(3) : Math.max(1, dp(1)), sel ? 0xFFFFFFFF
                    : requireContext().getColor(R.color.glassStroke));
            dot.setBackground(bg);
            if (sel) {
                dot.setScaleX(1.08f);
                dot.setScaleY(1.08f);
            }
            cell.addView(dot);
            if (a.swatch == 0) {
                // «Авто» — значок внутри
                ImageView ic = new ImageView(requireContext());
                ic.setImageResource(R.drawable.ic_auto);
                ic.setColorFilter(Theming.isDark(requireContext()) ? 0xFF000000 : 0xFFFFFFFF);
                android.widget.FrameLayout.LayoutParams ilp = new android.widget.FrameLayout.LayoutParams(dp(18), dp(18));
                ilp.gravity = Gravity.CENTER;
                ic.setLayoutParams(ilp);
                cell.addView(ic);
            }
            Press.attach(dot, 0.9f);
            cell.setOnClickListener(x -> pickAccent(a));
            dot.setOnClickListener(x -> pickAccent(a));
            row.addView(cell);
        }
        // Дополняем последний ряд пустыми ячейками
        if (row != null) {
            while (row.getChildCount() < 5) {
                View sp = new View(requireContext());
                sp.setLayoutParams(new LinearLayout.LayoutParams(0, 1, 1f));
                row.addView(sp);
            }
        }
    }

    private void pickAccent(Theming.Accent a) {
        if (a.key.equals(Prefs.accent())) return;
        Prefs.setAccent(a.key);
        apply();
    }

    private void apply() {
        App.applyTheme();
        dismissAllowingStateLoss();
        if (listener != null) listener.onThemePicked();
    }

    private int dp(int v) {
        return Math.round(v * d);
    }
}
