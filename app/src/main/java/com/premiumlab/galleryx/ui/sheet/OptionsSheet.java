package com.premiumlab.galleryx.ui.sheet;

import android.app.Dialog;
import android.content.res.ColorStateList;
import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.annotation.DrawableRes;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.google.android.material.bottomsheet.BottomSheetBehavior;
import com.google.android.material.bottomsheet.BottomSheetDialog;
import com.google.android.material.bottomsheet.BottomSheetDialogFragment;
import com.premiumlab.galleryx.R;

import java.util.ArrayList;
import java.util.List;

/**
 * Универсальная шторка «Опции»: заголовок, подзаголовок и список строк
 * «иконка · название · пояснение». Строки задаются кодом перед показом.
 */
public class OptionsSheet extends BottomSheetDialogFragment {

    public static class Item {
        final @DrawableRes int icon;
        final CharSequence title;
        final @Nullable CharSequence sub;
        final boolean danger;
        final Runnable action;

        public Item(@DrawableRes int icon, CharSequence title, @Nullable CharSequence sub,
                    Runnable action) {
            this(icon, title, sub, false, action);
        }

        public Item(@DrawableRes int icon, CharSequence title, @Nullable CharSequence sub,
                    boolean danger, Runnable action) {
            this.icon = icon;
            this.title = title;
            this.sub = sub;
            this.danger = danger;
            this.action = action;
        }
    }

    private CharSequence title, sub;
    private final List<Item> items = new ArrayList<>();
    private View scrollRows;

    public OptionsSheet setHeader(CharSequence title, @Nullable CharSequence sub) {
        this.title = title;
        this.sub = sub;
        return this;
    }

    public OptionsSheet add(Item item) {
        items.add(item);
        return this;
    }

    @NonNull
    @Override
    public Dialog onCreateDialog(Bundle savedInstanceState) {
        BottomSheetDialog dialog = (BottomSheetDialog) super.onCreateDialog(savedInstanceState);
        if (items.isEmpty()) {
            // Пересоздание после смерти процесса — строки потеряны, просто закрываемся
            dismissAllowingStateLoss();
            return dialog;
        }
        View v = LayoutInflater.from(getContext()).inflate(R.layout.sheet_viewer_menu, null, false);
        dialog.setContentView(v);

        TextView txtTitle = v.findViewById(R.id.txtMenuTitle);
        TextView txtSub = v.findViewById(R.id.txtMenuSub);
        scrollRows = v.findViewById(R.id.scrollMenuRows);
        LinearLayout rows = v.findViewById(R.id.layoutMenuRows);
        txtTitle.setText(title == null ? getString(R.string.viewer_options) : title);
        if (sub == null || sub.length() == 0) {
            txtSub.setVisibility(View.GONE);
        } else {
            txtSub.setText(sub);
        }

        int danger = requireContext().getColor(R.color.danger);
        for (Item it : items) {
            View row = LayoutInflater.from(getContext())
                    .inflate(R.layout.item_option_row, rows, false);
            ImageView icon = row.findViewById(R.id.imgOptIcon);
            TextView t = row.findViewById(R.id.txtOptTitle);
            TextView s = row.findViewById(R.id.txtOptSub);
            icon.setImageResource(it.icon);
            t.setText(it.title);
            if (it.sub == null || it.sub.length() == 0) {
                s.setVisibility(View.GONE);
            } else {
                s.setText(it.sub);
            }
            if (it.danger) {
                icon.setImageTintList(ColorStateList.valueOf(danger));
                icon.setBackgroundResource(R.drawable.bg_icon_circle_danger);
                t.setTextColor(danger);
            }
            row.setOnClickListener(x -> {
                android.content.Context ctx = x.getContext();
                dismissAllowingStateLoss();
                if (it.action == null) return;
                try {
                    it.action.run();
                } catch (Throwable err) {
                    // Ошибка в действии не должна ронять приложение
                    com.premiumlab.galleryx.util.CrashLog.record("OptionsSheet:" + it.title, err);
                    com.premiumlab.galleryx.util.Toasts.makeText(ctx, R.string.error_generic,
                            android.widget.Toast.LENGTH_SHORT).show();
                }
            });
            rows.addView(row);
        }
        return dialog;
    }

    @Override
    public void onStart() {
        super.onStart();
        Dialog d = getDialog();
        if (d != null) {
            View bottom = d.findViewById(com.google.android.material.R.id.design_bottom_sheet);
            if (bottom != null) {
                ViewGroup.LayoutParams lp = bottom.getLayoutParams();
                lp.height = ViewGroup.LayoutParams.WRAP_CONTENT;
                bottom.setLayoutParams(lp);
                BottomSheetBehavior<View> behavior = BottomSheetBehavior.from(bottom);
                behavior.setPeekHeight(ViewGroup.LayoutParams.WRAP_CONTENT);
                behavior.setSkipCollapsed(true);
                behavior.setState(BottomSheetBehavior.STATE_EXPANDED);
                // Если пунктов больше, чем влезает (ландшафт!): листу —
                // максимум 88% высоты экрана, остальное добирается
                // прокруткой списка внутри
                bottom.post(() -> {
                    if (scrollRows == null) return;
                    int max = (int) (bottom.getResources().getDisplayMetrics()
                            .heightPixels * 0.88f);
                    int total = bottom.getHeight();
                    if (total > max && scrollRows.getHeight() > 0) {
                        ViewGroup.LayoutParams slp = scrollRows.getLayoutParams();
                        slp.height = Math.max(0, max - (total - scrollRows.getHeight()));
                        scrollRows.setLayoutParams(slp);
                    }
                });
            }
        }
    }
}
