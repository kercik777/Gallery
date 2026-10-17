package com.premiumlab.galleryx.resizer;

import android.content.Intent;
import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.widget.ImageView;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;
import androidx.recyclerview.widget.GridLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.bumptech.glide.Glide;
import com.bumptech.glide.signature.ObjectKey;
import com.premiumlab.galleryx.R;
import com.premiumlab.galleryx.data.LockStore;
import com.premiumlab.galleryx.data.MaskGuard;
import com.premiumlab.galleryx.data.MediaEngine;
import com.premiumlab.galleryx.data.MediaItem;
import com.premiumlab.galleryx.data.Prefs;
import com.premiumlab.galleryx.util.Fmt;
import com.premiumlab.galleryx.util.Press;
import com.premiumlab.galleryx.util.Toasts;

import java.io.File;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;

/**
 * Выбор одной или нескольких фотографий из галереи для «Изменения размера».
 * Показываются только фото (видео инструмент не обрабатывает), с теми же
 * правилами скрытия, что и в галерее (маскировка, заблокированные папки).
 * Результат — список путей в {@link ResizeSession}.
 */
public class PickPhotosActivity extends AppCompatActivity {

    public static final String EXTRA_PRESELECTED = "preselected";

    private final List<MediaItem> items = new ArrayList<>();
    private final LinkedHashSet<String> selected = new LinkedHashSet<>();
    private Adapter adapter;
    private TextView txtCount, btnDone, txtEmpty;
    private View progress;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        com.premiumlab.galleryx.util.Theming.apply(this);
        if (Prefs.flagSecure()) {
            getWindow().addFlags(WindowManager.LayoutParams.FLAG_SECURE);
        }
        setContentView(R.layout.activity_pick_photos);

        txtCount = findViewById(R.id.txtPickCount);
        btnDone = findViewById(R.id.btnPickDone);
        txtEmpty = findViewById(R.id.txtPickEmpty);
        progress = findViewById(R.id.progressPick);
        RecyclerView rv = findViewById(R.id.recyclerPick);
        ImageView btnBack = findViewById(R.id.btnPickBack);
        ImageView btnAll = findViewById(R.id.btnPickAll);
        Press.attach(btnBack);
        Press.attach(btnAll);
        Press.attach(btnDone, 0.97f);
        btnBack.setOnClickListener(v -> finish());
        btnAll.setOnClickListener(v -> toggleAll());
        btnDone.setOnClickListener(v -> done());

        List<String> pre = getIntent().getStringArrayListExtra(EXTRA_PRESELECTED);
        if (pre != null) selected.addAll(pre);

        int cols = 4;
        rv.setLayoutManager(new GridLayoutManager(this, cols));
        adapter = new Adapter();
        rv.setAdapter(adapter);
        rv.setHasFixedSize(true);
        com.premiumlab.galleryx.util.EdgeToEdge.padBottom(rv);
        updateCount();
        load();
    }

    private void load() {
        progress.setVisibility(View.VISIBLE);
        MediaEngine.loadAll(this, all -> {
            if (isFinishing() || isDestroyed()) return;
            progress.setVisibility(View.GONE);
            items.clear();
            boolean maskHidden = MaskGuard.hidden();
            boolean anyLocked = LockStore.any();
            for (MediaItem it : all) {
                if (it.isVideo) continue;
                if (maskHidden && MaskGuard.isHiddenPath(it.path)) continue;
                if (anyLocked && LockStore.isUnderLocked(it.path)) continue;
                items.add(it);
            }
            txtEmpty.setVisibility(items.isEmpty() ? View.VISIBLE : View.GONE);
            adapter.notifyDataSetChanged();
            updateCount();
        });
    }

    private void toggleAll() {
        if (items.isEmpty()) return;
        if (selected.size() >= items.size()) {
            selected.clear();
        } else {
            for (MediaItem it : items) selected.add(it.path);
        }
        adapter.notifyDataSetChanged();
        updateCount();
    }

    private void updateCount() {
        int n = selected.size();
        txtCount.setText(n == 0
                ? getString(R.string.resizer_pick_sub, items.size())
                : Fmt.plural(this, R.plurals.selected_count, n));
        btnDone.setEnabled(n > 0);
        btnDone.setAlpha(n > 0 ? 1f : 0.5f);
        btnDone.setText(n > 0 ? getString(R.string.resizer_pick_next_n, n)
                : getString(R.string.resizer_pick_next));
    }

    private void done() {
        if (selected.isEmpty()) {
            Toasts.makeText(this, R.string.resizer_pick_none, Toast.LENGTH_SHORT).show();
            return;
        }
        ResizeSession.paths = new ArrayList<>(selected);
        setResult(RESULT_OK, new Intent());
        finish();
    }

    // ---------- Сетка ----------

    private final class Adapter extends RecyclerView.Adapter<Adapter.VH> {

        @NonNull
        @Override
        public VH onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
            View v = LayoutInflater.from(parent.getContext())
                    .inflate(R.layout.item_pick_photo, parent, false);
            return new VH(v);
        }

        @Override
        public void onBindViewHolder(@NonNull VH h, int position) {
            MediaItem it = items.get(position);
            Glide.with(h.img)
                    .load(new File(it.path))
                    .signature(new ObjectKey(it.dateModified))
                    .override(320, 320)
                    .centerCrop()
                    .placeholder(R.drawable.placeholder_media_square)
                    .into(h.img);
            boolean sel = selected.contains(it.path);
            h.check.setVisibility(sel ? View.VISIBLE : View.GONE);
            h.dim.setVisibility(sel ? View.VISIBLE : View.GONE);
            h.card.setOnClickListener(v -> {
                int pos = h.getBindingAdapterPosition();
                if (pos == RecyclerView.NO_POSITION) return;
                String p = items.get(pos).path;
                if (!selected.remove(p)) selected.add(p);
                notifyItemChanged(pos);
                updateCount();
            });
        }

        @Override
        public int getItemCount() {
            return items.size();
        }

        final class VH extends RecyclerView.ViewHolder {
            final View card, dim;
            final ImageView img, check;

            VH(@NonNull View v) {
                super(v);
                card = v.findViewById(R.id.cardPick);
                img = v.findViewById(R.id.imgPick);
                dim = v.findViewById(R.id.viewPickDim);
                check = v.findViewById(R.id.imgPickCheck);
            }
        }
    }
}
