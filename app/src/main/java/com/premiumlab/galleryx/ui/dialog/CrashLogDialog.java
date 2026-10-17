package com.premiumlab.galleryx.ui.dialog;

import android.app.Activity;
import android.content.Intent;
import android.graphics.Typeface;
import android.util.TypedValue;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import com.google.android.material.dialog.MaterialAlertDialogBuilder;
import com.premiumlab.galleryx.R;
import com.premiumlab.galleryx.util.CrashLog;
import com.premiumlab.galleryx.util.Toasts;

/**
 * Просмотр журнала ошибок: текст можно выделить и скопировать, отправить
 * через любое приложение («Поделиться») или очистить.
 */
public final class CrashLogDialog {

    private CrashLogDialog() {
    }

    public static void show(Activity act, Runnable onChanged) {
        String text = CrashLog.read();
        if (text == null || text.trim().isEmpty()) {
            Toasts.makeText(act, R.string.crash_log_empty, Toast.LENGTH_SHORT).show();
            return;
        }
        CrashLog.markSeen();
        float d = act.getResources().getDisplayMetrics().density;
        TextView tv = new TextView(act);
        tv.setText(text);
        tv.setTypeface(Typeface.MONOSPACE);
        tv.setTextSize(TypedValue.COMPLEX_UNIT_SP, 11);
        tv.setTextIsSelectable(true);
        int pad = Math.round(20 * d);
        tv.setPadding(pad, Math.round(8 * d), pad, Math.round(8 * d));
        ScrollView sv = new ScrollView(act);
        sv.addView(tv);
        new MaterialAlertDialogBuilder(act)
                .setTitle(R.string.crash_log_title)
                .setView(sv)
                .setPositiveButton(R.string.action_share, (dlg, w) -> share(act, text))
                .setNeutralButton(R.string.crash_log_clear, (dlg, w) -> {
                    CrashLog.clear();
                    if (onChanged != null) onChanged.run();
                })
                .setNegativeButton(R.string.close, null)
                .show();
        // Показываем конец журнала — там самая свежая запись
        sv.post(() -> sv.fullScroll(ScrollView.FOCUS_DOWN));
    }

    private static void share(Activity act, String text) {
        try {
            Intent i = new Intent(Intent.ACTION_SEND);
            i.setType("text/plain");
            i.putExtra(Intent.EXTRA_SUBJECT, act.getString(R.string.crash_log_subject));
            i.putExtra(Intent.EXTRA_TEXT, text);
            act.startActivity(Intent.createChooser(i, act.getString(R.string.action_share)));
        } catch (Exception e) {
            Toasts.makeText(act, R.string.no_app_to_open, Toast.LENGTH_SHORT).show();
        }
    }
}
