package com.premiumlab.galleryx.data;

import android.os.Handler;
import android.os.Looper;

/**
 * Управление состоянием «скрытая галерея открыта».
 * После разблокировки запускает таймер автоскрытия (1 или 5 минут).
 * При сворачивании — закрытие обрабатывает MainActivity.
 */
public class SessionManager {

    public interface Listener {
        void onSessionLocked();
    }

    private static volatile boolean unlocked = false;
    private static final Handler MAIN = new Handler(Looper.getMainLooper());
    private static Runnable lockRunnable;
    private static Listener listener;

    private SessionManager() {
    }

    public static boolean isUnlocked() {
        return unlocked;
    }

    public static void setListener(Listener l) {
        listener = l;
    }

    public static void unlock() {
        unlocked = true;
        scheduleAutoLock();
    }

    public static void lock() {
        unlocked = false;
        cancelTimer();
        Listener l = listener;
        if (l != null) {
            if (Looper.myLooper() == Looper.getMainLooper()) {
                l.onSessionLocked();
            } else {
                MAIN.post(l::onSessionLocked);
            }
        }
    }

    private static void scheduleAutoLock() {
        cancelTimer();
        int mode = Prefs.autoHide();
        long delay = -1;
        if (mode == Prefs.AUTOHIDE_1MIN) delay = 60_000L;
        else if (mode == Prefs.AUTOHIDE_5MIN) delay = 300_000L;
        if (delay > 0) {
            lockRunnable = SessionManager::lock;
            MAIN.postDelayed(lockRunnable, delay);
        }
    }

    private static void cancelTimer() {
        if (lockRunnable != null) {
            MAIN.removeCallbacks(lockRunnable);
            lockRunnable = null;
        }
    }
}
