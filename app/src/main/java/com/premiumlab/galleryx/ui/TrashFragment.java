package com.premiumlab.galleryx.ui;

import android.content.Intent;
import android.view.View;

import com.google.android.material.dialog.MaterialAlertDialogBuilder;
import com.premiumlab.galleryx.PhotoViewerActivity;
import com.premiumlab.galleryx.R;
import com.premiumlab.galleryx.VideoPlayerActivity;
import com.premiumlab.galleryx.data.MediaItem;
import com.premiumlab.galleryx.data.TrashStore;
import com.premiumlab.galleryx.ui.dialog.OpProgressDialog;
import com.premiumlab.galleryx.util.FileOp;
import com.premiumlab.galleryx.util.Fmt;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;

/**
 * Корзина: недавно удалённые с оставшимся сроком хранения.
 */
public class TrashFragment extends BaseMediaFragment {

    private final List<TrashStore.Entry> entries = new ArrayList<>();

    @Override
    protected int layoutRes() {
        return R.layout.fragment_trash;
    }

    @Override
    protected void configureSelectionBar() {
        if (selActions == null) return;
        selActions.findViewById(R.id.btnSelFavorite).setVisibility(View.GONE);
        selActions.findViewById(R.id.btnSelCopy).setVisibility(View.GONE);
        selActions.findViewById(R.id.btnSelMove).setVisibility(View.GONE);
        selActions.findViewById(R.id.btnSelSafe).setVisibility(View.GONE);
        selActions.findViewById(R.id.btnSelShare).setVisibility(View.GONE);
        selActions.findViewById(R.id.btnSelRestore).setVisibility(View.VISIBLE);
    }

    @Override
    protected void loadMedia() {
        showLoading(true);
        recycler.post(() -> {
            if (!isAdded()) return;
            showLoading(false);
            entries.clear();
            entries.addAll(TrashStore.get().entries());
            List<MediaItem> items = new ArrayList<>();
            for (TrashStore.Entry e : entries) {
                MediaItem it = new MediaItem();
                it.path = e.path;
                it.name = e.name;
                it.size = e.size;
                it.dateModified = e.date;
                it.isVideo = e.video;
                it.mime = e.video ? "video/*" : "image/*";
                long daysLeft = 30 - TimeUnit.MILLISECONDS.toDays(
                        System.currentTimeMillis() - e.date);
                it.badge = Math.max(0, daysLeft) + " дн.";
                items.add(it);
            }
            setData(items);
            if (shownItems.isEmpty() && query.isEmpty()) {
                txtEmptyTitle.setText(R.string.trash_empty_title);
                txtEmptySub.setText(R.string.trash_empty_sub);
            }
        });
    }

    @Override
    protected void onItemOpen(MediaItem item) {
        if (item.isVideo) {
            Intent intent = new Intent(requireContext(), VideoPlayerActivity.class);
            intent.putExtra("path", item.path);
            intent.putExtra("name", item.name);
            intent.putExtra("mode", 1);
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
            intent.putExtra("mode", 1);
            startActivity(intent);
        }
    }

    @Override
    protected void onDeleteSelected(List<MediaItem> selected) {
        Actions.confirmDeleteForever(requireActivity(), selected, this::afterAction);
    }

    @Override
    protected void onRestoreSelected(java.util.Set<String> paths) {
        List<TrashStore.Entry> toRestore = new ArrayList<>();
        for (TrashStore.Entry e : entries) {
            if (paths.contains(e.path)) toRestore.add(e);
        }
        if (toRestore.isEmpty()) return;
        OpProgressDialog dlg = OpProgressDialog.show(requireActivity(),
                getString(R.string.op_restore), null);
        FileOp op = new FileOp(requireActivity());
        op.restoreTrash(toRestore, dlg, (ok, fail, cancelled) -> {
            dlg.dismiss();
            Toast_result(ok);
            afterAction();
        });
    }

    private void Toast_result(int ok) {
        android.widget.Toast.makeText(requireContext(),
                Fmt.plural(requireContext(), R.plurals.result_restored, ok),
                android.widget.Toast.LENGTH_SHORT).show();
    }

    @Override
    protected void onHeaderExtraClick() {
        if (entries.isEmpty()) return;
        new MaterialAlertDialogBuilder(requireActivity())
                .setTitle(R.string.trash_clear_btn)
                .setMessage(R.string.trash_clear_confirm_text)
                .setPositiveButton(R.string.delete, (d, w) -> {
                    TrashStore.get().clearAll();
                    afterAction();
                })
                .setNegativeButton(R.string.cancel, null)
                .show();
    }

    @Override
    protected boolean allowSort() {
        return false;
    }
}
