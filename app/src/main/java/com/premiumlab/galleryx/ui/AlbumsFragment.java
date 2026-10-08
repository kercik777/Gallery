package com.premiumlab.galleryx.ui;

import android.content.Intent;
import android.os.Bundle;
import android.view.LayoutInflater;
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
import com.premiumlab.galleryx.R;
import com.premiumlab.galleryx.RootPickerActivity;
import com.premiumlab.galleryx.data.Album;
import com.premiumlab.galleryx.data.MaskGuard;
import com.premiumlab.galleryx.data.MediaEngine;
import com.premiumlab.galleryx.data.Prefs;
import com.premiumlab.galleryx.data.SessionManager;
import com.premiumlab.galleryx.ui.adapter.AlbumsAdapter;
import com.premiumlab.galleryx.ui.dialog.CreateFolderDialog;
import com.premiumlab.galleryx.ui.dialog.DestSheet;
import com.premiumlab.galleryx.ui.dialog.OpProgressDialog;
import com.premiumlab.galleryx.util.FileOp;
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

    private RecyclerView recycler;
    private AlbumsAdapter adapter;
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
        layoutEmpty = v.findViewById(R.id.layoutEmptyAlbums);
        layoutLoading = v.findViewById(R.id.layoutLoadingAlbums);
        btnHideNow = v.findViewById(R.id.btnHideNowAlbums);

        adapter = new AlbumsAdapter(requireContext(), this);
        GridLayoutManager lm = new GridLayoutManager(requireContext(), 2);
        lm.setSpanSizeLookup(new GridLayoutManager.SpanSizeLookup() {
            @Override
            public int getSpanSize(int position) {
                return adapter.getItemViewType(position) == AlbumsAdapter.itemTypeSection()
                        ? 2 : 1;
            }
        });
        recycler.setLayoutManager(lm);
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
            boolean canDest = !MaskGuard.hidden();
            setActionVisible(R.id.btnSelCopy, canDest);
            setActionVisible(R.id.btnSelMove, canDest);
            setActionVisible(R.id.btnSelDelete, true);
            selActions.findViewById(R.id.btnSelCopy).setOnClickListener(x -> destFlow(true));
            selActions.findViewById(R.id.btnSelMove).setOnClickListener(x -> destFlow(false));
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
            v.findViewById(R.id.btnNewFolder)
                    .setVisibility(MaskGuard.hidden() ? View.GONE : View.VISIBLE);
        }
    }

    private void loadAlbums() {
        layoutLoading.setVisibility(View.VISIBLE);
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

    /** Перезагрузка списка (после смены состояния маскировки). */
    public void reload() {
        if (!isAdded() || adapter == null) return;
        exitSelection();
        setupSelectionChrome();
        updateHideNow();
        loadAlbums();
    }

    // ---------- Клики по альбомам ----------

    @Override
    public void onAlbumClick(Album album, int position) {
        if (adapter.isSelection()) {
            adapter.toggle(album.path);
            updateSelBar();
            return;
        }
        Intent intent = new Intent(requireContext(), FolderActivity.class);
        intent.putExtra("path", album.path);
        intent.putExtra("name", album.name);
        startActivity(intent);
    }

    @Override
    public void onAlbumLongClick(Album album, int position) {
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
        FragmentActivity act = requireActivity();

        if (Prefs.rootPath() == null) {
            showRootNeeded(() -> destFlow(copy));
            return;
        }

        DestSheet sheet = DestSheet.newInstance(copy);
        sheet.setListener(new DestSheet.Listener() {
            @Override
            public void onDestPicked(File dir) {
                runCopyMove(copy, dirs, dir);
            }

            @Override
            public void onNewFolderRequested() {
                CreateFolderDialog.show(act, null, folder -> runCopyMove(copy, dirs, folder));
            }
        });
        sheet.show(getParentFragmentManager(), "dest");
    }

    private void runCopyMove(boolean copy, List<File> dirs, File dest) {
        FragmentActivity act = requireActivity();
        // Нельзя переносить папку саму в себя или в свою подпапку
        List<File> safeDirs = new ArrayList<>();
        for (File d : dirs) {
            String dp = d.getAbsolutePath();
            String tp = dest.getAbsolutePath();
            if (!(tp.equals(dp) || tp.startsWith(dp + "/"))) safeDirs.add(d);
        }
        if (safeDirs.isEmpty()) {
            Toast.makeText(act, R.string.album_dest_inside_itself, Toast.LENGTH_SHORT).show();
            return;
        }
        FileOp op = new FileOp(act);
        OpProgressDialog dlg = OpProgressDialog.show(act,
                act.getString(copy ? R.string.op_copy : R.string.op_move), op::cancel);
        FileOp.Done done = (albumsOk, fail, cancelled) -> {
            dlg.dismiss();
            if (!isAdded()) return;
            Toast.makeText(act, cancelled ? act.getString(R.string.op_cancelled)
                    : Fmt.plural(act, copy ? R.plurals.result_albums_copied
                    : R.plurals.result_albums_moved, albumsOk), Toast.LENGTH_SHORT).show();
            exitSelection();
            loadAlbums();
        };
        if (copy) {
            // copyDirs считает файлы — для сообщения пользователю считаем альбомы
            op.copyDirs(safeDirs, dest, dlg, (ok, fail, cancelled) ->
                    done.onDone(cancelled ? 0 : safeDirs.size(), fail, cancelled));
        } else {
            op.moveDirs(safeDirs, dest, dlg, done);
        }
    }

    // ---------- Удаление папок ----------

    private void onDeleteSelected() {
        List<File> dirs = selectedDirs();
        if (dirs.isEmpty()) return;
        new MaterialAlertDialogBuilder(requireActivity())
                .setTitle(R.string.delete_folder)
                .setMessage(R.string.delete_selected_folders_confirm)
                .setPositiveButton(R.string.delete, (d, w) -> deleteFolders(dirs))
                .setNegativeButton(R.string.cancel, null)
                .show();
    }

    private void deleteFolders(List<File> dirs) {
        FragmentActivity act = requireActivity();
        FileOp op = new FileOp(act);
        OpProgressDialog dlg = OpProgressDialog.show(act,
                act.getString(R.string.op_trash), op::cancel);
        op.trashDirs(dirs, dlg, (ok, fail, cancelled) -> {
            dlg.dismiss();
            if (!isAdded()) return;
            Toast.makeText(act, Fmt.plural(act, R.plurals.result_albums_deleted, ok),
                    Toast.LENGTH_SHORT).show();
            exitSelection();
            loadAlbums();
        });
    }

    // ---------- Создание папки / корневая папка ----------

    private void onCreateFolder() {
        FragmentActivity act = requireActivity();
        CreateFolderDialog.show(act, () -> showRootNeeded(this::onCreateFolder),
                folder -> loadAlbums());
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
        if (requestCode == REQ_ROOT && resultCode == FragmentActivity.RESULT_OK) {
            Runnable r = pendingAfterRoot;
            pendingAfterRoot = null;
            if (r != null) r.run();
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
            loadAlbums();
        }
    }

    @Override
    public void onResume() {
        super.onResume();
        updateHideNow();
    }
}
