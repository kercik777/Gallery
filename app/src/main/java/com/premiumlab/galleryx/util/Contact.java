package com.premiumlab.galleryx.util;

import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.os.Build;
import android.widget.Toast;

import com.premiumlab.galleryx.R;

/**
 * Связь с разработчиком: Telegram-канал и письмо на почту.
 * Открывает системные приложения; если подходящего нет — копирует адрес в буфер.
 */
public final class Contact {

    public static final String TELEGRAM_CHANNEL = "TmAppPro";
    public static final String TELEGRAM_URL = "https://t.me/" + TELEGRAM_CHANNEL;
    public static final String DEV_EMAIL = "tmapppro97@gmail.com";

    private Contact() {
    }

    /** Открывает канал в Telegram (или в браузере, если Telegram не установлен). */
    public static void openTelegram(Context ctx) {
        try {
            Intent i = new Intent(Intent.ACTION_VIEW, Uri.parse(TELEGRAM_URL));
            i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            ctx.startActivity(i);
        } catch (Exception e) {
            copy(ctx, "telegram", TELEGRAM_URL);
            Toasts.makeText(ctx, ctx.getString(R.string.about_no_browser, TELEGRAM_URL),
                    Toast.LENGTH_LONG).show();
        }
    }

    /** Письмо разработчику: система предложит почтовые приложения на выбор. */
    public static void emailDeveloper(Context ctx) {
        String version;
        try {
            version = ctx.getPackageManager().getPackageInfo(ctx.getPackageName(), 0).versionName;
        } catch (Exception e) {
            version = "?";
        }
        String subject = ctx.getString(R.string.about_email_subject,
                ctx.getString(R.string.app_name), version);
        String body = "\n\n\n" + ctx.getString(R.string.about_email_footer) + "\n"
                + ctx.getString(R.string.app_name) + " " + version
                + "\nAndroid " + Build.VERSION.RELEASE + " (API " + Build.VERSION.SDK_INT + ")"
                + "\n" + Build.MANUFACTURER + " " + Build.MODEL;
        // mailto: — отвечают только почтовые клиенты, а не все приложения «Поделиться»
        Intent i = new Intent(Intent.ACTION_SENDTO, Uri.parse("mailto:"));
        i.putExtra(Intent.EXTRA_EMAIL, new String[]{DEV_EMAIL});
        i.putExtra(Intent.EXTRA_SUBJECT, subject);
        i.putExtra(Intent.EXTRA_TEXT, body);
        try {
            Intent chooser = Intent.createChooser(i, ctx.getString(R.string.about_email_chooser));
            chooser.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            ctx.startActivity(chooser);
        } catch (Exception e) {
            copy(ctx, "email", DEV_EMAIL);
            Toasts.makeText(ctx, ctx.getString(R.string.about_no_email_app, DEV_EMAIL),
                    Toast.LENGTH_LONG).show();
        }
    }

    private static void copy(Context ctx, String label, String text) {
        try {
            ClipboardManager cm = (ClipboardManager) ctx.getSystemService(Context.CLIPBOARD_SERVICE);
            if (cm != null) cm.setPrimaryClip(ClipData.newPlainText(label, text));
        } catch (Exception ignored) {
        }
    }
}
