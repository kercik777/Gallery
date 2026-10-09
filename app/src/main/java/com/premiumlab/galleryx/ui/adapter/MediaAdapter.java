package com.premiumlab.galleryx.ui.adapter;

import android.annotation.SuppressLint;
import android.content.Context;
import android.media.MediaMetadataRetriever;
import android.os.Handler;
import android.os.Looper;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.GridLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.bumptech.glide.Glide;
import com.premiumlab.galleryx.R;
import com.premiumlab.galleryx.data.Album;
import com.premiumlab.galleryx.data.FavStore;
import com.premiumlab.galleryx.data.MediaItem;
import com.premiumlab.galleryx.data.Prefs;
import com.premiumlab.galleryx.util.Fmt;

import java.io.File;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Сетка медиафайлов с заголовками дат и режимом множественного выделения.
 */
public class MediaAdapter extends RecyclerView.Adapter<RecyclerView.ViewHolder> {

    private static final int TYPE_HEADER = 0;
    private static final int TYPE_ITEM = 1;
    private static final int TYPE_FOLDERS = 2;

    /** Возвращает тип «заголовок даты» для SpanSizeLookup. */
    public static int getItemTypeHeader() {
        return TYPE_HEADER;
    }

    /** Тип «медиафайл» — единственный, занимающий одну ячейку сетки. */
    public static int getItemTypeMedia() {
        return TYPE_ITEM;
    }

    /** Строка сетки: заголовок даты, медиафайл или блок вложенных папок. */
    public static class Row {
        public final boolean header;
        public final String title;
        public final String countLabel;
        public final MediaItem item;
        public final List<Album> folders;

        Row(boolean header, String title, String countLabel, MediaItem item) {
            this(header, title, countLabel, item, null);
        }

        Row(boolean header, String title, String countLabel, MediaItem item,
            List<Album> folders) {
            this.header = header;
            this.title = title;
            this.countLabel = countLabel;
            this.item = item;
            this.folders = folders;
        }

        /** Блок вложенных папок (карточки как в «Альбомах») на всю ширину. */
        public static Row folders(String title, List<Album> folders) {
            return new Row(false, title, null, null, folders);
        }
    }

    public interface Listener {
        void onMediaClick(MediaItem item, int position);

        void onMediaLongClick(MediaItem item, int position);

        /** Нажатие на карточку вложенной папки. */
        default void onFolderOpen(Album folder) {
        }
    }

    private final Context ctx;
    private final Listener listener;
    private final List<Row> rows = new ArrayList<>();
    private final Set<String> selected = new HashSet<>();
    private final int thumbSize;
    private static final ExecutorService LAZY = Executors.newSingleThreadExecutor();
    private static final Handler MAIN = new Handler(Looper.getMainLooper());
    private boolean selectionMode = false;

    public MediaAdapter(Context ctx, Listener listener) {
        this.ctx = ctx;
        this.listener = listener;
        int w = ctx.getResources().getDisplayMetrics().widthPixels;
        this.thumbSize = w / 2;
    }

    public void submitRows(List<Row> newRows) {
        rows.clear();
        rows.addAll(newRows);
        selected.clear();
        selectionMode = false;
        notifyDataSetChanged();
    }

    public void clear() {
        rows.clear();
        selected.clear();
        selectionMode = false;
        notifyDataSetChanged();
    }

    // ---------- Выделение ----------

    public boolean isSelection() {
        return selectionMode;
    }

    public boolean isSelected(String path) {
        return selected.contains(path);
    }

    public void toggle(String path) {
        if (selected.contains(path)) {
            selected.remove(path);
        } else {
            selected.add(path);
        }
        selectionMode = !selected.isEmpty();
        notifyItemRangeChanged(0, rows.size(), "sel");
    }

    public void selectAll(List<MediaItem> items) {
        for (MediaItem it : items) selected.add(it.path);
        selectionMode = !selected.isEmpty();
        notifyItemRangeChanged(0, rows.size(), "sel");
    }

    public void exitSelection() {
        selected.clear();
        selectionMode = false;
        notifyItemRangeChanged(0, rows.size(), "sel");
    }

    public Set<String> getSelectedPaths() {
        return new HashSet<>(selected);
    }

    public int getSelectedCount() {
        return selected.size();
    }

    // ---------- ViewHolder'ы ----------

    @Override
    public int getItemViewType(int position) {
        Row r = rows.get(position);
        if (r.folders != null) return TYPE_FOLDERS;
        return r.header ? TYPE_HEADER : TYPE_ITEM;
    }

    @NonNull
    @Override
    public RecyclerView.ViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        LayoutInflater inf = LayoutInflater.from(parent.getContext());
        if (viewType == TYPE_HEADER) {
            return new HeaderVH(inf.inflate(R.layout.item_date_header, parent, false));
        }
        if (viewType == TYPE_FOLDERS) {
            return new FoldersVH(inf.inflate(R.layout.item_subfolders, parent, false));
        }
        return new MediaVH(inf.inflate(R.layout.item_media, parent, false));
    }

    @Override
    public void onBindViewHolder(@NonNull RecyclerView.ViewHolder holder, int position) {
        Row row = rows.get(position);
        if (holder instanceof HeaderVH) {
            ((HeaderVH) holder).bind(row);
        } else if (holder instanceof FoldersVH) {
            ((FoldersVH) holder).bind(row);
        } else if (holder instanceof MediaVH) {
            ((MediaVH) holder).bind(row.item);
        }
    }

    @Override
    public void onBindViewHolder(@NonNull RecyclerView.ViewHolder holder, int position,
                                 @NonNull List<Object> payloads) {
        if (!payloads.isEmpty() && "sel".equals(payloads.get(0))) {
            if (holder instanceof MediaVH) {
                ((MediaVH) holder).bindSelection(rows.get(position).item);
            }
            return; // заголовки и блок папок от выделения не зависят
        }
        super.onBindViewHolder(holder, position, payloads);
    }

    @Override
    public int getItemCount() {
        return rows.size();
    }

    // ---------- Заголовок даты ----------

    class HeaderVH extends RecyclerView.ViewHolder {
        final TextView txtDate, txtCount;

        HeaderVH(@NonNull View itemView) {
            super(itemView);
            txtDate = itemView.findViewById(R.id.txtDateHeader);
            txtCount = itemView.findViewById(R.id.txtHeaderCount);
        }

        void bind(Row row) {
            txtDate.setText(row.title);
            txtCount.setText(row.countLabel == null ? "" : row.countLabel);
        }
    }

    // ---------- Вложенные папки ----------

    class FoldersVH extends RecyclerView.ViewHolder {
        final TextView txtTitle;
        final RecyclerView grid;
        final AlbumsAdapter albumsAdapter;
        final GridLayoutManager lm;

        FoldersVH(@NonNull View itemView) {
            super(itemView);
            txtTitle = itemView.findViewById(R.id.txtSubfoldersTitle);
            grid = itemView.findViewById(R.id.recyclerSubfolders);
            albumsAdapter = new AlbumsAdapter(ctx, new AlbumsAdapter.Listener() {
                @Override
                public void onAlbumClick(Album album, int position) {
                    if (listener != null) listener.onFolderOpen(album);
                }

                @Override
                public void onAlbumLongClick(Album album, int position) {
                    if (listener != null) listener.onFolderOpen(album);
                }
            });
            lm = new GridLayoutManager(ctx, Prefs.albumColumns());
            grid.setLayoutManager(lm);
            grid.setAdapter(albumsAdapter);
            grid.setNestedScrollingEnabled(false);
        }

        void bind(Row row) {
            txtTitle.setText(row.title);
            if (lm.getSpanCount() != Prefs.albumColumns()) {
                lm.setSpanCount(Prefs.albumColumns());
            }
            albumsAdapter.submit(new ArrayList<>(row.folders));
        }
    }

    // ---------- Медиафайл ----------

    class MediaVH extends RecyclerView.ViewHolder {
        final ImageView img;
        final View dim;
        final ImageView imgFav;
        final ImageView imgCheck;
        final TextView txtBadge;
        final TextView txtDuration;
        final LinearLayout layoutVideo;
        final View card;

        String boundPath;

        MediaVH(@NonNull View itemView) {
            super(itemView);
            img = itemView.findViewById(R.id.imgMedia);
            dim = itemView.findViewById(R.id.viewDim);
            imgFav = itemView.findViewById(R.id.imgFavBadge);
            imgCheck = itemView.findViewById(R.id.imgCheckSel);
            txtBadge = itemView.findViewById(R.id.txtMediaBadge);
            txtDuration = itemView.findViewById(R.id.txtDuration);
            layoutVideo = itemView.findViewById(R.id.layoutVideoBadge);
            // CardView в разметке помечен clickable — слушатели вешаем на него,
            // иначе карточка перехватывает касания и клики по элементу не срабатывают.
            card = itemView.findViewById(R.id.cardMedia);

            View.OnClickListener click = v -> {
                int pos = getBindingAdapterPosition();
                if (pos == RecyclerView.NO_POSITION) return;
                MediaItem item = rows.get(pos).item;
                if (item != null && listener != null) listener.onMediaClick(item, pos);
            };
            View.OnLongClickListener longClick = v -> {
                int pos = getBindingAdapterPosition();
                if (pos == RecyclerView.NO_POSITION) return false;
                MediaItem item = rows.get(pos).item;
                if (item != null && listener != null) {
                    v.performHapticFeedback(
                            android.view.HapticFeedbackConstants.LONG_PRESS);
                    listener.onMediaLongClick(item, pos);
                }
                return true;
            };
            card.setOnClickListener(click);
            card.setOnLongClickListener(longClick);
            itemView.setOnClickListener(click);
            itemView.setOnLongClickListener(longClick);
        }

        void bind(MediaItem item) {
            boundPath = item.path;
            // Размер миниатюры под текущее число колонок: меньше декод — быстрее сетка
            int cols = Math.max(2, com.premiumlab.galleryx.data.Prefs.columns());
            int size = Math.max(120, ctx.getResources().getDisplayMetrics().widthPixels / cols);
            Glide.with(ctx)
                    .load(new File(item.path))
                    .override(size, size)
                    .centerCrop()
                    .placeholder(R.drawable.placeholder_media)
                    .into(img);

            bindSelection(item);

            if (item.isVideo) {
                layoutVideo.setVisibility(View.VISIBLE);
                String dur = Fmt.duration(item.duration);
                if (!dur.isEmpty()) {
                    txtDuration.setText(dur);
                } else {
                    txtDuration.setText("");
                    lazyDuration(item.path);
                }
            } else {
                layoutVideo.setVisibility(View.GONE);
            }

            if (item.badge != null) {
                txtBadge.setVisibility(View.VISIBLE);
                txtBadge.setText(item.badge);
            } else {
                txtBadge.setVisibility(View.GONE);
            }
        }

        void bindSelection(MediaItem item) {
            boolean sel = isSelected(item.path);
            dim.setVisibility(sel ? View.VISIBLE : View.GONE);
            imgCheck.setVisibility(sel ? View.VISIBLE : View.GONE);
            imgCheck.setScaleX(sel ? 1f : 0.6f);
            boolean fav = FavStore.isFav(item.path);
            imgFav.setVisibility(fav ? View.VISIBLE : View.GONE);
        }

        void lazyDuration(String path) {
            LAZY.execute(() -> {
                try {
                    MediaMetadataRetriever r = new MediaMetadataRetriever();
                    r.setDataSource(path);
                    long ms = 0;
                    try {
                        ms = Long.parseLong(r.extractMetadata(
                                MediaMetadataRetriever.METADATA_KEY_DURATION));
                    } catch (Exception ignored) {
                    }
                    r.release();
                    final long finalMs = ms;
                    MAIN.post(() -> {
                        if (finalMs > 0 && path.equals(boundPath)) {
                            txtDuration.setText(Fmt.duration(finalMs));
                        }
                    });
                } catch (Exception ignored) {
                }
            });
        }
    }

    /** Построение строк с группировкой по датам: заголовок, затем элементы группы. */
    public static List<Row> buildRows(Context ctx, List<MediaItem> items) {
        List<Row> rows = new ArrayList<>();
        String lastKey = null;
        int groupStart = 0;
        for (int i = 0; i <= items.size(); i++) {
            boolean end = i == items.size();
            String key = end ? null : Fmt.dateKey(ctx, items.get(i).dateModified);
            boolean changed = end || !key.equals(lastKey);
            if (changed) {
                if (lastKey != null) {
                    int count = i - groupStart;
                    rows.add(new Row(true, lastKey,
                            Fmt.plural(ctx, R.plurals.items_count, count), null));
                    for (int j = groupStart; j < i; j++) {
                        rows.add(new Row(false, null, null, items.get(j)));
                    }
                }
                if (!end) {
                    lastKey = key;
                    groupStart = i;
                }
            }
        }
        return rows;
    }
}
