package com.premiumlab.galleryx;

import android.content.Intent;
import android.os.Bundle;
import android.view.View;
import android.widget.ImageView;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.app.AppCompatActivity;

import com.google.android.material.dialog.MaterialAlertDialogBuilder;
import com.google.android.material.materialswitch.MaterialSwitch;
import com.premiumlab.galleryx.data.Prefs;
import com.premiumlab.galleryx.data.SessionManager;

/**
 * Настройки режима маскировки: включение, PIN-код, автоскрытие, защита снимков.
 */
public class MaskingSettingsActivity extends AppCompatActivity {

    private static final int REQ_PIN_CREATE = 201;
    private static final int REQ_PIN_DISABLE = 202;
    private static final int REQ_PIN_CHANGE = 203;

    private MaterialSwitch switchMasking, switchPin, switchSecure;
    private TextView txtHeroStatus;
    private View rowChangePin;
    private boolean guard = false;

    private final View[] autoRows = new View[4];
    private final ImageView[] autoChecks = new ImageView[4];

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_masking_settings);

        findViewById(R.id.btnBackMasking).setOnClickListener(v -> finish());

        switchMasking = findViewById(R.id.switchMasking);
        switchPin = findViewById(R.id.switchPin);
        switchSecure = findViewById(R.id.switchSecure);
        txtHeroStatus = findViewById(R.id.txtMaskingHeroStatus);
        rowChangePin = findViewById(R.id.rowChangePin);

        autoRows[0] = findViewById(R.id.rowAutoMinimize);
        autoRows[1] = findViewById(R.id.rowAuto1);
        autoRows[2] = findViewById(R.id.rowAuto5);
        autoRows[3] = findViewById(R.id.rowAutoManual);
        autoChecks[0] = findViewById(R.id.chkMinimize);
        autoChecks[1] = findViewById(R.id.chk1);
        autoChecks[2] = findViewById(R.id.chk5);
        autoChecks[3] = findViewById(R.id.chkManual);

        setupMaskingSwitch();
        setupPinSection();
        setupAutoHide();
        setupSecure();
        refreshStatus();
    }

    // ---------- Маскировка ----------

    private void setupMaskingSwitch() {
        switchMasking.setChecked(Prefs.masking());
        switchMasking.setOnCheckedChangeListener((buttonView, isChecked) -> {
            if (guard) return;
            if (isChecked) {
                new MaterialAlertDialogBuilder(this)
                        .setTitle(R.string.masking_enable_title)
                        .setMessage(R.string.masking_enable_msg)
                        .setPositiveButton(R.string.ok, (d, w) -> {
                            Prefs.setMasking(true);
                            SessionManager.lock();
                            refreshStatus();
                            if (!Prefs.pinSet()) {
                                Toast.makeText(this, R.string.masking_suggest_pin,
                                        Toast.LENGTH_LONG).show();
                            }
                        })
                        .setNegativeButton(R.string.cancel, (d, w) -> {
                            guard = true;
                            switchMasking.setChecked(false);
                            guard = false;
                        })
                        .show();
            } else {
                if (Prefs.pinSet()) {
                    guard = true;
                    switchMasking.setChecked(true);
                    guard = false;
                    Intent intent = new Intent(this, PinActivity.class);
                    intent.putExtra("mode", PinActivity.MODE_UNLOCK);
                    startActivityForResult(intent, REQ_PIN_DISABLE);
                } else {
                    confirmDisable();
                }
            }
        });
    }

    private void confirmDisable() {
        new MaterialAlertDialogBuilder(this)
                .setTitle(R.string.masking_disable_title)
                .setMessage(R.string.masking_disable_msg)
                .setPositiveButton(R.string.ok, (d, w) -> {
                    Prefs.setMasking(false);
                    refreshStatus();
                })
                .setNegativeButton(R.string.cancel, (d, w) -> {
                    guard = true;
                    switchMasking.setChecked(true);
                    guard = false;
                })
                .show();
    }

    // ---------- PIN ----------

    private void setupPinSection() {
        switchPin.setChecked(Prefs.pinSet());
        rowChangePin.setVisibility(Prefs.pinSet() ? View.VISIBLE : View.GONE);
        switchPin.setOnCheckedChangeListener((buttonView, isChecked) -> {
            if (guard) return;
            if (isChecked) {
                Intent intent = new Intent(this, PinActivity.class);
                intent.putExtra("mode", PinActivity.MODE_CREATE);
                startActivityForResult(intent, REQ_PIN_CREATE);
            } else {
                new MaterialAlertDialogBuilder(this)
                        .setTitle(R.string.pin_disabled_confirm_title)
                        .setMessage(R.string.pin_disabled_confirm_msg)
                        .setPositiveButton(R.string.ok, (d, w) -> {
                            Prefs.clearPin();
                            refreshStatus();
                        })
                        .setNegativeButton(R.string.cancel, (d, w) -> {
                            guard = true;
                            switchPin.setChecked(true);
                            guard = false;
                        })
                        .show();
            }
        });

        rowChangePin.setOnClickListener(v -> {
            Intent intent = new Intent(this, PinActivity.class);
            intent.putExtra("mode", PinActivity.MODE_CREATE);
            startActivityForResult(intent, REQ_PIN_CHANGE);
        });
    }

    // ---------- Автоскрытие ----------

    private void setupAutoHide() {
        int[] modes = {Prefs.AUTOHIDE_MINIMIZE, Prefs.AUTOHIDE_1MIN,
                Prefs.AUTOHIDE_5MIN, Prefs.AUTOHIDE_MANUAL};
        for (int i = 0; i < autoRows.length; i++) {
            final int mode = modes[i];
            autoRows[i].setOnClickListener(v -> {
                Prefs.setAutoHide(mode);
                // Если сменили режим при открытой галерее — перепланируем таймер
                if (SessionManager.isUnlocked()) {
                    SessionManager.unlock();
                }
                refreshStatus();
            });
        }
    }

    // ---------- Скриншоты ----------

    private void setupSecure() {
        switchSecure.setChecked(Prefs.flagSecure());
        switchSecure.setOnCheckedChangeListener((buttonView, isChecked) ->
                Prefs.setFlagSecure(isChecked));
    }

    // ---------- Статус ----------

    private void refreshStatus() {
        guard = true;
        switchMasking.setChecked(Prefs.masking());
        switchPin.setChecked(Prefs.pinSet());
        switchSecure.setChecked(Prefs.flagSecure());
        guard = false;

        txtHeroStatus.setText(Prefs.masking()
                ? R.string.masking_status_on : R.string.masking_status_off);
        rowChangePin.setVisibility(Prefs.pinSet() ? View.VISIBLE : View.GONE);

        int current = Prefs.autoHide();
        int[] modes = {Prefs.AUTOHIDE_MINIMIZE, Prefs.AUTOHIDE_1MIN,
                Prefs.AUTOHIDE_5MIN, Prefs.AUTOHIDE_MANUAL};
        for (int i = 0; i < modes.length; i++) {
            boolean selected = modes[i] == current;
            autoChecks[i].setVisibility(selected ? View.VISIBLE : View.INVISIBLE);
            autoRows[i].setBackgroundResource(selected
                    ? R.drawable.bg_row_selected : R.drawable.bg_row_normal);
        }
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode == REQ_PIN_CREATE) {
            if (resultCode == RESULT_OK) {
                Toast.makeText(this, R.string.pin_saved, Toast.LENGTH_SHORT).show();
            }
            refreshStatus();
        } else if (requestCode == REQ_PIN_CHANGE) {
            if (resultCode == RESULT_OK) {
                Toast.makeText(this, R.string.pin_saved, Toast.LENGTH_SHORT).show();
            }
            refreshStatus();
        } else if (requestCode == REQ_PIN_DISABLE && resultCode == RESULT_OK) {
            confirmDisable();
        }
    }

    @Override
    protected void onStop() {
        super.onStop();
        if (!App.isForeground() && Prefs.masking() && SessionManager.isUnlocked()
                && Prefs.autoHide() == Prefs.AUTOHIDE_MINIMIZE) {
            SessionManager.lock();
        }
    }
}
