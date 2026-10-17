package com.premiumlab.galleryx.util;

import android.Manifest;
import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Build;
import android.os.Environment;
import android.provider.Settings;

import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;

import com.premiumlab.galleryx.R;

/**
 * Проверка и запрос разрешений на доступ ко всем файлам устройства.
 */
public final class Perms {

    private Perms() {
    }

    public static boolean ok(Context ctx) {
        if (Build.VERSION.SDK_INT >= 30) {
            return Environment.isExternalStorageManager();
        }
        boolean read = ContextCompat.checkSelfPermission(ctx, Manifest.permission.READ_EXTERNAL_STORAGE)
                == PackageManager.PERMISSION_GRANTED;
        boolean write = ContextCompat.checkSelfPermission(ctx, Manifest.permission.WRITE_EXTERNAL_STORAGE)
                == PackageManager.PERMISSION_GRANTED;
        return read && write;
    }

    /** Запускает системный запрос разрешений (легаси до Android 11). */
    public static void requestLegacy(Activity act) {
        ActivityCompat.requestPermissions(act, new String[]{
                Manifest.permission.READ_EXTERNAL_STORAGE,
                Manifest.permission.WRITE_EXTERNAL_STORAGE
        }, 401);
    }

    /**
     * Разрешение на уведомления (Android 13+): нужно, чтобы был виден ход
     * фоновых файловых операций. Спрашиваем один раз, после доступа к файлам;
     * отказ ничего не ломает — операции всё равно идут в фоне.
     */
    public static void askNotificationsOnce(Activity act) {
        if (Build.VERSION.SDK_INT < 33) return;
        if (ContextCompat.checkSelfPermission(act, Manifest.permission.POST_NOTIFICATIONS)
                == PackageManager.PERMISSION_GRANTED) return;
        android.content.SharedPreferences sp = com.premiumlab.galleryx.data.Prefs.sp();
        if (sp.getBoolean("notif_asked", false)) return;
        sp.edit().putBoolean("notif_asked", true).apply();
        ActivityCompat.requestPermissions(act,
                new String[]{Manifest.permission.POST_NOTIFICATIONS}, 402);
    }

    /** Открывает системные настройки «Доступ ко всем файлам» (Android 11+). */
    public static void requestAllFiles(Activity act) {
        try {
            Intent intent = new Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION,
                    Uri.parse("package:" + act.getPackageName()));
            act.startActivity(intent);
        } catch (Exception e) {
            try {
                act.startActivity(new Intent(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION));
            } catch (Exception ignored) {
            }
        }
    }
}
