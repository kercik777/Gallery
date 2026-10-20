package com.premiumlab.galleryx.ui.sheet;

import android.app.Dialog;
import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.widget.TextView;

import androidx.annotation.NonNull;

import com.google.android.material.bottomsheet.BottomSheetDialog;
import com.google.android.material.bottomsheet.BottomSheetDialogFragment;
import com.premiumlab.galleryx.R;
import com.premiumlab.galleryx.data.Prefs;

/**
 * Скорость воспроизведения видео (как в YouTube): 0.25× … 2×.
 * Выбор запоминается и применяется ко всем видео просмотрщика.
 */
public class SpeedSheet extends BottomSheetDialogFragment {

    public interface Listener {
        void onSpeedPicked(float speed);
    }

    public static final float[] SPEEDS = {0.25f, 0.5f, 0.75f, 1f, 1.25f, 1.5f, 1.75f, 2f};

    private Listener listener;

    public void setListener(Listener listener) {
        this.listener = listener;
    }

    /** «1×», «0.5×», «1.25×» — без лишних нулей. */
    public static String label(float speed) {
        if (speed == Math.floor(speed)) return ((int) speed) + "×";
        String s = String.format(java.util.Locale.US, "%.2f", speed);
        while (s.endsWith("0")) s = s.substring(0, s.length() - 1);
        return s + "×";
    }

    @NonNull
    @Override
    public Dialog onCreateDialog(Bundle savedInstanceState) {
        BottomSheetDialog dialog = (BottomSheetDialog) super.onCreateDialog(savedInstanceState);
        View v = LayoutInflater.from(getContext()).inflate(R.layout.sheet_speed, null, false);
        dialog.setContentView(v);

        int[] rowIds = {R.id.rowSpeed0, R.id.rowSpeed1, R.id.rowSpeed2, R.id.rowSpeed3,
                R.id.rowSpeed4, R.id.rowSpeed5, R.id.rowSpeed6, R.id.rowSpeed7};
        int[] chkIds = {R.id.chkSpeed0, R.id.chkSpeed1, R.id.chkSpeed2, R.id.chkSpeed3,
                R.id.chkSpeed4, R.id.chkSpeed5, R.id.chkSpeed6, R.id.chkSpeed7};
        int[] txtIds = {R.id.txtSpeed0, R.id.txtSpeed1, R.id.txtSpeed2, R.id.txtSpeed3,
                R.id.txtSpeed4, R.id.txtSpeed5, R.id.txtSpeed6, R.id.txtSpeed7};

        float current = Prefs.videoSpeed();
        for (int i = 0; i < SPEEDS.length; i++) {
            final float speed = SPEEDS[i];
            int resId = rowIds[i];
            int chkId = chkIds[i];
            int txtId = txtIds[i];
            TextView label = v.findViewById(txtId);
            String text = label(speed);
            if (speed == 1f) {
                text = text + "  ·  " + getString(R.string.speed_normal);
            }
            label.setText(text);
            v.findViewById(chkId).setVisibility(
                    Math.abs(speed - current) < 0.01f ? View.VISIBLE : View.GONE);
            v.findViewById(resId).setOnClickListener(row -> {
                Prefs.setVideoSpeed(speed);
                dismiss();
                if (listener != null) listener.onSpeedPicked(speed);
            });
        }
        return dialog;
    }
}
