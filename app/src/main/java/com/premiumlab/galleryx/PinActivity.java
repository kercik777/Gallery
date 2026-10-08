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

    @Override
    public void onBackPressed() {
        setResult(RESULT_CANCELED);
        super.onBackPressed();
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        handler.removeCallbacksAndMessages(null);
    }
}
