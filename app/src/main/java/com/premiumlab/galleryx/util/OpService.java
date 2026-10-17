package com.premiumlab.galleryx.util;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ServiceInfo;
import android.os.Build;
import android.os.IBinder;
import android.os.PowerManager;
import android.os.SystemClock;

import androidx.annotation.Nullable;
import androidx.core.app.NotificationCompat;
import androidx.core.content.ContextCompat;

import com.premiumlab.galleryx.MainActivity;
import com.premiumlab.galleryx.R;

import java.util.concurrent.atomic.AtomicInteger;

/**
 * Фоновый режим файловых операций.
 *
 * Пока идёт копирование / перемещение / удаление / восстановление, приложение
 * держит foreground-сервис с уведомлением и частичным wake lock. Благодаря
 * этому операция доживает до конца, даже если пользователь свернул приложение,
 * смахнул его из «недавних» или погас экран: система не убивает процесс
 * с активным foreground-сервисом. Когда операций не осталось — сервис
 * останавливается сам, уведомление исчезает.
 *
 * Если экран приложения к моменту завершения уже закрыт, вместо тоста
 * показывается короткое уведомление «Готово».
 */
public class OpService extends Service {

    private static final String CHANNEL = "file_ops";
    private static final int NOTIF_ID = 4101;
    private static final int DONE_ID = 4102;
    private static final long THROTTLE_MS = 400;

    private static final AtomicInteger ACTIVE = new AtomicInteger();
    private static volatile OpService instance;
    private static volatile String curTitle = "";
    private static volatile String curFile = "";
    private static volatile int curPercent = 0;
    private static volatile long lastShown = 0;

    private PowerManager.WakeLock wakeLock;

    // ---------- Статический API для FileOp ----------

    private static final android.os.Handler MAIN =
            new android.os.Handler(android.os.Looper.getMainLooper());

    /** Выполняет на главном потоке: сразу, если уже на нём, иначе — через post (порядок сохраняется). */
    private static void onMain(Runnable r) {
        if (android.os.Looper.myLooper() == android.os.Looper.getMainLooper()) {
            r.run();
        } else {
            MAIN.post(r);
        }
    }

    /** Началась операция с заголовком title (например, «Копирование»). */
    public static void begin(Context ctx, String title) {
        final Context app = ctx.getApplicationContext();
        final String t = title == null ? "" : title;
        onMain(() -> {
            curTitle = t;
            curFile = "";
            curPercent = 0;
            lastShown = 0;
            if (ACTIVE.incrementAndGet() == 1) {
                startService(app);
            } else {
                OpService s = instance;
                if (s != null) s.refresh(true);
            }
        });
    }

    /**
     * Запуск сервиса. Операции начинаются с экрана приложения, поэтому используем
     * обычный startService(): он не требует вызвать startForeground() в жёсткий
     * срок, и мгновенно завершившаяся операция (один файл в корзину) не роняет
     * процесс исключением ForegroundServiceDidNotStartInTimeException. Сам сервис
     * всё равно уходит в foreground при создании. Если система отказала (приложение
     * уже в фоне) — пробуем startForegroundService(), а при неудаче работаем без сервиса.
     */
    private static void startService(Context app) {
        Intent i = new Intent(app, OpService.class);
        try {
            app.startService(i);
        } catch (IllegalStateException background) {
            try {
                ContextCompat.startForegroundService(app, i);
            } catch (Exception e) {
                ACTIVE.decrementAndGet();
            }
        } catch (Exception e) {
            ACTIVE.decrementAndGet();
        }
    }

    /** Прогресс текущей операции; уведомление обновляется не чаще раза в 400 мс. */
    public static void progress(String file, int percent) {
        curFile = file == null ? "" : file;
        curPercent = percent;
        OpService s = instance;
        if (s != null) s.refresh(false);
    }

    /** Операция завершена; при нуле активных сервис останавливается (на главном потоке). */
    public static void end(Context ctx) {
        onMain(() -> {
            int left = ACTIVE.decrementAndGet();
            OpService s = instance;
            if (left <= 0) {
                ACTIVE.set(0);
                if (s != null) s.stopIfIdle();
            } else if (s != null) {
                s.refresh(true);
            }
        });
    }

    /** Итоговое уведомление, когда экран приложения уже закрыт. */
    public static void notifyDone(Context ctx, String title, String text) {
        Context app = ctx.getApplicationContext();
        ensureChannel(app);
        NotificationManager nm = (NotificationManager) app.getSystemService(NOTIFICATION_SERVICE);
        if (nm == null) return;
        Notification n = new NotificationCompat.Builder(app, CHANNEL)
                .setSmallIcon(R.drawable.ic_check)
                .setContentTitle(title)
                .setContentText(text)
                .setContentIntent(openApp(app))
                .setAutoCancel(true)
                .setOnlyAlertOnce(true)
                .setPriority(NotificationCompat.PRIORITY_LOW)
                .build();
        try {
            nm.notify(DONE_ID, n);
        } catch (Exception ignored) {
        }
    }

    // ---------- Сервис ----------

    @Override
    public void onCreate() {
        super.onCreate();
        instance = this;
        ensureChannel(this);
        // Уходим в foreground сразу при создании — до любых остановок
        goForeground();
        PowerManager pm = (PowerManager) getSystemService(POWER_SERVICE);
        if (pm != null) {
            try {
                wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "GalleryX:fileops");
                wakeLock.setReferenceCounted(false);
                // Страховка: не держим процессор дольше часа, даже если что-то пошло не так
                wakeLock.acquire(60L * 60 * 1000);
            } catch (Exception ignored) {
                wakeLock = null;
            }
        }
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        // Каждый запуск «закрываем» вызовом startForeground() (повторный лишь обновляет уведомление)
        goForeground();
        // Операция могла закончиться раньше, чем сервис успел подняться
        if (ACTIVE.get() <= 0) stopIfIdle();
        return START_NOT_STICKY;
    }

    private boolean foregroundCalled;

    private void goForeground() {
        foregroundCalled = true;
        Notification n = build();
        try {
            if (Build.VERSION.SDK_INT >= 29) {
                startForeground(NOTIF_ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC);
            } else {
                startForeground(NOTIF_ID, n);
            }
        } catch (Exception ignored) {
            // Нет права на foreground-сервис нужного типа — работаем как обычный сервис
        }
        lastShown = SystemClock.elapsedRealtime();
    }

    @Override
    public void onDestroy() {
        instance = null;
        if (wakeLock != null) {
            try {
                if (wakeLock.isHeld()) wakeLock.release();
            } catch (Exception ignored) {
            }
            wakeLock = null;
        }
        super.onDestroy();
    }

    @Nullable
    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    private void stopIfIdle() {
        if (ACTIVE.get() > 0) return;
        // Никогда не останавливаемся, не побывав в foreground: иначе система
        // роняет процесс (ForegroundServiceDidNotStartInTimeException)
        if (!foregroundCalled) goForeground();
        try {
            stopForeground(STOP_FOREGROUND_REMOVE);
        } catch (Exception ignored) {
        }
        stopSelf();
    }

    private void refresh(boolean force) {
        long now = SystemClock.elapsedRealtime();
        if (!force && now - lastShown < THROTTLE_MS) return;
        lastShown = now;
        NotificationManager nm = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
        if (nm == null) return;
        try {
            nm.notify(NOTIF_ID, build());
        } catch (Exception ignored) {
        }
    }

    private Notification build() {
        int percent = Math.max(0, Math.min(100, curPercent));
        String title = curTitle.isEmpty() ? getString(R.string.app_name) : curTitle;
        String text = curFile.isEmpty()
                ? getString(R.string.op_notif_running)
                : curFile;
        return new NotificationCompat.Builder(this, CHANNEL)
                .setSmallIcon(R.drawable.ic_copy)
                .setContentTitle(title + " · " + percent + "%")
                .setContentText(text)
                .setProgress(100, percent, false)
                .setOngoing(true)
                .setOnlyAlertOnce(true)
                .setSilent(true)
                .setContentIntent(openApp(this))
                .setPriority(NotificationCompat.PRIORITY_LOW)
                .setCategory(NotificationCompat.CATEGORY_PROGRESS)
                .build();
    }

    private static PendingIntent openApp(Context ctx) {
        Intent i = new Intent(ctx, MainActivity.class);
        i.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_SINGLE_TOP);
        return PendingIntent.getActivity(ctx, 0, i,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
    }

    private static void ensureChannel(Context ctx) {
        NotificationManager nm = (NotificationManager) ctx.getSystemService(NOTIFICATION_SERVICE);
        if (nm == null || nm.getNotificationChannel(CHANNEL) != null) return;
        NotificationChannel ch = new NotificationChannel(CHANNEL,
                ctx.getString(R.string.op_notif_channel), NotificationManager.IMPORTANCE_LOW);
        ch.setDescription(ctx.getString(R.string.op_notif_channel_desc));
        ch.setShowBadge(false);
        nm.createNotificationChannel(ch);
    }
}
