package com.premiumlab.galleryx;

import android.os.Bundle;
import android.view.WindowManager;

import androidx.appcompat.app.AppCompatActivity;
import androidx.fragment.app.Fragment;
import androidx.fragment.app.FragmentManager;

import com.premiumlab.galleryx.data.Prefs;
import com.premiumlab.galleryx.ui.BackHandler;
import com.premiumlab.galleryx.ui.SearchFragment;

/** Полноэкранный «Поиск» (как в GorillaGallery): контейнер для {@link SearchFragment}. */
public class SearchActivity extends AppCompatActivity
        implements com.premiumlab.galleryx.ui.EdgeToEdgeHost {

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        com.premiumlab.galleryx.util.BackCompat.install(this, this::handleBack);
        com.premiumlab.galleryx.util.Theming.apply(this);
        if (Prefs.flagSecure()) {
            getWindow().addFlags(WindowManager.LayoutParams.FLAG_SECURE);
        }
        setContentView(R.layout.activity_folder);
        FragmentManager fm = getSupportFragmentManager();
        if (fm.findFragmentById(R.id.fragmentContainerFolder) == null) {
            fm.beginTransaction()
                    .replace(R.id.fragmentContainerFolder, new SearchFragment())
                    .commit();
        }
    }

    /** «Назад»: регистрируется через BackCompat (предиктивный жест, targetSdk 36+). */
    private boolean handleBack() {
        Fragment f = getSupportFragmentManager().findFragmentById(R.id.fragmentContainerFolder);
        if (f instanceof BackHandler && ((BackHandler) f).onBackPressedHandled()) {
            return true;
        }
        finish();
        overridePendingTransition(android.R.anim.fade_in, android.R.anim.fade_out);
        return true;
    }
}
