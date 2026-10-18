package com.premiumlab.galleryx.ui;

import com.premiumlab.galleryx.util.Toasts;
import android.annotation.SuppressLint;
import android.content.Intent;
import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.MotionEvent;
import android.view.ScaleGestureDetector;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageView;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.fragment.app.Fragment;
import androidx.fragment.app.FragmentActivity;
import androidx.recyclerview.widget.GridLayoutManager;
import androidx.recyclerview.widget.RecyclerView;
import androidx.recyclerview.widget.SimpleItemAnimator;

import com.google.android.material.dialog.MaterialAlertDialogBuilder;
import com.premiumlab.galleryx.FolderActivity;
import com.premiumlab.galleryx.MainActivity;
import com.premiumlab.galleryx.PinActivity;
import com.premiumlab.galleryx.R;
import com.premiumlab.galleryx.RootPickerActivity;
import com.premiumlab.galleryx.data.Album;
import com.premiumlab.galleryx.data.LockStore;
import com.premiumlab.galleryx.data.MaskGuard;
import com.premiumlab.galleryx.data.MediaEngine;
import com.premiumlab.galleryx.data.MediaItem;
import com.premiumlab.galleryx.data.Prefs;
import com.premiumlab.galleryx.data.SessionManager;
import com.premiumlab.galleryx.ui.adapter.AlbumsAdapter;
import com.premiumlab.galleryx.ui.dialog.CreateFolderDialog;
import com.premiumlab.galleryx.util.Fmt;

import java.io.File;
import java.util.ArrayList;
import java.util.List;

/**
 * Экран альбомов: «Мои папки» (внутри корня) и «Папки устройства».
 * Долгое нажатие на альбом включает режим выделения: можно выбрать несколько
 * папок (или все сразу) и скопировать / переместить их в другой альбом либо
 * удалить (содержимое уходит в корзину).
 * Панели выделения берутся с уровня MainActivity.
 */
public class AlbumsFragment extends Fragment
        implements AlbumsAdapter.Listener, BackHandler {

    private static final int REQ_ROOT = 502;
    private static final int REQ_PICK_PARENT = 503;
    private static final int REQ_PIN_OPEN = 503;
    private static final int REQ_PIN_UNLOCK = 504;

    private Album pendingOpen;

    private RecyclerView recycler;
    private AlbumsAdapter adapter;
    private GridLayoutManager layoutManager;
    private boolean resumedOnce = false;
    private View layoutEmpty, layoutLoading, btnHideNow;
    private View selTop, selActions;
    private TextView txtSelCount;
    private ImageView btnSelAll;
    private Runnable pendingAfterRoot;

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container,
                             @Nullable Bundle savedInstanceState) {
        return inflater.inflate(R.layout.fragment_albums, container, false);
    }

    @Override
    public void onViewCreated(@NonNull View v, @Nullable Bundle savedInstanceState) {
        super.onViewCreated(v, savedInstanceState);
        recycler = v.findViewById(R.id.recyclerAlbums);
        com.premiumlab.galleryx.util.EdgeToEdge.padBottom(recycler);
        layoutEmpty = v.findViewById(R.id.layoutEmptyAlbums);
        layoutLoading = v.findViewById(R.id.layoutLoadingAlbums);
        btnHideNow = v.findViewById(R.id.btnHideNowAlbums);

        adapter = new AlbumsAdapter(requireContext(), this);
        layoutManager = new GridLayoutManager(requireContext(), Prefs.albumColumns());
        layoutManager.setSpanSizeLookup(new GridLayoutManager.SpanSizeLookup() {
            @Override
            public int getSpanSize(int position) {
                int t = adapter.getItemViewType(position);
                return t == AlbumsAdapter.itemTypeSection() || t == AlbumsAdapter.itemTypeSmart()
                        ? layoutManager.getSpanCount() : 1;
            }
        });
        recycler.setLayoutManager(layoutManager);
        setupPinchZoom();
        recycler.setAdapter(adapter);
        recycler.setHasFixedSize(true);
        RecyclerView.ItemAnimator an = recycler.getItemAnimator();
        if (an instanceof SimpleItemAnimator) {
            ((SimpleItemAnimator) an).setSupportsChangeAnimations(false);
        }
        recycler.setItemViewCacheSize(12);

        v.findViewById(R.id.btnNewFolder).setOnClickListener(x -> onCreateFolder());

        setupSelectionChrome();

        updateHideNow();
        btnHideNow.setOnClickListener(x -> {
            SessionManager.lock();
            if (!(requireActivity() instanceof MainActivity)) requireActivity().finish();
        });

        loadAlbums();
    }

    /** Привязка панелей выделения (верхняя панель и нижний ряд) к этому фрагменту. */
    private void setupSelectionChrome() {
        FragmentActivity act = getActivity();
        if (act == null) return;
        selTop = act.findViewById(R.id.selectionTop);
        selActions = act.findViewById(R.id.selectionActions);

        if (selTop != null) {
            selTop.findViewById(R.id.btnSelClose).setOnClickListener(x -> exitSelection());
            btnSelAll = selTop.findViewById(R.id.btnSelAll);
            btnSelAll.setVisibility(View.VISIBLE);
            View more = selTop.findViewById(R.id.btnSelMore);
            if (more != null) more.setVisibility(View.GONE);
            btnSelAll.setOnClickListener(x -> {
                if (adapter == null) return;
                if (adapter.isAllSelected()) {
                    exitSelection();
                } else {
                    adapter.selectAll();
                    updateSelBar();
                }
            });
            txtSelCount = selTop.findViewById(R.id.txtSelCount);
        }
        if (selActions != null) {
            // Для папок: копировать, переместить, удалить (+ выбрать все сверху)
            setActionVisible(R.id.btnSelFavorite, false);
            setActionVisible(R.id.btnSelShare, false);
            setActionVisible(R.id.btnSelSafe, false);
            setActionVisible(R.id.btnSelRestore, false);
            setActionVisible(R.id.btnSelCopy, true);
            setActionVisible(R.id.btnSelMove, true);
            setActionVisible(R.id.btnSelDelete, true);
            setActionVisible(R.id.btnSelRename, false);
            setActionVisible(R.id.btnSelLock, true);
            selActions.findViewById(R.id.btnSelLock).setOnClickListener(x -> onLockSelected());
            selActions.findViewById(R.id.btnSelCopy).setOnClickListener(x -> destFlow(true));
            selActions.findViewById(R.id.btnSelMove).setOnClickListener(x -> destFlow(false));
            selActions.findViewById(R.id.btnSelRename).setOnClickListener(x -> onRenameSelected());
            selActions.findViewById(R.id.btnSelDelete)
                    .setOnClickListener(x -> onDeleteSelected());
        }
        updateSelBar();
    }

    private void setActionVisible(int id, boolean visible) {
        if (selActions == null) return;
        selActions.findViewById(id).setVisibility(visible ? View.VISIBLE : View.GONE);
    }

    private void updateHideNow() {
        btnHideNow.setVisibility(
                Prefs.masking() && Prefs.autoHide() == Prefs.AUTOHIDE_MANUAL
                        && SessionManager.isUnlocked()
                        ? View.VISIBLE : View.GONE);
        // Новые папки создаются в корневой — пока она скрыта, кнопку не показываем
        View v = getView();
        if (v != null) {
            View newFolder = v.findViewById(R.id.btnNewFolder);
            newFolder.setVisibility(MaskGuard.hidden() ? View.GONE : View.VISIBLE);
            // Пустая стеклянная «пилюля» без кнопок выглядела как полоска в углу
            View tools = v.findViewById(R.id.headerTools);
            if (tools != null) {
                boolean any = newFolder.getVisibility() == View.VISIBLE
                        || btnHideNow.getVisibility() == View.VISIBLE;
                tools.setVisibility(any ? View.VISIBLE : View.GONE);
            }
        }
    }

    private void loadAlbums() {
        // Если список уже показан — обновляем тихо, без мигания индикатора.
        // Дисковый кеш тоже мгновенный — спиннер только при первом запуске.
        if ((adapter == null || adapter.getItemCount() == 0)
                && !MediaEngine.hasDiskCache(requireContext())) {
            layoutLoading.setVisibility(View.VISIBLE);
        }
        layoutEmpty.setVisibility(View.GONE);
        MediaEngine.loadAlbums(requireContext(), rows -> {
            if (!isAdded()) return;
            layoutLoading.setVisibility(View.GONE);
            adapter.submit(rows);
            boolean empty = rows.isEmpty();
            layoutEmpty.setVisibility(empty ? View.VISIBLE : View.GONE);
            updateSelBar();
        });
    }

    /** Применяет размер сетки альбомов без перезапуска. */
    private void applyColumns() {
        if (layoutManager != null && recycler != null
                && layoutManager.getSpanCount() != Prefs.albumColumns()) {
            com.premiumlab.galleryx.util.GridZoom.change(recycler, layoutManager,
                    Prefs.albumColumns(), null);
        }
    }

    /** Щипок по сетке альбомов — 1–3 колонки, с анимацией перетекания. */
    private void setupPinchZoom() {
        com.premiumlab.galleryx.util.GridZoom.attach(recycler, layoutManager, 1, 3,
                Prefs::setAlbumColumns);
    }

    /** Тихое обновление (изменился MediaStore); в режиме выделения не трогаем список. */
    public void refreshData() {
        if (!isAdded() || adapter == null || adapter.isSelection()) return;
        loadAlbums();
    }

    /** Перезагрузка списка (после смены состояния маскировки). */
    public void reload() {
        if (!isAdded() || adapter == null) return;
        exitSelection();
        // Кнопки выделения общие — привязывает их только видимая вкладка
        if (!isHidden()) setupSelectionChrome();
        updateHideNow();
        loadAlbums();
    }

    // ---------- Клики по альбомам ----------

    @Override
    public void onAlbumClick(Album album, int position) {
        if (Album.ALL_PATH.equals(album.path)) {
            // Псевдо-альбом «Все» открывается как обычная папка (свой экран
            // со всеми фото и видео), а не переходом на вкладку «Галерея»
            if (adapter.isSelection()) return;
            openAlbum(album);
            return;
        }
        if (adapter.isSelection()) {
            adapter.toggle(album.path);
            updateSelBar();
            return;
        }
        if (LockStore.isLocked(album.path)) {
            // Заблокированная папка открывается только после PIN
            pendingOpen = album;
            Intent intent = new Intent(requireContext(), PinActivity.class);
            intent.putExtra("mode", PinActivity.MODE_UNLOCK);
            startActivityForResult(intent, REQ_PIN_OPEN);
            return;
        }
        openAlbum(album);
    }

    @Override
    public void onSmartClick(com.premiumlab.galleryx.data.SmartAlbum smart) {
        if (adapter.isSelection()) return;
        switch (smart.kind) {
            case com.premiumlab.galleryx.data.SmartAlbum.SAFE:
                startActivity(new Intent(requireContext(), com.premiumlab.galleryx.SafeActivity.class));
                return;
            case com.premiumlab.galleryx.data.SmartAlbum.TRASH:
                startActivity(new Intent(requireContext(), com.premiumlab.galleryx.TrashActivity.class));
                return;
            case com.premiumlab.galleryx.data.SmartAlbum.RESIZER:
                // Из инструментов: фото выбираются на отдельном экране
                startActivity(new Intent(requireContext(),
                        com.premiumlab.galleryx.resizer.ResizerActivity.class));
                return;
            default:
                Intent intent = new Intent(requireContext(), com.premiumlab.galleryx.SmartActivity.class);
                intent.putExtra("kind", smart.kind);
                startActivity(intent);
        }
    }

    private void openAlbum(Album album) {
        Intent intent = new Intent(requireContext(), FolderActivity.class);
        intent.putExtra("path", album.path);
        intent.putExtra("name", album.name);
        startActivity(intent);
    }

    // ---------- Блокировка папок ----------

    /** Все выбранные папки уже заблокированы → действие «Разблокировать». */
    private boolean selectionAllLocked() {
        List<Album> sel = adapter.selectedAlbums();
        if (sel.isEmpty()) return false;
        for (Album a : sel) if (!LockStore.isLocked(a.path)) return false;
        return true;
    }

    private void updateLockButton() {
        if (selActions == null || adapter == null) return;
        boolean unlock = selectionAllLocked();
        ImageView img = selActions.findViewById(R.id.imgSelLock);
        TextView txt = selActions.findViewById(R.id.txtSelLock);
        if (img != null) img.setImageResource(unlock ? R.drawable.ic_lock_open : R.drawable.ic_lock);
        if (txt != null) txt.setText(unlock ? R.string.unlock_folder : R.string.lock_folder);
    }

    private void onLockSelected() {
        List<Album> sel = adapter.selectedAlbums();
        if (sel.isEmpty()) return;
        FragmentActivity act = requireActivity();
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
        if (selectionAllLocked()) {
            // Снятие блокировки — только после ввода PIN
            Intent intent = new Intent(act, PinActivity.class);
            intent.putExtra("mode", PinActivity.MODE_UNLOCK);
            startActivityForResult(intent, REQ_PIN_UNLOCK);
            return;
        }
        List<String> paths = new ArrayList<>();
        for (Album a : sel) paths.add(a.path);
        FolderOps.lock(act, paths);
        exitSelection();
        loadAlbums();
    }

    private void unlockSelected() {
        if (!isAdded()) return;
        List<String> paths = new ArrayList<>();
        for (Album a : adapter.selectedAlbums()) paths.add(a.path);
        FolderOps.unlock(requireActivity(), paths);
        exitSelection();
        loadAlbums();
    }

    @Override
    public void onAlbumLongClick(Album album, int position) {
        if (Album.ALL_PATH.equals(album.path)) return; // «Все» не папка — не выделяется
        if (!adapter.isSelection()) {
            adapter.toggle(album.path);
            updateSelBar();
        }
    }

    private void updateSelBar() {
        if (!isAdded()) return;
        int count = adapter == null ? 0 : adapter.getSelectedCount();
        boolean active = count > 0;
        if (selTop != null) {
            selTop.setVisibility(active ? View.VISIBLE : View.GONE);
            if (active && txtSelCount != null) {
                txtSelCount.setText(Fmt.plural(requireContext(),
                        R.plurals.selected_count, count));
            }
            if (btnSelAll != null && adapter != null) {
                boolean all = adapter.isAllSelected();
                btnSelAll.setImageResource(all
                        ? R.drawable.ic_deselect_all : R.drawable.ic_select_all);
                btnSelAll.setContentDescription(getString(all
                        ? R.string.deselect_all : R.string.select_all));
            }
        }
        if (selActions != null) {
            selActions.setVisibility(active ? View.VISIBLE : View.GONE);
            // Переименование доступно только для одной пользовательской папки
            boolean canRename = false;
            if (count == 1 && !MaskGuard.hidden() && Prefs.rootPath() != null) {
                List<Album> sel = adapter.selectedAlbums();
                canRename = !sel.isEmpty()
                        && MediaEngine.isUnder(sel.get(0).path, Prefs.rootPath());
            }
            setActionVisible(R.id.btnSelRename, canRename);
            updateLockButton();
        }
        if (getActivity() instanceof SelectionHost) {
            ((SelectionHost) getActivity()).onSelectionChanged(count);
        }
    }

    private void exitSelection() {
        if (adapter != null && adapter.isSelection()) {
            adapter.exitSelection();
        }
        updateSelBar();
    }

    @Override
    public boolean onBackPressedHandled() {
        if (adapter != null && adapter.isSelection()) {
            exitSelection();
            return true;
        }
        return false;
    }

    public boolean isInSelection() {
        return adapter != null && adapter.isSelection();
    }

    private List<File> selectedDirs() {
        List<File> out = new ArrayList<>();
        for (Album a : adapter.selectedAlbums()) out.add(new File(a.path));
        return out;
    }

    // ---------- Копирование / перемещение папок ----------

    private void destFlow(boolean copy) {
        List<File> dirs = selectedDirs();
        if (dirs.isEmpty()) return;
        FolderOps.copyMove(this, copy, dirs, () -> {
            exitSelection();
            loadAlbums();
        });
    }

    // ---------- Переименование папки ----------

    private void onRenameSelected() {
        List<Album> sel = adapter.selectedAlbums();
        if (sel.size() != 1) return;
        FolderOps.rename(requireActivity(), new File(sel.get(0).path), () -> {
            if (!isAdded()) return;
            exitSelection();
            loadAlbums();
        });
    }

    // ---------- Удаление папок ----------

    private void onDeleteSelected() {
        List<File> dirs = selectedDirs();
        if (dirs.isEmpty()) return;
        FolderOps.delete(this, dirs, () -> {
            exitSelection();
            loadAlbums();
        });
    }

    // ---------- Создание папки / корневая папка ----------

    private void onCreateFolder() {
        FragmentActivity act = requireActivity();
        // Где создать: в корневой папке приложения или в любом месте памяти устройства
        String[] options = {
                getString(R.string.create_in_root),
                getString(R.string.create_in_device)
        };
        new MaterialAlertDialogBuilder(act)
                .setTitle(R.string.create_folder)
                .setItems(options, (d, which) -> {
                    if (which == 0) {
                        CreateFolderDialog.show(act, () -> showRootNeeded(this::onCreateFolder),
                                folder -> loadAlbums());
                    } else {
                        Intent i = new Intent(act, RootPickerActivity.class);
                        i.putExtra(RootPickerActivity.EXTRA_PICK_ANY, true);
                        i.putExtra(RootPickerActivity.EXTRA_CREATE_ONLY, true);
                        startActivityForResult(i, REQ_PICK_PARENT);
                    }
                })
                .setNegativeButton(R.string.cancel, null)
                .show();
    }

    /** Папка создана в памяти устройства — обновляем список и сразу открываем её. */
    private void createInDevice(File folder) {
        if (!isAdded()) return;
        Toasts.makeText(requireContext(), R.string.folder_created, android.widget.Toast.LENGTH_SHORT).show();
        loadAlbums();
        Intent intent = new Intent(requireContext(), FolderActivity.class);
        intent.putExtra("path", folder.getAbsolutePath());
        intent.putExtra("name", folder.getName());
        startActivity(intent);
    }

    private void showRootNeeded(Runnable after) {
        FragmentActivity act = requireActivity();
        pendingAfterRoot = after;
        new MaterialAlertDialogBuilder(act)
                .setTitle(R.string.root_needed_title)
                .setMessage(R.string.root_needed_desc)
                .setPositiveButton(R.string.continue_btn, (d, w) ->
                        startActivityForResult(new Intent(act, RootPickerActivity.class),
                                REQ_ROOT))
                .setNegativeButton(R.string.cancel, (d, w) -> pendingAfterRoot = null)
                .show();
    }

    @Override
    public void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode == REQ_PICK_PARENT && resultCode == FragmentActivity.RESULT_OK
                && data != null) {
            String path = data.getStringExtra(RootPickerActivity.EXTRA_PATH);
            if (path != null) createInDevice(new File(path));
            return;
        }
        if (requestCode == REQ_ROOT && resultCode == FragmentActivity.RESULT_OK) {
            Runnable r = pendingAfterRoot;
            pendingAfterRoot = null;
            if (r != null) r.run();
        } else if (requestCode == REQ_PIN_OPEN) {
            Album a = pendingOpen;
            pendingOpen = null;
            if (resultCode == FragmentActivity.RESULT_OK && a != null) openAlbum(a);
        } else if (requestCode == REQ_PIN_UNLOCK) {
            if (resultCode == FragmentActivity.RESULT_OK && adapter != null) unlockSelected();
        }
    }

    @Override
    public void onHiddenChanged(boolean hidden) {
        super.onHiddenChanged(hidden);
        if (!isAdded()) return;
        if (hidden) {
            // Уходим с вкладки — выделение сбрасываем, чтобы панели не «зависали»
            exitSelection();
        } else {
            setupSelectionChrome();
            updateHideNow();
            applyColumns();
            loadAlbums();
        }
    }

    @Override
    public void onResume() {
        super.onResume();
        updateHideNow();
        applyColumns();
        if (resumedOnce && !isHidden()) {
            // Вернулись из папки/просмотрщика — обложки и счётчики могли измениться
            loadAlbums();
        }
        resumedOnce = true;
        com.premiumlab.galleryx.data.SelfieIndex.addListener(selfieListener);
    }

    @Override
    public void onPause() {
        super.onPause();
        com.premiumlab.galleryx.data.SelfieIndex.removeListener(selfieListener);
    }

    /** Фоновый анализ лиц нашёл новые селфи — обновляем счётчик «Селфи». */
    private final com.premiumlab.galleryx.data.SelfieIndex.Listener selfieListener = () -> {
        if (isAdded() && !isHidden() && adapter != null && !adapter.isSelection()) loadAlbums();
    };
}
