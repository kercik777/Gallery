package com.premiumlab.galleryx.ui;

import com.premiumlab.galleryx.util.Toasts;
import android.view.View;

import com.google.android.material.dialog.MaterialAlertDialogBuilder;
import com.premiumlab.galleryx.R;
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
    protected boolean hidesMaskedPaths() {
        return false;
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
                // Корзина показывает всё удалённое независимо от маскировки
                MediaItem it = new MediaItem();
                it.path = e.path;
                it.name = e.name;
                it.size = e.size;
                it.dateModified = e.date;
                it.isVideo = e.video;
                it.mime = e.video ? "video/*" : "image/*";
                long daysLeft = 30 - TimeUnit.MILLISECONDS.toDays(
                        System.currentTimeMillis() - e.date);
                it.badge = getString(R.string.trash_days_badge, (int) Math.max(0, daysLeft));
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
        openViewer(item, 1);
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
        FileOp op = new FileOp(requireActivity());
        OpProgressDialog dlg = OpProgressDialog.show(requireActivity(),
                getString(R.string.op_restore), op::cancel);
        op.restoreTrash(toRestore, dlg, (ok, fail, cancelled) -> {
            dlg.dismiss();
            Toast_result(ok);
            afterAction();
        });
    }

    private void Toast_result(int ok) {
        com.premiumlab.galleryx.util.Toasts.makeText(requireContext(),
                Fmt.plural(requireContext(), R.plurals.result_restored, ok),
                android.widget.Toast.LENGTH_SHORT).show();
    }

    @Override
    protected void onHeaderExtraClick() {
        if (entries.isEmpty()) return;
        if (typeFilter == TYPE_ALL) {
            new MaterialAlertDialogBuilder(requireActivity())
                    .setTitle(R.string.trash_clear_btn)
                    .setMessage(R.string.trash_clear_confirm_text)
                    .setPositiveButton(R.string.delete, (d, w) -> {
                        // Как и везде: в фоне, с диалогом прогресса и отменой —
                        // раньше корзина чистилась синхронно и замораживала экран
                        List<MediaItem> all = new ArrayList<>(allItems);
                        FileOp op = new FileOp(requireActivity());
                        OpProgressDialog dlg = OpProgressDialog.show(requireActivity(),
                                getString(R.string.op_delete), op::cancel);
                        op.deleteForever(all, dlg, (ok, fail, cancelled) -> {
                            dlg.dismiss();
                            // Записи без файлов (если такие остались) убираем тихо
                            if (!cancelled) TrashStore.get().clearMissing();
                            if (isAdded()) {
                                Toasts.makeText(requireContext(), Fmt.plural(requireContext(),
                                        R.plurals.result_deleted, ok),
                                        android.widget.Toast.LENGTH_SHORT).show();
                                afterAction();
                            }
                        });
                    })
                    .setNegativeButton(R.string.cancel, null)
                    .show();
            return;
        }
        // Активен фильтр «Фото» или «Видео» — очищаем только отфильтрованную часть корзины
        List<MediaItem> visible = new ArrayList<>(applyTypeFilter(allItems));
        if (visible.isEmpty()) return;
        boolean photos = typeFilter == TYPE_PHOTO;
        new MaterialAlertDialogBuilder(requireActivity())
                .setTitle(photos ? R.string.trash_clear_photos_title : R.string.trash_clear_videos_title)
                .setMessage(photos ? R.string.trash_clear_photos_text : R.string.trash_clear_videos_text)
                .setPositiveButton(R.string.delete, (d, w) -> {
                    FileOp op = new FileOp(requireActivity());
                    OpProgressDialog dlg = OpProgressDialog.show(requireActivity(),
                            getString(R.string.op_delete), op::cancel);
                    op.deleteForever(visible, dlg, (ok, fail, cancelled) -> {
                        dlg.dismiss();
                        if (isAdded()) {
                            Toasts.makeText(requireContext(), Fmt.plural(requireContext(),
                                    R.plurals.result_deleted, ok), android.widget.Toast.LENGTH_SHORT).show();
                            afterAction();
                        }
                    });
                })
                .setNegativeButton(R.string.cancel, null)
                .show();
    }

    @Override
    protected boolean allowResize() {
        return false;
    }

    /** Корзина всегда показывает последние удалённые файлы первыми (по дате удаления). */
    @Override
    protected int sortMode() {
        return com.premiumlab.galleryx.data.Prefs.SORT_DATE_DESC;
    }

    @Override
    protected boolean allowSort() {
        return false;
    }
}
