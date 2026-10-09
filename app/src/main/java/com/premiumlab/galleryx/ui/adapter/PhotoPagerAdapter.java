package com.premiumlab.galleryx.ui.adapter;

import android.content.Context;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;

import com.bumptech.glide.Glide;
import com.premiumlab.galleryx.R;
import com.premiumlab.galleryx.data.MediaEngine;
import com.premiumlab.galleryx.ui.view.ZoomableImageView;

import java.io.File;
import java.util.ArrayList;
import java.util.List;

/**
 * Страницы полноэкранного просмотрщика: фото с зумом и видео (кадр + кнопка «play»).
 */
public class PhotoPagerAdapter extends RecyclerView.Adapter<PhotoPagerAdapter.VH> {

    /** Нажатие на кнопку воспроизведения на странице видео. */
    public interface PlayListener {
        void onPlay(String path);
    }

    private final List<String> paths = new ArrayList<>();
    private final ZoomableImageView.TapListener tapListener;
    private final ZoomableImageView.DragListener dragListener;
    private final PlayListener playListener;
    private final int loadSize;
    /** Явный список путей-видео (сейф хранит файлы под именами .bin). */
    private java.util.Set<String> videoPaths;

    public PhotoPagerAdapter(Context ctx, ZoomableImageView.TapListener tapListener,
                             ZoomableImageView.DragListener dragListener,
                             PlayListener playListener) {
        this.tapListener = tapListener;
        this.dragListener = dragListener;
        this.playListener = playListener;
        // Размер декода = большая сторона экрана: быстро и достаточно для зума
        this.loadSize = Math.max(ctx.getResources().getDisplayMetrics().widthPixels,
                ctx.getResources().getDisplayMetrics().heightPixels);
    }

    public void setVideoPaths(java.util.Set<String> paths) {
        this.videoPaths = paths;
    }

    public boolean isVideo(String path) {
        if (videoPaths != null && videoPaths.contains(path)) return true;
        return MediaEngine.isVideoName(path);
    }

    public void submit(List<String> list) {
        paths.clear();
        paths.addAll(list);
        notifyDataSetChanged();
    }

    public void removeAt(int index) {
        if (index >= 0 && index < paths.size()) {
            paths.remove(index);
            notifyItemRemoved(index);
        }
    }

    public String getPath(int index) {
        if (index < 0 || index >= paths.size()) return null;
        return paths.get(index);
    }

    public int indexOf(String path) {
        return paths.indexOf(path);
    }

    @NonNull
    @Override
    public VH onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        View v = LayoutInflater.from(parent.getContext())
                .inflate(R.layout.item_viewer_page, parent, false);
        return new VH(v);
    }

    @Override
    public void onBindViewHolder(@NonNull VH holder, int position) {
        holder.bind(paths.get(position));
    }

    @Override
    public void onBindViewHolder(@NonNull VH holder, int position,
                                 @NonNull List<Object> payloads) {
        holder.bind(paths.get(position));
    }

    @Override
    public int getItemCount() {
        return paths.size();
    }

    class VH extends RecyclerView.ViewHolder {
        final ZoomableImageView zoom;
        final View btnPlay;

        VH(@NonNull View itemView) {
            super(itemView);
            zoom = itemView.findViewById(R.id.zoomImage);
            btnPlay = itemView.findViewById(R.id.btnPagePlay);
        }

        void bind(String path) {
            boolean video = isVideo(path);
            btnPlay.setVisibility(video ? View.VISIBLE : View.GONE);
            btnPlay.setOnClickListener(video && playListener != null
                    ? v -> playListener.onPlay(path) : null);
            zoom.reset();
            // Страница могла остаться «улетевшей» после отменённого свайпа
            zoom.setTranslationX(0f);
            zoom.setTranslationY(0f);
            zoom.setScaleX(1f);
            zoom.setScaleY(1f);
            zoom.setAlpha(1f);
            zoom.setupGestureDetectors(itemView.getContext());
            zoom.setTapListener(tapListener);
            zoom.setDragListener(dragListener);
            Glide.with(itemView)
                    .load(new File(path))
                    .override(loadSize, loadSize)
                    .into(zoom);
        }
    }
}
