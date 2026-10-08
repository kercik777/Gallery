package com.premiumlab.galleryx.ui.dialog;

import android.content.Context;
import android.text.Editable;
import android.text.TextWatcher;
import android.view.LayoutInflater;
import android.view.View;
import android.widget.EditText;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.app.AlertDialog;

import com.google.android.material.dialog.MaterialAlertDialogBuilder;
import com.premiumlab.galleryx.R;
import com.premiumlab.galleryx.data.Prefs;
import com.premiumlab.galleryx.util.Fmt;

import java.io.File;

/**
 * Диалог создания папки (внутри корневой или любой выбранной). Предпросмотр пути и валидация.
 */
public final class CreateFolderDialog {

    public interface Callback {
        void onFolderCreated(File folder);
    }

    private CreateFolderDialog() {
    }

    /**
     * Показывает диалог. Если корень не выбран — вызывает onNeedRoot().
     */
    public static void show(Context ctx, Runnable onNeedRoot, Callback callback) {
        String rootPath = Prefs.rootPath();
        if (rootPath == null) {
            if (onNeedRoot != null) onNeedRoot.run();
            return;
        }
        showIn(ctx, new File(rootPath), callback);
    }

    /**
     * Показывает диалог создания папки внутри произвольной родительской папки
     * (вложенные папки любой глубины).
     */
    public static void showIn(Context ctx, File parent, Callback callback) {
        final String rootPath = parent.getAbsolutePath();

        View v = LayoutInflater.from(ctx).inflate(R.layout.dialog_create_folder, null, false);
        EditText edit = v.findViewById(R.id.editFolderName);
        TextView txtPreview = v.findViewById(R.id.txtFolderPreview);
        TextView txtError = v.findViewById(R.id.txtFolderError);

        edit.addTextChangedListener(new TextWatcher() {
            @Override
            public void beforeTextChanged(CharSequence s, int a, int b, int c) {
            }

            @Override
            public void onTextChanged(CharSequence s, int a, int b, int c) {
            }

            @Override
            public void afterTextChanged(Editable s) {
                txtError.setVisibility(View.GONE);
                txtPreview.setText(ctx.getString(R.string.will_be_created,
                        new File(rootPath, s.toString().trim()).getAbsolutePath()));
            }
        });
        txtPreview.setText(ctx.getString(R.string.will_be_created,
                new File(rootPath, "").getAbsolutePath()));

        AlertDialog dialog = new MaterialAlertDialogBuilder(ctx)
                .setTitle(R.string.create_folder)
                .setView(v)
                .setPositiveButton(R.string.save, null)
                .setNegativeButton(R.string.cancel, null)
                .create();
        dialog.show();

        dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(btn -> {
            String name = edit.getText().toString().trim();
            if (!Fmt.isValidName(name)) {
                txtError.setVisibility(View.VISIBLE);
                return;
            }
            File target = new File(rootPath, name);
            if (target.exists()) {
                txtError.setText(R.string.folder_exists);
                txtError.setVisibility(View.VISIBLE);
                return;
            }
            boolean ok = target.mkdirs() || target.isDirectory();
            if (!ok) {
                txtError.setText(R.string.error_generic);
                txtError.setVisibility(View.VISIBLE);
                return;
            }
            dialog.dismiss();
            Toast.makeText(ctx, R.string.folder_created, Toast.LENGTH_SHORT).show();
            if (callback != null) callback.onFolderCreated(target);
        });
    }
}
