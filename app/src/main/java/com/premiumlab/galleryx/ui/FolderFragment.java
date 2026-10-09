package com.premiumlab.galleryx.ui;

import android.content.Intent;
import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.widget.EditText;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.appcompat.app.AlertDialog;
import androidx.fragment.app.FragmentActivity;

import com.google.android.material.dialog.MaterialAlertDialogBuilder;
import com.premiumlab.galleryx.FolderActivity;
import com.premiumlab.galleryx.PinActivity;
import com.premiumlab.galleryx.R;
import com.premiumlab.galleryx.data.Album;
import com.premiumlab.galleryx.data.AppDirs;
import com.premiumlab.galleryx.data.LockStore;
import com.premiumlab.galleryx.data.MediaEngine;
import com.premiumlab.galleryx.data.MediaItem;
import com.premiumlab.galleryx.data.Prefs;
import com.premiumlab.galleryx.ui.adapter.MediaAdapter;
import com.premiumlab.galleryx.ui.dialog.CreateFolderDialog;
import com.premiumlab.galleryx.util.Fmt;
import com.premiumlab.galleryx.util.Scan;

import java.io.File;
import java.util.ArrayList;
import java.util.List;

/**
 * Содержимое конкретной папки: медиафайлы, подпапки, меню для пользовательских папок.
 */
public class FolderFragment extends BaseMediaFragment {

    private File currentDir;
    private boolean isUserFolder;
    private final List<Album> subfolders = new ArrayList<>();

    @Override
    protected int layoutRes() {
        return R.layout.fragment_folder;
    }

    @Override
    public void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        String path = requireArguments().getString("path");
        currentDir = new File(path == null ? "/" : path);
        isUserFolder = Prefs.rootPath() != null
                && MediaEngine.isUnder(currentDir.getAbsolutePath(), Prefs.rootPath());
    }

    @Override
    protected void onViewsReady(@NonNull View root) {
        txtHeaderTitle.setText(requireArguments().getString("name", currentDir.getName()));
        if (!isUserFolder) {
            btnHeaderExtra.setVisibility(View.GONE);
        }
        buildSubdirChips();
    }

    /**
     * Вложенные папки показываются карточками (как в «Альбомах») в начале
     * сетки, а не мелкими чипами. Список строится в фоне вместе с медиа.
     */
    private void buildSubdirChips() {
        if (chipRow != null) chipRow.setVisibility(View.GONE);
    }

    @Override
    protected List<MediaAdapter.Row> topRows() {
        if (subfolders.isEmpty()) return new ArrayList<>();
        List<MediaAdapter.Row> rows = new ArrayList<>();
        rows.add(MediaAdapter.Row.folders(getString(R.string.subfolders_title), subfolders));
        return rows;
    }

    @Override
    public void onFolderOpen(Album folder) {
        if (LockStore.isLocked(folder.path)) {
            // Заблокированная подпапка — сначала PIN
            pendingLocked = new File(folder.path);
            Intent intent = new Intent(requireContext(), PinActivity.class);
            intent.putExtra("mode", PinActivity.MODE_UNLOCK);
            startActivityForResult(intent, REQ_PIN_LOCKED);
            return;
        }
        navigateTo(new File(folder.path));
    }

    private static final int REQ_PIN_LOCKED = 731;
    private File pendingLocked;

    /** Внутри папки файлы видны — пользователь уже прошёл PIN при входе. */
    @Override
    protected boolean hidesLockedFolders() {
        return false;
    }

    private void navigateTo(File dir) {
        exitSelection();
        currentDir = dir;
        subfolders.clear();
        allItems.clear();
        isUserFolder = Prefs.rootPath() != null
                && MediaEngine.isUnder(currentDir.getAbsolutePath(), Prefs.rootPath());
        txtHeaderTitle.setText(dir.getName());
        btnHeaderExtra.setVisibility(isUserFolder ? View.VISIBLE : View.GONE);
        buildSubdirChips();
        loadMedia();
    }

    /** Возвращает true, если навигация вверх удалась (иначе — закрыть экран). */
    public boolean goBack() {
        String rootPath = Prefs.rootPath();
        if (rootPath != null && currentDir.getAbsolutePath().equals(rootPath)) {
            return false; // корень — выше некуда
        }
        File startDir = new File(requireArguments().getString("path"));
        if (currentDir.equals(startDir) || currentDir.getParentFile() == null) {
            return false;
        }
        navigateTo(currentDir.getParentFile());
        return true;
    }

    @Override
    protected String destParentPath() {
        return isUserFolder ? currentDir.getAbsolutePath() : null;
    }

    @Override
    protected void loadMedia() {
        showLoading(true);
        final File dir = currentDir;
        final boolean user = isUserFolder;
        // Карточки подпапок — только внутри своих папок (в корневой);
        // папки устройства показываются в «Альбомах» каждая отдельно
        new Thread(() -> {
            List<Album> subs = user ? MediaEngine.subfolderAlbums(dir, true)
                    : new ArrayList<>();
            if (getActivity() == null) return;
            getActivity().runOnUiThread(() -> {
                if (!isAdded() || !dir.equals(currentDir)) return;
                subfolders.clear();
                subfolders.addAll(subs);
                applyFilter();
            });
        }).start();
        MediaEngine.loadFolder(currentDir, items -> {
            if (!isAdded() || !dir.equals(currentDir)) return;
            showLoading(false);
            setData(items);
            if (shownItems.isEmpty() && query.isEmpty()) {
                txtEmptyTitle.setText(R.string.folder_empty_title);
                txtEmptySub.setText(R.string.folder_empty_sub);
            }
        });
    }

    @Override
    protected void onItemOpen(MediaItem item) {
        openViewer(item, 0);
    }

    @Override
    protected void onHeaderExtraClick() {
        if (!isUserFolder) return;
        FragmentActivity act = requireActivity();
        String[] options = {
                getString(R.string.create_folder),
                getString(R.string.rename),
                getString(R.string.delete_folder)
        };
        new MaterialAlertDialogBuilder(act)
                .setTitle(currentDir.getName())
                .setItems(options, (d, which) -> {
                    if (which == 0) onCreateFolder();
                    else if (which == 1) onRenameFolder();
                    else if (which == 2) onDeleteFolder();
                })
                .setNegativeButton(R.string.cancel, null)
                .show();
    }

    /** Создаёт вложенную папку внутри текущей и сразу показывает её карточкой. */
    private void onCreateFolder() {
        FragmentActivity act = requireActivity();
        CreateFolderDialog.showIn(act, currentDir, folder -> {
            if (!isAdded()) return;
            loadMedia();
        });
    }

    private void onRenameFolder() {
        FragmentActivity act = requireActivity();
        View v = LayoutInflater.from(act).inflate(R.layout.dialog_create_folder, null, false);
        EditText edit = v.findViewById(R.id.editFolderName);
        TextView txtTitle = v.findViewById(R.id.txtDialogTitle);
        TextView txtPreview = v.findViewById(R.id.txtFolderPreview);
        txtTitle.setText(R.string.rename_folder_title);
        txtPreview.setVisibility(View.GONE);
        edit.setText(currentDir.getName());

        AlertDialog dialog = new MaterialAlertDialogBuilder(act)
                .setTitle(R.string.rename)
                .setView(v)
                .setPositiveButton(R.string.save, null)
                .setNegativeButton(R.string.cancel, null)
                .create();
        dialog.show();
        dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(btn -> {
            String name = edit.getText().toString().trim();
            if (!Fmt.isValidName(name)) {
                Toast.makeText(act, R.string.invalid_folder_name, Toast.LENGTH_SHORT).show();
                return;
            }
            File parent = currentDir.getParentFile();
            if (parent == null) return;
            File target = new File(parent, name);
            if (target.exists()) {
                Toast.makeText(act, R.string.folder_exists, Toast.LENGTH_SHORT).show();
                return;
            }
            if (currentDir.renameTo(target)) {
                Scan.files(act, currentDir.getAbsolutePath(), target.getAbsolutePath());
                // Обновим корневую папку, если переименовали её
                String rootPath = Prefs.rootPath();
                if (rootPath != null && rootPath.equals(currentDir.getAbsolutePath())) {
                    AppDirs.setRoot(target.getAbsolutePath());
                } else {
                    com.premiumlab.galleryx.data.FavStore.rewritePrefix(
                            currentDir.getAbsolutePath(), target.getAbsolutePath());
                }
                MediaEngine.invalidateAll();
                dialog.dismiss();
                txtHeaderTitle.setText(name);
                currentDir = target;
                loadMedia();
            } else {
                Toast.makeText(act, R.string.error_generic, Toast.LENGTH_SHORT).show();
            }
        });
    }

    private void onDeleteFolder() {
        FragmentActivity act = requireActivity();
        new MaterialAlertDialogBuilder(act)
                .setTitle(R.string.delete_folder)
                .setMessage(act.getString(R.string.delete_folder_confirm, currentDir.getName()))
                .setPositiveButton(R.string.delete, (d, w) -> deleteFolderRecursive(act))
                .setNegativeButton(R.string.cancel, null)
                .show();
    }

    private void deleteFolderRecursive(FragmentActivity act) {
        File trashDir = com.premiumlab.galleryx.data.TrashStore.trashDir();
        if (trashDir == null) {
            showRootNeeded(() -> deleteFolderRecursive(act));
            return;
        }
        Toast.makeText(act, R.string.loading, Toast.LENGTH_SHORT).show();
        new Thread(() -> {
            // Все файлы — в корзину
            List<File> files = new ArrayList<>();
            collectFiles(currentDir, files);
            for (File f : files) {
                File target = new File(trashDir, "t" + System.nanoTime() + "_" + f.getName());
                if (f.renameTo(target)) {
                    com.premiumlab.galleryx.data.TrashStore.get().add(
                            f.getAbsolutePath(), target, f.getName(), f.length(),
                            MediaEngine.isVideoName(f.getName()));
                }
            }
            deleteEmptyTree(currentDir);
            Scan.files(act, currentDir.getAbsolutePath());
            if (act instanceof FolderActivity) {
                act.runOnUiThread(((FolderActivity) act)::finishSmoothly);
            }
        }).start();
    }

    private void collectFiles(File dir, List<File> out) {
        File[] arr = dir.listFiles();
        if (arr == null) return;
        for (File f : arr) {
            if (f.isDirectory()) collectFiles(f, out);
            else if (!f.getName().startsWith(".")) out.add(f);
        }
    }

    private void deleteEmptyTree(File dir) {
        File[] arr = dir.listFiles();
        if (arr != null) {
            for (File f : arr) {
                if (f.isDirectory()) deleteEmptyTree(f);
                //noinspection ResultOfMethodCallIgnored
                f.delete();
            }
        }
        //noinspection ResultOfMethodCallIgnored
        dir.delete();
    }

    @Override
    protected void updateEmptyState() {
        super.updateEmptyState();
        if (shownItems.isEmpty() && query.isEmpty()
                && txtEmptyTitle != null) {
            txtEmptyTitle.setText(R.string.folder_empty_title);
            txtEmptySub.setText(R.string.folder_empty_sub);
        }
    }

    @Override
    protected List<MediaItem> extraFilter(List<MediaItem> input) {
        return input;
    }

    @Override
    public void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode == REQ_ROOT) {
            isUserFolder = Prefs.rootPath() != null
                    && MediaEngine.isUnder(currentDir.getAbsolutePath(), Prefs.rootPath());
            btnHeaderExtra.setVisibility(isUserFolder ? View.VISIBLE : View.GONE);
        } else if (requestCode == REQ_PIN_LOCKED) {
            if (resultCode == FragmentActivity.RESULT_OK && pendingLocked != null) {
                navigateTo(pendingLocked);
            }
            pendingLocked = null;
        }
    }
}
