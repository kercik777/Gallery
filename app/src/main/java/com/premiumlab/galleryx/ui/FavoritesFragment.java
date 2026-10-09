package com.premiumlab.galleryx.ui;

import android.view.View;

import com.premiumlab.galleryx.R;
import com.premiumlab.galleryx.data.FavStore;
import com.premiumlab.galleryx.data.MediaItem;


/**
 * Экран избранного.
 */
public class FavoritesFragment extends BaseMediaFragment {

    @Override
    protected int layoutRes() {
        return R.layout.fragment_favorites;
    }

    @Override
    protected void loadMedia() {
        showLoading(true);
        recycler.post(() -> {
            if (!isAdded()) return;
            showLoading(false);
            setData(FavStore.items());
            if (shownItems.isEmpty() && query.isEmpty()) {
                txtEmptyTitle.setText(R.string.fav_empty_title);
                txtEmptySub.setText(R.string.fav_empty_sub);
            }
        });
    }

    @Override
    protected void onItemOpen(MediaItem item) {
        openViewer(item, 0);
    }

    @Override
    protected void onViewsReady(View root) {
        // Заголовок уже задан в layout
    }
}
