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
import java.util.Locale;

/**
 * Список папок-назначений для копирования/перемещения.
 * Первая строка — «Новая папка…», затем корневая папка и все подпапки.
 */
public class DestAdapter extends RecyclerView.Adapter<RecyclerView.ViewHolder> {

    private static final int TYPE_NEW = 0;
    private static final int TYPE_ROOT = 1;
    private static final int TYPE_FOLDER = 2;

    public interface Listener {
        void onNewFolder();

        void onDestPicked(File dir);
    }

    private final Listener listener;
    private final List<File> folders = new ArrayList<>();

    public DestAdapter(Listener listener) {
        this.listener = listener;
    }

    public void submit(List<File> list) {
        folders.clear();
        folders.addAll(list);
        notifyDataSetChanged();
    }

    @NonNull
    @Override
    public RecyclerView.ViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        LayoutInflater inf = LayoutInflater.from(parent.getContext());
        if (viewType == TYPE_NEW) {
            return new ActionVH(inf.inflate(R.layout.item_dest_row, parent, false), TYPE_NEW);
        }
        if (viewType == TYPE_ROOT) {
            return new ActionVH(inf.inflate(R.layout.item_dest_row, parent, false), TYPE_ROOT);
        }
        return new VH(inf.inflate(R.layout.item_dest_row, parent, false));
    }

    @Override
    public int getItemViewType(int position) {
        if (position == 0) return TYPE_NEW;
        if (position == 1) return TYPE_ROOT;
        return TYPE_FOLDER;
    }

    @Override
    public void onBindViewHolder(@NonNull RecyclerView.ViewHolder holder, int position) {
        if (holder instanceof ActionVH) {
            ((ActionVH) holder).bind();
        } else {
            VH vh = (VH) holder;
            File f = folders.get(position - 2);
            vh.bind(f);
        }
    }

    @Override
    public int getItemCount() {
        return folders.size() + 2;
    }

    class ActionVH extends RecyclerView.ViewHolder {
        final int type;
        final ImageView icon;
        final TextView name, path;

        ActionVH(@NonNull View itemView, int type) {
            super(itemView);
            this.type = type;
            icon = itemView.findViewById(R.id.imgDestIcon);
            name = itemView.findViewById(R.id.txtDestName);
            path = itemView.findViewById(R.id.txtDestPath);
            itemView.setOnClickListener(v -> {
                if (listener == null) return;
                if (type == TYPE_NEW) listener.onNewFolder();
                else if (type == TYPE_ROOT && Prefs.rootPath() != null) {
                    listener.onDestPicked(new File(Prefs.rootPath()));
                }
            });
        }

        void bind() {
            if (type == TYPE_NEW) {
                icon.setImageResource(R.drawable.ic_folder_plus);
                name.setText(R.string.dest_new_folder);
                path.setText(R.string.create_folder);
            } else {
                icon.setImageResource(R.drawable.ic_sd);
                name.setText(R.string.dest_root_folder);
                String rp = Prefs.rootPath();
                path.setText(rp == null ? path.getContext().getString(R.string.root_not_set) : rp);
            }
        }
    }

    class VH extends RecyclerView.ViewHolder {
        final TextView name, path;

        VH(@NonNull View itemView) {
            super(itemView);
            name = itemView.findViewById(R.id.txtDestName);
            path = itemView.findViewById(R.id.txtDestPath);
            itemView.setOnClickListener(v -> {
                int pos = getBindingAdapterPosition();
                if (pos == RecyclerView.NO_POSITION || listener == null) return;
                listener.onDestPicked(folders.get(pos - 2));
            });
        }

        void bind(File f) {
            name.setText(f.getName());
            String rp = Prefs.rootPath();
            String rel = f.getParent();
            if (rp != null && rel != null && rel.startsWith(rp)) {
                rel = "…" + rel.substring(rp.length());
            }
            path.setText(rel == null ? "" : rel.toLowerCase(Locale.getDefault()));
        }
    }
}
