package com.premiumlab.galleryx;

import android.content.Intent;
import android.os.Bundle;
import android.view.WindowManager;
import android.widget.ImageView;

import androidx.appcompat.app.AppCompatActivity;

import com.premiumlab.galleryx.data.Prefs;
import com.premiumlab.galleryx.util.Press;

/**
 * Настройки → Поиск дубликатов: выбор, что искать — дубликаты фото
 * или дубликаты видео (как «Фото / Видео» в сейфе). Дальше открывается
 * общий экран сканирования DuplicatesActivity в нужном режиме.
 */
public class DuplicatesHubActivity extends AppCompatActivity {

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        com.premiumlab.galleryx.util.Theming.apply(this);
        if (Prefs.flagSecure()) {
            getWindow().addFlags(WindowManager.LayoutParams.FLAG_SECURE);
        }
        setContentView(R.layout.activity_duplicates_hub);

        ImageView btnBack = findViewById(R.id.btnDupHubBack);
        Press.attach(btnBack);
        btnBack.setOnClickListener(v -> finish());

        android.view.View cardPhoto = findViewById(R.id.cardDupPhoto);
        android.view.View cardVideo = findViewById(R.id.cardDupVideo);
        Press.attach(cardPhoto, 0.98f);
        Press.attach(cardVideo, 0.98f);
        cardPhoto.setOnClickListener(v -> open(false));
        cardVideo.setOnClickListener(v -> open(true));
        com.premiumlab.galleryx.util.EdgeToEdge.padBottom(findViewById(R.id.dupHubRoot));
    }

    private void open(boolean video) {
        Intent i = new Intent(this, DuplicatesActivity.class);
        i.putExtra(DuplicatesActivity.EXTRA_VIDEO, video);
        startActivity(i);
    }
}
