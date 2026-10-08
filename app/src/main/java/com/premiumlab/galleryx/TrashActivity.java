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
public class TrashActivity extends AppCompatActivity {

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
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

    @Override
    public void onBackPressed() {
        // Сначала отменяем выделение, потом выход
        Fragment f = getSupportFragmentManager().findFragmentById(R.id.fragmentContainerTrash);
        if (f instanceof BackHandler && ((BackHandler) f).onBackPressedHandled()) {
            return;
        }
        super.onBackPressed();
    }
}
