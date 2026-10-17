package com.premiumlab.galleryx.ui;

import android.content.Context;
import android.os.Bundle;
import android.text.Editable;
import android.text.TextWatcher;
import android.view.HapticFeedbackConstants;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.EditText;
import android.widget.ImageView;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AlertDialog;
import androidx.fragment.app.Fragment;
import androidx.fragment.app.FragmentActivity;
import androidx.recyclerview.widget.GridLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.bumptech.glide.Glide;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;
import com.premiumlab.galleryx.R;
import com.premiumlab.galleryx.SafeActivity;
import com.premiumlab.galleryx.data.MediaItem;
import com.premiumlab.galleryx.data.Prefs;
import com.premiumlab.galleryx.data.SafeStore;
import com.premiumlab.galleryx.ui.dialog.OpProgressDialog;
import com.premiumlab.galleryx.util.EdgeToEdge;
import com.premiumlab.galleryx.util.FileOp;
import com.premiumlab.galleryx.util.Fmt;
import com.premiumlab.galleryx.util.Press;
import com.premiumlab.galleryx.util.Toasts;

import java.io.File;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * Раздел сейфа («Изображения» или «Видео»): сетка папок сейфа. Первая —
 * встроенная «Личный» (нельзя удалить и переименовать), дальше — созданные
 * пользователем. Долгое нажатие выделяет папки: «Раскрыть» для любых,
 * «Переименовать» и «Удалить» — только для пользовательских.
 */
public class SafeKindFragment extends Fragment implements BackHandler {

    private static final String ARG_VIDEO = "video";

    public static SafeKindFragment newInstance(boolean video) {
        SafeKindFragment f = new SafeKindFragment();
        Bundle b = new Bundle();
        b.putBoolean(ARG_VIDEO, video);
        f.setArguments(b);
        return f;
    }

    /** Папка сейфа для показа. */
    static class Row {
        final String key;
        final boolean personal;
        final int count;
        @Nullable
        final String cover;

        Row(String key, boolean personal, int count, @Nullable String cover) {
            this.key = key;
            this.personal = personal;
            this.count = count;
            this.cover = cover;
        }
    }

    private boolean video;
    private RecyclerView recycler;
    private FolderAdapter adapter;
    private View selTop, selActions;
    private TextView txtSelCount;
    private final List<Row> rows = new ArrayList<>();
    private final Set<String> selected = new LinkedHashSet<>();

    /** Отображаемое имя папки сейфа. */
    public static String displayName(Context ctx, String key) {
        return SafeStore.PERSONAL.equals(key) ? ctx.getString(R.string.safe_folder_personal) : key;
    }

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container,
                             @Nullable Bundle savedInstanceState) {
        return inflater.inflate(R.layout.fragment_safe_kind, container, false);
    }

    @Override
    public void onViewCreated(@NonNull View v, @Nullable Bundle savedInstanceState) {
        video = getArguments() != null && getArguments().getBoolean(ARG_VIDEO, false);
        ((TextView) v.findViewById(R.id.txtHeaderTitle))
                .setText(video ? R.string.safe_videos : R.string.safe_images);
        View back = v.findViewById(R.id.btnBack);
        Press.attach(back);
        back.setOnClickListener(x -> requireActivity().getOnBackPressedDispatcher().onBackPressed());
        View add = v.findViewById(R.id.btnNewFolder);
        Press.attach(add);
        add.setOnClickListener(x -> createFolder());

        recycler = v.findViewById(R.id.recyclerSafeFolders);
        recycler.setLayoutManager(new GridLayoutManager(requireContext(),
                Math.max(1, Prefs.albumColumns())));
        adapter = new FolderAdapter();
        recycler.setAdapter(adapter);
        EdgeToEdge.padBottom(recycler);

        setupSelectionChrome();
    }

    @Override
    public void onResume() {
        super.onResume();
        GridLayoutManager lm = (GridLayoutManager) recycler.getLayoutManager();
        if (lm != null && lm.getSpanCount() != Math.max(1, Prefs.albumColumns())) {
            lm.setSpanCount(Math.max(1, Prefs.albumColumns()));
        }
        setupSelectionChrome();
        load();
    }

    // ---------- Данные ----------

    private void load() {
        rows.clear();
        rows.add(row(SafeStore.PERSONAL, true));
        for (String f : SafeStore.get().folders(video)) rows.add(row(f, false));
        // Выделение может ссылаться на удалённые папки
        List<String> keys = new ArrayList<>();
        for (Row r : rows) keys.add(r.key);
        selected.retainAll(keys);
        adapter.notifyDataSetChanged();
        updateSelBar();
    }

    private Row row(String key, boolean personal) {
        List<SafeStore.Entry> list = SafeStore.get().entries(video, key);
        String cover = null;
        long newest = Long.MIN_VALUE;
        for (SafeStore.Entry e : list) {
            if (e.date > newest) {
                newest = e.date;
                cover = e.path;
            }
        }
        return new Row(key, personal, list.size(), cover);
    }

    // ---------- Выделение ----------

    private void setupSelectionChrome() {
        FragmentActivity act = getActivity();
        if (act == null) return;
        selTop = act.findViewById(R.id.selectionTop);
        selActions = act.findViewById(R.id.selectionActions);
        if (selActions != null && selActions.getTag(R.id.e2e_done) == null) {
            EdgeToEdge.growBottom(selActions);
            selActions.setTag(R.id.e2e_done, true);
        }
        if (selTop != null) {
            selTop.findViewById(R.id.btnSelClose).setOnClickListener(x -> exitSelection());
            selTop.findViewById(R.id.btnSelAll).setVisibility(View.GONE);
            txtSelCount = selTop.findViewById(R.id.txtSelCount);
        }
        if (selActions != null) {
            int[] hide = {R.id.btnSelFavorite, R.id.btnSelShare, R.id.btnSelCopy,
                    R.id.btnSelMove, R.id.btnSelSafe, R.id.btnSelLock};
            for (int id : hide) selActions.findViewById(id).setVisibility(View.GONE);
            View restore = selActions.findViewById(R.id.btnSelRestore);
            restore.setVisibility(View.VISIBLE);
            SafeFolderFragment.labelReveal(restore);
            restore.setOnClickListener(x -> revealSelected());
            selActions.findViewById(R.id.btnSelRename).setOnClickListener(x -> renameSelected());
            selActions.findViewById(R.id.btnSelDelete).setOnClickListener(x -> deleteSelected());
        }
        updateSelBar();
    }

    private boolean inSelection() {
        return !selected.isEmpty();
    }

    private void exitSelection() {
        if (selected.isEmpty()) return;
        selected.clear();
        adapter.notifyDataSetChanged();
        updateSelBar();
    }

    private void toggle(String key) {
        if (!selected.remove(key)) selected.add(key);
        adapter.notifyDataSetChanged();
        updateSelBar();
    }

    private void updateSelBar() {
        if (!isAdded()) return;
        boolean active = inSelection();
        boolean anyPersonal = selected.contains(SafeStore.PERSONAL);
        if (selTop != null) {
            selTop.setVisibility(active ? View.VISIBLE : View.GONE);
            if (active && txtSelCount != null) {
                txtSelCount.setText(Fmt.plural(requireContext(), R.plurals.selected_count,
                        selected.size()));
            }
        }
        if (selActions != null) {
            selActions.setVisibility(active ? View.VISIBLE : View.GONE);
            // «Личный» нельзя переименовать или удалить
            selActions.findViewById(R.id.btnSelRename).setVisibility(
                    selected.size() == 1 && !anyPersonal ? View.VISIBLE : View.GONE);
            selActions.findViewById(R.id.btnSelDelete).setVisibility(
                    !anyPersonal ? View.VISIBLE : View.GONE);
        }
        if (recycler != null) {
            int pad = (int) ((active ? 100 : 20) * getResources().getDisplayMetrics().density)
                    + EdgeToEdge.bottomInset(recycler);
            recycler.setPadding(recycler.getPaddingLeft(), recycler.getPaddingTop(),
                    recycler.getPaddingRight(), pad);
        }
    }

    @Override
    public boolean onBackPressedHandled() {
        if (inSelection()) {
            exitSelection();
            return true;
        }
        return false;
    }

    // ---------- Действия с папками ----------

    private List<SafeStore.Entry> entriesOfSelected() {
        List<SafeStore.Entry> out = new ArrayList<>();
        for (String key : selected) out.addAll(SafeStore.get().entries(video, key));
        return out;
    }

    /** «Раскрыть»: все файлы выбранных папок возвращаются в исходные места. */
    private void revealSelected() {
        List<SafeStore.Entry> entries = entriesOfSelected();
        if (entries.isEmpty()) {
            Toasts.makeText(requireContext(), R.string.album_no_files, Toast.LENGTH_SHORT).show();
            return;
        }
        FragmentActivity act = requireActivity();
        new MaterialAlertDialogBuilder(act)
                .setTitle(R.string.safe_reveal_title)
                .setMessage(getString(R.string.safe_reveal_text,
                        Fmt.plural(act, R.plurals.items_count, entries.size())))
                .setPositiveButton(R.string.safe_reveal, (d, w) -> {
                    FileOp op = new FileOp(act);
                    OpProgressDialog dlg = OpProgressDialog.show(act,
                            getString(R.string.op_restore), op::cancel);
                    op.restoreSafe(entries, dlg, (ok, fail, cancelled) -> {
                        dlg.dismiss();
                        if (!isAdded()) return;
                        Toasts.makeText(act, Fmt.plural(act, R.plurals.result_restored, ok),
                                Toast.LENGTH_SHORT).show();
                        exitSelection();
                        load();
                    });
                })
                .setNegativeButton(R.string.cancel, null)
                .show();
    }

    /** Удаляет пользовательские папки вместе с файлами — навсегда, без корзины. */
    private void deleteSelected() {
        if (selected.isEmpty() || selected.contains(SafeStore.PERSONAL)) return;
        List<String> keys = new ArrayList<>(selected);
        List<SafeStore.Entry> entries = entriesOfSelected();
        FragmentActivity act = requireActivity();
        String title = keys.size() == 1
                ? getString(R.string.safe_delete_folder_title, keys.get(0))
                : getString(R.string.safe_delete_folders_title);
        new MaterialAlertDialogBuilder(act)
                .setTitle(title)
                .setMessage(getString(R.string.safe_delete_folder_text,
                        Fmt.plural(act, R.plurals.items_count, entries.size())
                                .toLowerCase(Locale.getDefault())))
                .setPositiveButton(R.string.delete, (d, w) -> {
                    Runnable finishUp = () -> {
                        for (String k : keys) SafeStore.get().removeFolder(video, k);
                        if (!isAdded()) return;
                        exitSelection();
                        load();
                    };
                    if (entries.isEmpty()) {
                        finishUp.run();
                        return;
                    }
                    List<MediaItem> items = new ArrayList<>();
                    for (SafeStore.Entry e : entries) {
                        MediaItem it = new MediaItem();
                        it.path = e.path;
                        it.name = e.name;
                        items.add(it);
                    }
                    FileOp op = new FileOp(act);
                    OpProgressDialog dlg = OpProgressDialog.show(act,
                            getString(R.string.op_delete), op::cancel);
                    op.deleteForever(items, dlg, (ok, fail, cancelled) -> {
                        dlg.dismiss();
                        SafeStore.get().removeAll(entries);
                        if (!cancelled) finishUp.run();
                        else if (isAdded()) {
                            exitSelection();
                            load();
                        }
                    });
                })
                .setNegativeButton(R.string.cancel, null)
                .show();
    }

    private void renameSelected() {
        if (selected.size() != 1) return;
        String key = selected.iterator().next();
        if (SafeStore.PERSONAL.equals(key)) return;
        nameDialog(R.string.rename_folder_title, key, name -> {
            if (!SafeStore.get().renameFolder(video, key, name)) {
                Toasts.makeText(requireContext(), R.string.folder_exists, Toast.LENGTH_SHORT).show();
                return false;
            }
            exitSelection();
            load();
            return true;
        });
    }

    private void createFolder() {
        nameDialog(R.string.create_folder, "", name -> {
            if (!SafeStore.get().addFolder(video, name)) {
                Toasts.makeText(requireContext(), R.string.folder_exists, Toast.LENGTH_SHORT).show();
                return false;
            }
            Toasts.makeText(requireContext(), R.string.folder_created, Toast.LENGTH_SHORT).show();
            load();
            return true;
        });
    }

    interface NameCallback {
        /** @return true — закрыть диалог. */
        boolean onName(String name);
    }

    /** Диалог ввода имени папки сейфа (без предпросмотра пути — папки виртуальные). */
    private void nameDialog(int titleRes, String initial, NameCallback cb) {
        FragmentActivity act = requireActivity();
        View v = LayoutInflater.from(act).inflate(R.layout.dialog_create_folder, null, false);
        EditText edit = v.findViewById(R.id.editFolderName);
        TextView txtError = v.findViewById(R.id.txtFolderError);
        v.findViewById(R.id.txtDialogTitle).setVisibility(View.GONE);
        v.findViewById(R.id.txtFolderPreview).setVisibility(View.GONE);
        edit.setText(initial);
        edit.setSelection(edit.getText().length());
        edit.addTextChangedListener(new TextWatcher() {
            @Override
            public void beforeTextChanged(CharSequence s, int a, int b, int c) {
            }

            @Override
            public void onTextChanged(CharSequence s, int a, int b, int c) {
            }

            @Override
            public void afterTextChanged(Editable s) {
                txtError.setVisibility(View.GONE);
            }
        });
        AlertDialog dialog = new MaterialAlertDialogBuilder(act)
                .setTitle(titleRes)
                .setView(v)
                .setPositiveButton(R.string.save, null)
                .setNegativeButton(R.string.cancel, null)
                .create();
        dialog.show();
        dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(btn -> {
            String name = edit.getText().toString().trim();
            String personal = getString(R.string.safe_folder_personal);
            if (!Fmt.isValidName(name) || name.equalsIgnoreCase(personal)) {
                txtError.setText(R.string.invalid_folder_name);
                txtError.setVisibility(View.VISIBLE);
                return;
            }
            if (cb.onName(name)) dialog.dismiss();
        });
    }

    // ---------- Адаптер ----------

    private class FolderAdapter extends RecyclerView.Adapter<FolderVH> {

        @NonNull
        @Override
        public FolderVH onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
            View v = LayoutInflater.from(parent.getContext())
                    .inflate(R.layout.item_album, parent, false);
            return new FolderVH(v);
        }

        @Override
        public void onBindViewHolder(@NonNull FolderVH h, int position) {
            h.bind(rows.get(position));
        }

        @Override
        public int getItemCount() {
            return rows.size();
        }
    }

    private class FolderVH extends RecyclerView.ViewHolder {
        final ImageView imgCover, imgCheck, imgBadge, imgArt;
        final View card, coverArt;
        final TextView txtName, txtCount;

        FolderVH(@NonNull View itemView) {
            super(itemView);
            imgCover = itemView.findViewById(R.id.imgCover);
            imgCheck = itemView.findViewById(R.id.imgCheckOverlayAlbum);
            imgBadge = itemView.findViewById(R.id.imgLockBadge);
            imgArt = itemView.findViewById(R.id.imgFolderArt);
            coverArt = itemView.findViewById(R.id.layoutCoverArt);
            card = itemView.findViewById(R.id.cardAlbum);
            txtName = itemView.findViewById(R.id.txtAlbumName);
            txtCount = itemView.findViewById(R.id.txtAlbumCount);
            itemView.findViewById(R.id.txtUserBadge).setVisibility(View.GONE);
            Press.attachCard(card);
            View.OnClickListener click = v -> {
                int pos = getBindingAdapterPosition();
                if (pos == RecyclerView.NO_POSITION) return;
                Row r = rows.get(pos);
                if (inSelection()) {
                    toggle(r.key);
                } else if (getActivity() instanceof SafeActivity) {
                    ((SafeActivity) getActivity()).openFolder(video, r.key);
                }
            };
            View.OnLongClickListener longClick = v -> {
                int pos = getBindingAdapterPosition();
                if (pos == RecyclerView.NO_POSITION) return false;
                v.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS);
                toggle(rows.get(pos).key);
                return true;
            };
            card.setOnClickListener(click);
            card.setOnLongClickListener(longClick);
            itemView.setOnClickListener(click);
            itemView.setOnLongClickListener(longClick);
        }

        void bind(Row r) {
            Context ctx = itemView.getContext();
            txtName.setText(displayName(ctx, r.key));
            txtCount.setText(Fmt.plural(ctx, R.plurals.items_count, r.count)
                    .toLowerCase(Locale.getDefault()));
            boolean sel = selected.contains(r.key);
            imgCheck.setVisibility(sel ? View.VISIBLE : View.GONE);
            imgCover.setAlpha(sel ? 0.55f : 1f);
            // Щит на встроенной папке «Личный» — её нельзя удалить
            imgBadge.setImageResource(R.drawable.ic_shield);
            imgBadge.setImageTintList(android.content.res.ColorStateList.valueOf(
                    com.premiumlab.galleryx.util.Theming.accent(ctx)));
            imgBadge.setVisibility(r.personal ? View.VISIBLE : View.GONE);
            imgArt.setImageResource(video ? R.drawable.ic_movie : R.drawable.ic_image);
            if (r.cover != null) {
                coverArt.setVisibility(View.GONE);
                int cols = Math.max(1, Prefs.albumColumns());
                int size = Math.max(160, ctx.getResources().getDisplayMetrics().widthPixels / cols);
                Glide.with(ctx)
                        .load(new File(r.cover))
                        .override(size, size)
                        .centerCrop()
                        .placeholder(R.drawable.placeholder_media)
                        .into(imgCover);
            } else {
                coverArt.setVisibility(View.VISIBLE);
                imgCover.setImageDrawable(null);
            }
        }
    }
}
