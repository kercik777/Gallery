package com.premiumlab.galleryx;

import com.premiumlab.galleryx.util.Toasts;
import android.content.Intent;
import android.os.Bundle;
import android.view.View;
import android.view.WindowManager;
import android.widget.Toast;

import androidx.appcompat.app.AppCompatActivity;
import androidx.fragment.app.Fragment;
import androidx.fragment.app.FragmentManager;

import com.premiumlab.galleryx.data.Prefs;
import com.premiumlab.galleryx.ui.SafeFolderFragment;
import com.premiumlab.galleryx.ui.SafeHomeFragment;
import com.premiumlab.galleryx.ui.SafeKindFragment;

/**
 * Сейф: экран защищён PIN-кодом. Без PIN-кода вход невозможен — при первом
 * открытии предлагается его создать. При сворачивании приложения сейф
 * снова закрывается автоматически.
 */
public class SafeActivity extends AppCompatActivity
        implements com.premiumlab.galleryx.ui.EdgeToEdgeHost {

    private static final int REQ_PIN = 301;
    private static final int REQ_PIN_CREATE = 302;

    private View gate, content;
    private boolean passed = false;
    private boolean pinPending = false;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        com.premiumlab.galleryx.util.BackCompat.install(this, this::handleBack);
        com.premiumlab.galleryx.util.Theming.apply(this);
        if (Prefs.flagSecure()) {
            getWindow().addFlags(WindowManager.LayoutParams.FLAG_SECURE);
        }
        setContentView(R.layout.activity_safe);

        gate = findViewById(R.id.layoutSafeGate);
        content = findViewById(R.id.fragmentContainerSafe);
        // Файлы, лежащие в папке сейфа, но отсутствующие в списке — обратно в сейф
        try {
            com.premiumlab.galleryx.data.SafeStore.get().syncFromDisk();
        } catch (Exception ignored) {
        }

        findViewById(R.id.btnOpenSafe).setOnClickListener(v -> askPinIfNeeded());

        FragmentManager fm = getSupportFragmentManager();
        if (fm.findFragmentById(R.id.fragmentContainerSafe) == null) {
            fm.beginTransaction()
                    .replace(R.id.fragmentContainerSafe, new SafeHomeFragment())
                    .commit();
        }
        updateGate();

        // Сейф без PIN-кода не имеет смысла: при первом входе сразу
        // предлагаем создать код. Отказ — выходим из сейфа.
        if (!Prefs.pinSet() && savedInstanceState == null) {
            Toasts.makeText(this, R.string.safe_pin_setup_hint, Toast.LENGTH_SHORT).show();
            Intent intent = new Intent(this, PinActivity.class);
            intent.putExtra("mode", PinActivity.MODE_CREATE);
            pinPending = true;
            startActivityForResult(intent, REQ_PIN_CREATE);
        }
    }

    /**
     * PIN запрашивается сразу, без промежуточного экрана «Сейф защищён».
     * Экран-заглушка остаётся только фоном под окном ввода кода.
     */
    private void askPinIfNeeded() {
        if (pinPending || passed || isFinishing() || !Prefs.pinSet()) return;
        pinPending = true;
        Intent intent = new Intent(this, PinActivity.class);
        intent.putExtra("mode", PinActivity.MODE_UNLOCK);
        startActivityForResult(intent, REQ_PIN);
    }

    private void updateGate() {
        boolean needPin = Prefs.pinSet() && !passed;
        gate.setVisibility(needPin ? View.VISIBLE : View.GONE);
        content.setVisibility(needPin ? View.GONE : View.VISIBLE);
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        pinPending = false;
        if (requestCode == REQ_PIN) {
            if (resultCode == RESULT_OK) {
                passed = true;
                updateGate();
            } else {
                // Отказались вводить код — в сейфе делать нечего
                finish();
            }
        } else if (requestCode == REQ_PIN_CREATE) {
            if (resultCode == RESULT_OK) {
                Toasts.makeText(this, R.string.pin_saved, Toast.LENGTH_SHORT).show();
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
        askPinIfNeeded();
    }

    /** Раздел сейфа: папки с изображениями или с видео. */
    public void openKind(boolean video) {
        getSupportFragmentManager().beginTransaction()
                .setCustomAnimations(android.R.anim.fade_in, android.R.anim.fade_out,
                        android.R.anim.fade_in, android.R.anim.fade_out)
                .replace(R.id.fragmentContainerSafe, SafeKindFragment.newInstance(video))
                .addToBackStack("kind")
                .commit();
    }

    /** Файлы одной папки сейфа. */
    public void openFolder(boolean video, String folder) {
        getSupportFragmentManager().beginTransaction()
                .setCustomAnimations(android.R.anim.fade_in, android.R.anim.fade_out,
                        android.R.anim.fade_in, android.R.anim.fade_out)
                .replace(R.id.fragmentContainerSafe, SafeFolderFragment.newInstance(video, folder))
                .addToBackStack("folder")
                .commit();
    }

    /** «Назад»: регистрируется через BackCompat (предиктивный жест, targetSdk 36+). */
    private boolean handleBack() {
        // Сначала отменяем выделение, потом шаг назад по разделам, потом выход
        Fragment f = getSupportFragmentManager().findFragmentById(R.id.fragmentContainerSafe);
        if (f instanceof com.premiumlab.galleryx.ui.BackHandler
                && ((com.premiumlab.galleryx.ui.BackHandler) f).onBackPressedHandled()) {
            return true;
        }
        // Панели выделения общие для всех экранов сейфа — прячем при смене экрана
        View top = findViewById(R.id.selectionTop);
        View actions = findViewById(R.id.selectionActions);
        if (top != null) top.setVisibility(View.GONE);
        if (actions != null) actions.setVisibility(View.GONE);
        if (getSupportFragmentManager().getBackStackEntryCount() > 0) {
            getSupportFragmentManager().popBackStack();
            return true;
        }
        return false;
    }
}
