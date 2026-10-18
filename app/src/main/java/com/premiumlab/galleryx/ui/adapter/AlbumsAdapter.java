package com.premiumlab.galleryx.ui.adapter;

import android.content.Context;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.DiffUtil;
import androidx.recyclerview.widget.RecyclerView;

import com.bumptech.glide.Glide;
import com.premiumlab.galleryx.R;
import com.premiumlab.galleryx.data.Album;
import com.premiumlab.galleryx.data.SmartAlbum;
import com.premiumlab.galleryx.data.LockStore;
import com.premiumlab.galleryx.data.Prefs;
import com.premiumlab.galleryx.util.Fmt;

import java.io.File;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Список альбомов с секциями «Мои папки» / «Папки устройства»
 * и выделением нескольких папок.
 */
public class AlbumsAdapter extends RecyclerView.Adapter<RecyclerView.ViewHolder> {

    private static final int TYPE_SECTION = 0;
    private static final int TYPE_ALBUM = 1;
    private static final int TYPE_SMART = 2;

    /** Тип строки «группа умных альбомов» для SpanSizeLookup. */
    public static int itemTypeSmart() {
        return TYPE_SMART;
    }

    /** Тип строки «заголовок секции» для SpanSizeLookup. */
    public static int itemTypeSection() {
        return TYPE_SECTION;
    }

    public interface Listener {
        void onAlbumClick(Album album, int position);

        void onAlbumLongClick(Album album, int position);

        /** Нажатие на строку «умного» альбома (Фото, Видео, Селфи, Корзина…). */
        default void onSmartClick(SmartAlbum smart) {
        }
    }

    private final Context ctx;
    private final Listener listener;
    private final List<Object> rows = new ArrayList<>();
    private final Set<String> selected = new HashSet<>();
    private boolean selectionMode = false;
    private static final ExecutorService DIFF = Executors.newSingleThreadExecutor();
    private static final android.os.Handler MAIN =
            new android.os.Handler(android.os.Looper.getMainLooper());
    private int submitGen = 0;

    public AlbumsAdapter(Context ctx, Listener listener) {
        this.ctx = ctx;
        this.listener = listener;
    }

    public void submit(List<Object> data) {
        // Выделение переживает тихое обновление (возврат из фона): остаются
        // только папки, которые всё ещё есть в списке
        if (!selected.isEmpty()) {
            Set<String> present = new HashSet<>();
            for (Object o : data) if (o instanceof Album) present.add(((Album) o).path);
            selected.retainAll(present);
        }
        selectionMode = !selected.isEmpty();
        // Идентичный список альбомов — не трогаем вообще (см. MediaAdapter)
        if (rows.size() == data.size()) {
            boolean same = true;
            for (int i = 0; i < rows.size(); i++) {
                if (!RowDiff.rowSame(rows.get(i), data.get(i))) {
                    same = false;
                    break;
                }
            }
            if (same) return;
        }
        if (rows.isEmpty() || data.isEmpty()) {
            submitGen++;
            rows.clear();
            rows.addAll(data);
            notifyDataSetChanged();
            return;
        }
        // Удаление/создание альбомов анимируется, а не мигает всем списком
        final List<Object> oldRows = new ArrayList<>(rows);
        final List<Object> target = data;
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
                    result.dispatchUpdatesTo(AlbumsAdapter.this);
                } catch (Throwable t) {
                    notifyDataSetChanged();
                }
            });
        });
    }

    /** Сравнение строк альбомов для DiffUtil (альбомы — по пути папки). */
    private static final class RowDiff extends DiffUtil.Callback {
        private final List<Object> oldRows;
        private final List<Object> newRows;

        RowDiff(List<Object> oldRows, List<Object> newRows) {
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

        private static String rowId(Object o) {
            if (o instanceof Album) return "a:" + ((Album) o).path;
            if (o instanceof SmartAlbum.Group) return "g:" + ((SmartAlbum.Group) o).title;
            return "s:" + o;
        }

        private static boolean rowSame(Object a, Object b) {
            if (a instanceof Album && b instanceof Album) {
                Album x = (Album) a, y = (Album) b;
                return eq(x.name, y.name) && eq(x.path, y.path)
                        && eq(x.coverPath, y.coverPath) && x.count == y.count
                        && x.isUser == y.isUser;
            }
            if (a instanceof SmartAlbum.Group && b instanceof SmartAlbum.Group) {
                SmartAlbum.Group x = (SmartAlbum.Group) a, y = (SmartAlbum.Group) b;
                if (x.title != y.title || x.rows.size() != y.rows.size()) return false;
                for (int i = 0; i < x.rows.size(); i++) {
                    SmartAlbum m = x.rows.get(i), n = y.rows.get(i);
                    if (m.kind != n.kind || m.count != n.count) return false;
                }
                return true;
            }
            return a == null ? b == null : a.equals(b);
        }

        private static boolean eq(String a, String b) {
            return a == null ? b == null : a.equals(b);
        }
    }

    /** Список с внешним состоянием выделения (карточки подпапок внутри папки). */
    public void submit(List<Object> data, Set<String> externalSelected) {
        rows.clear();
        rows.addAll(data);
        setSelected(externalSelected);
    }

    /** Подменяет набор выбранных путей (выделение хранится снаружи). */
    public void setSelected(Set<String> paths) {
        selected.clear();
        if (paths != null) selected.addAll(paths);
        selectionMode = !selected.isEmpty();
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
        if (selected.contains(path)) selected.remove(path);
        else selected.add(path);
        selectionMode = !selected.isEmpty();
        notifyDataSetChanged();
    }

    public void exitSelection() {
        selected.clear();
        selectionMode = false;
        notifyDataSetChanged();
    }

    public Set<String> getSelectedPaths() {
        return new HashSet<>(selected);
    }

    public int getSelectedCount() {
        return selected.size();
    }

    /** Все альбомы списка (без заголовков секций). */
    public List<Album> albums() {
        List<Album> out = new ArrayList<>();
        for (Object o : rows) {
            if (o instanceof Album) out.add((Album) o);
        }
        return out;
    }

    /** Выбранные альбомы в порядке списка. */
    public List<Album> selectedAlbums() {
        List<Album> out = new ArrayList<>();
        for (Album a : albums()) {
            if (selected.contains(a.path)) out.add(a);
        }
        return out;
    }

    public boolean isAllSelected() {
        List<Album> all = albums();
        return !all.isEmpty() && selected.size() >= all.size();
    }

    public void selectAll() {
        for (Album a : albums()) selected.add(a.path);
        selectionMode = !selected.isEmpty();
        notifyDataSetChanged();
    }

    // ---------- VH ----------

    @Override
    public int getItemViewType(int position) {
        Object o = rows.get(position);
        if (o instanceof Album) return TYPE_ALBUM;
        if (o instanceof SmartAlbum.Group) return TYPE_SMART;
        return TYPE_SECTION;
    }

    @NonNull
    @Override
    public RecyclerView.ViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        LayoutInflater inf = LayoutInflater.from(parent.getContext());
        if (viewType == TYPE_SECTION) {
            return new SectionVH(inf.inflate(R.layout.item_section, parent, false));
        }
        if (viewType == TYPE_SMART) {
            return new SmartVH(inf.inflate(R.layout.item_smart_group, parent, false));
        }
        return new AlbumVH(inf.inflate(R.layout.item_album, parent, false));
    }

    @Override
    public void onBindViewHolder(@NonNull RecyclerView.ViewHolder holder, int position) {
        Object o = rows.get(position);
        if (holder instanceof SectionVH) {
            SectionVH vh = (SectionVH) holder;
            Boolean isUser = (Boolean) o;
            vh.bind(isUser);
        } else if (holder instanceof SmartVH) {
            ((SmartVH) holder).bind((SmartAlbum.Group) o);
        } else {
            AlbumVH vh = (AlbumVH) holder;
            vh.bind((Album) o);
        }
    }

    @Override
    public int getItemCount() {
        return rows.size();
    }

    class SectionVH extends RecyclerView.ViewHolder {
        final TextView title;
        final View divider;

        SectionVH(@NonNull View itemView) {
            super(itemView);
            title = itemView.findViewById(R.id.txtSectionTitle);
            divider = itemView.findViewById(R.id.viewDivider);
        }

        void bind(Boolean isUser) {
            title.setText(isUser ? R.string.my_folders : R.string.device_folders);
            divider.setVisibility(isUser ? View.GONE : View.VISIBLE);
        }
    }

    /** Карточка «умных» альбомов: строки «иконка · название · счётчик». */
    class SmartVH extends RecyclerView.ViewHolder {
        final TextView title;
        final LinearLayout rowsLayout;

        SmartVH(@NonNull View itemView) {
            super(itemView);
            title = itemView.findViewById(R.id.txtSmartTitle);
            rowsLayout = itemView.findViewById(R.id.layoutSmartRows);
        }

        void bind(SmartAlbum.Group g) {
            title.setText(g.title);
            rowsLayout.removeAllViews();
            LayoutInflater inf = LayoutInflater.from(ctx);
            for (int i = 0; i < g.rows.size(); i++) {
                SmartAlbum sa = g.rows.get(i);
                View row = inf.inflate(R.layout.item_smart_row, rowsLayout, false);
                row.findViewById(R.id.viewSmartDivider)
                        .setVisibility(i == 0 ? View.GONE : View.VISIBLE);
                ImageView icon = row.findViewById(R.id.imgSmartIcon);
                icon.setImageResource(sa.icon);
                android.graphics.drawable.GradientDrawable bg =
                        new android.graphics.drawable.GradientDrawable();
                bg.setShape(android.graphics.drawable.GradientDrawable.OVAL);
                bg.setColor(sa.color);
                icon.setBackground(bg);
                ((TextView) row.findViewById(R.id.txtSmartLabel)).setText(sa.label);
                TextView count = row.findViewById(R.id.txtSmartCount);
                if (sa.count < 0) {
                    count.setText("");
                    count.setVisibility(View.GONE);
                } else {
                    count.setVisibility(View.VISIBLE);
                    count.setText(String.valueOf(sa.count));
                }
                View clickable = row.findViewById(R.id.rowSmart);
                clickable.setOnClickListener(v -> {
                    if (listener != null) listener.onSmartClick(sa);
                });
                rowsLayout.addView(row);
            }
        }
    }

    class AlbumVH extends RecyclerView.ViewHolder {
        final ImageView imgCover;
        final View layoutCoverArt;
        final TextView txtName, txtCount, txtBadge;
        final ImageView imgCheck;
        final ImageView imgFolderArt;
        final ImageView imgLock;
        final View card;
        int appliedCols = -1;

        AlbumVH(@NonNull View itemView) {
            super(itemView);
            imgCover = itemView.findViewById(R.id.imgCover);
            imgFolderArt = itemView.findViewById(R.id.imgFolderArt);
            imgLock = itemView.findViewById(R.id.imgLockBadge);
            layoutCoverArt = itemView.findViewById(R.id.layoutCoverArt);
            txtName = itemView.findViewById(R.id.txtAlbumName);
            txtCount = itemView.findViewById(R.id.txtAlbumCount);
            txtBadge = itemView.findViewById(R.id.txtUserBadge);
            imgCheck = itemView.findViewById(R.id.imgCheckOverlayAlbum);
            // CardView помечен clickable и перехватывает касания —
            // слушатели обязательно вешаем на саму карточку.
            card = itemView.findViewById(R.id.cardAlbum);

            View.OnClickListener click = v -> {
                int pos = getBindingAdapterPosition();
                if (pos == RecyclerView.NO_POSITION) return;
                Object o = rows.get(pos);
                if (o instanceof Album && listener != null) listener.onAlbumClick((Album) o, pos);
            };
            View.OnLongClickListener longClick = v -> {
                int pos = getBindingAdapterPosition();
                if (pos == RecyclerView.NO_POSITION) return false;
                Object o = rows.get(pos);
                if (o instanceof Album && listener != null) {
                    v.performHapticFeedback(android.view.HapticFeedbackConstants.LONG_PRESS);
                    listener.onAlbumLongClick((Album) o, pos);
                }
                return true;
            };
            card.setOnClickListener(click);
            card.setOnLongClickListener(longClick);
            itemView.setOnClickListener(click);
            com.premiumlab.galleryx.util.Press.attachCard(card);
            itemView.setOnLongClickListener(longClick);
        }

        void bind(Album album) {
            applyDensity(Math.max(1, Prefs.albumColumns()));
            boolean locked = LockStore.isLocked(album.path);
            // У заблокированной папки прячем и название: «test» → «t**t»
            txtName.setText(locked ? LockStore.maskName(album.name) : album.name);
            txtCount.setText(Fmt.plural(ctx, R.plurals.items_count, album.count)
                    .toLowerCase(Locale.getDefault()));
            txtBadge.setVisibility(album.isUser ? View.VISIBLE : View.GONE);

            boolean sel = isSelected(album.path);
            imgCheck.setVisibility(sel ? View.VISIBLE : View.GONE);
            imgCover.setAlpha(sel ? 0.55f : 1f);

            imgLock.setVisibility(locked ? View.VISIBLE : View.GONE);
            ViewGroup.LayoutParams llp = imgLock.getLayoutParams();
            llp.width = dp(appliedCols >= 3 ? 22 : 26);
            llp.height = llp.width;
            imgLock.setLayoutParams(llp);

            // Обложку заблокированной папки не показываем — только значок папки с замком
            if (album.coverPath != null && !locked) {
                layoutCoverArt.setVisibility(View.GONE);
                int cols = Math.max(1, Prefs.albumColumns());
                int size = Math.max(160, ctx.getResources().getDisplayMetrics().widthPixels / cols);
                Glide.with(ctx)
                        .load(new File(album.coverPath))
                        .override(size, size)
                        .centerCrop()
                        .placeholder(R.drawable.placeholder_media)
                        .into(imgCover);
            } else {
                layoutCoverArt.setVisibility(View.VISIBLE);
                imgCover.setImageDrawable(null);
            }
        }

        /** Компактнее бейдж, подписи и иконка папки при 3–4 колонках. */
        void applyDensity(int cols) {
            if (cols == appliedCols) return;
            appliedCols = cols;
            float cellDp = ctx.getResources().getDisplayMetrics().widthPixels
                    / ctx.getResources().getDisplayMetrics().density / cols;
            float nameSp, countSp, badgeSp;
            int badgePadH, badgePadV, badgeMargin, art, check;
            CharSequence badgeText;
            if (cellDp >= 150f) {          // 1–2 колонки
                nameSp = 14f; countSp = 12f; badgeSp = 11f;
                badgePadH = 10; badgePadV = 3; badgeMargin = 10; art = 44; check = 30;
                badgeText = ctx.getString(R.string.my_folders);
            } else if (cellDp >= 115f) {   // 3 колонки
                nameSp = 13f; countSp = 11f; badgeSp = 10f;
                badgePadH = 8; badgePadV = 2; badgeMargin = 8; art = 36; check = 26;
                badgeText = ctx.getString(R.string.my_folders);
            } else {                       // 4 колонки — короткая метка
                nameSp = 12f; countSp = 10f; badgeSp = 9f;
                badgePadH = 6; badgePadV = 2; badgeMargin = 6; art = 30; check = 24;
                badgeText = ctx.getString(R.string.my_folders_short);
            }
            txtName.setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP, nameSp);
            txtCount.setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP, countSp);
            txtBadge.setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP, badgeSp);
            txtBadge.setText(badgeText);
            txtBadge.setPadding(dp(badgePadH), dp(badgePadV), dp(badgePadH), dp(badgePadV));
            ViewGroup.LayoutParams blp = txtBadge.getLayoutParams();
            if (blp instanceof ViewGroup.MarginLayoutParams) {
                int m = dp(badgeMargin);
                ((ViewGroup.MarginLayoutParams) blp).setMargins(m, m, m, m);
                txtBadge.setLayoutParams(blp);
            }
            ViewGroup.LayoutParams ap = imgFolderArt.getLayoutParams();
            ap.width = dp(art);
            ap.height = dp(art);
            imgFolderArt.setLayoutParams(ap);
            ViewGroup.LayoutParams cp = imgCheck.getLayoutParams();
            cp.width = dp(check);
            cp.height = dp(check);
            imgCheck.setLayoutParams(cp);
            int chkPad = dp(check) / 5;
            imgCheck.setPadding(chkPad, chkPad, chkPad, chkPad);
        }

        private int dp(float v) {
            return Math.round(v * ctx.getResources().getDisplayMetrics().density);
        }
    }
}
