package com.premiumlab.galleryx;

import android.os.Bundle;
import android.view.WindowManager;

import androidx.appcompat.app.AppCompatActivity;
import androidx.fragment.app.Fragment;
import androidx.fragment.app.FragmentManager;

import com.premiumlab.galleryx.data.Prefs;
import com.premiumlab.galleryx.ui.BackHandler;
import com.premiumlab.galleryx.ui.TrashFragment;

/**
 * Контейнер экрана корзины.
 */
public class TrashActivity extends AppCompatActivity
        implements com.premiumlab.galleryx.ui.EdgeToEdgeHost {

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        com.premiumlab.galleryx.util.BackCompat.install(this, this::handleBack);
        com.premiumlab.galleryx.util.Theming.apply(this);
        if (Prefs.flagSecure()) {
            getWindow().addFlags(WindowManager.LayoutParams.FLAG_SECURE);
        }
        setContentView(R.layout.activity_trash);

        FragmentManager fm = getSupportFragmentManager();
        if (fm.findFragmentById(R.id.fragmentContainerTrash) == null) {
            fm.beginTransaction()
                    .replace(R.id.fragmentContainerTrash, new TrashFragment())
                    .commit();
        }
    }

    /** «Назад»: регистрируется через BackCompat (предиктивный жест, targetSdk 36+). */
    private boolean handleBack() {
        // Сначала отменяем выделение, потом выход
        Fragment f = getSupportFragmentManager().findFragmentById(R.id.fragmentContainerTrash);
        if (f instanceof BackHandler && ((BackHandler) f).onBackPressedHandled()) {
            return true;
        }
        return false;
    }
}
