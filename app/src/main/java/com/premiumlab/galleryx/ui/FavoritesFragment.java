package com.premiumlab.galleryx.ui;

import android.content.Intent;
import android.view.View;

import com.premiumlab.galleryx.PhotoViewerActivity;
import com.premiumlab.galleryx.R;
import com.premiumlab.galleryx.VideoPlayerActivity;
import com.premiumlab.galleryx.data.FavStore;
import com.premiumlab.galleryx.data.MediaItem;

import java.util.ArrayList;

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
        if (item.isVideo) {
            Intent intent = new Intent(requireContext(), VideoPlayerActivity.class);
            intent.putExtra("path", item.path);
            intent.putExtra("name", item.name);
            intent.putExtra("mode", 0);
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
            intent.putExtra("mode", 0);
            startActivity(intent);
        }
    }

    @Override
    protected void onViewsReady(View root) {
        // Заголовок уже задан в layout
    }
}
