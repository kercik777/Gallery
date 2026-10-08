package com.premiumlab.galleryx.ui.sheet;

import android.app.Dialog;
import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;

import androidx.annotation.NonNull;

import com.google.android.material.bottomsheet.BottomSheetDialog;
import com.google.android.material.bottomsheet.BottomSheetDialogFragment;
import com.premiumlab.galleryx.App;
import com.premiumlab.galleryx.R;
import com.premiumlab.galleryx.data.Prefs;

/**
 * Нижний лист выбора темы оформления.
 */
public class ThemeSheet extends BottomSheetDialogFragment {

    public interface Listener {
        void onThemePicked();
    }

    private Listener listener;

    public void setListener(Listener listener) {
        this.listener = listener;
    }

    @NonNull
    @Override
    public Dialog onCreateDialog(Bundle savedInstanceState) {
        BottomSheetDialog dialog = (BottomSheetDialog) super.onCreateDialog(savedInstanceState);
        View v = LayoutInflater.from(getContext()).inflate(R.layout.sheet_theme, null, false);
        dialog.setContentView(v);

        int current = Prefs.theme();
        v.findViewById(R.id.chkThemeSystem).setVisibility(
                current == Prefs.THEME_SYSTEM ? View.VISIBLE : View.GONE);
        v.findViewById(R.id.chkThemeLight).setVisibility(
                current == Prefs.THEME_LIGHT ? View.VISIBLE : View.GONE);
        v.findViewById(R.id.chkThemeDark).setVisibility(
                current == Prefs.THEME_DARK ? View.VISIBLE : View.GONE);

        v.findViewById(R.id.rowThemeSystem).setOnClickListener(x -> pick(Prefs.THEME_SYSTEM));
        v.findViewById(R.id.rowThemeLight).setOnClickListener(x -> pick(Prefs.THEME_LIGHT));
        v.findViewById(R.id.rowThemeDark).setOnClickListener(x -> pick(Prefs.THEME_DARK));
        return dialog;
    }

    private void pick(int mode) {
        Prefs.setTheme(mode);
        App.applyTheme();
        dismiss();
        if (listener != null) listener.onThemePicked();
    }
}
