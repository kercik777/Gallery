package com.premiumlab.galleryx.ui;

import android.view.View;

import com.premiumlab.galleryx.R;
import com.premiumlab.galleryx.data.MediaItem;
import com.premiumlab.galleryx.data.SafeStore;
import com.premiumlab.galleryx.ui.dialog.OpProgressDialog;
import com.premiumlab.galleryx.util.FileOp;
import com.premiumlab.galleryx.util.Fmt;

import java.util.ArrayList;
import java.util.List;

/**
 * Сейф: скрытые файлы. Доступ к экрану контролирует SafeActivity (PIN).
 */
public class SafeFragment extends BaseMediaFragment {

    private final List<SafeStore.Entry> entries = new ArrayList<>();

    @Override
    protected int layoutRes() {
        return R.layout.fragment_safe;
    }

    @Override
    protected void onViewsReady(@androidx.annotation.NonNull View root) {
        // Замок в шапке убран: сейф запирается автоматически при выходе
        if (btnHeaderExtra != null) btnHeaderExtra.setVisibility(View.GONE);
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
            entries.addAll(SafeStore.get().entries());
            List<MediaItem> items = new ArrayList<>();
            for (SafeStore.Entry e : entries) {
                MediaItem it = new MediaItem();
                it.path = e.path;
                it.name = e.name;
                it.size = e.size;
                it.dateModified = e.date;
                it.isVideo = e.video;
                it.mime = e.video ? "video/*" : "image/*";
                items.add(it);
            }
            setData(items);
            if (shownItems.isEmpty() && query.isEmpty()) {
                txtEmptyTitle.setText(R.string.safe_empty_title);
                txtEmptySub.setText(R.string.safe_empty_sub);
            }
        });
    }

    @Override
    protected void onItemOpen(MediaItem item) {
        openViewer(item, 2);
    }

    @Override
    protected void onDeleteSelected(List<MediaItem> selected) {
        Actions.confirmDeleteForever(requireActivity(), selected, () -> {
            // Убираем записи о безвозвратно удалённых
            for (MediaItem it : selected) {
                for (SafeStore.Entry e : new ArrayList<>(entries)) {
                    if (e.path.equals(it.path)) SafeStore.get().remove(e);
                }
            }
            afterAction();
        });
    }

    @Override
    protected void onRestoreSelected(java.util.Set<String> paths) {
        List<SafeStore.Entry> toRestore = new ArrayList<>();
        for (SafeStore.Entry e : entries) {
            if (paths.contains(e.path)) toRestore.add(e);
        }
        if (toRestore.isEmpty()) return;
        OpProgressDialog dlg = OpProgressDialog.show(requireActivity(),
                getString(R.string.op_restore), null);
        FileOp op = new FileOp(requireActivity());
        op.restoreSafe(toRestore, dlg, (ok, fail, cancelled) -> {
            dlg.dismiss();
            android.widget.Toast.makeText(requireContext(),
                    Fmt.plural(requireContext(), R.plurals.result_restored, ok),
                    android.widget.Toast.LENGTH_SHORT).show();
            afterAction();
        });
    }

    @Override
    protected void onHeaderExtraClick() {
        // Сейф запирается сам при выходе — отдельная кнопка не нужна
    }

    @Override
    protected boolean allowSort() {
        return false;
    }
}
