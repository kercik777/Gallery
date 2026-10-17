package com.premiumlab.galleryx.ui;

import android.view.LayoutInflater;
import android.view.View;
import android.widget.EditText;
import android.widget.TextView;

import androidx.appcompat.app.AlertDialog;
import androidx.fragment.app.FragmentActivity;
import androidx.fragment.app.FragmentManager;

import com.google.android.material.dialog.MaterialAlertDialogBuilder;
import com.premiumlab.galleryx.R;
import com.premiumlab.galleryx.data.SafeStore;
import com.premiumlab.galleryx.ui.sheet.OptionsSheet;
import com.premiumlab.galleryx.util.Fmt;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Общие диалоги сейфа: выбор папки назначения (для копирования / перемещения
 * внутри сейфа) и создание новой папки. Используются и экраном папки сейфа,
 * и просмотрщиком в режиме сейфа — логика одна.
 */
public final class SafeDialogs {

    public interface OnFolder {
        void pick(String folderKey);
    }

    private SafeDialogs() {
    }

    /**
     * Лист с папками сейфа того же вида (кроме текущей) и пунктом «Новая папка».
     * Выбор возвращается через cb ключом папки (SafeStore.PERSONAL — «Личный»).
     */
    public static void pickFolder(FragmentActivity act, FragmentManager fm, boolean copy,
                                  boolean video, String currentFolder, OnFolder cb) {
        if (act == null || act.isFinishing() || act.isDestroyed()) return;
        OptionsSheet sheet = new OptionsSheet();
        sheet.setHeader(act.getString(copy ? R.string.dest_copy_title : R.string.dest_move_title),
                act.getString(video ? R.string.safe_dest_sub_videos : R.string.safe_dest_sub_photos));
        List<String> targets = new ArrayList<>();
        targets.add(SafeStore.PERSONAL);
        targets.addAll(SafeStore.get().folders(video));
        String cur = currentFolder == null ? SafeStore.PERSONAL : currentFolder;
        for (String key : targets) {
            if (key.equals(cur)) continue;
            int n = SafeStore.get().entries(video, key).size();
            sheet.add(new OptionsSheet.Item(
                    SafeStore.PERSONAL.equals(key) ? R.drawable.ic_shield : R.drawable.ic_folder,
                    SafeKindFragment.displayName(act, key),
                    Fmt.plural(act, R.plurals.items_count, n).toLowerCase(Locale.getDefault()),
                    () -> cb.pick(key)));
        }
        sheet.add(new OptionsSheet.Item(R.drawable.ic_folder_plus,
                act.getString(R.string.dest_new_folder), act.getString(R.string.safe_new_folder_sub),
                () -> newFolder(act, video, cb)));
        try {
            sheet.show(fm, "safe_dest");
        } catch (IllegalStateException ignored) {
            // Состояние уже сохранено (экран уходит) — лист показывать поздно
        }
    }

    /** Диалог создания папки сейфа; при успехе вызывает cb с именем папки. */
    public static void newFolder(FragmentActivity act, boolean video, OnFolder cb) {
        if (act == null || act.isFinishing() || act.isDestroyed()) return;
        View v = LayoutInflater.from(act).inflate(R.layout.dialog_create_folder, null, false);
        EditText edit = v.findViewById(R.id.editFolderName);
        TextView txtError = v.findViewById(R.id.txtFolderError);
        v.findViewById(R.id.txtDialogTitle).setVisibility(View.GONE);
        v.findViewById(R.id.txtFolderPreview).setVisibility(View.GONE);
        AlertDialog dialog = new MaterialAlertDialogBuilder(act)
                .setTitle(R.string.create_folder)
                .setView(v)
                .setPositiveButton(R.string.save, null)
                .setNegativeButton(R.string.cancel, null)
                .create();
        dialog.show();
        dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(b -> {
            String name = edit.getText().toString().trim();
            if (!Fmt.isValidName(name)
                    || name.equalsIgnoreCase(act.getString(R.string.safe_folder_personal))) {
                txtError.setText(R.string.invalid_folder_name);
                txtError.setVisibility(View.VISIBLE);
                return;
            }
            if (!SafeStore.get().addFolder(video, name)) {
                txtError.setText(R.string.folder_exists);
                txtError.setVisibility(View.VISIBLE);
                return;
            }
            dialog.dismiss();
            cb.pick(name);
        });
    }
}
