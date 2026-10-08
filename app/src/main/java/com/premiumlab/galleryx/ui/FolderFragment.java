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
import com.premiumlab.galleryx.PhotoViewerActivity;
import com.premiumlab.galleryx.R;
import com.premiumlab.galleryx.VideoPlayerActivity;
import com.premiumlab.galleryx.data.MediaEngine;
import com.premiumlab.galleryx.data.MediaItem;
import com.premiumlab.galleryx.data.Prefs;
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

    private void buildSubdirChips() {
        if (chipContainer == null) return;
        List<File> subdirs = MediaEngine.listSubdirs(currentDir);
        if (subdirs.isEmpty()) {
            chipRow.setVisibility(View.GONE);
            return;
        }
        chipRow.setVisibility(View.VISIBLE);
        chipContainer.removeAllViews();
        for (File d : subdirs) {
            TextView chip = (TextView) LayoutInflater.from(requireContext())
                    .inflate(R.layout.item_chip_subdir, chipContainer, false);
            chip.setText(d.getName());
            chip.setOnClickListener(v -> navigateTo(d));
            chipContainer.addView(chip);
        }
    }

    private void navigateTo(File dir) {
        currentDir = dir;
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
    protected void loadMedia() {
        showLoading(true);
        MediaEngine.loadFolder(currentDir, items -> {
            if (!isAdded()) return;
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
        if (item.isVideo) {
            Intent intent = new Intent(requireContext(), VideoPlayerActivity.class);
            intent.putExtra("path", item.path);
            intent.putExtra("name", item.name);
            intent.putExtra("mode", 0);
            startActivity(intent);
        } else {
            ArrayList<String> photos = new ArrayList<>();
            int index = 0;
            for (MediaItem it : shownItems) {
                if (!it.isVideo) {
                    if (it.path.equals(item.path)) index = photos.size();
                    photos.add(it.path);
                }
            }
            Intent intent = new Intent(requireContext(), PhotoViewerActivity.class);
            intent.putStringArrayListExtra("paths", photos);
            intent.putExtra("index", index);
            intent.putExtra("mode", 0);
            startActivity(intent);
        }
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

    private void onCreateFolder() {
        FragmentActivity act = requireActivity();
        CreateFolderDialog.show(act, () -> showRootNeeded(this::onCreateFolder),
                folder -> Toast.makeText(act, R.string.folder_created,
                        Toast.LENGTH_SHORT).show());
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
                    Prefs.setRootPath(target.getAbsolutePath());
                }
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
        Toast.makeText(act, R.string.loading, Toast.LENGTH_SHORT).show();
        new Thread(() -> {
            // Все файлы — в корзину
            List<File> files = new ArrayList<>();
            collectFiles(currentDir, files);
            for (File f : files) {
                File trashDir = com.premiumlab.galleryx.data.TrashStore.trashDir();
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
        }
    }
}
