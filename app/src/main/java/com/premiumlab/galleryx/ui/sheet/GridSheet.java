package com.premiumlab.galleryx.ui.sheet;

import android.app.Dialog;
import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.widget.TextView;

import androidx.annotation.NonNull;

import com.google.android.material.bottomsheet.BottomSheetDialog;
import com.google.android.material.bottomsheet.BottomSheetDialogFragment;
import com.google.android.material.slider.Slider;
import com.premiumlab.galleryx.R;
import com.premiumlab.galleryx.data.Prefs;

/**
 * Нижний лист настройки размера сетки (2–6 колонок).
 */
public class GridSheet extends BottomSheetDialogFragment {

    public interface Listener {
        void onColumnsPicked(int columns);
    }

    private Listener listener;

    public void setListener(Listener listener) {
        this.listener = listener;
    }

    @NonNull
    @Override
    public Dialog onCreateDialog(Bundle savedInstanceState) {
        BottomSheetDialog dialog = (BottomSheetDialog) super.onCreateDialog(savedInstanceState);
        View v = LayoutInflater.from(getContext()).inflate(R.layout.sheet_grid, null, false);
        dialog.setContentView(v);

        TextView txtValue = v.findViewById(R.id.txtGridValue);
        Slider slider = v.findViewById(R.id.sliderColumns);
        slider.setValue(Prefs.columns());
        txtValue.setText(getString(R.string.grid_value, (int) slider.getValue()));
        slider.addOnChangeListener((s, value, fromUser) ->
                txtValue.setText(getString(R.string.grid_value, (int) value)));
        slider.addOnSliderTouchListener(new Slider.OnSliderTouchListener() {
            @Override
            public void onStartTrackingTouch(@NonNull Slider slider) {
            }

            @Override
            public void onStopTrackingTouch(@NonNull Slider slider) {
                int columns = (int) slider.getValue();
                Prefs.setColumns(columns);
                if (listener != null) listener.onColumnsPicked(columns);
            }
        });
        return dialog;
    }
}
