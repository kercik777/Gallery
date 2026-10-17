package com.premiumlab.galleryx.ui.adapter;

import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;

import com.premiumlab.galleryx.R;

import java.io.File;
import java.util.ArrayList;
import java.util.List;

/**
 * Список каталогов для выбора корневой папки.
 */
public class DirAdapter extends RecyclerView.Adapter<DirAdapter.VH> {

    private final List<File> dirs = new ArrayList<>();
    private final Listener listener;

    public interface Listener {
        void onDirClick(File dir);
    }

    public DirAdapter(Listener listener) {
        this.listener = listener;
    }

    public void submit(List<File> list) {
        dirs.clear();
        dirs.addAll(list);
        notifyDataSetChanged();
    }

    @NonNull
    @Override
    public VH onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        return new VH(LayoutInflater.from(parent.getContext())
                .inflate(R.layout.item_dir_row, parent, false));
    }

    @Override
    public void onBindViewHolder(@NonNull VH holder, int position) {
        final File f = dirs.get(position);
        holder.txtName.setText(f.getName());
        holder.itemView.setOnClickListener(v -> {
            if (listener != null) listener.onDirClick(f);
        });
    }

    @Override
    public int getItemCount() {
        return dirs.size();
    }

    static class VH extends RecyclerView.ViewHolder {
        final TextView txtName;

        VH(@NonNull View itemView) {
            super(itemView);
            txtName = itemView.findViewById(R.id.txtDirName);
        }
    }
}
