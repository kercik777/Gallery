package com.premiumlab.galleryx;

import android.content.Intent;
import android.os.Bundle;
import android.os.Environment;
import android.view.View;
import android.widget.EditText;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.app.AppCompatActivity;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.premiumlab.galleryx.data.AppDirs;
import com.premiumlab.galleryx.data.MediaEngine;
import com.premiumlab.galleryx.ui.adapter.DirAdapter;
import com.premiumlab.galleryx.util.Fmt;

import java.io.File;
import java.util.List;

/**
 * Навигация по каталогам устройства.
 *
 * Режимы:
 * <ul>
 *   <li>по умолчанию — выбор корневой папки приложения (создать новую или
 *       выбрать текущую); служебные папки переезжают в новый корень;</li>
 *   <li>{@link #EXTRA_PICK_ANY} = true — выбор любой папки назначения для
 *       копирования/перемещения; путь возвращается в {@link #EXTRA_PATH}.</li>
 * </ul>
 */
public class RootPickerActivity extends AppCompatActivity {

    public static final String EXTRA_PICK_ANY = "pick_any";
    public static final String EXTRA_START = "start";
    public static final String EXTRA_PATH = "path";

    private File current;
    private TextView txtPath, txtEmpty;
    private DirAdapter adapter;
    private EditText editName;
    private boolean pickAny;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_root_picker);

        pickAny = getIntent().getBooleanExtra(EXTRA_PICK_ANY, false);

        txtPath = findViewById(R.id.txtCurrentPath);
        txtEmpty = findViewById(R.id.txtDirsEmpty);
        editName = findViewById(R.id.editRootName);

        if (pickAny) {
            ((TextView) findViewById(R.id.txtRootTitle)).setText(R.string.dest_pick_title);
            ((TextView) findViewById(R.id.txtRootDesc)).setText(R.string.dest_pick_desc);
            editName.setHint(R.string.folder_name_hint);
            ((TextView) findViewById(R.id.btnCreateRoot)).setText(R.string.dest_create_here_btn);
            ((TextView) findViewById(R.id.btnPickCurrent)).setText(R.string.dest_pick_current_btn);
        } else {
            editName.setText(R.string.app_name);
        }

        findViewById(R.id.btnRootBack).setOnClickListener(v -> {
            if (!goUp()) finish();
        });

        RecyclerView recycler = findViewById(R.id.recyclerDirs);
        recycler.setLayoutManager(new LinearLayoutManager(this));
        adapter = new DirAdapter(this::navigateTo);
        recycler.setAdapter(adapter);

        findViewById(R.id.btnCreateRoot).setOnClickListener(v -> createHere());
        findViewById(R.id.btnPickCurrent).setOnClickListener(v -> pickCurrent());

        File start = Environment.getExternalStorageDirectory();
        String startPath = getIntent().getStringExtra(EXTRA_START);
        if (startPath != null && new File(startPath).isDirectory()) {
            start = new File(startPath);
        }
        navigateTo(start);
    }

    private void navigateTo(File dir) {
        current = dir;
        txtPath.setText(dir.getAbsolutePath());
        List<File> subdirs = MediaEngine.listSubdirs(dir);
        // Не пускаем в служебные каталоги Android
        subdirs.removeIf(f -> f.getName().equals("Android")
                && f.getParentFile() != null
                && f.getParentFile().equals(Environment.getExternalStorageDirectory()));
        adapter.submit(subdirs);
        txtEmpty.setVisibility(subdirs.isEmpty() ? View.VISIBLE : View.GONE);
    }

    private boolean goUp() {
        if (current != null && current.getParentFile() != null
                && !current.equals(Environment.getExternalStorageDirectory())) {
            navigateTo(current.getParentFile());
            return true;
        }
        return false;
    }

    private void createHere() {
        String name = editName.getText().toString().trim();
        if (!Fmt.isValidName(name)) {
            Toast.makeText(this, R.string.invalid_folder_name, Toast.LENGTH_SHORT).show();
            return;
        }
        File dir = new File(current, name);
        if (!(dir.exists() && dir.isDirectory()) && !dir.mkdirs()) {
            Toast.makeText(this, R.string.error_generic, Toast.LENGTH_SHORT).show();
            return;
        }
        deliver(dir);
    }

    private void pickCurrent() {
        deliver(current);
    }

    private void deliver(File dir) {
        if (pickAny) {
            Intent data = new Intent();
            data.putExtra(EXTRA_PATH, dir.getAbsolutePath());
            setResult(RESULT_OK, data);
            finish();
            return;
        }
        AppDirs.setRoot(dir.getAbsolutePath());
        Toast.makeText(this, R.string.root_selected, Toast.LENGTH_SHORT).show();
        setResult(RESULT_OK);
        finish();
    }

    @Override
    public void onBackPressed() {
        if (!goUp()) super.onBackPressed();
    }
}
