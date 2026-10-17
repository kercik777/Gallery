package com.premiumlab.galleryx;

import android.content.Intent;
import android.os.Bundle;
import android.view.WindowManager;

import androidx.appcompat.app.AppCompatActivity;
import androidx.fragment.app.FragmentManager;

import com.premiumlab.galleryx.data.Prefs;
import com.premiumlab.galleryx.ui.FolderFragment;

/**
 * Контейнер экрана папки.
 */
public class FolderActivity extends AppCompatActivity
        implements com.premiumlab.galleryx.ui.EdgeToEdgeHost {

    private FolderFragment fragment;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        com.premiumlab.galleryx.util.BackCompat.install(this, this::handleBack);
        com.premiumlab.galleryx.util.Theming.apply(this);
        if (Prefs.flagSecure()) {
            getWindow().addFlags(WindowManager.LayoutParams.FLAG_SECURE);
        }
        setContentView(R.layout.activity_folder);

        String path = getIntent().getStringExtra("path");
        String name = getIntent().getStringExtra("name");
        if (path == null) {
            finish();
            return;
        }

        FragmentManager fm = getSupportFragmentManager();
        fragment = (FolderFragment) fm.findFragmentById(R.id.fragmentContainerFolder);
        if (fragment == null) {
            fragment = new FolderFragment();
            Bundle args = new Bundle();
            args.putString("path", path);
            args.putString("name", name);
            fragment.setArguments(args);
            fm.beginTransaction()
                    .replace(R.id.fragmentContainerFolder, fragment)
                    .commit();
        }
    }

    /** Плавное завершение после удаления папки. */
    public void finishSmoothly() {
        runOnUiThread(this::finish);
    }

    /** «Назад»: регистрируется через BackCompat (предиктивный жест, targetSdk 36+). */
    private boolean handleBack() {
        // Сначала отменяем выделение, потом навигация вверх по подпапкам
        // (Oglum → Masgalam → … до папки, с которой открыли экран), потом выход
        if (fragment != null && fragment.onBackPressedHandled()) {
            return true;
        }
        if (fragment != null && fragment.goBack()) {
            return true;
        }
        return false;
    }
}
