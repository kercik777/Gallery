package com.premiumlab.galleryx;

import android.content.Intent;
import android.os.Bundle;
import android.text.Editable;
import android.text.TextWatcher;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.widget.EditText;
import android.widget.ImageView;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.bumptech.glide.Glide;
import com.bumptech.glide.signature.ObjectKey;
import com.google.android.material.materialswitch.MaterialSwitch;
import com.premiumlab.galleryx.data.ExtraSources;
import com.premiumlab.galleryx.data.MediaEngine;
import com.premiumlab.galleryx.data.Prefs;
import com.premiumlab.galleryx.util.Fmt;
import com.premiumlab.galleryx.util.Press;
import com.premiumlab.galleryx.util.Toasts;

import java.io.File;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * «Дополнительные источники» (настройки маскировки): полный обход памяти при
 * каждом открытии, список папок с фото/видео, которых нет в обычной галерее,
 * с переключателями «показывать в галерее». Группы: данные приложений
 * (Android/media — WhatsApp, Telegram…), скрытые папки (.имя, .nomedia),
 * другие папки. Нажатие на обложку — предпросмотр папки.
 */
public class ExtraSourcesActivity extends AppCompatActivity {

    private static final int TYPE_HEADER = 0;
    private static final int TYPE_SOURCE = 1;

    private final List<ExtraSources.Source> all = new ArrayList<>();
    private final List<Object> rows = new ArrayList<>();
    private Adapter adapter;
    private LinearLayoutManager layoutManager;
    private TextView txtStatus, txtScanning;
    private View layoutScanning, layoutEmpty, btnClear;
    private ImageView btnRescan;
    private android.view.animation.RotateAnimation spin;
    private EditText editFilter;
    private String filter = "";
    private volatile int scanToken = 0;
    private boolean scanning;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        com.premiumlab.galleryx.util.Theming.apply(this);
        if (Prefs.flagSecure()) {
            getWindow().addFlags(WindowManager.LayoutParams.FLAG_SECURE);
        }
        setContentView(R.layout.activity_extra_sources);

        txtStatus = findViewById(R.id.txtSourcesStatus);
        txtScanning = findViewById(R.id.txtSourcesScanning);
        layoutScanning = findViewById(R.id.layoutSourcesScanning);
        layoutEmpty = findViewById(R.id.layoutSourcesEmpty);
        editFilter = findViewById(R.id.editSourcesFilter);
        btnClear = findViewById(R.id.btnSourcesFilterClear);
        ImageView btnBack = findViewById(R.id.btnSourcesBack);
        btnRescan = findViewById(R.id.btnSourcesRescan);
        View btnAll = findViewById(R.id.btnSourcesAll);
        View btnNone = findViewById(R.id.btnSourcesNone);
        RecyclerView rv = findViewById(R.id.recyclerSources);

        Press.attach(btnBack);
        Press.attach(btnRescan);
        Press.attach(btnAll, 0.96f);
        Press.attach(btnNone, 0.96f);
        btnBack.setOnClickListener(v -> finish());
        // Повторное сканирование по кнопке — тоже в фоне, если список уже виден
        btnRescan.setOnClickListener(v -> scan(!all.isEmpty()));
        btnAll.setOnClickListener(v -> setAll(true));
        btnNone.setOnClickListener(v -> setAll(false));
        btnClear.setOnClickListener(v -> editFilter.setText(""));
        editFilter.addTextChangedListener(new TextWatcher() {
            @Override
            public void beforeTextChanged(CharSequence s, int a, int b, int c) {
            }

            @Override
            public void onTextChanged(CharSequence s, int a, int b, int c) {
            }

            @Override
            public void afterTextChanged(Editable s) {
                filter = s.toString().trim().toLowerCase(Locale.ROOT);
                btnClear.setVisibility(filter.isEmpty() ? View.GONE : View.VISIBLE);
                rebuildRows();
            }
        });

        layoutManager = new LinearLayoutManager(this);
        rv.setLayoutManager(layoutManager);
        adapter = new Adapter();
        rv.setAdapter(adapter);
        com.premiumlab.galleryx.util.EdgeToEdge.padBottom(rv);

        Prefs.setExtraSourcesSeenAt(System.currentTimeMillis());
        // Кеш прошлого сканирования показываем сразу; свежее — тихо в фоне.
        // Загрузочный экран — только при самом первом входе, когда кеша ещё нет.
        List<ExtraSources.Source> cached = null;
        try {
            cached = ExtraSources.loadCache(this);
        } catch (Throwable ignored) {
        }
        if (cached != null) {
            all.addAll(cached);
            rebuildRows();
            scan(true);
        } else {
            scan(false);
        }
    }

    // ---------- Сканирование ----------

    /**
     * Каждый вход — свежий обход: могли появиться новые папки и файлы.
     * Фоновый режим не трогает список: прогресс виден в статусе и по вращению
     * иконки, в конце список бесшумно обновляется (позиция прокрутки сохраняется).
     */
    private void scan(boolean background) {
        if (scanning) return;
        scanning = true;
        final int token = ++scanToken;
        final boolean fg = !background;
        if (fg) {
            layoutScanning.setVisibility(View.VISIBLE);
            layoutEmpty.setVisibility(View.GONE);
            txtScanning.setText(R.string.sources_scanning);
            txtStatus.setText(R.string.sources_scanning);
        } else {
            setSpinning(true);
            txtStatus.setText(R.string.sources_scanning);
        }
        new Thread(() -> {
            List<ExtraSources.Source> found;
            try {
                found = ExtraSources.scan(getApplicationContext(), (dirs, n) -> runOnUiThread(() -> {
                    if (token != scanToken || isFinishing()) return;
                    if (fg) {
                        txtScanning.setText(getString(R.string.sources_scanning_progress, dirs, n));
                    } else {
                        txtStatus.setText(getString(R.string.sources_scanning_progress, dirs, n));
                    }
                }));
            } catch (Throwable t) {
                found = new ArrayList<>();
            }
            // Переключатели, тронутые во время сканирования, — поверх результата
            try {
                java.util.Set<String> enabled = Prefs.extraFolders();
                for (ExtraSources.Source s : found) s.enabled = enabled.contains(s.path);
                ExtraSources.saveCache(getApplicationContext(), found);
            } catch (Throwable ignored) {
            }
            final List<ExtraSources.Source> result = found;
            runOnUiThread(() -> {
                if (token != scanToken || isFinishing() || isDestroyed()) return;
                scanning = false;
                setSpinning(false);
                int added = 0;
                if (!fg && !all.isEmpty()) {
                    java.util.HashSet<String> before = new java.util.HashSet<>();
                    for (ExtraSources.Source s : all) before.add(s.path);
                    for (ExtraSources.Source s : result) {
                        if (before.add(s.path)) added++;
                    }
                }
                android.os.Parcelable lmState = layoutManager == null
                        ? null : layoutManager.onSaveInstanceState();
                all.clear();
                all.addAll(result);
                if (fg) layoutScanning.setVisibility(View.GONE);
                rebuildRows();
                if (lmState != null && layoutManager != null) {
                    layoutManager.onRestoreInstanceState(lmState);
                }
                if (added > 0) {
                    Toasts.makeText(this, getString(R.string.sources_new_found,
                            Fmt.plural(this, R.plurals.folders_count, added)),
                            Toast.LENGTH_SHORT).show();
                }
            });
        }, "extra-sources-scan").start();
    }

    /** Вращение иконки «сканировать» как индикатор фонового обновления. */
    private void setSpinning(boolean on) {
        if (btnRescan == null) return;
        if (!on) {
            btnRescan.clearAnimation();
            return;
        }
        if (spin == null) {
            spin = new android.view.animation.RotateAnimation(0f, 360f,
                    android.view.animation.Animation.RELATIVE_TO_SELF, 0.5f,
                    android.view.animation.Animation.RELATIVE_TO_SELF, 0.5f);
            spin.setDuration(900);
            spin.setRepeatCount(android.view.animation.Animation.INFINITE);
            spin.setInterpolator(new android.view.animation.LinearInterpolator());
        }
        btnRescan.startAnimation(spin);
    }

    private void rebuildRows() {
        rows.clear();
        int lastCat = -1;
        List<ExtraSources.Source> visible = new ArrayList<>();
        for (ExtraSources.Source s : all) {
            if (!filter.isEmpty() && !s.rel.toLowerCase(Locale.ROOT).contains(filter)) continue;
            visible.add(s);
        }
        int[] perCat = new int[3];
        for (ExtraSources.Source s : visible) perCat[s.category]++;
        for (ExtraSources.Source s : visible) {
            if (s.category != lastCat) {
                rows.add(new int[]{s.category, perCat[s.category]});
                lastCat = s.category;
            }
            rows.add(s);
        }
        adapter.notifyDataSetChanged();
        layoutEmpty.setVisibility(!scanning && rows.isEmpty() ? View.VISIBLE : View.GONE);
        updateStatus();
    }

    private void updateStatus() {
        int on = 0, files = 0;
        for (ExtraSources.Source s : all) {
            files += s.total();
            if (s.enabled) on++;
        }
        txtStatus.setText(getString(R.string.sources_status,
                Fmt.plural(this, R.plurals.folders_count, all.size()), files, on));
    }

    private void setAll(boolean on) {
        if (all.isEmpty()) return;
        // «Показать/скрыть все» действует на то, что сейчас в списке (с учётом фильтра)
        List<ExtraSources.Source> target = new ArrayList<>();
        for (Object o : rows) {
            if (o instanceof ExtraSources.Source) target.add((ExtraSources.Source) o);
        }
        ExtraSources.setAll(target, on);
        adapter.notifyDataSetChanged();
        updateStatus();
        Toasts.makeText(this, on ? R.string.sources_all_on : R.string.sources_all_off,
                Toast.LENGTH_SHORT).show();
    }

    private void toggle(ExtraSources.Source s, int pos) {
        s.enabled = !s.enabled;
        ExtraSources.setEnabled(s.path, s.enabled);
        adapter.notifyItemChanged(pos);
        updateStatus();
    }

    private void preview(ExtraSources.Source s) {
        Intent intent = new Intent(this, FolderActivity.class);
        intent.putExtra("path", s.path);
        intent.putExtra("name", s.name);
        startActivity(intent);
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        scanToken++;
        MediaEngine.invalidateAll();
    }

    // ---------- Список ----------

    private final class Adapter extends RecyclerView.Adapter<RecyclerView.ViewHolder> {

        @Override
        public int getItemViewType(int position) {
            return rows.get(position) instanceof ExtraSources.Source ? TYPE_SOURCE : TYPE_HEADER;
        }

        @NonNull
        @Override
        public RecyclerView.ViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
            LayoutInflater li = LayoutInflater.from(parent.getContext());
            if (viewType == TYPE_HEADER) {
                return new HeaderVH(li.inflate(R.layout.item_extra_source_header, parent, false));
            }
            return new SourceVH(li.inflate(R.layout.item_extra_source, parent, false));
        }

        @Override
        public void onBindViewHolder(@NonNull RecyclerView.ViewHolder holder, int position) {
            Object o = rows.get(position);
            if (holder instanceof HeaderVH) {
                int[] h = (int[]) o;
                HeaderVH vh = (HeaderVH) holder;
                vh.title.setText(h[0] == ExtraSources.CAT_APPS ? R.string.sources_group_apps
                        : h[0] == ExtraSources.CAT_HIDDEN ? R.string.sources_group_hidden
                        : R.string.sources_group_other);
                vh.count.setText(Fmt.plural(ExtraSourcesActivity.this, R.plurals.folders_count, h[1]));
                return;
            }
            SourceVH vh = (SourceVH) holder;
            ExtraSources.Source s = (ExtraSources.Source) o;
            vh.name.setText(s.name);
            vh.path.setText(s.rel);
            vh.counts.setText(ExtraSources.countsLabel(ExtraSourcesActivity.this, s));
            vh.badge.setVisibility(s.hidden ? View.VISIBLE : View.GONE);
            vh.video.setVisibility(s.cover != null && MediaEngine.isVideoName(s.cover)
                    ? View.VISIBLE : View.GONE);
            if (s.cover != null) {
                Glide.with(vh.cover)
                        .load(new File(s.cover))
                        .signature(new ObjectKey(s.newest))
                        .override(160, 160)
                        .centerCrop()
                        .placeholder(R.drawable.placeholder_media_square)
                        .into(vh.cover);
            } else {
                vh.cover.setImageResource(R.drawable.placeholder_media_square);
            }
            vh.sw.setChecked(s.enabled);
            vh.root.setAlpha(s.enabled ? 1f : 0.82f);
            vh.root.setOnClickListener(v -> {
                int p = vh.getBindingAdapterPosition();
                if (p == RecyclerView.NO_POSITION) return;
                toggle((ExtraSources.Source) rows.get(p), p);
            });
            vh.card.setOnClickListener(v -> preview(s));
        }

        @Override
        public int getItemCount() {
            return rows.size();
        }
    }

    private static final class HeaderVH extends RecyclerView.ViewHolder {
        final TextView title, count;

        HeaderVH(@NonNull View v) {
            super(v);
            title = v.findViewById(R.id.txtSourceGroup);
            count = v.findViewById(R.id.txtSourceGroupCount);
        }
    }

    private static final class SourceVH extends RecyclerView.ViewHolder {
        final View root, card;
        final ImageView cover, video;
        final TextView name, path, counts, badge;
        final MaterialSwitch sw;

        SourceVH(@NonNull View v) {
            super(v);
            root = v.findViewById(R.id.rowSource);
            card = v.findViewById(R.id.cardSourceCover);
            cover = v.findViewById(R.id.imgSourceCover);
            video = v.findViewById(R.id.imgSourceVideo);
            name = v.findViewById(R.id.txtSourceName);
            path = v.findViewById(R.id.txtSourcePath);
            counts = v.findViewById(R.id.txtSourceCounts);
            badge = v.findViewById(R.id.txtSourceBadge);
            sw = v.findViewById(R.id.switchSource);
            Press.attach(root, 0.985f);
        }
    }
}
