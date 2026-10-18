package com.premiumlab.galleryx.ui;

import com.premiumlab.galleryx.util.Toasts;
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
    /** Псевдо-альбом «Все»: папки на диске нет, показываем всю галерею списком. */
    private boolean isAll;
    private final List<Album> subfolders = new ArrayList<>();
    /** Позиция прокрутки каждой посещённой папки: [позиция, сдвиг px]. */
    private final java.util.Map<String, int[]> scrollMem = new java.util.HashMap<>();
    /** Папка, чьё содержимое сейчас на экране (позицию трогаем только при смене). */
    private String loadedDirPath;

    @Override
    protected int layoutRes() {
        return R.layout.fragment_folder;
    }

    @Override
    public void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        String path = requireArguments().getString("path");
        isAll = Album.ALL_PATH.equals(path);
        currentDir = new File(path == null ? "/" : path);
        isUserFolder = !isAll && Prefs.rootPath() != null
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
        // Ряд чипов занят фильтром «Все / Фото / Видео» (см. BaseMediaFragment)
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
    private static final int REQ_PIN_UNLOCK_SEL = 732;
    private File pendingLocked;

    // ---------- Действия над выбранными подпапками ----------

    @Override
    protected void configureFolderSelectionBar(List<Album> sel) {
        View rename = selActions.findViewById(R.id.btnSelRename);
        if (rename != null) {
            rename.setVisibility(sel.size() == 1 ? View.VISIBLE : View.GONE);
        }
        List<String> paths = new ArrayList<>();
        for (Album a : sel) paths.add(a.path);
        boolean unlock = FolderOps.allLocked(paths);
        android.widget.ImageView img = selActions.findViewById(R.id.imgSelLock);
        TextView txt = selActions.findViewById(R.id.txtSelLock);
        if (img != null) img.setImageResource(unlock ? R.drawable.ic_lock_open : R.drawable.ic_lock);
        if (txt != null) txt.setText(unlock ? R.string.unlock_folder : R.string.lock_folder);
    }

    @Override
    protected void onFolderAction(int actionId) {
        if (adapter == null) return;
        List<File> dirs = selectedFolderDirs();
        if (dirs.isEmpty()) return;
        Runnable done = () -> {
            if (!isAdded()) return;
            MediaEngine.invalidateAll();
            exitSelection();
            loadMedia();
        };
        if (actionId == R.id.btnSelCopy || actionId == R.id.btnSelMove) {
            FolderOps.copyMove(this, actionId == R.id.btnSelCopy, dirs, done);
        } else if (actionId == R.id.btnSelDelete) {
            FolderOps.delete(this, dirs, done);
        } else if (actionId == R.id.btnSelRename) {
            if (dirs.size() == 1) FolderOps.rename(requireActivity(), dirs.get(0), done);
        } else if (actionId == R.id.btnSelLock) {
            lockSelectedFolders(done);
        }
    }

    private void lockSelectedFolders(Runnable done) {
        FragmentActivity act = requireActivity();
        List<String> paths = new ArrayList<>();
        for (Album a : adapter.selectedFolders()) paths.add(a.path);
        if (paths.isEmpty()) return;
        if (!Prefs.pinSet()) {
            // Без PIN блокировка бессмысленна — отправляем создать код
            new MaterialAlertDialogBuilder(act)
                    .setTitle(R.string.pin_required_title)
                    .setMessage(R.string.pin_required_lock_msg)
                    .setPositiveButton(R.string.pin_required_btn, (d, w) -> {
                        Intent intent = new Intent(act, PinActivity.class);
                        intent.putExtra("mode", PinActivity.MODE_CREATE);
                        startActivity(intent);
                    })
                    .setNegativeButton(R.string.cancel, null)
                    .show();
            return;
        }
        if (FolderOps.allLocked(paths)) {
            // Снятие блокировки — только после ввода PIN
            Intent intent = new Intent(act, PinActivity.class);
            intent.putExtra("mode", PinActivity.MODE_UNLOCK);
            startActivityForResult(intent, REQ_PIN_UNLOCK_SEL);
            return;
        }
        FolderOps.lock(act, paths);
        done.run();
    }

    /** Внутри папки файлы видны — пользователь уже прошёл PIN при входе. */
    @Override
    protected boolean hidesLockedFolders() {
        return false;
    }

    private void navigateTo(File dir) {
        saveScroll();
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

    /** Запоминает позицию прокрутки текущей папки перед уходом из неё. */
    private void saveScroll() {
        if (recycler == null || layoutManager == null || currentDir == null) return;
        int pos = layoutManager.findFirstVisibleItemPosition();
        if (pos < 0) return;
        View child = layoutManager.findViewByPosition(pos);
        int off = child != null ? child.getTop() - recycler.getPaddingTop() : 0;
        scrollMem.put(currentDir.getAbsolutePath(), new int[]{pos, off});
    }

    /** Куда встать после загрузки: сохранённая позиция папки или самый верх. */
    private void restoreScroll(File dir) {
        if (recycler == null || layoutManager == null) return;
        int[] mem = scrollMem.get(dir.getAbsolutePath());
        if (mem != null) {
            layoutManager.scrollToPositionWithOffset(mem[0], mem[1]);
        } else {
            // Свежий вход в папку — всегда с самого верха, где подпапки
            layoutManager.scrollToPositionWithOffset(0, 0);
        }
    }

    /** Отложенный индикатор: быстрые папки открываются без «Загрузка…». */
    private Runnable pendingSpinner;

    @Override
    protected void loadMedia() {
        // Спиннер — только если загрузка затянулась (>180 мс): раньше он
        // мигал при каждом входе в папку, хотя файлы уже были готовы
        if (recycler != null) {
            if (pendingSpinner != null) recycler.removeCallbacks(pendingSpinner);
            final File spinDir = currentDir;
            pendingSpinner = () -> {
                if (isAdded() && spinDir.equals(currentDir)) showLoading(true);
            };
            recycler.postDelayed(pendingSpinner, 180);
        }
        final File dir = currentDir;
        final boolean user = isUserFolder;
        // Карточки подпапок — только внутри своих папок (в корневой);
        // папки устройства показываются в «Альбомах» каждая отдельно.
        // Подпапки и файлы грузятся ОДНИМ пакетом в фоне и ставятся одним
        // обновлением: раньше два потока приходили по очереди, список
        // мигал, а сетка «якорилась» на первом файле мимо папок.
        final android.content.Context appCtx = getContext() != null
                ? getContext().getApplicationContext() : null;
        new Thread(() -> {
            List<Album> subs = user ? MediaEngine.subfolderAlbums(dir, true)
                    : new ArrayList<>();
            // «Все» — не папка: список берём как общую галерею целиком
            List<MediaItem> items = isAll
                    ? (appCtx != null ? MediaEngine.queryAll(appCtx) : new ArrayList<>())
                    : MediaEngine.listFolder(dir);
            if (getActivity() == null) return;
            getActivity().runOnUiThread(() -> {
                if (!isAdded() || !dir.equals(currentDir)) return;
                if (recycler != null && pendingSpinner != null) {
                    recycler.removeCallbacks(pendingSpinner);
                }
                showLoading(false);
                // Позицию устанавливаем только при СМЕНЕ папки: при обычном
                // обновлении того же экрана (вернулись из просмотрщика,
                // создали папку) список должен стоять там, где стоял
                boolean freshDir = !dir.getAbsolutePath().equals(loadedDirPath);
                subfolders.clear();
                subfolders.addAll(subs);
                setData(items);
                if (freshDir) {
                    restoreScroll(dir);
                    loadedDirPath = dir.getAbsolutePath();
                }
                if (shownItems.isEmpty() && query.isEmpty()) {
                    txtEmptyTitle.setText(R.string.folder_empty_title);
                    txtEmptySub.setText(R.string.folder_empty_sub);
                }
            });
        }, "folder-load").start();
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
        txtTitle.setVisibility(View.GONE);
        txtPreview.setVisibility(View.GONE);
        edit.setText(currentDir.getName());

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
            File parent = currentDir.getParentFile();
            if (parent == null) return;
            File target = new File(parent, name);
            if (target.exists()) {
                Toasts.makeText(act, R.string.folder_exists, Toast.LENGTH_SHORT).show();
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
                    LockStore.rewritePrefix(currentDir.getAbsolutePath(), target.getAbsolutePath());
                    com.premiumlab.galleryx.data.NoteStore.rewritePrefix(
                            currentDir.getAbsolutePath(), target.getAbsolutePath());
                }
                MediaEngine.invalidateAll();
                dialog.dismiss();
                txtHeaderTitle.setText(name);
                currentDir = target;
                loadMedia();
            } else {
                Toasts.makeText(act, R.string.error_generic, Toast.LENGTH_SHORT).show();
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
        Toasts.makeText(act, R.string.loading, Toast.LENGTH_SHORT).show();
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
        } else if (requestCode == REQ_PIN_UNLOCK_SEL) {
            if (resultCode == FragmentActivity.RESULT_OK && adapter != null) {
                List<String> paths = new ArrayList<>();
                for (Album a : adapter.selectedFolders()) paths.add(a.path);
                if (!paths.isEmpty()) FolderOps.unlock(requireActivity(), paths);
                MediaEngine.invalidateAll();
                exitSelection();
                loadMedia();
            }
        }
    }
}
