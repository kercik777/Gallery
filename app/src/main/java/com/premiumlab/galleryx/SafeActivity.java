package com.premiumlab.galleryx;

import android.content.Intent;
import android.os.Bundle;
import android.view.View;
import android.view.WindowManager;
import android.widget.Toast;

import androidx.appcompat.app.AppCompatActivity;
import androidx.fragment.app.Fragment;
import androidx.fragment.app.FragmentManager;

import com.premiumlab.galleryx.data.Prefs;
import com.premiumlab.galleryx.ui.SafeFragment;

/**
 * Сейф: экран защищён PIN-кодом. Без PIN-кода вход невозможен — при первом
 * открытии предлагается его создать. При сворачивании приложения сейф
 * снова закрывается автоматически.
 */
public class SafeActivity extends AppCompatActivity {

    private static final int REQ_PIN = 301;
    private static final int REQ_PIN_CREATE = 302;

    private View gate, content;
    private boolean passed = false;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        if (Prefs.flagSecure()) {
            getWindow().addFlags(WindowManager.LayoutParams.FLAG_SECURE);
        }
        setContentView(R.layout.activity_safe);

        gate = findViewById(R.id.layoutSafeGate);
        content = findViewById(R.id.fragmentContainerSafe);

        findViewById(R.id.btnOpenSafe).setOnClickListener(v -> {
            Intent intent = new Intent(this, PinActivity.class);
            intent.putExtra("mode", PinActivity.MODE_UNLOCK);
            startActivityForResult(intent, REQ_PIN);
        });

        FragmentManager fm = getSupportFragmentManager();
        if (fm.findFragmentById(R.id.fragmentContainerSafe) == null) {
            fm.beginTransaction()
                    .replace(R.id.fragmentContainerSafe, new SafeFragment())
                    .commit();
        }
        updateGate();

        // Сейф без PIN-кода не имеет смысла: при первом входе сразу
        // предлагаем создать код. Отказ — выходим из сейфа.
        if (!Prefs.pinSet() && savedInstanceState == null) {
            Toast.makeText(this, R.string.safe_pin_setup_hint, Toast.LENGTH_SHORT).show();
            Intent intent = new Intent(this, PinActivity.class);
            intent.putExtra("mode", PinActivity.MODE_CREATE);
            startActivityForResult(intent, REQ_PIN_CREATE);
        }
    }

    private void updateGate() {
        boolean needPin = Prefs.pinSet() && !passed;
        gate.setVisibility(needPin ? View.VISIBLE : View.GONE);
        content.setVisibility(needPin ? View.GONE : View.VISIBLE);
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode == REQ_PIN && resultCode == RESULT_OK) {
            passed = true;
            updateGate();
        } else if (requestCode == REQ_PIN_CREATE) {
            if (resultCode == RESULT_OK) {
                Toast.makeText(this, R.string.pin_saved, Toast.LENGTH_SHORT).show();
                // Код только что создан — пользователь уже «прошёл» проверку
                passed = true;
                updateGate();
            } else {
                finish();
            }
        }
    }

    @Override
    protected void onStop() {
        super.onStop();
        if (!App.isForeground()) {
            passed = false;
        }
    }

    @Override
    protected void onResume() {
        super.onResume();
        updateGate();
    }

    @Override
    public void onBackPressed() {
        // Сначала отменяем выделение, потом выход
        Fragment f = getSupportFragmentManager().findFragmentById(R.id.fragmentContainerSafe);
        if (f instanceof com.premiumlab.galleryx.ui.BackHandler
                && ((com.premiumlab.galleryx.ui.BackHandler) f).onBackPressedHandled()) {
            return;
        }
        super.onBackPressed();
    }
}
