package com.premiumlab.galleryx.ui;

import android.view.LayoutInflater;
import android.view.View;
import android.widget.EditText;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.app.AlertDialog;
import androidx.fragment.app.Fragment;
import androidx.fragment.app.FragmentActivity;

import com.google.android.material.dialog.MaterialAlertDialogBuilder;
import com.premiumlab.galleryx.R;
import com.premiumlab.galleryx.data.LockStore;
import com.premiumlab.galleryx.data.MediaEngine;
import com.premiumlab.galleryx.data.MediaItem;
import com.premiumlab.galleryx.ui.dialog.DestSheet;
import com.premiumlab.galleryx.ui.dialog.OpProgressDialog;
import com.premiumlab.galleryx.util.FileOp;
import com.premiumlab.galleryx.util.Fmt;
import com.premiumlab.galleryx.util.Scan;
import com.premiumlab.galleryx.util.Toasts;

import java.io.File;
import java.util.ArrayList;
import java.util.List;

/**
 * Операции над выбранными папками, общие для вкладки «Альбомы» и карточек
 * вложенных папок внутри папки: копирование / перемещение содержимого,
 * переименование, удаление в корзину, блокировка.
 */
public final class FolderOps {

    private FolderOps() {
    }

    /** Все медиафайлы папки и её подпапок (плоским списком), без скрытых. */
    public static List<MediaItem> collectFiles(File dir, int depth) {
        List<MediaItem> out = new ArrayList<>(MediaEngine.listFolder(dir));
        if (depth >= 6) return out;
        for (File sub : MediaEngine.listSubdirs(dir)) {
            out.addAll(collectFiles(sub, depth + 1));
        }
        return out;
    }

    // ---------- Копирование / перемещение ----------

    /**
     * Спрашивает, что переносить — только содержимое или папку целиком, — затем
     * показывает выбор папки назначения и выполняет операцию.
     */
    public static void copyMove(Fragment host, boolean copy, List<File> dirs, Runnable done) {
        if (dirs.isEmpty() || !host.isAdded()) return;
        FragmentActivity act = host.requireActivity();
        // Шторка с двумя явно разделёнными строками «иконка · название · пояснение»
        com.premiumlab.galleryx.ui.sheet.OptionsSheet sheet =
                new com.premiumlab.galleryx.ui.sheet.OptionsSheet();
        sheet.setHeader(act.getString(copy ? R.string.action_copy : R.string.action_move),
                act.getString(R.string.folder_transfer_question));
        sheet.add(new com.premiumlab.galleryx.ui.sheet.OptionsSheet.Item(R.drawable.ic_image,
                act.getString(R.string.folder_transfer_contents),
                act.getString(R.string.folder_transfer_contents_sub),
                () -> pickDest(host, copy, dirs, false, done)));
        sheet.add(new com.premiumlab.galleryx.ui.sheet.OptionsSheet.Item(R.drawable.ic_folder,
                act.getString(R.string.folder_transfer_whole),
                act.getString(R.string.folder_transfer_whole_sub),
                () -> pickDest(host, copy, dirs, true, done)));
        sheet.show(host.getParentFragmentManager(), "transfer_mode");
    }

    private static void pickDest(Fragment host, boolean copy, List<File> dirs, boolean whole,
                                 Runnable done) {
        if (!host.isAdded()) return;
        DestSheet sheet = DestSheet.newInstance(copy);
        sheet.setListener(dir -> {
            if (whole) runCopyMoveDirs(host, copy, dirs, dir, done);
            else runCopyMove(host, copy, dirs, dir, done);
        });
        sheet.show(host.getParentFragmentManager(), "dest");
    }

    /** Переносит папки целиком: A → dest/A (со всеми вложенными папками и файлами). */
    private static void runCopyMoveDirs(Fragment host, boolean copy, List<File> dirs, File dest,
                                        Runnable done) {
        if (!host.isAdded()) return;
        FragmentActivity act = host.requireActivity();
        String tp = dest.getAbsolutePath();
        List<File> valid = new ArrayList<>();
        for (File d : dirs) {
            String dp = d.getAbsolutePath();
            if (tp.equals(dp) || tp.startsWith(dp + "/")) continue;
            valid.add(d);
        }
        if (valid.isEmpty()) {
            Toasts.makeText(act, R.string.album_dest_inside_itself, Toast.LENGTH_SHORT).show();
            return;
        }
        FileOp op = new FileOp(act);
        OpProgressDialog dlg = OpProgressDialog.show(act,
                act.getString(copy ? R.string.op_copy : R.string.op_move), op::cancel);
        FileOp.Done cb = (ok, fail, cancelled) -> {
            dlg.dismiss();
            if (!host.isAdded()) return;
            Toasts.makeText(act, cancelled ? act.getString(R.string.op_cancelled)
                    : Fmt.plural(act, copy ? R.plurals.result_albums_copied
                    : R.plurals.result_albums_moved, copy ? Math.min(ok, valid.size()) : ok),
                    Toast.LENGTH_SHORT).show();
            MediaEngine.invalidateAll();
            if (done != null) done.run();
        };
        if (copy) {
            op.copyDirs(valid, dest, dlg, cb);
        } else {
            op.moveDirs(valid, dest, dlg, cb);
        }
    }

    private static void runCopyMove(Fragment host, boolean copy, List<File> dirs, File dest,
                                    Runnable done) {
        if (!host.isAdded()) return;
        FragmentActivity act = host.requireActivity();
        // Переносим ТОЛЬКО файлы из выбранных папок (включая вложенные),
        // сама папка никуда не копируется и остаётся на месте.
        // Папку в саму себя — пропускаем, в её подпапку — тоже.
        String tp = dest.getAbsolutePath();
        List<MediaItem> items = new ArrayList<>();
        int skipped = 0;
        for (File d : dirs) {
            String dp = d.getAbsolutePath();
            if (tp.equals(dp) || tp.startsWith(dp + "/")) {
                skipped++;
                continue;
            }
            items.addAll(collectFiles(d, 0));
        }
        if (items.isEmpty()) {
            Toasts.makeText(act, skipped == dirs.size()
                    ? R.string.album_dest_inside_itself
                    : R.string.album_no_files, Toast.LENGTH_SHORT).show();
            return;
        }
        FileOp op = new FileOp(act);
        OpProgressDialog dlg = OpProgressDialog.show(act,
                act.getString(copy ? R.string.op_copy : R.string.op_move), op::cancel);
        FileOp.Done cb = (ok, fail, cancelled) -> {
            dlg.dismiss();
            if (!host.isAdded()) return;
            Toasts.makeText(act, cancelled ? act.getString(R.string.op_cancelled)
                    : Fmt.plural(act, copy ? R.plurals.result_copied
                    : R.plurals.result_moved, ok), Toast.LENGTH_SHORT).show();
            MediaEngine.invalidateAll();
            if (done != null) done.run();
        };
        if (copy) {
            op.copy(items, dest, dlg, cb);
        } else {
            op.move(items, dest, dlg, cb);
        }
    }

    // ---------- Переименование ----------

    /** Диалог переименования одной пользовательской папки. */
    public static void rename(FragmentActivity act, File dir, Runnable done) {
        View v = LayoutInflater.from(act).inflate(R.layout.dialog_create_folder, null, false);
        EditText edit = v.findViewById(R.id.editFolderName);
        TextView txtTitle = v.findViewById(R.id.txtDialogTitle);
        v.findViewById(R.id.txtFolderPreview).setVisibility(View.GONE);
        txtTitle.setVisibility(View.GONE);
        edit.setText(dir.getName());
        edit.setSelection(edit.getText().length());

        AlertDialog dialog = new MaterialAlertDialogBuilder(act)
                .setTitle(R.string.rename_folder_title)
                .setView(v)
                .setPositiveButton(R.string.save, null)
                .setNegativeButton(R.string.cancel, null)
                .create();
        dialog.show();
        dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(btn -> {
            String name = edit.getText().toString().trim();
            if (!Fmt.isValidName(name)) {
                Toasts.makeText(act, R.string.invalid_folder_name, Toast.LENGTH_SHORT).show();
                return;
            }
            if (name.equals(dir.getName())) {
                dialog.dismiss();
                return;
            }
            File parent = dir.getParentFile();
            if (parent == null) return;
            File target = new File(parent, name);
            if (target.exists()) {
                Toasts.makeText(act, R.string.folder_exists, Toast.LENGTH_SHORT).show();
                return;
            }
            if (dir.renameTo(target)) {
                com.premiumlab.galleryx.data.FavStore.rewritePrefix(
                        dir.getAbsolutePath(), target.getAbsolutePath());
                LockStore.rewritePrefix(dir.getAbsolutePath(), target.getAbsolutePath());
                com.premiumlab.galleryx.data.NoteStore.rewritePrefix(
                        dir.getAbsolutePath(), target.getAbsolutePath());
                Scan.files(act, dir.getAbsolutePath(), target.getAbsolutePath());
                MediaEngine.invalidateAll();
                dialog.dismiss();
                if (done != null) done.run();
            } else {
                Toasts.makeText(act, R.string.error_generic, Toast.LENGTH_SHORT).show();
            }
        });
    }

    // ---------- Удаление ----------

    /** Подтверждение и перенос содержимого папок в корзину. */
    public static void delete(Fragment host, List<File> dirs, Runnable done) {
        if (dirs.isEmpty() || !host.isAdded()) return;
        FragmentActivity act = host.requireActivity();
        if (!Actions.ensureRoot(act)) return;
        new MaterialAlertDialogBuilder(act)
                .setTitle(R.string.delete_folder)
                .setMessage(R.string.delete_selected_folders_confirm)
                .setPositiveButton(R.string.delete, (d, w) -> {
                    if (!host.isAdded()) return;
                    FileOp op = new FileOp(act);
                    OpProgressDialog dlg = OpProgressDialog.show(act,
                            act.getString(R.string.op_trash), op::cancel);
                    op.trashDirs(dirs, dlg, (ok, fail, cancelled) -> {
                        dlg.dismiss();
                        for (File f : dirs) {
                            if (!f.exists()) LockStore.removeUnder(f.getAbsolutePath());
                        }
                        if (!host.isAdded()) return;
                        Toasts.makeText(act, Fmt.plural(act, R.plurals.result_albums_deleted, ok),
                                Toast.LENGTH_SHORT).show();
                        if (done != null) done.run();
                    });
                })
                .setNegativeButton(R.string.cancel, null)
                .show();
    }

    // ---------- Блокировка ----------

    /** true, если список не пуст и все папки уже заблокированы. */
    public static boolean allLocked(List<String> paths) {
        if (paths.isEmpty()) return false;
        for (String p : paths) if (!LockStore.isLocked(p)) return false;
        return true;
    }

    public static void lock(FragmentActivity act, List<String> paths) {
        LockStore.lock(paths);
        MediaEngine.invalidateAll();
        Toasts.makeText(act, R.string.lock_done, Toast.LENGTH_SHORT).show();
    }

    public static void unlock(FragmentActivity act, List<String> paths) {
        LockStore.unlock(paths);
        MediaEngine.invalidateAll();
        Toasts.makeText(act, R.string.unlock_done, Toast.LENGTH_SHORT).show();
    }
}
