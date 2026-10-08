package com.premiumlab.galleryx.ui;

import android.content.ClipData;
import android.content.Intent;
import android.net.Uri;
import android.widget.Toast;

import androidx.core.content.FileProvider;
import androidx.fragment.app.FragmentActivity;

import com.google.android.material.dialog.MaterialAlertDialogBuilder;
import com.premiumlab.galleryx.R;
import com.premiumlab.galleryx.RootPickerActivity;
import com.premiumlab.galleryx.data.AppDirs;
import com.premiumlab.galleryx.data.FavStore;
import com.premiumlab.galleryx.data.MediaItem;
import com.premiumlab.galleryx.ui.dialog.OpProgressDialog;
import com.premiumlab.galleryx.ui.sheet.InfoSheet;
import com.premiumlab.galleryx.util.FileOp;

import java.io.File;
import java.util.ArrayList;
import java.util.List;

/**
 * Общие действия над выбранными файлами: поделиться, свойства, избранное,
 * перемещение в корзину/сейф, удаление навсегда.
 */
public final class Actions {

    private Actions() {
    }

    public static void share(FragmentActivity act, List<MediaItem> items) {
        if (items.isEmpty()) return;
        try {
            ArrayList<Uri> uris = new ArrayList<>();
            String mime = items.get(0).isVideo ? "video/*" : "image/*";
            boolean mixed = false;
            for (MediaItem it : items) {
                if (it.isVideo != items.get(0).isVideo) mixed = true;
                uris.add(FileProvider.getUriForFile(act,
                        act.getPackageName() + ".fileprovider", new File(it.path)));
            }
            Intent intent;
            if (uris.size() == 1) {
                intent = new Intent(Intent.ACTION_SEND);
                intent.putExtra(Intent.EXTRA_STREAM, uris.get(0));
            } else {
                intent = new Intent(Intent.ACTION_SEND_MULTIPLE);
                intent.putParcelableArrayListExtra(Intent.EXTRA_STREAM, uris);
            }
            intent.setType(mixed ? "*/*" : mime);
            intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
            if (uris.size() > 1) {
                intent.setClipData(ClipData.newRawUri("", uris.get(0)));
                for (Uri u : uris) intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
            }
            act.startActivity(Intent.createChooser(intent, act.getString(R.string.share_via)));
        } catch (Exception e) {
            Toast.makeText(act, R.string.no_app_to_share, Toast.LENGTH_SHORT).show();
        }
    }

    public static void showInfo(FragmentActivity act, MediaItem item) {
        InfoSheet sheet = new InfoSheet();
        sheet.setItem(item);
        sheet.show(act.getSupportFragmentManager(), "info");
    }

    public static void favorite(FragmentActivity act, List<MediaItem> items, Runnable done) {
        if (items.isEmpty()) return;
        boolean anyWasFav = false;
        for (MediaItem it : items) {
            if (FavStore.isFav(it.path)) anyWasFav = true;
        }
        for (MediaItem it : items) {
            FavStore.toggle(it.path);
        }
        Toast.makeText(act,
                anyWasFav ? R.string.fav_removed : R.string.fav_added,
                Toast.LENGTH_SHORT).show();
        if (done != null) done.run();
    }

    /**
     * Проверяет, выбрана ли корневая папка (в ней живут корзина и сейф).
     * Если нет — показывает пояснение и открывает выбор папки; возвращает false.
     */
    public static boolean ensureRoot(FragmentActivity act) {
        if (AppDirs.ready()) return true;
        new MaterialAlertDialogBuilder(act)
                .setTitle(R.string.root_required_title)
                .setMessage(R.string.root_required_msg)
                .setPositiveButton(R.string.root_required_btn, (d, w) ->
                        act.startActivity(new Intent(act, RootPickerActivity.class)))
                .setNegativeButton(R.string.cancel, null)
                .show();
        return false;
    }

    /** Подтверждение и перемещение в корзину. */
    public static void confirmTrash(FragmentActivity act, List<MediaItem> items, Runnable done) {
        if (items.isEmpty()) return;
        if (!ensureRoot(act)) return;
        new MaterialAlertDialogBuilder(act)
                .setTitle(R.string.delete_confirm_title)
                .setMessage(R.string.delete_confirm_text)
                .setPositiveButton(R.string.delete, (d, w) -> {
                    FileOp op = new FileOp(act);
                    OpProgressDialog dlg = OpProgressDialog.show(act,
                            act.getString(R.string.op_trash), op::cancel);
                    op.trash(items, dlg, (ok, fail, cancelled) -> {
                        dlg.dismiss();
                        Toast.makeText(act, com.premiumlab.galleryx.util.Fmt.plural(
                                act, R.plurals.result_trashed, ok), Toast.LENGTH_SHORT).show();
                        if (done != null) done.run();
                    });
                })
                .setNegativeButton(R.string.cancel, null)
                .show();
    }

    /** Подтверждение и перемещение в сейф. */
    public static void confirmSafe(FragmentActivity act, List<MediaItem> items, Runnable done) {
        if (items.isEmpty()) return;
        if (!ensureRoot(act)) return;
        new MaterialAlertDialogBuilder(act)
                .setTitle(R.string.safe_move_confirm_title)
                .setMessage(R.string.safe_move_confirm_text)
                .setPositiveButton(R.string.ok, (d, w) -> {
                    FileOp op = new FileOp(act);
                    OpProgressDialog dlg = OpProgressDialog.show(act,
                            act.getString(R.string.op_safe), op::cancel);
                    op.safe(items, dlg, (ok, fail, cancelled) -> {
                        dlg.dismiss();
                        Toast.makeText(act, com.premiumlab.galleryx.util.Fmt.plural(
                                act, R.plurals.result_safed, ok), Toast.LENGTH_SHORT).show();
                        if (done != null) done.run();
                    });
                })
                .setNegativeButton(R.string.cancel, null)
                .show();
    }

    /** Подтверждение удаления навсегда. */
    public static void confirmDeleteForever(FragmentActivity act, List<MediaItem> items, Runnable done) {
        if (items.isEmpty()) return;
        new MaterialAlertDialogBuilder(act)
                .setTitle(R.string.delete_forever_confirm_title)
                .setMessage(R.string.delete_forever_confirm_text)
                .setPositiveButton(R.string.delete, (d, w) -> {
                    FileOp op = new FileOp(act);
                    OpProgressDialog dlg = OpProgressDialog.show(act,
                            act.getString(R.string.op_delete), op::cancel);
                    op.deleteForever(items, dlg, (ok, fail, cancelled) -> {
                        dlg.dismiss();
                        Toast.makeText(act, com.premiumlab.galleryx.util.Fmt.plural(
                                act, R.plurals.result_deleted, ok), Toast.LENGTH_SHORT).show();
                        if (done != null) done.run();
                    });
                })
                .setNegativeButton(R.string.cancel, null)
                .show();
    }
}
