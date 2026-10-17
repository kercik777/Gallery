package com.premiumlab.galleryx.ui;

import android.os.Bundle;
import android.view.View;
import android.widget.TextView;

import androidx.annotation.NonNull;

import com.premiumlab.galleryx.MainActivity;
import com.premiumlab.galleryx.R;
import com.premiumlab.galleryx.data.MediaEngine;
import com.premiumlab.galleryx.data.MediaItem;

import java.util.ArrayList;
import java.util.List;

/**
 * Главный экран: все фото и видео устройства с фильтрами
 * «Все / Фото / Видео / Избранное».
 */
public class GalleryFragment extends BaseMediaFragment {

    private static final int FILTER_ALL = 0;
    private static final int FILTER_PHOTO = 1;
    private static final int FILTER_VIDEO = 2;
    private static final int FILTER_FAV = 3;

    private int filter = FILTER_ALL;
    private TextView chipAll, chipPhotos, chipVideos, chipFavorites;
    private boolean pendingSearch = false;

    @Override
    protected int layoutRes() {
        return R.layout.fragment_gallery;
    }

    /** У галереи свой набор чипов (с «Избранным»). */
    @Override
    protected boolean useTypeChips() {
        return false;
    }

    @Override
    protected void onViewsReady(@NonNull View root) {
        chipAll = root.findViewById(R.id.chipAll);
        chipPhotos = root.findViewById(R.id.chipPhotos);
        chipVideos = root.findViewById(R.id.chipVideos);
        chipAll.setOnClickListener(v -> setFilter(FILTER_ALL));
        chipPhotos.setOnClickListener(v -> setFilter(FILTER_PHOTO));
        chipVideos.setOnClickListener(v -> setFilter(FILTER_VIDEO));
        chipFavorites = root.findViewById(R.id.chipFavorites);
        chipFavorites.setOnClickListener(v -> setFilter(FILTER_FAV));
        updateChips();
        // Заголовок «Галерея» — секретная кнопка маскировки (удержание 3 секунды)
        if (getActivity() instanceof MainActivity && txtHeaderTitle != null) {
            ((MainActivity) getActivity()).registerHoldTarget(txtHeaderTitle);
        }
        // Поиск открывается круглой кнопкой у панели вкладок — иконку в шапке прячем
        if (btnSearch != null && getActivity() instanceof MainActivity) {
            btnSearch.setVisibility(View.GONE);
        }
        if (pendingSearch) {
            pendingSearch = false;
            root.post(this::openSearch);
        }
    }

    /** Раскрывает строку поиска и показывает клавиатуру. */
    public void openSearch() {
        toggleSearch(true);
        if (editSearch != null) {
            editSearch.post(() -> {
                editSearch.requestFocus();
                android.view.inputmethod.InputMethodManager imm =
                        (android.view.inputmethod.InputMethodManager) requireContext()
                                .getSystemService(android.content.Context.INPUT_METHOD_SERVICE);
                if (imm != null) imm.showSoftInput(editSearch,
                        android.view.inputmethod.InputMethodManager.SHOW_IMPLICIT);
            });
        }
    }

    /** Если вкладка ещё не создана — откроем поиск сразу после её появления. */
    public void openSearchWhenReady() {
        pendingSearch = true;
    }

    private void setFilter(int f) {
        if (filter == f) return;
        filter = f;
        updateChips();
        applyFilter();
        if (shownItems.isEmpty() && query.isEmpty() && txtEmptyTitle != null) {
            if (filter == FILTER_FAV) {
                txtEmptyTitle.setText(R.string.fav_empty_title);
                txtEmptySub.setText(R.string.fav_empty_sub);
            } else {
                txtEmptyTitle.setText(R.string.empty_gallery_title);
                txtEmptySub.setText(R.string.empty_gallery_sub);
            }
        }
    }

    private void updateChips() {
        styleChip(chipAll, filter == FILTER_ALL);
        styleChip(chipPhotos, filter == FILTER_PHOTO);
        styleChip(chipVideos, filter == FILTER_VIDEO);
        styleChip(chipFavorites, filter == FILTER_FAV);
    }

    @Override
    protected void loadMedia() {
        // При свежем кэше не мигаем индикатором загрузки — переключение мгновенное.
        // Дисковый кеш тоже отдаётся мгновенно — спиннер не нужен и на холодном старте.
        if (!MediaEngine.hasFreshCache()
                && !MediaEngine.hasDiskCache(requireContext())) {
            showLoading(true);
        }
        MediaEngine.loadAll(requireContext(), items -> {
            if (!isAdded()) return;
            showLoading(false);
            setData(items);
            // Пустое состояние с учётом фильтра
            if (shownItems.isEmpty() && query.isEmpty()) {
                if (filter == FILTER_FAV) {
                    txtEmptyTitle.setText(R.string.fav_empty_title);
                    txtEmptySub.setText(R.string.fav_empty_sub);
                } else {
                    txtEmptyTitle.setText(R.string.empty_gallery_title);
                    txtEmptySub.setText(R.string.empty_gallery_sub);
                }
            }
        });
    }

    @Override
    protected List<MediaItem> extraFilter(List<MediaItem> input) {
        if (filter == FILTER_ALL) return input;
        List<MediaItem> out = new ArrayList<>();
        if (filter == FILTER_FAV) {
            java.util.HashSet<String> favs = new java.util.HashSet<>(
                    com.premiumlab.galleryx.data.Prefs.favorites());
            if (favs.isEmpty()) return out;
            for (MediaItem it : input) {
                if (favs.contains(it.path)) out.add(it);
            }
            return out;
        }
        for (MediaItem it : input) {
            if (filter == FILTER_PHOTO && !it.isVideo) out.add(it);
            if (filter == FILTER_VIDEO && it.isVideo) out.add(it);
        }
        return out;
    }

    @Override
    protected void onItemOpen(MediaItem item) {
        openViewer(item, 0);
    }
}
