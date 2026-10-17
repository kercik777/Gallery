package com.premiumlab.galleryx.ui;

import android.os.Bundle;
import android.view.View;

import androidx.annotation.NonNull;

import com.premiumlab.galleryx.R;
import com.premiumlab.galleryx.data.MediaEngine;
import com.premiumlab.galleryx.data.MediaItem;
import com.premiumlab.galleryx.data.Prefs;
import com.premiumlab.galleryx.data.SelfieIndex;
import com.premiumlab.galleryx.data.SmartAlbum;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;

/**
 * «Умный» альбом: Фото / Видео / Селфи / Скриншоты / Избранное / Отредактированные.
 * Список всех медиа устройства, отфильтрованный по правилу {@link SmartAlbum#matches}.
 */
public class SmartFragment extends BaseMediaFragment {

    private int kind = SmartAlbum.PHOTOS;

    public static SmartFragment newInstance(int kind) {
        SmartFragment f = new SmartFragment();
        Bundle b = new Bundle();
        b.putInt("kind", kind);
        f.setArguments(b);
        return f;
    }

    @Override
    protected int layoutRes() {
        return R.layout.fragment_folder;
    }

    @Override
    protected void onViewsReady(@NonNull View root) {
        if (getArguments() != null) kind = getArguments().getInt("kind", SmartAlbum.PHOTOS);
        if (chipRow != null) chipRow.setVisibility(View.GONE);
        if (btnBack != null) btnBack.setVisibility(View.VISIBLE);
        if (btnHeaderExtra != null) btnHeaderExtra.setVisibility(View.GONE);
        if (txtHeaderTitle != null) txtHeaderTitle.setText(titleRes(kind));
    }

    public static int titleRes(int kind) {
        switch (kind) {
            case SmartAlbum.VIDEOS:
                return R.string.smart_videos;
            case SmartAlbum.SELFIES:
                return R.string.smart_selfies;
            case SmartAlbum.SCREENSHOTS:
                return R.string.smart_screenshots;
            case SmartAlbum.FAVORITES:
                return R.string.smart_favorites;
            case SmartAlbum.EDITED:
                return R.string.smart_edited;
            default:
                return R.string.smart_photos;
        }
    }

    @Override
    protected void loadMedia() {
        if (!MediaEngine.hasFreshCache()
                && !MediaEngine.hasDiskCache(requireContext())) {
            showLoading(true);
        }
        MediaEngine.loadAll(requireContext(), items -> {
            if (!isAdded()) return;
            showLoading(false);
            if (kind == SmartAlbum.SELFIES) {
                SelfieIndex.warmUp(requireContext());
                SelfieIndex.ensureIndexed(requireContext(), items);
            }
            setData(items);
            if (shownItems.isEmpty() && query.isEmpty() && txtEmptyTitle != null) {
                boolean analysing = kind == SmartAlbum.SELFIES
                        && (SelfieIndex.isIndexing() || SelfieIndex.pending() > 0);
                txtEmptyTitle.setText(analysing
                        ? R.string.smart_analysing_title : R.string.smart_empty_title);
                txtEmptySub.setText(analysing
                        ? R.string.smart_analysing_sub : R.string.smart_empty_sub);
            }
        });
    }

    private final SelfieIndex.Listener selfieListener = () -> {
        if (kind == SmartAlbum.SELFIES && isAdded()) loadMedia();
    };

    @Override
    public void onStart() {
        super.onStart();
        SelfieIndex.addListener(selfieListener);
    }

    @Override
    public void onStop() {
        super.onStop();
        SelfieIndex.removeListener(selfieListener);
    }

    @Override
    protected List<MediaItem> extraFilter(List<MediaItem> input) {
        HashSet<String> favs = kind == SmartAlbum.FAVORITES
                ? new HashSet<>(Prefs.favorites()) : null;
        List<MediaItem> out = new ArrayList<>();
        for (MediaItem it : input) {
            if (SmartAlbum.matches(kind, it, favs)) out.add(it);
        }
        return out;
    }

    @Override
    protected void onItemOpen(MediaItem item) {
        openViewer(item, 0);
    }
}
