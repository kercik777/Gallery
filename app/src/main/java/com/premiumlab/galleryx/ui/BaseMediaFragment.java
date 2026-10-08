package com.premiumlab.galleryx.ui;

import android.content.Intent;
import android.os.Bundle;
import android.text.Editable;
import android.text.TextWatcher;
import android.view.LayoutInflater;
import android.view.MotionEvent;
import android.view.ScaleGestureDetector;
import android.view.View;
import android.view.ViewGroup;
import android.widget.EditText;
import android.widget.HorizontalScrollView;
import android.widget.ImageView;
import android.widget.LinearLayout;
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
import com.premiumlab.galleryx.MainActivity;
import com.premiumlab.galleryx.R;
import com.premiumlab.galleryx.RootPickerActivity;
import com.premiumlab.galleryx.data.MaskGuard;
import com.premiumlab.galleryx.data.MediaItem;
import com.premiumlab.galleryx.data.Prefs;
import com.premiumlab.galleryx.data.SessionManager;
import com.premiumlab.galleryx.ui.adapter.MediaAdapter;
import com.premiumlab.galleryx.ui.dialog.CreateFolderDialog;
import com.premiumlab.galleryx.ui.dialog.DestSheet;
import com.premiumlab.galleryx.ui.dialog.OpProgressDialog;
import com.premiumlab.galleryx.ui.sheet.SortSheet;
import com.premiumlab.galleryx.util.FileOp;

import java.io.File;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Базовый фрагмент любой сетки медиафайлов: галерея, избранное, папка,
 * корзина, сейф. Реализует поиск, сортировку, щипок изменения сетки,
 * множественное выделение и все операции с файлами.
 *
 * Панели выделения (верхняя и нижняя) живут на уровне host-activity
 * (selectionTop / selectionActions) и появляются поверх контента —
 * сетка при входе в режим выделения не смещается.
 */
public abstract class BaseMediaFragment extends Fragment
        implements MediaAdapter.Listener, BackHandler {

    protected static final int REQ_ROOT = 501;

    protected RecyclerView recycler;
    protected MediaAdapter adapter;
    protected GridLayoutManager layoutManager;
    protected final List<MediaItem> allItems = new ArrayList<>();
    protected final List<MediaItem> shownItems = new ArrayList<>();
    protected String query = "";

    protected View headerRoot, layoutEmpty, layoutLoading, searchRow;
    protected HorizontalScrollView chipRow;
    protected LinearLayout chipContainer;
    protected EditText editSearch;
    protected TextView txtHeaderTitle, txtEmptyTitle, txtEmptySub;
    protected ImageView imgEmpty, btnBack, btnSearch, btnSort, btnHeaderExtra;
    protected ImageView btnHideNow, btnSearchClose;

    /** Хром выделения на уровне activity. */
    protected View selTop, selActions;
    protected TextView txtSelCount;

    /** Действие, которое нужно повторить после выбора корневой папки. */
    protected Runnable pendingAfterRoot;
    /** Отложенная операция копирования/перемещения. */
    protected List<MediaItem> pendingDestItems;
    protected boolean pendingDestCopy;

    // ---------- Контракт подклассов ----------

    protected abstract int layoutRes();

    protected abstract void loadMedia();

    protected void onViewsReady(@NonNull View root) {
    }

    protected List<MediaItem> extraFilter(List<MediaItem> input) {
        return input;
    }

    /** Настраивает видимость кнопок нижнего ряда действий под конкретный экран. */
    protected void configureSelectionBar() {
    }

    protected boolean allowSearch() {
        return true;
    }

    protected boolean allowSort() {
        return true;
    }

    protected void onItemOpen(MediaItem item) {
    }

    protected void onDeleteSelected(List<MediaItem> selected) {
        FragmentActivity act = requireActivity();
        Actions.confirmTrash(act, selected, this::afterAction);
    }

    protected void onRestoreSelected(java.util.Set<String> paths) {
    }

    protected void onHeaderExtraClick() {
    }

    // ---------- Жизненный цикл ----------

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container,
                             @Nullable Bundle savedInstanceState) {
        return inflater.inflate(layoutRes(), container, false);
    }

    @Override
    public void onViewCreated(@NonNull View v, @Nullable Bundle savedInstanceState) {
        bindViews(v);
        setupRecycler(v);
        setupHeader(v);
        setupSelectionChrome();
        setupPinchZoom(v);
        onViewsReady(v);
        loadMedia();
    }

    protected void bindViews(View v) {
        headerRoot = v.findViewById(R.id.headerRoot);
        layoutEmpty = v.findViewById(R.id.layoutEmpty);
        layoutLoading = v.findViewById(R.id.layoutLoading);
        searchRow = v.findViewById(R.id.layoutSearchRow);
        chipRow = v.findViewById(R.id.chipRow);
        chipContainer = v.findViewById(R.id.chipContainer);
        editSearch = v.findViewById(R.id.editSearch);
        txtHeaderTitle = v.findViewById(R.id.txtHeaderTitle);
        txtEmptyTitle = v.findViewById(R.id.txtEmptyTitle);
        txtEmptySub = v.findViewById(R.id.txtEmptySub);
        imgEmpty = v.findViewById(R.id.imgEmpty);
        btnBack = v.findViewById(R.id.btnBack);
        btnSearch = v.findViewById(R.id.btnSearch);
        btnSort = v.findViewById(R.id.btnSort);
        btnHeaderExtra = v.findViewById(R.id.btnHeaderExtra);
        btnHideNow = v.findViewById(R.id.btnHideNow);
        btnSearchClose = v.findViewById(R.id.btnSearchClose);
    }

    protected void setupRecycler(View v) {
        recycler = v.findViewById(R.id.recyclerMedia);
        adapter = new MediaAdapter(requireContext(), this);
        layoutManager = new GridLayoutManager(requireContext(), Prefs.columns()) {
            @Override
            public boolean canScrollVertically() {
                return true;
            }
        };
        layoutManager.setSpanSizeLookup(new GridLayoutManager.SpanSizeLookup() {
            @Override
            public int getSpanSize(int position) {
                return adapter.getItemViewType(position) == MediaAdapter.getItemTypeHeader()
                        ? layoutManager.getSpanCount() : 1;
            }
        });
        recycler.setLayoutManager(layoutManager);
        recycler.setAdapter(adapter);
        recycler.setHasFixedSize(true);
        // Плавность: без анимации «мерцания» при смене выделения
        RecyclerView.ItemAnimator an = recycler.getItemAnimator();
        if (an instanceof SimpleItemAnimator) {
            ((SimpleItemAnimator) an).setSupportsChangeAnimations(false);
        }
        recycler.setItemViewCacheSize(12);
    }

    protected void setupHeader(View v) {
        if (btnBack != null) {
            btnBack.setOnClickListener(x -> requireActivity().finish());
        }
        if (btnSearch != null) {
            if (allowSearch()) {
                btnSearch.setOnClickListener(x -> toggleSearch(true));
            } else {
                btnSearch.setVisibility(View.GONE);
            }
        }
        if (btnSearchClose != null) {
            btnSearchClose.setOnClickListener(x -> toggleSearch(false));
        }
        if (editSearch != null) {
            editSearch.addTextChangedListener(new TextWatcher() {
                @Override
                public void beforeTextChanged(CharSequence s, int a, int b, int c) {
                }

                @Override
                public void onTextChanged(CharSequence s, int a, int b, int c) {
                }

                @Override
                public void afterTextChanged(Editable s) {
                    query = s.toString().trim().toLowerCase(Locale.getDefault());
                    applyFilter();
                }
            });
        }
        if (btnSort != null) {
            if (allowSort()) {
                btnSort.setOnClickListener(x -> {
                    SortSheet sheet = new SortSheet();
                    sheet.setListener(mode -> applyFilter());
                    sheet.show(getParentFragmentManager(), "sort");
                });
            } else {
                btnSort.setVisibility(View.GONE);
            }
        }
        if (btnHeaderExtra != null) {
            btnHeaderExtra.setOnClickListener(x -> onHeaderExtraClick());
        }
        if (btnHideNow != null) {
            updateHideNowButton();
            btnHideNow.setOnClickListener(x -> {
                SessionManager.lock();
                if (!(requireActivity() instanceof MainActivity)) {
                    requireActivity().finish();
                }
            });
        }
    }

    protected void updateHideNowButton() {
        if (btnHideNow == null) return;
        boolean show = Prefs.masking() && Prefs.autoHide() == Prefs.AUTOHIDE_MANUAL
                && SessionManager.isUnlocked();
        btnHideNow.setVisibility(show ? View.VISIBLE : View.GONE);
    }

    protected void toggleSearch(boolean show) {
        if (searchRow == null || btnSearch == null) return;
        searchRow.setVisibility(show ? View.VISIBLE : View.GONE);
        btnSearch.setVisibility(show ? View.GONE : View.VISIBLE);
        if (show && editSearch != null) {
            editSearch.requestFocus();
        } else if (editSearch != null) {
            editSearch.setText("");
            query = "";
            applyFilter();
        }
    }

    // ---------- Хром множественного выделения ----------

    /**
     * Привязывает верхнюю панель и нижний ряд действий (находятся в layout
     * host-activity) к этому фрагменту. Вызывается при создании и каждом
     * показе фрагмента, чтобы видимая вкладка всегда владела кнопками.
     */
    protected void setupSelectionChrome() {
        FragmentActivity act = getActivity();
        if (act == null) return;
        selTop = act.findViewById(R.id.selectionTop);
        selActions = act.findViewById(R.id.selectionActions);

        if (selTop != null) {
            selTop.findViewById(R.id.btnSelClose).setOnClickListener(x -> exitSelection());
            View btnAll = selTop.findViewById(R.id.btnSelAll);
            btnAll.setVisibility(View.VISIBLE);
            btnAll.setOnClickListener(x -> {
                if (adapter == null) return;
                if (isAllSelected()) {
                    exitSelection();
                } else {
                    adapter.selectAll(shownItems);
                    updateSelBar();
                }
            });
            txtSelCount = selTop.findViewById(R.id.txtSelCount);
        }

        if (selActions != null) {
            // Панели общие для всех вкладок activity: сначала возвращаем набор
            // кнопок по умолчанию, иначе после «Альбомов» (где остаётся только
            // «Удалить») в галерее пропадали остальные действия.
            resetSelectionBar();
            selActions.findViewById(R.id.btnSelFavorite).setOnClickListener(x -> {
                List<MediaItem> sel = selectedItems();
                if (!sel.isEmpty()) {
                    Actions.favorite(requireActivity(), sel, this::afterAction);
                }
            });
            selActions.findViewById(R.id.btnSelCopy).setOnClickListener(x -> destFlow(true));
            selActions.findViewById(R.id.btnSelMove).setOnClickListener(x -> destFlow(false));
            selActions.findViewById(R.id.btnSelSafe).setOnClickListener(x -> {
                List<MediaItem> sel = selectedItems();
                if (!sel.isEmpty()) {
                    Actions.confirmSafe(requireActivity(), sel, this::afterAction);
                }
            });
            selActions.findViewById(R.id.btnSelShare).setOnClickListener(x -> {
                List<MediaItem> sel = selectedItems();
                if (!sel.isEmpty()) Actions.share(requireActivity(), sel);
            });
            selActions.findViewById(R.id.btnSelDelete).setOnClickListener(x -> {
                List<MediaItem> sel = selectedItems();
                if (!sel.isEmpty()) onDeleteSelected(sel);
            });
            selActions.findViewById(R.id.btnSelRestore).setOnClickListener(x -> {
                if (adapter != null && adapter.getSelectedCount() > 0) {
                    onRestoreSelected(adapter.getSelectedPaths());
                }
            });
        }

        configureSelectionBar();
        // Пока галерея «закрыта» маскировкой — сейф и корневые папки не показываем
        if (MaskGuard.hidden() && selActions != null) {
            selActions.findViewById(R.id.btnSelSafe).setVisibility(View.GONE);
            selActions.findViewById(R.id.btnSelCopy).setVisibility(View.GONE);
            selActions.findViewById(R.id.btnSelMove).setVisibility(View.GONE);
        }
        updateSelBar();
    }

    /** Набор кнопок нижнего ряда по умолчанию (обычная сетка медиа). */
    private void resetSelectionBar() {
        if (selActions == null) return;
        int[] visible = {R.id.btnSelFavorite, R.id.btnSelShare, R.id.btnSelCopy,
                R.id.btnSelMove, R.id.btnSelSafe, R.id.btnSelDelete};
        for (int id : visible) {
            View b = selActions.findViewById(id);
            if (b != null) b.setVisibility(View.VISIBLE);
        }
        View restore = selActions.findViewById(R.id.btnSelRestore);
        if (restore != null) restore.setVisibility(View.GONE);
        View rename = selActions.findViewById(R.id.btnSelRename);
        if (rename != null) rename.setVisibility(View.GONE);
    }

    protected boolean isAllSelected() {
        return adapter != null && !shownItems.isEmpty()
                && adapter.getSelectedCount() >= shownItems.size();
    }

    protected void setupPinchZoom(View v) {
        if (recycler == null) return;
        ScaleGestureDetector detector = new ScaleGestureDetector(requireContext(),
                new ScaleGestureDetector.SimpleOnScaleGestureListener() {
                    float beginSpan;

                    @Override
                    public boolean onScaleBegin(@NonNull ScaleGestureDetector d) {
                        beginSpan = Math.max(1f, d.getScaleFactor());
                        return true;
                    }

                    @Override
                    public boolean onScale(ScaleGestureDetector d) {
                        return true;
                    }

                    @Override
                    public void onScaleEnd(@NonNull ScaleGestureDetector d) {
                        float factor = d.getCurrentSpan() / Math.max(1f, beginSpan);
                        int oldSpan = layoutManager.getSpanCount();
                        int newSpan = Math.round(oldSpan / factor);
                        newSpan = Math.max(2, Math.min(6, newSpan));
                        if (newSpan != oldSpan) {
                            Prefs.setColumns(newSpan);
                            layoutManager.setSpanCount(newSpan);
                        }
                    }
                });
        recycler.addOnItemTouchListener(new RecyclerView.SimpleOnItemTouchListener() {
            @Override
            public boolean onInterceptTouchEvent(@NonNull RecyclerView rv,
                                                 @NonNull MotionEvent e) {
                if (detector != null) detector.onTouchEvent(e);
                return detector.isInProgress();
            }

            @Override
            public void onTouchEvent(@NonNull RecyclerView rv, @NonNull MotionEvent e) {
                if (detector != null) detector.onTouchEvent(e);
            }
        });
    }

    // ---------- Данные ----------

    protected void setData(List<MediaItem> items) {
        allItems.clear();
        allItems.addAll(items);
        applyFilter();
    }

    protected void applyFilter() {
        List<MediaItem> filtered = new ArrayList<>();
        String q = query;
        boolean maskHidden = MaskGuard.hidden();
        for (MediaItem it : extraFilter(allItems)) {
            // Содержимое корневой папки скрыто, пока маскировка «закрыта»
            if (maskHidden && MaskGuard.isHiddenPath(it.path)) continue;
            if (q.isEmpty() || it.name.toLowerCase(Locale.getDefault()).contains(q)) {
                filtered.add(it);
            }
        }
        shownItems.clear();
        shownItems.addAll(filtered);
        MediaEngine_sort(shownItems);
        if (adapter != null) {
            adapter.submitRows(MediaAdapter.buildRows(requireContext(), shownItems));
        }
        updateEmptyState();
    }

    private void MediaEngine_sort(List<MediaItem> list) {
        com.premiumlab.galleryx.data.MediaEngine.sort(list, Prefs.sortMode());
    }

    protected void updateEmptyState() {
        if (layoutLoading != null) layoutLoading.setVisibility(View.GONE);
        if (layoutEmpty != null) {
            boolean empty = shownItems.isEmpty();
            layoutEmpty.setVisibility(empty ? View.VISIBLE : View.GONE);
            if (!query.isEmpty() && empty) {
                txtEmptyTitle.setText(R.string.search_no_results_title);
                txtEmptySub.setText(R.string.search_no_results_sub);
            }
        }
    }

    protected void showLoading(boolean show) {
        if (layoutLoading != null) layoutLoading.setVisibility(show ? View.VISIBLE : View.GONE);
        if (show && layoutEmpty != null) layoutEmpty.setVisibility(View.GONE);
    }

    protected void reload() {
        exitSelection();
        loadMedia();
    }

    /** Полное обновление экрана после смены состояния маскировки. */
    public void reloadForMask() {
        if (!isAdded() || adapter == null) return;
        exitSelection();
        setupSelectionChrome();
        updateHideNowButton();
        loadMedia();
    }

    protected void afterAction() {
        exitSelection();
        loadMedia();
    }

    // ---------- Выделение ----------

    protected List<MediaItem> selectedItems() {
        List<MediaItem> out = new ArrayList<>();
        for (MediaItem it : shownItems) {
            if (adapter.isSelected(it.path)) out.add(it);
        }
        return out;
    }

    public boolean isInSelection() {
        return adapter != null && adapter.isSelection();
    }

    protected void exitSelection() {
        if (adapter != null && adapter.isSelection()) {
            adapter.exitSelection();
        }
        updateSelBar();
    }

    protected void updateSelBar() {
        if (adapter == null || !isAdded()) return;
        int count = adapter.getSelectedCount();
        boolean active = count > 0;
        if (selTop != null) {
            selTop.setVisibility(active ? View.VISIBLE : View.GONE);
            if (active && txtSelCount != null) {
                txtSelCount.setText(Fmt_sel(count));
            }
            ImageView btnAll = selTop.findViewById(R.id.btnSelAll);
            if (btnAll != null) {
                boolean all = isAllSelected();
                btnAll.setImageResource(all
                        ? R.drawable.ic_deselect_all : R.drawable.ic_select_all);
                btnAll.setContentDescription(getString(all
                        ? R.string.deselect_all : R.string.select_all));
            }
        }
        if (selActions != null) {
            selActions.setVisibility(active ? View.VISIBLE : View.GONE);
        }
        // В полноэкранных экранах (папка/корзина/сейф) панель действий
        // перекрывает низ сетки — даём контенту отступ на время выделения
        if (recycler != null && !(getActivity() instanceof SelectionHost)) {
            int pad = (int) (active ? 100 : 20) * getResources().getDisplayMetrics().densityDpi
                    / 160;
            recycler.setPadding(recycler.getPaddingLeft(), recycler.getPaddingTop(),
                    recycler.getPaddingRight(), pad);
        }
        if (getActivity() instanceof SelectionHost) {
            ((SelectionHost) getActivity()).onSelectionChanged(count);
        }
    }

    private String Fmt_sel(int count) {
        return com.premiumlab.galleryx.util.Fmt.plural(requireContext(),
                R.plurals.selected_count, count);
    }

    @Override
    public boolean onBackPressedHandled() {
        if (isInSelection()) {
            exitSelection();
            return true;
        }
        return false;
    }

    @Override
    public void onMediaClick(MediaItem item, int position) {
        if (adapter.isSelection()) {
            adapter.toggle(item.path);
            updateSelBar();
        } else {
            onItemOpen(item);
        }
    }

    @Override
    public void onMediaLongClick(MediaItem item, int position) {
        if (!adapter.isSelection()) {
            adapter.toggle(item.path);
            updateSelBar();
        }
    }

    // ---------- Копирование / перемещение ----------

    protected void destFlow(boolean copy) {
        List<MediaItem> sel = selectedItems();
        if (sel.isEmpty()) return;
        FragmentActivity act = requireActivity();

        if (Prefs.rootPath() == null) {
            pendingDestItems = sel;
            pendingDestCopy = copy;
            showRootNeeded(() -> destFlow(copy));
            return;
        }

        DestSheet sheet = DestSheet.newInstance(copy, destParentPath());
        sheet.setListener(dir -> runCopyMove(copy, sel, dir));
        sheet.show(getParentFragmentManager(), "dest");
    }

    /** Папка, в которой «Новая папка…» листа назначений создаёт подпапку (null — корень). */
    protected String destParentPath() {
        return null;
    }

    protected void runCopyMove(boolean copy, List<MediaItem> items, File dir) {
        FragmentActivity act = requireActivity();
        FileOp op = new FileOp(act);
        OpProgressDialog dlg = OpProgressDialog.show(act,
                act.getString(copy ? R.string.op_copy : R.string.op_move), op::cancel);
        if (copy) {
            op.copy(items, dir, dlg, (ok, fail, cancelled) ->
                    onCopyMoveDone(act, ok, cancelled, true));
        } else {
            op.move(items, dir, dlg, (ok, fail, cancelled) ->
                    onCopyMoveDone(act, ok, cancelled, false));
        }
    }

    private void onCopyMoveDone(FragmentActivity act, int ok, boolean cancelled, boolean copy) {
        Toast.makeText(act, cancelled ? act.getString(R.string.op_cancelled)
                : com.premiumlab.galleryx.util.Fmt.plural(act,
                copy ? R.plurals.result_copied : R.plurals.result_moved, ok),
                Toast.LENGTH_SHORT).show();
        afterAction();
    }

    // ---------- Корневая папка ----------

    protected void showRootNeeded(Runnable after) {
        FragmentActivity act = requireActivity();
        pendingAfterRoot = after;
        new MaterialAlertDialogBuilder(act)
                .setTitle(R.string.root_needed_title)
                .setMessage(R.string.root_needed_desc)
                .setPositiveButton(R.string.continue_btn, (d, w) ->
                        startActivityForResult(new Intent(act, RootPickerActivity.class), REQ_ROOT))
                .setNegativeButton(R.string.cancel, (d, w) -> pendingAfterRoot = null)
                .show();
    }

    @Override
    public void onActivityResult(int requestCode, int resultCode, @Nullable Intent data) {
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
            // Уходим с вкладки — сбрасываем выделение, чтобы панели не «зависали»
            exitSelection();
        } else {
            // Видимая вкладка заново владеет панелями выделения
            setupSelectionChrome();
            reload();
        }
    }

    @Override
    public void onResume() {
        super.onResume();
        updateHideNowButton();
    }
}
