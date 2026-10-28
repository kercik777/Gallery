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
        com.premiumlab.galleryx.util.CrashLog.install(this);
        // Анти-тампер: чужая пересборка (с другим сертификатом) не запустится.
        // Пока хэш в IntegrityGuard не вписан — проверка отключена.
        if (!com.premiumlab.galleryx.util.IntegrityGuard.verify(this)) {
            throw new IllegalStateException("signature mismatch");
        }
        Prefs.init(this);
        SessionManager.init();
        // После переустановки PIN подхватывается из служебной папки корня
        try {
            com.premiumlab.galleryx.data.PinBackup.restoreIfNeeded();
        } catch (Exception ignored) {
        }
        // Флаг .nomedia в корне — по текущему состоянию маскировки; база заметок — прогрев
        com.premiumlab.galleryx.data.MediaVisibility.sync();
        com.premiumlab.galleryx.data.NoteStore.warmUp();
        applyTheme();
        registerLifecycle();
        // Очистка корзины старше 30 дней — в фоне
        new Thread(() -> {
            try {
                // Сейф: файлы, оставшиеся в корне после переустановки, возвращаются в список
                com.premiumlab.galleryx.data.SafeStore.get().syncFromDisk();
            } catch (Exception ignored) {
            }
            try {
                // Заблокированные папки: список в корне — подхватить отсутствующие
                // и записать копию, если её ещё нет
                com.premiumlab.galleryx.data.LockStore.syncWithRootFile();
            } catch (Exception ignored) {
            }
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
            case Prefs.THEME_AMOLED:
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
                // Запрет снимков экрана действует во всём приложении и меняется «на лету»
                try {
                    if (Prefs.flagSecure()) {
                        activity.getWindow().addFlags(
                                android.view.WindowManager.LayoutParams.FLAG_SECURE);
                    } else {
                        activity.getWindow().clearFlags(
                                android.view.WindowManager.LayoutParams.FLAG_SECURE);
                    }
                } catch (Exception ignored) {
                }
            }

            @Override
            public void onActivityStopped(Activity activity) {
                startedCount = Math.max(0, startedCount - 1);
                if (startedCount == 0) foreground = false;
            }

            @Override
            public void onActivityCreated(Activity activity, Bundle savedInstanceState) {
                // Иконки системных панелей: тёмные в светлой теме, светлые в тёмной.
                // Цвета панелей задаёт тема; просмотрщик управляет ими сам.
                if (activity instanceof PhotoViewerActivity) return;
                // Акцентный оверлей темы — ДО первого обращения к окну: декор окна
                // читает windowBackground один раз при создании, и без этого в зоне
                // строки состояния оставался фон базовой палитры (полоса другого цвета
                // после смены темы/акцента).
                try {
                    com.premiumlab.galleryx.util.Theming.apply(activity);
                } catch (Exception ignored) {
                }
                // Прозрачные строка состояния и панель навигации на всех экранах
                try {
                    com.premiumlab.galleryx.util.EdgeToEdge.setup(activity);
                } catch (Exception ignored) {
                }
                try {
                    boolean light = (activity.getResources().getConfiguration().uiMode
                            & android.content.res.Configuration.UI_MODE_NIGHT_MASK)
                            != android.content.res.Configuration.UI_MODE_NIGHT_YES;
                    androidx.core.view.WindowInsetsControllerCompat ic =
                            new androidx.core.view.WindowInsetsControllerCompat(
                                    activity.getWindow(), activity.getWindow().getDecorView());
                    ic.setAppearanceLightStatusBars(light);
                    ic.setAppearanceLightNavigationBars(light);
                } catch (Exception ignored) {
                }
            }

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
