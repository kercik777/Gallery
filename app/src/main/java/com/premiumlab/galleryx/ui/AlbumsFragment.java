package com.premiumlab.galleryx.ui;

import android.content.Intent;
import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
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
import com.premiumlab.galleryx.data.MediaEngine;
import com.premiumlab.galleryx.data.Prefs;
import com.premiumlab.galleryx.data.SessionManager;
import com.premiumlab.galleryx.data.TrashStore;
import com.premiumlab.galleryx.ui.adapter.AlbumsAdapter;
import com.premiumlab.galleryx.ui.dialog.CreateFolderDialog;
import com.premiumlab.galleryx.util.Fmt;
import com.premiumlab.galleryx.util.Scan;

import java.io.File;
import java.util.ArrayList;
import java.util.List;

/**
 * Экран альбомов: «Мои папки» (внутри корня) и «Папки устройства».
 * Поддерживает выделение нескольких папок для удаления.
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
            // «Выбрать все» для папок не нужен
            selTop.findViewById(R.id.btnSelAll).setVisibility(View.GONE);
            txtSelCount = selTop.findViewById(R.id.txtSelCount);
        }
        if (selActions != null) {
            // Для папок актуально только удаление
            setActionVisible(R.id.btnSelFavorite, false);
            setActionVisible(R.id.btnSelShare, false);
            setActionVisible(R.id.btnSelCopy, false);
            setActionVisible(R.id.btnSelMove, false);
            setActionVisible(R.id.btnSelSafe, false);
            setActionVisible(R.id.btnSelRestore, false);
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
                        ? View.VISIBLE : View.GONE);
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
        if (!album.isUser) return;
        if (!adapter.isSelection()) {
            adapter.toggle(album.path);
            updateSelBar();
        }
    }

    private void updateSelBar() {
        int count = adapter == null ? 0 : adapter.getSelectedCount();
        boolean active = count > 0;
        if (selTop != null) {
            selTop.setVisibility(active ? View.VISIBLE : View.GONE);
            if (active && txtSelCount != null) {
                txtSelCount.setText(Fmt.plural(requireContext(),
                        R.plurals.selected_count, count));
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

    // ---------- Удаление папок ----------

    private void onDeleteSelected() {
        List<String> selected = new ArrayList<>(adapter.getSelectedPaths());
        if (selected.isEmpty()) return;
        new MaterialAlertDialogBuilder(requireActivity())
                .setTitle(R.string.delete_folder)
                .setMessage(R.string.delete_selected_folders_confirm)
                .setPositiveButton(R.string.delete, (d, w) -> deleteFolders(selected))
                .setNegativeButton(R.string.cancel, null)
                .show();
    }

    private void deleteFolders(List<String> paths) {
        Toast.makeText(requireContext(), R.string.loading, Toast.LENGTH_SHORT).show();
        FragmentActivity act = requireActivity();
        new Thread(() -> {
            for (String p : paths) {
                File dir = new File(p);
                if (!dir.exists()) continue;
                List<File> files = new ArrayList<>();
                collectFiles(dir, files);
                for (File f : files) {
                    File trashDir = TrashStore.trashDir();
                    File target = new File(trashDir, "t" + System.nanoTime() + "_" + f.getName());
                    if (f.renameTo(target)) {
                        TrashStore.get().add(f.getAbsolutePath(), target,
                                f.getName(), f.length(), MediaEngine.isVideoName(f.getName()));
                    }
                }
                deleteTree(dir);
                Scan.files(act, p);
            }
            act.runOnUiThread(() -> {
                exitSelection();
                loadAlbums();
            });
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

    private void deleteTree(File dir) {
        File[] arr = dir.listFiles();
        if (arr != null) {
            for (File f : arr) {
                if (f.isDirectory()) deleteTree(f);
                //noinspection ResultOfMethodCallIgnored
                f.delete();
            }
        }
        //noinspection ResultOfMethodCallIgnored
        dir.delete();
    }

    // ---------- Создание и переименование ----------

    private void onCreateFolder() {
        FragmentActivity act = requireActivity();
        CreateFolderDialog.show(act, () -> {
            pendingAfterRoot = this::onCreateFolder;
            new MaterialAlertDialogBuilder(act)
                    .setTitle(R.string.root_needed_title)
                    .setMessage(R.string.root_needed_desc)
                    .setPositiveButton(R.string.continue_btn, (d, w) ->
                            startActivityForResult(new Intent(act, RootPickerActivity.class),
                                    REQ_ROOT))
                    .setNegativeButton(R.string.cancel, (d, w) -> pendingAfterRoot = null)
                    .show();
        }, folder -> loadAlbums());
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
        if (!hidden && isAdded()) {
            setupSelectionChrome();
            loadAlbums();
        }
    }

    @Override
    public void onResume() {
        super.onResume();
        updateHideNow();
    }
}
