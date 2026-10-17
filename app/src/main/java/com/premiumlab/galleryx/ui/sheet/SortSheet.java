package com.premiumlab.galleryx.ui.sheet;

import android.app.Dialog;
import android.content.Context;
import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;

import androidx.annotation.NonNull;

import com.google.android.material.bottomsheet.BottomSheetDialog;
import com.google.android.material.bottomsheet.BottomSheetDialogFragment;
import com.premiumlab.galleryx.R;
import com.premiumlab.galleryx.data.Prefs;

/**
 * Нижний лист выбора сортировки.
 */
public class SortSheet extends BottomSheetDialogFragment {

    public interface Listener {
        void onSortPicked(int mode);
    }

    private Listener listener;

    public void setListener(Listener listener) {
        this.listener = listener;
    }

    @NonNull
    @Override
    public Dialog onCreateDialog(Bundle savedInstanceState) {
        BottomSheetDialog dialog = (BottomSheetDialog) super.onCreateDialog(savedInstanceState);
        View v = LayoutInflater.from(getContext()).inflate(R.layout.sheet_sort, null, false);
        dialog.setContentView(v);

        int[] rowIds = {R.id.rowSort0, R.id.rowSort1, R.id.rowSort2,
                R.id.rowSort3, R.id.rowSort4, R.id.rowSort5};
        int[] chkIds = {R.id.chkSort0, R.id.chkSort1, R.id.chkSort2,
                R.id.chkSort3, R.id.chkSort4, R.id.chkSort5};

        int current = Prefs.sortMode();
        for (int i = 0; i < rowIds.length; i++) {
            final int mode = i;
            v.findViewById(chkIds[i]).setVisibility(i == current ? View.VISIBLE : View.GONE);
            v.findViewById(rowIds[i]).setOnClickListener(row -> {
                Prefs.setSortMode(mode);
                dismiss();
                if (listener != null) listener.onSortPicked(mode);
            });
        }
        return dialog;
    }
}
