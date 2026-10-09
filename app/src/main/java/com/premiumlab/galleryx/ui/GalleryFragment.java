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
 * Главный экран: все фото и видео устройства с фильтрами «Все / Фото / Видео».
 */
public class GalleryFragment extends BaseMediaFragment {

    private static final int FILTER_ALL = 0;
    private static final int FILTER_PHOTO = 1;
    private static final int FILTER_VIDEO = 2;

    private int filter = FILTER_ALL;
    private TextView chipAll, chipPhotos, chipVideos;

    @Override
    protected int layoutRes() {
        return R.layout.fragment_gallery;
    }

    @Override
    protected void onViewsReady(@NonNull View root) {
        chipAll = root.findViewById(R.id.chipAll);
        chipPhotos = root.findViewById(R.id.chipPhotos);
        chipVideos = root.findViewById(R.id.chipVideos);
        chipAll.setOnClickListener(v -> setFilter(FILTER_ALL));
        chipPhotos.setOnClickListener(v -> setFilter(FILTER_PHOTO));
        chipVideos.setOnClickListener(v -> setFilter(FILTER_VIDEO));
        updateChips();
        // Заголовок «Галерея» — секретная кнопка маскировки (удержание 3 секунды)
        if (getActivity() instanceof MainActivity && txtHeaderTitle != null) {
            ((MainActivity) getActivity()).registerHoldTarget(txtHeaderTitle);
        }
    }

    private void setFilter(int f) {
        if (filter == f) return;
        filter = f;
        updateChips();
        applyFilter();
    }

    private void updateChips() {
        styleChip(chipAll, filter == FILTER_ALL);
        styleChip(chipPhotos, filter == FILTER_PHOTO);
        styleChip(chipVideos, filter == FILTER_VIDEO);
    }

    private void styleChip(TextView chip, boolean active) {
        if (chip == null) return;
        chip.setBackgroundResource(active ? R.drawable.bg_chip_active : R.drawable.bg_chip);
        chip.setTextColor(active ? getResources().getColor(R.color.onPrimary)
                : getResources().getColor(R.color.textSecondary));
    }

    @Override
    protected void loadMedia() {
        // При свежем кэше не мигаем индикатором загрузки — переключение мгновенное
        if (!MediaEngine.hasFreshCache()) {
            showLoading(true);
        }
        MediaEngine.loadAll(requireContext(), items -> {
            if (!isAdded()) return;
            showLoading(false);
            setData(items);
            // Пустое состояние с учётом фильтра
            if (shownItems.isEmpty() && query.isEmpty()) {
                txtEmptyTitle.setText(R.string.empty_gallery_title);
                txtEmptySub.setText(R.string.empty_gallery_sub);
            }
        });
    }

    @Override
    protected List<MediaItem> extraFilter(List<MediaItem> input) {
        if (filter == FILTER_ALL) return input;
        List<MediaItem> out = new ArrayList<>();
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
