package com.premiumlab.galleryx.ui.adapter;

import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageView;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;

import com.premiumlab.galleryx.R;
import com.premiumlab.galleryx.data.Prefs;

import java.io.File;
import java.util.ArrayList;
import java.util.List;

/**
 * Список назначений для копирования/перемещения.
 * Строки: действия («Новая папка…», «Корневая папка», «Другая папка на устройстве…»),
 * заголовки секций и папки.
 */
public class DestAdapter extends RecyclerView.Adapter<RecyclerView.ViewHolder> {

    public static final int ACTION_NEW = 1;
    public static final int ACTION_ROOT = 2;
    public static final int ACTION_BROWSE = 3;

    private static final int TYPE_ACTION = 0;
    private static final int TYPE_SECTION = 1;
    private static final int TYPE_FOLDER = 2;

    public interface Listener {
        void onAction(int action);

        void onDestPicked(File dir);
    }

    /** Строка списка. */
    public static final class Row {
        final int type;
        final int action;
        final CharSequence title;
        final CharSequence subtitle;
        final int icon;
        final File dir;

        private Row(int type, int action, CharSequence title, CharSequence subtitle,
                    int icon, File dir) {
            this.type = type;
            this.action = action;
            this.title = title;
            this.subtitle = subtitle;
            this.icon = icon;
            this.dir = dir;
        }

        public static Row action(int action, CharSequence title, CharSequence subtitle, int icon) {
            return new Row(TYPE_ACTION, action, title, subtitle, icon, null);
        }

        public static Row section(CharSequence title) {
            return new Row(TYPE_SECTION, 0, title, null, 0, null);
        }

        public static Row folder(File dir, CharSequence subtitle) {
            return new Row(TYPE_FOLDER, 0, dir.getName(), subtitle, R.drawable.ic_folder, dir);
        }
    }

    private final Listener listener;
    private final List<Row> rows = new ArrayList<>();

    public DestAdapter(Listener listener) {
        this.listener = listener;
    }

    public void submit(List<Row> list) {
        rows.clear();
        rows.addAll(list);
        notifyDataSetChanged();
    }

    @NonNull
    @Override
    public RecyclerView.ViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        LayoutInflater inf = LayoutInflater.from(parent.getContext());
        if (viewType == TYPE_SECTION) {
            return new SectionVH(inf.inflate(R.layout.item_section, parent, false));
        }
        return new RowVH(inf.inflate(R.layout.item_dest_row, parent, false));
    }

    @Override
    public int getItemViewType(int position) {
        return rows.get(position).type;
    }

    @Override
    public void onBindViewHolder(@NonNull RecyclerView.ViewHolder holder, int position) {
        Row r = rows.get(position);
        if (holder instanceof SectionVH) {
            ((SectionVH) holder).bind(r);
        } else {
            ((RowVH) holder).bind(r);
        }
    }

    @Override
    public int getItemCount() {
        return rows.size();
    }

    /** Относительный путь папки для подписи: «…/подпапка» внутри корня. */
    public static String subtitleFor(File f) {
        String rp = Prefs.rootPath();
        String parent = f.getParent();
        if (parent == null) return "";
        if (rp != null && (parent.equals(rp) || parent.startsWith(rp + "/"))) {
            return "…" + parent.substring(rp.length());
        }
        return parent;
    }

    static class SectionVH extends RecyclerView.ViewHolder {
        final TextView title;

        SectionVH(@NonNull View itemView) {
            super(itemView);
            title = itemView.findViewById(R.id.txtSectionTitle);
            View divider = itemView.findViewById(R.id.viewDivider);
            if (divider != null) divider.setVisibility(View.GONE);
            itemView.setPadding(itemView.getPaddingLeft(),
                    itemView.getPaddingBottom() * 2,
                    itemView.getPaddingRight(),
                    itemView.getPaddingBottom() / 2);
        }

        void bind(Row r) {
            title.setText(r.title);
        }
    }

    class RowVH extends RecyclerView.ViewHolder {
        final ImageView icon;
        final TextView name, path;

        RowVH(@NonNull View itemView) {
            super(itemView);
            icon = itemView.findViewById(R.id.imgDestIcon);
            name = itemView.findViewById(R.id.txtDestName);
            path = itemView.findViewById(R.id.txtDestPath);
            itemView.setOnClickListener(v -> {
                int pos = getBindingAdapterPosition();
                if (pos == RecyclerView.NO_POSITION || listener == null) return;
                Row r = rows.get(pos);
                if (r.type == TYPE_ACTION) listener.onAction(r.action);
                else if (r.dir != null) listener.onDestPicked(r.dir);
            });
        }

        void bind(Row r) {
            icon.setImageResource(r.icon);
            name.setText(r.title);
            if (r.subtitle == null || r.subtitle.length() == 0) {
                path.setVisibility(View.GONE);
            } else {
                path.setVisibility(View.VISIBLE);
                path.setText(r.subtitle);
            }
        }
    }
}
