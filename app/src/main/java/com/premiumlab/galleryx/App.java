package com.premiumlab.galleryx;

import android.app.Activity;
import android.app.Application;
import android.content.Context;
import android.os.Bundle;

import androidx.appcompat.app.AppCompatDelegate;

import com.premiumlab.galleryx.data.Prefs;
import com.premiumlab.galleryx.data.SessionManager;
import com.premiumlab.galleryx.data.TrashStore;

/**
 * Класс приложения: инициализация настроек, темы и фоновой очистки корзины.
 * Запускается мгновенно, без тяжёлых операций на главном потоке.
 */
public class App extends Application {

    private static int startedCount = 0;
    private static boolean foreground = false;

    @Override
    public void onCreate() {
        super.onCreate();
        Prefs.init(this);
        applyTheme();
        registerLifecycle();
        // Очистка корзины старше 30 дней — в фоне
        new Thread(() -> {
            try {
                TrashStore.get().cleanup(30);
            } catch (Exception ignored) {
            }
        }, "trash-cleanup").start();
    }

    public static void applyTheme() {
        int mode;
        switch (Prefs.theme()) {
            case Prefs.THEME_LIGHT:
                mode = AppCompatDelegate.MODE_NIGHT_NO;
                break;
            case Prefs.THEME_DARK:
                mode = AppCompatDelegate.MODE_NIGHT_YES;
                break;
            default:
                mode = AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM;
                break;
        }
        AppCompatDelegate.setDefaultNightMode(mode);
    }

    private void registerLifecycle() {
        registerActivityLifecycleCallbacks(new ActivityLifecycleCallbacks() {
            @Override
            public void onActivityStarted(Activity activity) {
                startedCount++;
                foreground = true;
            }

            @Override
            public void onActivityStopped(Activity activity) {
                startedCount = Math.max(0, startedCount - 1);
                if (startedCount == 0) foreground = false;
            }

            @Override public void onActivityCreated(Activity activity, Bundle savedInstanceState) { }
            @Override public void onActivityResumed(Activity activity) { }
            @Override public void onActivityPaused(Activity activity) { }
            @Override public void onActivitySaveInstanceState(Activity activity, Bundle outState) { }
            @Override public void onActivityDestroyed(Activity activity) { }
        });
    }

    public static boolean isForeground() {
        return foreground;
    }

    public static Context appContext() {
        return Prefs.ctx();
    }
}
