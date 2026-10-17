package com.premiumlab.galleryx;

import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.VibrationEffect;
import android.os.Vibrator;
import android.view.View;
import android.view.WindowManager;
import android.view.animation.Animation;
import android.view.animation.AnimationUtils;
import android.widget.TextView;

import androidx.appcompat.app.AppCompatActivity;

import com.premiumlab.galleryx.data.Prefs;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.Locale;

/**
 * Экран PIN-кода. Режимы:
 *  - «unlock» — вход в скрытую галерею;
 *  - «create» — установка нового PIN (ввод + подтверждение).
 */
public class PinActivity extends AppCompatActivity {

    public static final String MODE_UNLOCK = "unlock";
    public static final String MODE_CREATE = "create";
    /** Разрешить восстановление забытого PIN (только из «Сменить PIN-код»). */
    public static final String EXTRA_RECOVERY = "recovery";

    private static final int PIN_LEN = 4;
    private static final int MAX_ATTEMPTS = 5;
    private static final long LOCK_MS = 15_000L;

    private String mode = MODE_UNLOCK;
    private final StringBuilder input = new StringBuilder();
    private String firstAttempt = null;
    private final View[] dots = new View[PIN_LEN];
    private TextView txtTitle, txtSub, txtError;
    private View layoutDots;

    private int attempts = 0;
    private long lockUntil = 0;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private final Runnable lockTick = this::updateLockState;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        com.premiumlab.galleryx.util.BackCompat.install(this, this::handleBack);
        com.premiumlab.galleryx.util.Theming.apply(this);
        if (Prefs.flagSecure()) {
            getWindow().addFlags(WindowManager.LayoutParams.FLAG_SECURE);
        }
        setContentView(R.layout.activity_pin);

        mode = getIntent().getStringExtra("mode");
        if (mode == null) mode = MODE_UNLOCK;

        txtTitle = findViewById(R.id.txtPinTitle);
        txtSub = findViewById(R.id.txtPinSub);
        txtError = findViewById(R.id.txtPinError);
        layoutDots = findViewById(R.id.layoutDots);
        dots[0] = findViewById(R.id.pinDot0);
        dots[1] = findViewById(R.id.pinDot1);
        dots[2] = findViewById(R.id.pinDot2);
        dots[3] = findViewById(R.id.pinDot3);

        int[] keyIds = {R.id.btnPin1, R.id.btnPin2, R.id.btnPin3, R.id.btnPin4,
                R.id.btnPin5, R.id.btnPin6, R.id.btnPin7, R.id.btnPin8, R.id.btnPin9,
                R.id.btnPin0};
        for (int i = 0; i < keyIds.length; i++) {
            final int digit = (i == 9) ? 0 : i + 1;
            findViewById(keyIds[i]).setOnClickListener(v -> onDigit(digit));
        }
        findViewById(R.id.btnPinBack).setOnClickListener(v -> onBackspace());

        applyModeTitle();
        updateDots();
        setupBiometric();
        setupRecovery();
    }

    // ---------- Восстановление забытого PIN ----------

    /**
     * Только на экране «Сменить PIN-код»: долгое нажатие на иконку замка или
     * заголовок «Введите PIN-код» открывает ввод секретного кода. Секретный код —
     * текущий заряд батареи телефона в процентах (например, 56). При совпадении
     * экран завершается как после верного PIN, и приложение сразу предлагает
     * придумать новый.
     */
    private void setupRecovery() {
        if (!MODE_UNLOCK.equals(mode) || !getIntent().getBooleanExtra(EXTRA_RECOVERY, false)) {
            return;
        }
        View.OnLongClickListener l = v -> {
            showRecoveryDialog();
            return true;
        };
        findViewById(R.id.imgPinIcon).setOnLongClickListener(l);
        txtTitle.setOnLongClickListener(l);
    }

    private void showRecoveryDialog() {
        if (isFinishing()) return;
        android.view.View v = android.view.LayoutInflater.from(this)
                .inflate(R.layout.dialog_create_folder, null, false);
        android.widget.EditText edit = v.findViewById(R.id.editFolderName);
        v.findViewById(R.id.txtDialogTitle).setVisibility(View.GONE);
        v.findViewById(R.id.txtFolderPreview).setVisibility(View.GONE);
        v.findViewById(R.id.txtFolderError).setVisibility(View.GONE);
        edit.setHint(R.string.pin_recovery_hint);
        edit.setInputType(android.text.InputType.TYPE_CLASS_NUMBER);
        edit.setFilters(new android.text.InputFilter[]{
                new android.text.InputFilter.LengthFilter(3)});
        androidx.appcompat.app.AlertDialog dialog =
                new com.google.android.material.dialog.MaterialAlertDialogBuilder(this)
                        .setTitle(R.string.pin_recovery_title)
                        .setMessage(R.string.pin_recovery_msg)
                        .setView(v)
                        .setPositiveButton(R.string.ok, null)
                        .setNegativeButton(R.string.cancel, null)
                        .create();
        dialog.setOnShowListener(d -> {
            edit.requestFocus();
            dialog.getButton(androidx.appcompat.app.AlertDialog.BUTTON_POSITIVE)
                    .setOnClickListener(x -> {
                        String code = edit.getText().toString().trim();
                        int level = batteryLevel();
                        if (!code.isEmpty() && level >= 0 && code.equals(String.valueOf(level))) {
                            dialog.dismiss();
                            attempts = 0;
                            setResult(RESULT_OK);
                            finish();
                        } else {
                            edit.setText("");
                            vibrate();
                            com.premiumlab.galleryx.util.Toasts.makeText(this,
                                    R.string.pin_recovery_wrong, android.widget.Toast.LENGTH_SHORT).show();
                        }
                    });
        });
        dialog.show();
    }

    /** Текущий заряд батареи в процентах или -1, если недоступен. */
    private int batteryLevel() {
        try {
            android.os.BatteryManager bm =
                    (android.os.BatteryManager) getSystemService(BATTERY_SERVICE);
            if (bm != null) {
                int v = bm.getIntProperty(android.os.BatteryManager.BATTERY_PROPERTY_CAPACITY);
                if (v >= 0 && v <= 100) return v;
            }
            android.content.Intent b = registerReceiver(null,
                    new android.content.IntentFilter(android.content.Intent.ACTION_BATTERY_CHANGED));
            if (b != null) {
                int level = b.getIntExtra(android.os.BatteryManager.EXTRA_LEVEL, -1);
                int scale = b.getIntExtra(android.os.BatteryManager.EXTRA_SCALE, 100);
                if (level >= 0 && scale > 0) return Math.round(level * 100f / scale);
            }
        } catch (Exception ignored) {
        }
        return -1;
    }

    // ---------- Биометрия (настройка «Вход по отпечатку / лицу») ----------

    private void setupBiometric() {
        View btn = findViewById(R.id.btnPinBio);
        if (!MODE_UNLOCK.equals(mode) || !Prefs.biometric() || !biometricAvailable(this)) {
            btn.setVisibility(View.INVISIBLE);
            return;
        }
        btn.setVisibility(View.VISIBLE);
        btn.setOnClickListener(v -> showBiometric());
        if (getIntent().getBooleanExtra("no_auto_bio", false)) return;
        // Сразу предлагаем отпечаток, PIN остаётся запасным вариантом
        handler.postDelayed(this::showBiometric, 250);
    }

    public static boolean biometricAvailable(android.content.Context ctx) {
        try {
            androidx.biometric.BiometricManager bm = androidx.biometric.BiometricManager.from(ctx);
            int r = bm.canAuthenticate(androidx.biometric.BiometricManager.Authenticators.BIOMETRIC_WEAK);
            return r == androidx.biometric.BiometricManager.BIOMETRIC_SUCCESS;
        } catch (Throwable t) {
            return false;
        }
    }

    private void showBiometric() {
        if (isFinishing()) return;
        try {
            androidx.biometric.BiometricPrompt prompt = new androidx.biometric.BiometricPrompt(
                    this, androidx.core.content.ContextCompat.getMainExecutor(this),
                    new androidx.biometric.BiometricPrompt.AuthenticationCallback() {
                        @Override
                        public void onAuthenticationSucceeded(
                                @androidx.annotation.NonNull androidx.biometric.BiometricPrompt.AuthenticationResult result) {
                            attempts = 0;
                            setResult(RESULT_OK);
                            finish();
                        }
                    });
            androidx.biometric.BiometricPrompt.PromptInfo info =
                    new androidx.biometric.BiometricPrompt.PromptInfo.Builder()
                            .setTitle(getString(R.string.pin_enter_title))
                            .setSubtitle(getString(R.string.app_name))
                            .setNegativeButtonText(getString(R.string.pin_use_code))
                            .setAllowedAuthenticators(
                                    androidx.biometric.BiometricManager.Authenticators.BIOMETRIC_WEAK)
                            .build();
            prompt.authenticate(info);
        } catch (Throwable ignored) {
        }
    }

    private void applyModeTitle() {
        if (MODE_CREATE.equals(mode)) {
            txtTitle.setText(R.string.pin_create_title);
            txtSub.setText(R.string.app_name);
        } else {
            txtTitle.setText(R.string.pin_enter_title);
            txtSub.setText(R.string.app_name);
        }
    }

    private void onDigit(int d) {
        if (System.currentTimeMillis() < lockUntil) return;
        if (input.length() >= PIN_LEN) return;
        input.append(d);
        updateDots();
        if (input.length() == PIN_LEN) {
            handler.postDelayed(this::submit, 120);
        }
    }

    private void onBackspace() {
        if (input.length() > 0) {
            input.deleteCharAt(input.length() - 1);
            updateDots();
        }
    }

    private void submit() {
        String pin = input.toString();
        if (MODE_CREATE.equals(mode)) {
            if (firstAttempt == null) {
                firstAttempt = pin;
                input.setLength(0);
                txtTitle.setText(R.string.pin_confirm_title);
                updateDots();
            } else if (firstAttempt.equals(pin)) {
                savePin(pin);
                setResult(RESULT_OK);
                finish();
            } else {
                firstAttempt = null;
                input.setLength(0);
                txtTitle.setText(R.string.pin_create_title);
                showError(R.string.pin_mismatch);
            }
            return;
        }

        // Режим разблокировки
        String salt = Prefs.pinSalt();
        String candidate = hash(pin + (salt == null ? "" : salt));
        if (candidate.equals(Prefs.pinHash())) {
            attempts = 0;
            setResult(RESULT_OK);
            finish();
        } else {
            attempts++;
            input.setLength(0);
            updateDots();
            if (attempts >= MAX_ATTEMPTS) {
                attempts = 0;
                lockUntil = System.currentTimeMillis() + LOCK_MS;
                handler.post(lockTick);
            } else {
                showError(R.string.pin_wrong);
                vibrate();
            }
        }
    }

    private void updateLockState() {
        long left = lockUntil - System.currentTimeMillis();
        if (left > 0) {
            txtError.setText(getString(R.string.pin_locked_seconds,
                    (int) Math.ceil(left / 1000.0)));
            txtError.setVisibility(View.VISIBLE);
            handler.postDelayed(lockTick, 500);
        } else {
            txtError.setVisibility(View.INVISIBLE);
        }
    }

    private void showError(int resId) {
        if (resId != 0) txtError.setText(getString(resId));
        txtError.setVisibility(View.VISIBLE);
        Animation shake = AnimationUtils.loadAnimation(this, R.anim.shake);
        layoutDots.startAnimation(shake);
        for (View d : dots) {
            d.setBackgroundResource(R.drawable.bg_dot_error);
        }
        handler.postDelayed(() -> {
            txtError.setVisibility(View.INVISIBLE);
            input.setLength(0);
            updateDots();
        }, 700);
    }

    private void vibrate() {
        try {
            Vibrator v = (Vibrator) getSystemService(VIBRATOR_SERVICE);
            if (v == null) return;
            if (android.os.Build.VERSION.SDK_INT >= 26) {
                v.vibrate(VibrationEffect.createOneShot(60,
                        VibrationEffect.DEFAULT_AMPLITUDE));
            } else {
                //noinspection deprecation
                v.vibrate(60);
            }
        } catch (Exception ignored) {
        }
    }

    private void updateDots() {
        for (int i = 0; i < PIN_LEN; i++) {
            if (i < input.length()) {
                dots[i].setBackgroundResource(R.drawable.bg_dot_filled);
            } else {
                dots[i].setBackgroundResource(R.drawable.bg_dot);
            }
        }
    }

    private void savePin(String pin) {
        String salt = newSalt();
        Prefs.setPin(hash(pin + salt), salt);
    }

    private static String newSalt() {
        SecureRandom r = new SecureRandom();
        byte[] b = new byte[16];
        r.nextBytes(b);
        StringBuilder sb = new StringBuilder();
        for (byte x : b) sb.append(String.format(Locale.US, "%02x", x));
        return sb.toString();
    }

    public static String hash(String s) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] digest = md.digest(s.getBytes(StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder();
            for (byte x : digest) sb.append(String.format(Locale.US, "%02x", x));
            return sb.toString();
        } catch (Exception e) {
            return String.valueOf(s.hashCode());
        }
    }

    /** «Назад»: регистрируется через BackCompat (предиктивный жест, targetSdk 36+). */
    private boolean handleBack() {
        setResult(RESULT_CANCELED);
        return false;
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        handler.removeCallbacksAndMessages(null);
    }
}
