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
import androidx.annotation.Nullable;
import androidx.recyclerview.widget.DiffUtil;
import androidx.recyclerview.widget.GridLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.bumptech.glide.Glide;
import com.premiumlab.galleryx.R;
import com.premiumlab.galleryx.data.Album;
import com.premiumlab.galleryx.data.FavStore;
import com.premiumlab.galleryx.data.MediaItem;
import com.premiumlab.galleryx.data.NoteStore;
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

        /** Короткое нажатие на карточку подпапки (в режиме выделения — переключить). */
        default void onFolderClick(Album folder) {
            onFolderOpen(folder);
        }

        /** Долгое нажатие на карточку подпапки — начать выделение папок. */
        default void onFolderLongClick(Album folder) {
        }
    }

    private final Context ctx;
    private final Listener listener;
    private final List<Row> rows = new ArrayList<>();
    private final Set<String> selected = new HashSet<>();
    /** Выбранные карточки вложенных папок (отдельно от файлов: либо папки, либо файлы). */
    private final Set<String> selectedFolders = new HashSet<>();
    private final int thumbSize;
    private static final ExecutorService LAZY = Executors.newSingleThreadExecutor();
    private static final Handler MAIN = new Handler(Looper.getMainLooper());
    /** Фон для DiffUtil: большие списки сравниваются не на главном потоке. */
    private static final ExecutorService DIFF = Executors.newSingleThreadExecutor();
    /** Поколение submitRows: устаревшие результаты сравнения отбрасываются. */
    private int submitGen = 0;
    private boolean selectionMode = false;
    /**
     * Экранный кадр последней нажатой ячейки (left, top, width, height) — просмотрщик
     * «вырастает» из неё и возвращается в неё при закрытии.
     */
    public static int[] lastClickRect;

    /**
     * Размер миниатюр текущей сетки (px). Просмотрщик повторяет этот запрос
     * для открытого файла — миниатюра уже в памяти Glide и рисуется мгновенно.
     */
    public static int thumbSize(Context ctx) {
        int cols = Math.max(2, com.premiumlab.galleryx.data.Prefs.columns());
        int size = Math.max(120, ctx.getResources().getDisplayMetrics().widthPixels / cols);
        // «Качественные миниатюры» — декодируем вдвое крупнее ячейки
        if (com.premiumlab.galleryx.data.Prefs.hqThumbs()) size *= 2;
        return size;
    }

    public MediaAdapter(Context ctx, Listener listener) {
        this.ctx = ctx;
        this.listener = listener;
        int w = ctx.getResources().getDisplayMetrics().widthPixels;
        this.thumbSize = w / 2;
    }

    public void submitRows(List<Row> newRows) {
        // Выделение переживает тихое обновление списка (возврат из фона, новые
        // файлы): остаются только элементы, которые всё ещё есть на экране
        if (!selected.isEmpty() || !selectedFolders.isEmpty()) {
            Set<String> present = new HashSet<>();
            Set<String> presentFolders = new HashSet<>();
            for (Row r : newRows) {
                if (r.item != null) present.add(r.item.path);
                if (r.folders != null) for (Album a : r.folders) presentFolders.add(a.path);
            }
            selected.retainAll(present);
            selectedFolders.retainAll(presentFolders);
        }
        selectionMode = !selected.isEmpty() || !selectedFolders.isEmpty();
        // Первый показ и полное опустошение — без анимации, как раньше
        if (rows.isEmpty() || newRows.isEmpty()) {
            submitGen++;
            rows.clear();
            rows.addAll(newRows);
            notifyDataSetChanged();
            return;
        }
        // Обновление — через DiffUtil: удаления/добавления анимируются
        // (ячейки плавно схлопываются/сдвигаются, а не мигают всем списком)
        final List<Row> oldRows = new ArrayList<>(rows);
        final List<Row> target = newRows;
        final int gen = ++submitGen;
        DIFF.execute(() -> {
            final DiffUtil.DiffResult result;
            try {
                result = DiffUtil.calculateDiff(new RowDiff(oldRows, target), false);
            } catch (Throwable t) {
                MAIN.post(() -> {
                    if (gen != submitGen) return;
                    rows.clear();
                    rows.addAll(target);
                    notifyDataSetChanged();
                });
                return;
            }
            MAIN.post(() -> {
                if (gen != submitGen) return;
                rows.clear();
                rows.addAll(target);
                try {
                    result.dispatchUpdatesTo(MediaAdapter.this);
                } catch (Throwable t) {
                    notifyDataSetChanged();
                }
            });
        });
    }

    /** Сравнение строк сетки для DiffUtil (файлы — по пути, заголовки — по дате). */
    private static final class RowDiff extends DiffUtil.Callback {
        private final List<Row> oldRows;
        private final List<Row> newRows;

        RowDiff(List<Row> oldRows, List<Row> newRows) {
            this.oldRows = oldRows;
            this.newRows = newRows;
        }

        @Override
        public int getOldListSize() {
            return oldRows.size();
        }

        @Override
        public int getNewListSize() {
            return newRows.size();
        }

        @Override
        public boolean areItemsTheSame(int o, int n) {
            return rowId(oldRows.get(o)).equals(rowId(newRows.get(n)));
        }

        @Override
        public boolean areContentsTheSame(int o, int n) {
            return rowSame(oldRows.get(o), newRows.get(n));
        }

        private static String rowId(Row r) {
            if (r.item != null) return "m:" + r.item.path;
            if (r.folders != null) return "f:" + r.title;
            return "h:" + r.title;
        }

        private static boolean rowSame(Row a, Row b) {
            if (a.header != b.header) return false;
            if (a.item != null || b.item != null) {
                return a.item != null && b.item != null && itemSame(a.item, b.item);
            }
            if (a.folders != null || b.folders != null) {
                if (a.folders == null || b.folders == null) return false;
                if (!eq(a.title, b.title) || a.folders.size() != b.folders.size()) return false;
                for (int i = 0; i < a.folders.size(); i++) {
                    if (!albumSame(a.folders.get(i), b.folders.get(i))) return false;
                }
                return true;
            }
            return eq(a.title, b.title) && eq(a.countLabel, b.countLabel);
        }

        private static boolean itemSame(MediaItem a, MediaItem b) {
            if (a.id != b.id || !eq(a.path, b.path) || !eq(a.name, b.name)
                    || !eq(a.folderPath, b.folderPath) || a.size != b.size
                    || a.dateModified != b.dateModified || a.duration != b.duration
                    || a.isVideo != b.isVideo || !eq(a.badge, b.badge)) {
                return false;
            }
            // Сердечко и значок заметки читаются из хранилищ при привязке
            try {
                if (FavStore.isFav(a.path) != FavStore.isFav(b.path)) return false;
            } catch (Exception ignored) {
            }
            try {
                if (NoteStore.has(a.path) != NoteStore.has(b.path)) return false;
            } catch (Exception ignored) {
            }
            return true;
        }

        private static boolean albumSame(Album a, Album b) {
            return eq(a.name, b.name) && eq(a.path, b.path) && eq(a.coverPath, b.coverPath)
                    && a.count == b.count && a.isUser == b.isUser;
        }

        private static boolean eq(String a, String b) {
            return a == null ? b == null : a.equals(b);
        }
    }

    public void clear() {
        submitGen++;
        rows.clear();
        selected.clear();
        selectedFolders.clear();
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

    /** Путь файла на позиции или null (заголовок даты, блок папок, вне списка). */
    @Nullable
    public String pathAt(int position) {
        if (position < 0 || position >= rows.size()) return null;
        MediaItem it = rows.get(position).item;
        return it == null ? null : it.path;
    }

    /** Пакетное выделение (жест протягивания): одно обновление списка. */
    public void setSelected(java.util.Collection<String> paths, boolean sel) {
        if (paths == null || paths.isEmpty()) return;
        if (sel) selected.addAll(paths);
        else selected.removeAll(paths);
        selectionMode = !selected.isEmpty() || !selectedFolders.isEmpty();
        notifyItemRangeChanged(0, rows.size(), "sel");
    }

    public void selectAll(List<MediaItem> items) {
        for (MediaItem it : items) selected.add(it.path);
        selectionMode = !selected.isEmpty();
        notifyItemRangeChanged(0, rows.size(), "sel");
    }

    public void exitSelection() {
        selected.clear();
        selectedFolders.clear();
        selectionMode = false;
        notifyItemRangeChanged(0, rows.size(), "sel");
    }

    public Set<String> getSelectedPaths() {
        return new HashSet<>(selected);
    }

    public int getSelectedCount() {
        return selected.size();
    }

    // ----- Выделение вложенных папок -----

    /** Активно выделение папок (тогда файлы не выбираются, и наоборот). */
    public boolean isFolderSelection() {
        return !selectedFolders.isEmpty();
    }

    public boolean isFolderSelected(String path) {
        return selectedFolders.contains(path);
    }

    public void toggleFolder(String path) {
        if (selectedFolders.contains(path)) {
            selectedFolders.remove(path);
        } else {
            selectedFolders.add(path);
        }
        selectionMode = !selected.isEmpty() || !selectedFolders.isEmpty();
        notifyItemRangeChanged(0, rows.size(), "sel");
    }

    public int getSelectedFolderCount() {
        return selectedFolders.size();
    }

    /** Все карточки подпапок, показанные в сетке. */
    public List<Album> allFolders() {
        List<Album> out = new ArrayList<>();
        for (Row r : rows) if (r.folders != null) out.addAll(r.folders);
        return out;
    }

    /** Выбранные подпапки в порядке показа. */
    public List<Album> selectedFolders() {
        List<Album> out = new ArrayList<>();
        for (Album a : allFolders()) if (selectedFolders.contains(a.path)) out.add(a);
        return out;
    }

    public void selectAllFolders() {
        for (Album a : allFolders()) selectedFolders.add(a.path);
        selectionMode = !selected.isEmpty() || !selectedFolders.isEmpty();
        notifyItemRangeChanged(0, rows.size(), "sel");
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
            } else if (holder instanceof FoldersVH) {
                ((FoldersVH) holder).albumsAdapter.setSelected(selectedFolders);
            }
            return; // заголовки от выделения не зависят
        }
        super.onBindViewHolder(holder, position, payloads);
    }

    /** Позиция ячейки файла в сетке или -1. */
    public int positionOf(String path) {
        if (path == null) return -1;
        for (int i = 0; i < rows.size(); i++) {
            MediaItem it = rows.get(i).item;
            if (it != null && path.equals(it.path)) return i;
        }
        return -1;
    }

    /** Подпись для «пузыря» быстрой прокрутки: дата элемента на позиции. */
    public String labelAt(int position) {
        if (position < 0 || position >= rows.size()) return null;
        Row r = rows.get(position);
        if (r.header) return r.title;
        if (r.item != null) return Fmt.dateKey(ctx, r.item.dateModified);
        return null;
    }

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
                    if (listener != null) listener.onFolderClick(album);
                }

                @Override
                public void onAlbumLongClick(Album album, int position) {
                    if (listener != null) listener.onFolderLongClick(album);
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
            albumsAdapter.submit(new ArrayList<>(row.folders), selectedFolders);
        }
    }

    // ---------- Медиафайл ----------

    class MediaVH extends RecyclerView.ViewHolder {
        final ImageView img;
        final View dim;
        final ImageView imgFav;
        final ImageView imgNote;
        final ImageView imgCheck;
        final TextView txtBadge;
        final TextView txtDuration;
        final LinearLayout layoutVideo;
        final ImageView imgPlayBadge;
        final View card;

        String boundPath;
        int appliedCols = -1;

        MediaVH(@NonNull View itemView) {
            super(itemView);
            img = itemView.findViewById(R.id.imgMedia);
            dim = itemView.findViewById(R.id.viewDim);
            imgFav = itemView.findViewById(R.id.imgFavBadge);
            imgNote = itemView.findViewById(R.id.imgNoteBadge);
            imgCheck = itemView.findViewById(R.id.imgCheckSel);
            txtBadge = itemView.findViewById(R.id.txtMediaBadge);
            txtDuration = itemView.findViewById(R.id.txtDuration);
            layoutVideo = itemView.findViewById(R.id.layoutVideoBadge);
            imgPlayBadge = itemView.findViewById(R.id.imgPlayBadge);
            // CardView в разметке помечен clickable — слушатели вешаем на него,
            // иначе карточка перехватывает касания и клики по элементу не срабатывают.
            card = itemView.findViewById(R.id.cardMedia);

            View.OnClickListener click = v -> {
                int pos = getBindingAdapterPosition();
                if (pos == RecyclerView.NO_POSITION) return;
                MediaItem item = rows.get(pos).item;
                int[] loc = new int[2];
                itemView.getLocationOnScreen(loc);
                lastClickRect = new int[]{loc[0], loc[1], itemView.getWidth(), itemView.getHeight()};
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
            com.premiumlab.galleryx.util.Press.attachCard(card);
            itemView.setOnLongClickListener(longClick);
        }

        void bind(MediaItem item) {
            boundPath = item.path;
            // Размер миниатюры под текущее число колонок: меньше декод — быстрее сетка
            int size = thumbSize(ctx);
            applyDensity(Math.max(2, com.premiumlab.galleryx.data.Prefs.columns()));
            Glide.with(ctx)
                    .load(new File(item.path))
                    .signature(new com.bumptech.glide.signature.ObjectKey(item.dateModified))
                    .override(size, size)
                    .centerCrop()
                    .placeholder(R.drawable.placeholder_media_square)
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

        /**
         * Подгоняет бейджи под размер ячейки: чем больше колонок, тем компактнее
         * значки, отступы и шрифт, чтобы ничего не обрезалось.
         */
        void applyDensity(int cols) {
            if (cols == appliedCols) return;
            appliedCols = cols;
            float cellDp = ctx.getResources().getDisplayMetrics().widthPixels
                    / ctx.getResources().getDisplayMetrics().density / cols;
            int margin, padH, padV, icon, badgeSize;
            float textSp;
            boolean showDuration;
            if (cellDp >= 110f) {          // 2–3 колонки
                margin = 8; padH = 8; padV = 3; icon = 13; textSp = 11f; badgeSize = 24;
                showDuration = true;
            } else if (cellDp >= 85f) {    // 4 колонки
                margin = 6; padH = 6; padV = 2; icon = 12; textSp = 10f; badgeSize = 22;
                showDuration = true;
            } else if (cellDp >= 68f) {    // 5 колонок
                margin = 4; padH = 5; padV = 2; icon = 11; textSp = 9f; badgeSize = 20;
                showDuration = true;
            } else {                       // 6 колонок — только значок
                margin = 4; padH = 4; padV = 4; icon = 10; textSp = 9f; badgeSize = 18;
                showDuration = false;
            }
            int m = dp(margin), ph = dp(padH), pv = dp(padV), ic = dp(icon), bs = dp(badgeSize);

            setMargins(layoutVideo, m);
            layoutVideo.setPadding(ph, pv, ph, pv);
            ViewGroup.LayoutParams ip = imgPlayBadge.getLayoutParams();
            ip.width = ic;
            ip.height = ic;
            imgPlayBadge.setLayoutParams(ip);
            txtDuration.setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP, textSp);
            txtDuration.setVisibility(showDuration ? View.VISIBLE : View.GONE);

            setMargins(txtBadge, m);
            txtBadge.setPadding(ph + dp(2), pv, ph + dp(2), pv);
            txtBadge.setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP, textSp);

            setMargins(imgFav, m);
            ViewGroup.LayoutParams fp = imgFav.getLayoutParams();
            fp.width = bs;
            fp.height = bs;
            imgFav.setLayoutParams(fp);
            int favPad = Math.max(dp(3), bs / 6);
            imgFav.setPadding(favPad, favPad, favPad, favPad);

            setMargins(imgCheck, m);
            int cs = dp(cellDp >= 85f ? 30 : 24);
            ViewGroup.LayoutParams cp = imgCheck.getLayoutParams();
            cp.width = cs;
            cp.height = cs;
            imgCheck.setLayoutParams(cp);
            int chkPad = cs / 5;
            imgCheck.setPadding(chkPad, chkPad, chkPad, chkPad);
        }

        private int dp(float v) {
            return Math.round(v * ctx.getResources().getDisplayMetrics().density);
        }

        private void setMargins(View v, int m) {
            ViewGroup.LayoutParams lp = v.getLayoutParams();
            if (lp instanceof ViewGroup.MarginLayoutParams) {
                ((ViewGroup.MarginLayoutParams) lp).setMargins(m, m, m, m);
                v.setLayoutParams(lp);
            }
        }

        void bindSelection(MediaItem item) {
            boolean sel = isSelected(item.path);
            // Выделенная ячейка «утапливается» (0.86) с затемнением, как в GorillaGallery
            com.premiumlab.galleryx.util.Press.setRestScale(card, sel ? 0.86f : 1f,
                    card.isAttachedToWindow());
            dim.setVisibility(sel ? View.VISIBLE : View.GONE);
            imgCheck.setVisibility(sel ? View.VISIBLE : View.GONE);
            imgCheck.setScaleX(sel ? 1f : 0.6f);
            boolean fav = FavStore.isFav(item.path);
            imgFav.setVisibility(fav ? View.VISIBLE : View.GONE);
            // Значок заметки — под звездой, если она есть
            boolean note = NoteStore.has(item.path);
            imgNote.setVisibility(note ? View.VISIBLE : View.GONE);
            if (note) {
                ViewGroup.LayoutParams lp = imgNote.getLayoutParams();
                if (lp instanceof ViewGroup.MarginLayoutParams) {
                    ViewGroup.MarginLayoutParams mlp = (ViewGroup.MarginLayoutParams) lp;
                    ViewGroup.LayoutParams flp = imgFav.getLayoutParams();
                    int m = flp instanceof ViewGroup.MarginLayoutParams
                            ? ((ViewGroup.MarginLayoutParams) flp).topMargin : dp(8);
                    int size = flp.width > 0 ? flp.width : dp(24);
                    imgNote.getLayoutParams().width = size;
                    imgNote.getLayoutParams().height = size;
                    mlp.setMargins(m, fav ? m + size + dp(4) : m, m, m);
                    imgNote.setLayoutParams(mlp);
                }
            }
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
        if (com.premiumlab.galleryx.data.Prefs.groupBy() == com.premiumlab.galleryx.data.Prefs.GROUP_NONE) {
            // Без заголовков — сплошная сетка
            for (MediaItem it : items) rows.add(new Row(false, null, null, it));
            return rows;
        }
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
