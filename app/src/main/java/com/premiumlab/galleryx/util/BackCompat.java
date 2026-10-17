package com.premiumlab.galleryx.util;

import androidx.activity.ComponentActivity;
import androidx.activity.OnBackPressedCallback;

/**
 * Единая обработка кнопки «Назад».
 *
 * Начиная с targetSdk 36 система включает предиктивный жест «Назад»
 * (enableOnBackInvokedCallback = true по умолчанию). В этом режиме
 * переопределения {@code Activity.onBackPressed()} больше не вызываются —
 * система сама закрывает активность. Поэтому вся логика «Назад» регистрируется
 * через {@link androidx.activity.OnBackPressedDispatcher}: это работает и на
 * старых версиях Android, и с предиктивным жестом.
 */
public final class BackCompat {

    /** Возвращает true, если нажатие обработано; false — закрыть экран как обычно. */
    public interface Handler {
        boolean handleBack();
    }

    private BackCompat() {
    }

    public static void install(ComponentActivity activity, Handler handler) {
        activity.getOnBackPressedDispatcher().addCallback(activity, new OnBackPressedCallback(true) {
            @Override
            public void handleOnBackPressed() {
                boolean handled;
                try {
                    handled = handler.handleBack();
                } catch (RuntimeException e) {
                    handled = false;
                }
                if (handled) return;
                // Стандартное поведение: отключаем себя и отдаём событие дальше
                // (стек фрагментов → закрытие активности), затем включаемся снова.
                setEnabled(false);
                try {
                    activity.getOnBackPressedDispatcher().onBackPressed();
                } finally {
                    setEnabled(true);
                }
            }
        });
    }
}
