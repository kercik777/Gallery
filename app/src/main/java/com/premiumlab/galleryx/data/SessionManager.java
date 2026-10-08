package com.premiumlab.galleryx.data;

import android.os.Handler;
import android.os.Looper;

/**
 * Управление состоянием «скрытая галерея открыта».
 *
 * Состояние сохраняется между запусками:
 * <ul>
 *   <li>«Только вручную» — галерея остаётся открытой сколько угодно (дни, месяцы,
 *       перезапуски приложения), пока пользователь не нажмёт «глаз»;</li>
 *   <li>1 / 5 минут — хранится момент, когда истекает сессия; после перезапуска
 *       приложение досчитает оставшееся время;</li>
 *   <li>«При сворачивании» — после перезапуска всегда закрыто.</li>
 * </ul>
 */
public class SessionManager {

    public interface Listener {
        void onSessionLocked();
    }

    /** Значение ключа: -1 — открыто до ручного скрытия, 0 — закрыто, >0 — срок (wall-clock ms). */
    private static final String KEY = "unlock_until";
    private static final long UNTIL_MANUAL = -1L;

    private static volatile boolean unlocked = false;
    private static final Handler MAIN = new Handler(Looper.getMainLooper());
    private static Runnable lockRunnable;
    private static Listener listener;

    private SessionManager() {
    }

    /** Восстанавливает сохранённое состояние. Вызывается из App.onCreate(). */
    public static void init() {
        if (!Prefs.masking()) {
            unlocked = false;
            persist(0);
            return;
        }
        long until = Prefs.sp().getLong(KEY, 0L);
        int mode = Prefs.autoHide();
        if (until == UNTIL_MANUAL && mode == Prefs.AUTOHIDE_MANUAL) {
            unlocked = true;
        } else if (until > 0 && (mode == Prefs.AUTOHIDE_1MIN || mode == Prefs.AUTOHIDE_5MIN)) {
            long left = until - System.currentTimeMillis();
            if (left > 0) {
                unlocked = true;
                scheduleAt(left);
            } else {
                unlocked = false;
                persist(0);
            }
        } else {
            unlocked = false;
            persist(0);
        }
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
        persist(0);
        Listener l = listener;
        if (l != null) {
            if (Looper.myLooper() == Looper.getMainLooper()) {
                l.onSessionLocked();
            } else {
                MAIN.post(l::onSessionLocked);
            }
        }
    }

    /** Перепланирует таймер под текущий режим (вызывать при смене режима автоскрытия). */
    public static void rescheduleIfUnlocked() {
        if (unlocked) scheduleAutoLock();
    }

    private static void scheduleAutoLock() {
        cancelTimer();
        int mode = Prefs.autoHide();
        long delay = -1;
        if (mode == Prefs.AUTOHIDE_1MIN) delay = 60_000L;
        else if (mode == Prefs.AUTOHIDE_5MIN) delay = 300_000L;
        if (delay > 0) {
            persist(System.currentTimeMillis() + delay);
            scheduleAt(delay);
        } else if (mode == Prefs.AUTOHIDE_MANUAL) {
            persist(UNTIL_MANUAL);
        } else {
            // При сворачивании: в памяти — открыто, на диске — закрыто
            persist(0);
        }
    }

    private static void scheduleAt(long delayMs) {
        lockRunnable = SessionManager::lock;
        MAIN.postDelayed(lockRunnable, Math.max(0, delayMs));
    }

    private static void cancelTimer() {
        if (lockRunnable != null) {
            MAIN.removeCallbacks(lockRunnable);
            lockRunnable = null;
        }
    }

    private static void persist(long value) {
        Prefs.sp().edit().putLong(KEY, value).apply();
    }

    /** Для диагностики: сколько миллисекунд осталось до автозакрытия (-1 — без лимита). */
    @SuppressWarnings("unused")
    public static long remainingMs() {
        long until = Prefs.sp().getLong(KEY, 0L);
        if (until <= 0) return until;
        return Math.max(0, until - System.currentTimeMillis());
    }
}
