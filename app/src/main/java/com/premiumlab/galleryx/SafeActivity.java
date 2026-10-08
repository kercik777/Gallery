package com.premiumlab.galleryx;

import android.content.Intent;
import android.os.Bundle;
import android.view.View;
import android.view.WindowManager;

import androidx.appcompat.app.AppCompatActivity;
import androidx.fragment.app.Fragment;
import androidx.fragment.app.FragmentManager;

import com.premiumlab.galleryx.data.Prefs;
import com.premiumlab.galleryx.ui.SafeFragment;

/**
 * Сейф: экран защищён PIN-кодом (если PIN установлен).
 * При сворачивании приложения сейф снова закрывается.
 */
public class SafeActivity extends AppCompatActivity {

    private static final int REQ_PIN = 301;

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
    }

    private void updateGate() {
        boolean needPin = Prefs.pinSet() && !passed;
        gate.setVisibility(needPin ? View.VISIBLE : View.GONE);
        content.setVisibility(needPin ? View.GONE : View.VISIBLE);
    }

    public void lockSafe() {
        passed = false;
        updateGate();
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode == REQ_PIN && resultCode == RESULT_OK) {
            passed = true;
            updateGate();
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
