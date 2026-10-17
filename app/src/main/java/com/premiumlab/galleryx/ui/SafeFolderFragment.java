package com.premiumlab.galleryx.ui;

import android.os.Bundle;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.fragment.app.FragmentActivity;

import com.premiumlab.galleryx.R;
import com.premiumlab.galleryx.data.MediaItem;
import com.premiumlab.galleryx.data.SafeStore;
import com.premiumlab.galleryx.ui.dialog.OpProgressDialog;
import com.premiumlab.galleryx.util.FileOp;
import com.premiumlab.galleryx.util.Fmt;
import com.premiumlab.galleryx.util.Toasts;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/**
 * Папка сейфа: файлы одного вида (фото или видео) одной папки. Без фильтра
 * «Все / Фото / Видео» — вид задаёт раздел. Копирование и перемещение —
 * только между папками сейфа того же вида. Доступ контролирует SafeActivity (PIN).
 */
public class SafeFolderFragment extends BaseMediaFragment {

    private static final String ARG_VIDEO = "video";
    private static final String ARG_FOLDER = "folder";

    public static SafeFolderFragment newInstance(boolean video, String folder) {
        SafeFolderFragment f = new SafeFolderFragment();
        Bundle b = new Bundle();
        b.putBoolean(ARG_VIDEO, video);
        b.putString(ARG_FOLDER, folder);
        f.setArguments(b);
        return f;
    }

    private boolean video;
    private String folder = SafeStore.PERSONAL;
    private final List<SafeStore.Entry> entries = new ArrayList<>();

    @Override
    protected int layoutRes() {
        return R.layout.fragment_safe;
    }

    @Override
    protected boolean useTypeChips() {
        return false;
    }

    @Override
    protected void onViewsReady(@NonNull View root) {
        if (getArguments() != null) {
            video = getArguments().getBoolean(ARG_VIDEO, false);
            folder = getArguments().getString(ARG_FOLDER, SafeStore.PERSONAL);
        }
        if (txtHeaderTitle != null) {
            txtHeaderTitle.setText(SafeKindFragment.displayName(requireContext(), folder));
        }
        if (chipRow != null) chipRow.setVisibility(View.GONE);
        // Замок в шапке убран: сейф запирается автоматически при выходе
        if (btnHeaderExtra != null) btnHeaderExtra.setVisibility(View.GONE);
        if (btnBack != null) btnBack.setOnClickListener(x -> requireActivity().getOnBackPressedDispatcher().onBackPressed());
    }

    @Override
    protected boolean hidesMaskedPaths() {
        return false;
    }

    /** Подпись кнопки «Восстановить» в сейфе — «Раскрыть». */
    static void labelReveal(View restoreButton) {
        if (!(restoreButton instanceof ViewGroup)) return;
        ViewGroup g = (ViewGroup) restoreButton;
        for (int i = 0; i < g.getChildCount(); i++) {
            View c = g.getChildAt(i);
            if (c instanceof TextView) ((TextView) c).setText(R.string.safe_reveal);
        }
    }

    @Override
    protected void configureSelectionBar() {
        if (selActions == null) return;
        selActions.findViewById(R.id.btnSelFavorite).setVisibility(View.GONE);
        selActions.findViewById(R.id.btnSelSafe).setVisibility(View.GONE);
        selActions.findViewById(R.id.btnSelShare).setVisibility(View.GONE);
        selActions.findViewById(R.id.btnSelCopy).setVisibility(View.VISIBLE);
        selActions.findViewById(R.id.btnSelMove).setVisibility(View.VISIBLE);
        View restore = selActions.findViewById(R.id.btnSelRestore);
        restore.setVisibility(View.VISIBLE);
        labelReveal(restore);
    }

    @Override
    protected void loadMedia() {
        showLoading(true);
        recycler.post(() -> {
            if (!isAdded()) return;
            showLoading(false);
            entries.clear();
            entries.addAll(SafeStore.get().entries(video, folder));
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
                txtEmptyTitle.setText(R.string.safe_folder_empty_title);
                txtEmptySub.setText(R.string.safe_folder_empty_sub);
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
            List<SafeStore.Entry> gone = new ArrayList<>();
            for (MediaItem it : selected) {
                for (SafeStore.Entry e : entries) {
                    if (e.path.equals(it.path) && !new java.io.File(e.path).exists()) gone.add(e);
                }
            }
            if (!gone.isEmpty()) SafeStore.get().removeAll(gone);
            afterAction();
        });
    }

    @Override
    protected void onRestoreSelected(Set<String> paths) {
        List<SafeStore.Entry> toRestore = new ArrayList<>();
        for (SafeStore.Entry e : entries) {
            if (paths.contains(e.path)) toRestore.add(e);
        }
        if (toRestore.isEmpty()) return;
        FileOp op = new FileOp(requireActivity());
        OpProgressDialog dlg = OpProgressDialog.show(requireActivity(),
                getString(R.string.op_restore), op::cancel);
        op.restoreSafe(toRestore, dlg, (ok, fail, cancelled) -> {
            dlg.dismiss();
            if (!isAdded()) return;
            Toasts.makeText(requireContext(),
                    Fmt.plural(requireContext(), R.plurals.result_restored, ok),
                    Toast.LENGTH_SHORT).show();
            afterAction();
        });
    }

    // ---------- Копирование / перемещение внутри сейфа ----------

    @Override
    protected void destFlow(boolean copy) {
        List<MediaItem> sel = selectedItems();
        if (sel.isEmpty()) return;
        SafeDialogs.pickFolder(requireActivity(), getParentFragmentManager(), copy, video, folder,
                key -> runSafeCopyMove(copy, sel, key));
    }

    private void runSafeCopyMove(boolean copy, List<MediaItem> sel, String target) {
        if (!isAdded()) return;
        FragmentActivity act = requireActivity();
        List<SafeStore.Entry> chosen = new ArrayList<>();
        List<String> paths = new ArrayList<>();
        for (MediaItem it : sel) {
            paths.add(it.path);
            for (SafeStore.Entry e : entries) {
                if (e.path.equals(it.path)) chosen.add(e);
            }
        }
        if (copy) {
            FileOp op = new FileOp(act);
            OpProgressDialog dlg = OpProgressDialog.show(act, getString(R.string.op_copy), op::cancel);
            op.safeCopy(chosen, target, dlg, (ok, fail, cancelled) -> {
                dlg.dismiss();
                if (!isAdded()) return;
                Toasts.makeText(act, cancelled ? getString(R.string.op_cancelled)
                        : Fmt.plural(act, R.plurals.result_copied, ok), Toast.LENGTH_SHORT).show();
                afterAction();
            });
        } else {
            // Папки сейфа виртуальные — перемещение мгновенное, файлы не трогаем
            int n = SafeStore.get().moveToFolder(paths, target);
            Toasts.makeText(act, Fmt.plural(act, R.plurals.result_moved, n), Toast.LENGTH_SHORT).show();
            afterAction();
        }
    }

    @Override
    protected void onHeaderExtraClick() {
        // Сейф запирается сам при выходе — отдельная кнопка не нужна
    }

    @Override
    protected boolean allowResize() {
        return false;
    }

    @Override
    protected boolean allowSort() {
        return false;
    }
}
