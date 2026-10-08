package com.premiumlab.galleryx;

import android.os.Bundle;
import android.os.Environment;
import android.view.View;
import android.widget.EditText;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.app.AppCompatActivity;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.premiumlab.galleryx.data.Prefs;
import com.premiumlab.galleryx.data.MediaEngine;
import com.premiumlab.galleryx.ui.adapter.DirAdapter;
import com.premiumlab.galleryx.util.Fmt;

import java.io.File;
import java.util.List;

/**
 * Выбор корневой папки приложения: навигация по каталогам устройства,
 * создание новой папки в выбранном месте или выбор текущей.
 */
public class RootPickerActivity extends AppCompatActivity {

    private File current;
    private TextView txtPath, txtEmpty;
    private DirAdapter adapter;
    private EditText editName;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_root_picker);

        txtPath = findViewById(R.id.txtCurrentPath);
        txtEmpty = findViewById(R.id.txtDirsEmpty);
        editName = findViewById(R.id.editRootName);
        editName.setText(R.string.app_name);

        findViewById(R.id.btnRootBack).setOnClickListener(v -> {
            if (current != null && current.getParentFile() != null
                    && !current.equals(Environment.getExternalStorageDirectory())) {
                navigateTo(current.getParentFile());
            } else {
                finish();
            }
        });

        RecyclerView recycler = findViewById(R.id.recyclerDirs);
        recycler.setLayoutManager(new LinearLayoutManager(this));
        adapter = new DirAdapter(this::navigateTo);
        recycler.setAdapter(adapter);

        findViewById(R.id.btnCreateRoot).setOnClickListener(v -> createRootHere());
        findViewById(R.id.btnPickCurrent).setOnClickListener(v -> pickCurrent());

        navigateTo(Environment.getExternalStorageDirectory());
    }

    private void navigateTo(File dir) {
        current = dir;
        txtPath.setText(dir.getAbsolutePath());
        List<File> subdirs = MediaEngine.listSubdirs(dir);
        // Не пускаем в служебные каталоги Android
        subdirs.removeIf(f -> f.getName().equals("Android"));
        adapter.submit(subdirs);
        txtEmpty.setVisibility(subdirs.isEmpty() ? View.VISIBLE : View.GONE);
    }

    private void createRootHere() {
        String name = editName.getText().toString().trim();
        if (!Fmt.isValidName(name)) {
            Toast.makeText(this, R.string.invalid_folder_name, Toast.LENGTH_SHORT).show();
            return;
        }
        File root = new File(current, name);
        if (root.exists() && root.isDirectory()) {
            Prefs.setRootPath(root.getAbsolutePath());
            Toast.makeText(this, R.string.root_selected, Toast.LENGTH_SHORT).show();
            setResult(RESULT_OK);
            finish();
            return;
        }
        if (root.mkdirs()) {
            Prefs.setRootPath(root.getAbsolutePath());
            Toast.makeText(this, R.string.root_selected, Toast.LENGTH_SHORT).show();
            setResult(RESULT_OK);
            finish();
        } else {
            Toast.makeText(this, R.string.error_generic, Toast.LENGTH_SHORT).show();
        }
    }

    private void pickCurrent() {
        Prefs.setRootPath(current.getAbsolutePath());
        Toast.makeText(this, R.string.root_selected, Toast.LENGTH_SHORT).show();
        setResult(RESULT_OK);
        finish();
    }

    @Override
    public void onBackPressed() {
        if (current != null && current.getParentFile() != null
                && !current.equals(Environment.getExternalStorageDirectory())) {
            navigateTo(current.getParentFile());
        } else {
            super.onBackPressed();
        }
    }
}
