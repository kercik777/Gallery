package com.premiumlab.galleryx.ui.dialog;

import android.content.Context;
import android.view.LayoutInflater;
import android.view.View;
import android.widget.TextView;

import androidx.appcompat.app.AlertDialog;

import com.google.android.material.dialog.MaterialAlertDialogBuilder;
import com.premiumlab.galleryx.R;

/**
 * Красивый диалог «О приложении».
 */
public final class AboutDialog {

    private AboutDialog() {
    }

    public static void show(Context ctx) {
        View v = LayoutInflater.from(ctx).inflate(R.layout.dialog_about, null, false);
        TextView txtVersion = v.findViewById(R.id.txtAboutVersion);
        String version;
        try {
            version = ctx.getPackageManager()
                    .getPackageInfo(ctx.getPackageName(), 0).versionName;
        } catch (Exception e) {
            version = "1.0";
        }
        txtVersion.setText(ctx.getString(R.string.settings_version, version));

        View btnTg = v.findViewById(R.id.btnAboutTelegram);
        View btnMail = v.findViewById(R.id.btnAboutEmail);
        com.premiumlab.galleryx.util.Press.attach(btnTg, 0.96f);
        com.premiumlab.galleryx.util.Press.attach(btnMail, 0.96f);
        btnTg.setOnClickListener(x -> com.premiumlab.galleryx.util.Contact.openTelegram(ctx));
        btnMail.setOnClickListener(x -> com.premiumlab.galleryx.util.Contact.emailDeveloper(ctx));

        new MaterialAlertDialogBuilder(ctx)
                .setView(v)
                .setPositiveButton(R.string.ok, null)
                .show();
    }
}
