package com.premiumlab.galleryx;

import android.animation.Animator;
import android.animation.AnimatorListenerAdapter;
import android.animation.ValueAnimator;
import android.annotation.SuppressLint;
import android.content.Intent;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.MotionEvent;
import android.view.View;
import android.view.WindowManager;
import android.widget.ProgressBar;

import androidx.appcompat.app.AppCompatActivity;
import androidx.fragment.app.Fragment;
import androidx.fragment.app.FragmentTransaction;

import com.google.android.material.bottomnavigation.BottomNavigationView;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;
import com.premiumlab.galleryx.data.MaskGuard;
import com.premiumlab.galleryx.data.Prefs;
import com.premiumlab.galleryx.data.SessionManager;
import com.premiumlab.galleryx.ui.AlbumsFragment;
import com.premiumlab.galleryx.ui.BackHandler;
import com.premiumlab.galleryx.ui.BaseMediaFragment;
import com.premiumlab.galleryx.ui.FavoritesFragment;
import com.premiumlab.galleryx.ui.GalleryFragment;
import com.premiumlab.galleryx.ui.SelectionHost;
import com.premiumlab.galleryx.ui.SettingsFragment;
import com.premiumlab.galleryx.util.Perms;

/**
 * Главный экран приложения. Без Splash и онбординга — мгновенный запуск.
 *
 * Режим маскировки: галерея всегда настоящая, но пока она «закрыта» —
 * скрыты корневая папка (все «Мои папки» и их файлы), сейф и пункт
 * настроек маскировки. Открыть: удержание заголовка «Галерея» 3 секунды,
 * при установленном PIN-коде — после его ввода.
 *
 * В режиме выделения bottom bar заменяется рядом действий (нижняя зона
 * фиксированной высоты — сетка не смещается).
 */
public class MainActivity extends AppCompatActivity
        implements SessionManager.Listener, SelectionHost {

    private static final int REQ_PIN = 101;
    private static final long HOLD_MS = 3000L;

    private View layoutReal, layoutPermission;
    private BottomNavigationView bottomNav;
    private View selectionTop, selectionActions;
    private ProgressBar progressHold;

    private final Handler handler = new Handler(Looper.getMainLooper());
    private Runnable holdRunnable;
    private ValueAnimator holdAnimator;
    private boolean holdTriggered;

    private final String[] fragTags = {"gallery", "albums", "favorites", "settings"};
    private String currentTag = null;
    private boolean fragmentsAdded = false;
    /** Состояние маскировки на момент последнего показа — чтобы обновить вкладки. */
    private boolean lastMaskHidden;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        if (Prefs.flagSecure()) {
            getWindow().addFlags(WindowManager.LayoutParams.FLAG_SECURE);
        }
        setContentView(R.layout.activity_main);

        layoutReal = findViewById(R.id.layoutReal);
        layoutPermission = findViewById(R.id.layoutPermission);
        bottomNav = findViewById(R.id.bottomNav);
        selectionTop = findViewById(R.id.selectionTop);
        selectionActions = findViewById(R.id.selectionActions);
        progressHold = findViewById(R.id.progressHold);

        findViewById(R.id.btnGrant).setOnClickListener(v -> requestPermissionsFlow());

        bottomNav.setOnItemSelectedListener(item -> {
            int id = item.getItemId();
            if (id == R.id.nav_gallery) showFragment(0);
            else if (id == R.id.nav_albums) showFragment(1);
            else if (id == R.id.nav_favorites) showFragment(2);
            else if (id == R.id.nav_settings) showFragment(3);
            return true;
        });
    }

    // ---------- Разрешения ----------

    private void requestPermissionsFlow() {
        if (android.os.Build.VERSION.SDK_INT >= 30) {
            new MaterialAlertDialogBuilder(this)
                    .setTitle(R.string.permission_title)
                    .setMessage(R.string.permission_desc)
                    .setPositiveButton(R.string.permission_btn, (d, w) ->
                            Perms.requestAllFiles(this))
                    .setNegativeButton(R.string.cancel, null)
                    .show();
        } else {
            Perms.requestLegacy(this);
        }
    }

    // ---------- Состояние экранов ----------

    @Override
    protected void onResume() {
        super.onResume();
        SessionManager.setListener(this);
        evaluateScreenState();
    }

    @Override
    protected void onPause() {
        super.onPause();
        SessionManager.setListener(null);
        cancelHold();
    }

    @Override
    protected void onStop() {
        super.onStop();
        if (!App.isForeground()
                && Prefs.masking()
                && SessionManager.isUnlocked()
                && Prefs.autoHide() == Prefs.AUTOHIDE_MINIMIZE) {
            SessionManager.lock();
        }
    }

    private void evaluateScreenState() {
        if (!Perms.ok(this)) {
            layoutPermission.setVisibility(View.VISIBLE);
            layoutReal.setVisibility(View.GONE);
            return;
        }
        layoutPermission.setVisibility(View.GONE);
        layoutReal.setVisibility(View.VISIBLE);
        syncChromeWithCurrentFragment();
        if (!ensureFragments() && lastMaskHidden != MaskGuard.hidden()) {
            // Галерея закрылась/открылась, пока экран был не на переднем плане
            // (таймер автоскрытия, сворачивание, кнопка в другой activity)
            refreshAllFragments();
        }
        lastMaskHidden = MaskGuard.hidden();
    }

    /** @return true, если вкладки только что были созданы. */
    private boolean ensureFragments() {
        if (fragmentsAdded) return false;
        fragmentsAdded = true;
        showFragment(0);
        return true;
    }

    private Fragment createFragment(int index) {
        switch (index) {
            case 1:
                return new AlbumsFragment();
            case 2:
                return new FavoritesFragment();
            case 3:
                return new SettingsFragment();
            default:
                return new GalleryFragment();
        }
    }

    /**
     * Показывает вкладку. Фрагменты создаются лениво (только при первом
     * открытии вкладки) — запуск и переключения остаются мгновенными.
     */
    private void showFragment(int index) {
        androidx.fragment.app.FragmentManager fm = getSupportFragmentManager();
        FragmentTransaction tx = fm.beginTransaction();
        for (int i = 0; i < fragTags.length; i++) {
            Fragment f = fm.findFragmentByTag(fragTags[i]);
            if (i == index) {
                if (f == null) {
                    f = createFragment(i);
                    tx.add(R.id.fragmentContainer, f, fragTags[i]);
                }
                tx.show(f);
            } else if (f != null) {
                tx.hide(f);
            }
        }
        tx.commitAllowingStateLoss();
        currentTag = fragTags[index];
    }

    /** Обновляет все созданные вкладки после открытия/закрытия скрытого содержимого. */
    private void refreshAllFragments() {
        lastMaskHidden = MaskGuard.hidden();
        androidx.fragment.app.FragmentManager fm = getSupportFragmentManager();
        for (String tag : fragTags) {
            Fragment f = fm.findFragmentByTag(tag);
            if (f == null || !f.isAdded()) continue;
            if (f instanceof BaseMediaFragment) {
                ((BaseMediaFragment) f).reloadForMask();
            } else if (f instanceof AlbumsFragment) {
                ((AlbumsFragment) f).reload();
            } else if (f instanceof SettingsFragment) {
                ((SettingsFragment) f).refresh();
            }
        }
        syncChromeWithCurrentFragment();
    }

    // ---------- Режим выделения (SelectionHost) ----------

    @Override
    public void onSelectionChanged(int selectedCount) {
        bottomNav.setVisibility(selectedCount > 0 ? View.GONE : View.VISIBLE);
    }

    /** Синхронизирует панели выделения и bottom bar с текущей вкладкой. */
    private void syncChromeWithCurrentFragment() {
        Fragment f = currentTag == null
                ? null : getSupportFragmentManager().findFragmentByTag(currentTag);
        boolean active = false;
        if (f instanceof BaseMediaFragment) {
            active = ((BaseMediaFragment) f).isInSelection();
        } else if (f instanceof AlbumsFragment) {
            active = ((AlbumsFragment) f).isInSelection();
        }
        setContextualChrome(active);
    }

    private void setContextualChrome(boolean active) {
        bottomNav.setVisibility(active ? View.GONE : View.VISIBLE);
        if (selectionTop != null) {
            selectionTop.setVisibility(active ? View.VISIBLE : View.GONE);
        }
        if (selectionActions != null) {
            selectionActions.setVisibility(active ? View.VISIBLE : View.GONE);
        }
    }

    // ---------- Назад ----------

    @Override
    public void onBackPressed() {
        Fragment f = currentTag == null
                ? null : getSupportFragmentManager().findFragmentByTag(currentTag);
        if (f instanceof BackHandler && ((BackHandler) f).onBackPressedHandled()) {
            return; // сначала отменяем выделение, выходим только повторным нажатием
        }
        super.onBackPressed();
    }

    // ---------- Маскировка: удержание заголовка 3 секунды ----------

    /**
     * Делает view «секретной кнопкой»: удержание 3 секунды открывает скрытое
     * содержимое. Вызывается вкладкой «Галерея» для своего заголовка.
     * Когда маскировка выключена или уже открыта — касания проходят как обычно.
     */
    @SuppressLint("ClickableViewAccessibility")
    public void registerHoldTarget(View target) {
        if (target == null) return;
        target.setOnTouchListener((v, event) -> {
            if (!MaskGuard.hidden()) return false;
            switch (event.getActionMasked()) {
                case MotionEvent.ACTION_DOWN:
                    startHold(v);
                    return true;
                case MotionEvent.ACTION_UP:
                case MotionEvent.ACTION_CANCEL:
                    cancelHold();
                    return true;
                default:
                    return false;
            }
        });
    }

    private void startHold(final View v) {
        cancelHold();
        holdTriggered = false;
        v.performHapticFeedback(android.view.HapticFeedbackConstants.VIRTUAL_KEY);
        progressHold.setVisibility(View.VISIBLE);
        progressHold.setProgress(0);

        holdAnimator = ValueAnimator.ofInt(0, 100);
        holdAnimator.setDuration(HOLD_MS);
        holdAnimator.addUpdateListener(a ->
                progressHold.setProgress((int) a.getAnimatedValue()));
        holdAnimator.addListener(new AnimatorListenerAdapter() {
            @Override
            public void onAnimationCancel(Animator a) {
                if (!holdTriggered) {
                    progressHold.setVisibility(View.GONE);
                    progressHold.setProgress(0);
                }
            }
        });
        holdAnimator.start();

        holdRunnable = () -> {
            holdTriggered = true;
            progressHold.setVisibility(View.GONE);
            progressHold.setProgress(0);
            v.performHapticFeedback(android.view.HapticFeedbackConstants.LONG_PRESS);
            attemptUnlock();
        };
        handler.postDelayed(holdRunnable, HOLD_MS);
    }

    private void cancelHold() {
        if (holdRunnable != null) {
            handler.removeCallbacks(holdRunnable);
            holdRunnable = null;
        }
        if (holdAnimator != null) {
            holdAnimator.cancel();
            holdAnimator = null;
        }
        if (progressHold != null) {
            progressHold.setVisibility(View.GONE);
            progressHold.setProgress(0);
        }
    }

    private void attemptUnlock() {
        if (Prefs.pinSet()) {
            Intent intent = new Intent(this, PinActivity.class);
            intent.putExtra("mode", PinActivity.MODE_UNLOCK);
            startActivityForResult(intent, REQ_PIN);
        } else {
            SessionManager.unlock();
            refreshAllFragments();
        }
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode == REQ_PIN && resultCode == RESULT_OK) {
            SessionManager.unlock();
            refreshAllFragments();
        }
    }

    // ---------- Автоскрытие ----------

    @Override
    public void onSessionLocked() {
        if (Prefs.masking()) {
            refreshAllFragments();
        }
    }

    /** Скрыть галерею сейчас (кнопка в ручном режиме). */
    public void hideNow() {
        SessionManager.lock();
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        handler.removeCallbacksAndMessages(null);
    }
}
