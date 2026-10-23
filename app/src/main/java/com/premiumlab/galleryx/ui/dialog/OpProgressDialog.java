package com.premiumlab.galleryx.ui.dialog;

import android.content.Context;
import android.view.LayoutInflater;
import android.view.View;
import android.widget.ProgressBar;
import android.widget.TextView;

import androidx.appcompat.app.AlertDialog;

import com.google.android.material.dialog.MaterialAlertDialogBuilder;
import com.premiumlab.galleryx.R;
import com.premiumlab.galleryx.util.FileOp;

/**
 * Диалог прогресса файловой операции с кнопкой отмены.
 */
public class OpProgressDialog implements FileOp.Progress {

    public interface CancelHook {
        void onCancelRequested();
    }

    private final AlertDialog dialog;
    private final TextView txtTitle;
    private final TextView txtFile;
    private final TextView txtPercent;
    private final ProgressBar progress;
    /**
     * Отложенный показ: мгновенные операции (один файл в корзину и т.п.)
     * не должны мигать диалогом на долю секунды. Диалог появится, только
     * если операция длится дольше паузы; у массовых операций прогресс
     * виден как раньше.
     */
    private final android.os.Handler showHandler =
            new android.os.Handler(android.os.Looper.getMainLooper());
    private boolean dismissed = false;
    private final Runnable showRunnable = () -> {
        if (dismissed) return;
        try {
            dialog.show();
        } catch (Exception ignored) {
        }
    };
    private static final long SHOW_DELAY_MS = 400;

    private OpProgressDialog(AlertDialog dialog, TextView txtTitle, TextView txtFile,
                             TextView txtPercent, ProgressBar progress) {
        this.dialog = dialog;
        this.txtTitle = txtTitle;
        this.txtFile = txtFile;
        this.txtPercent = txtPercent;
        this.progress = progress;
    }

    public static OpProgressDialog show(Context ctx, String title, CancelHook hook) {
        View v = LayoutInflater.from(ctx).inflate(R.layout.dialog_progress, null, false);
        TextView txtTitle = v.findViewById(R.id.txtProgressTitle);
        TextView txtFile = v.findViewById(R.id.txtProgressFile);
        TextView txtPercent = v.findViewById(R.id.txtProgressPercent);
        ProgressBar progress = v.findViewById(R.id.progressBarOp);
        TextView btnCancel = v.findViewById(R.id.btnProgressCancel);
        txtTitle.setText(title);

        AlertDialog dialog = new MaterialAlertDialogBuilder(ctx)
                .setView(v)
                .setCancelable(false)
                .create();
        if (hook != null) {
            btnCancel.setOnClickListener(x -> hook.onCancelRequested());
        }
        OpProgressDialog result = new OpProgressDialog(dialog, txtTitle, txtFile,
                txtPercent, progress);
        // Покажем чуть позже: если операция успеет закончиться — диалог
        // вообще не появится, и экран не мигнёт вторым окном
        result.showHandler.postDelayed(result.showRunnable, SHOW_DELAY_MS);
        return result;
    }

    public void updateTitle(String title) {
        txtTitle.setText(title);
    }

    @Override
    public void onProgress(int done, int total, String fileName, int percent) {
        if (txtFile != null) txtFile.setText(fileName);
        if (txtPercent != null) {
            txtPercent.setText(String.format(java.util.Locale.US,
                    "%d%%", Math.max(0, Math.min(100, percent))));
        }
        if (progress != null) {
            progress.setIndeterminate(total <= 0);
            progress.setProgress(Math.max(0, Math.min(100, percent)));
        }
    }

    public void dismiss() {
        dismissed = true;
        showHandler.removeCallbacks(showRunnable);
        try {
            dialog.dismiss();
        } catch (Exception ignored) {
        }
    }

    public boolean isShowing() {
        return dialog.isShowing();
    }
}
