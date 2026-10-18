package com.premiumlab.galleryx;

import android.annotation.SuppressLint;
import android.content.Intent;
import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.widget.ImageView;
import android.widget.ProgressBar;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.bumptech.glide.Glide;
import com.bumptech.glide.signature.ObjectKey;
import com.premiumlab.galleryx.data.DuplicateFinder;
import com.premiumlab.galleryx.data.MediaEngine;
import com.premiumlab.galleryx.data.MediaItem;
import com.premiumlab.galleryx.data.Prefs;
import com.premiumlab.galleryx.ui.Actions;
import com.premiumlab.galleryx.util.Fmt;
import com.premiumlab.galleryx.util.Press;
import com.premiumlab.galleryx.util.Toasts;

import java.io.File;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Поиск дубликатов фото (Настройки → Поиск дубликатов).
 *
 * Кнопка «Сканировать изображения» запускает {@link DuplicateFinder}:
 * точные копии ищутся по содержимому (размер + SHA-256), имена не важны.
 * Результат — группы одинаковых файлов: у каждого видно миниатюру, имя,
 * папку, размер и дату; тап по миниатюре открывает просмотрщик с файлами
 * группы, кнопка «i» — свойства. Пользователь сам отмечает лишние
 * (или кнопкой «Выбрать лишние» — всё, кроме самого старого в группе)
 * и удаляет их в корзину.
 */
public class DuplicatesActivity extends AppCompatActivity {

    /** Intent-extra: true — режим видео, иначе фото. */
    public static final String EXTRA_VIDEO = "video";

    private static final int TYPE_HEADER = 0;
    private static final int TYPE_FILE = 1;

    private boolean videoMode;

    private final List<DuplicateFinder.Group> groups = new ArrayList<>();
    private final List<Object> rows = new ArrayList<>();          // Header | MediaItem
    private final Map<String, DuplicateFinder.Group> groupByPath = new HashMap<>();
    private final Set<String> selected = new HashSet<>();

    private Adapter adapter;
    private DuplicateFinder finder;
    private com.premiumlab.galleryx.data.VideoDuplicateFinder videoFinder;
    private boolean scanning;
    private boolean scannedOnce;

    private TextView txtTitle, txtIntroTitle, txtIntroSub, txtScanning, txtEmptySub, btnScanText;

    private TextView txtStatus, txtProgress, txtDelete;
    private View layoutIntro, layoutScanning, layoutEmpty, layoutActions, btnDelete;
    private ImageView btnRescan;
    private ProgressBar progressBar;
    private RecyclerView recycler;

    /** Заголовок группы в списке. */
    private static final class Header {
        final DuplicateFinder.Group group;
        final int number;

        Header(DuplicateFinder.Group group, int number) {
            this.group = group;
            this.number = number;
        }
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        com.premiumlab.galleryx.util.Theming.apply(this);
        if (Prefs.flagSecure()) {
            getWindow().addFlags(WindowManager.LayoutParams.FLAG_SECURE);
        }
        setContentView(R.layout.activity_duplicates);
        videoMode = getIntent().getBooleanExtra(EXTRA_VIDEO, false);

        txtTitle = findViewById(R.id.txtDupTitle);
        txtIntroTitle = findViewById(R.id.txtDupIntroTitle);
        txtIntroSub = findViewById(R.id.txtDupIntroSub);
        txtScanning = findViewById(R.id.txtDupScanning);
        txtEmptySub = findViewById(R.id.txtDupEmptySub);
        btnScanText = findViewById(R.id.btnDupScan);
        if (videoMode) {
            txtTitle.setText(R.string.dup_hub_video);
            txtIntroTitle.setText(R.string.dup_intro_title_video);
            txtIntroSub.setText(R.string.dup_intro_sub_video);
            txtScanning.setText(R.string.dup_scanning_video);
            txtEmptySub.setText(R.string.dup_empty_sub_video);
            btnScanText.setText(R.string.dup_scan_btn_video);
        }

        txtStatus = findViewById(R.id.txtDupStatus);
        txtProgress = findViewById(R.id.txtDupProgress);
        txtDelete = findViewById(R.id.txtDupDelete);
        layoutIntro = findViewById(R.id.layoutDupIntro);
        layoutScanning = findViewById(R.id.layoutDupScanning);
        layoutEmpty = findViewById(R.id.layoutDupEmpty);
        layoutActions = findViewById(R.id.layoutDupActions);
        btnDelete = findViewById(R.id.btnDupDelete);
        btnRescan = findViewById(R.id.btnDupRescan);
        progressBar = findViewById(R.id.progressDup);
        recycler = findViewById(R.id.recyclerDup);

        ImageView btnBack = findViewById(R.id.btnDupBack);
        View btnScan = findViewById(R.id.btnDupScan);
        View btnAuto = findViewById(R.id.btnDupAuto);
        View btnClear = findViewById(R.id.btnDupClear);

        Press.attach(btnBack);
        Press.attach(btnRescan);
        Press.attach(btnScan, 0.97f);
        Press.attach(btnAuto, 0.96f);
        Press.attach(btnClear, 0.96f);
        Press.attach(btnDelete, 0.97f);

        btnBack.setOnClickListener(v -> finish());
        btnScan.setOnClickListener(v -> scan());
        btnRescan.setOnClickListener(v -> scan());
        btnAuto.setOnClickListener(v -> autoSelect());
        btnClear.setOnClickListener(v -> {
            selected.clear();
            adapter.notifyDataSetChanged();
            updateDeleteBar();
        });
        btnDelete.setOnClickListener(v -> deleteSelected());

        recycler.setLayoutManager(new LinearLayoutManager(this));
        adapter = new Adapter();
        recycler.setAdapter(adapter);
        com.premiumlab.galleryx.util.EdgeToEdge.padBottom(recycler);
        com.premiumlab.galleryx.util.EdgeToEdge.padBottom(findViewById(R.id.layoutDupBottom));

        txtStatus.setText(R.string.dup_status_idle);
        showState();
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        if (finder != null) finder.cancel();
        if (videoFinder != null) videoFinder.cancel();
    }

    // ---------- Сканирование ----------

    private void scan() {
        if (scanning) return;
        scanning = true;
        selected.clear();
        groups.clear();
        rebuildRows();
        updateDeleteBar();
        txtProgress.setText(R.string.dup_preparing);
        progressBar.setIndeterminate(true);
        showState();

        finder = new DuplicateFinder();
        DuplicateFinder current = finder;
        finder.scan(this, new DuplicateFinder.Listener() {
            @Override
            public void onProgress(int done, int total) {
                if (current != finder || isFinishing()) return;
                if (total <= 0) {
                    txtProgress.setText(R.string.dup_preparing);
                    return;
                }
                progressBar.setIndeterminate(false);
                progressBar.setMax(total);
                progressBar.setProgress(done);
                txtProgress.setText(getString(R.string.dup_progress, done, total));
            }

            @Override
            public void onResult(List<DuplicateFinder.Group> result) {
                if (current != finder || isFinishing()) return;
                scanning = false;
                scannedOnce = true;
                groups.clear();
                groups.addAll(result);
                rebuildRows();
                updateStatus();
                updateDeleteBar();
                showState();
            }
        });
    }

    // ---------- Список ----------

    @SuppressLint("NotifyDataSetChanged")
    private void rebuildRows() {
        rows.clear();
        groupByPath.clear();
        int n = 0;
        for (DuplicateFinder.Group g : groups) {
            n++;
            rows.add(new Header(g, n));
            for (MediaItem it : g.items) {
                rows.add(it);
                groupByPath.put(it.path, g);
            }
        }
        adapter.notifyDataSetChanged();
    }

    private void updateStatus() {
        if (groups.isEmpty()) {
            txtStatus.setText(R.string.dup_status_idle);
            return;
        }
        int extra = 0;
        long wasted = 0;
        for (DuplicateFinder.Group g : groups) {
            extra += g.items.size() - 1;
            wasted += g.wasted();
        }
        txtStatus.setText(getString(R.string.dup_status_found,
                groups.size(), extra, Fmt.size(wasted)));
    }

    private void showState() {
        boolean hasResults = !groups.isEmpty();
        layoutScanning.setVisibility(scanning ? View.VISIBLE : View.GONE);
        layoutIntro.setVisibility(!scanning && !scannedOnce ? View.VISIBLE : View.GONE);
        layoutEmpty.setVisibility(!scanning && scannedOnce && !hasResults
                ? View.VISIBLE : View.GONE);
        recycler.setVisibility(!scanning && hasResults ? View.VISIBLE : View.GONE);
        layoutActions.setVisibility(!scanning && hasResults ? View.VISIBLE : View.GONE);
        btnRescan.setVisibility(scanning ? View.GONE : View.VISIBLE);
    }

    // ---------- Выбор ----------

    /** В каждой группе отмечает все копии, кроме самой старой. */
    @SuppressLint("NotifyDataSetChanged")
    private void autoSelect() {
        selected.clear();
        for (DuplicateFinder.Group g : groups) {
            for (int i = 1; i < g.items.size(); i++) {
                selected.add(g.items.get(i).path);
            }
        }
        adapter.notifyDataSetChanged();
        updateDeleteBar();
        Toasts.makeText(this, R.string.dup_auto_hint,
                android.widget.Toast.LENGTH_SHORT).show();
    }

    private void updateDeleteBar() {
        int count = 0;
        long size = 0;
        for (DuplicateFinder.Group g : groups) {
            for (MediaItem it : g.items) {
                if (selected.contains(it.path)) {
                    count++;
                    size += it.size;
                }
            }
        }
        if (count == 0) {
            btnDelete.setVisibility(View.GONE);
        } else {
            btnDelete.setVisibility(View.VISIBLE);
            txtDelete.setText(getString(R.string.dup_delete_btn, count, Fmt.size(size)));
        }
    }

    // ---------- Удаление ----------

    private void deleteSelected() {
        List<MediaItem> items = new ArrayList<>();
        for (DuplicateFinder.Group g : groups) {
            for (MediaItem it : g.items) {
                if (selected.contains(it.path)) items.add(it);
            }
        }
        if (items.isEmpty()) return;
        // Обычное удаление приложения: в корзину на 30 дней
        // (или сразу навсегда, если корзина выключена в настройках)
        Actions.confirmTrash(this, items, this::afterDelete);
    }

    /** Убирает удалённые файлы из групп; группы из одного файла исчезают. */
    private void afterDelete() {
        MediaEngine.invalidateAll();
        selected.clear();
        for (int i = groups.size() - 1; i >= 0; i--) {
            DuplicateFinder.Group g = groups.get(i);
            for (int j = g.items.size() - 1; j >= 0; j--) {
                if (!new File(g.items.get(j).path).exists()) g.items.remove(j);
            }
            if (g.items.size() < 2) groups.remove(i);
        }
        rebuildRows();
        updateStatus();
        updateDeleteBar();
        showState();
    }

    // ---------- Просмотр ----------

    /** Открывает просмотрщик с файлами группы, начиная с нажатого. */
    private void openViewer(MediaItem item) {
        DuplicateFinder.Group g = groupByPath.get(item.path);
        if (g == null) return;
        ArrayList<String> paths = new ArrayList<>();
        int index = 0;
        for (MediaItem it : g.items) {
            if (it.path.equals(item.path)) index = paths.size();
            paths.add(it.path);
        }
        Intent intent = new Intent(this, PhotoViewerActivity.class);
        intent.putStringArrayListExtra("paths", paths);
        intent.putExtra("index", index);
        startActivity(intent);
    }

    // ---------- Адаптер ----------

    private final class Adapter extends RecyclerView.Adapter<RecyclerView.ViewHolder> {

        @Override
        public int getItemViewType(int position) {
            return rows.get(position) instanceof Header ? TYPE_HEADER : TYPE_FILE;
        }

        @Override
        public int getItemCount() {
            return rows.size();
        }

        @NonNull
        @Override
        public RecyclerView.ViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
            LayoutInflater inf = LayoutInflater.from(parent.getContext());
            if (viewType == TYPE_HEADER) {
                return new HeaderVH(inf.inflate(R.layout.item_dup_header, parent, false));
            }
            return new FileVH(inf.inflate(R.layout.item_dup_file, parent, false));
        }

        @Override
        public void onBindViewHolder(@NonNull RecyclerView.ViewHolder holder, int position) {
            Object row = rows.get(position);
            if (holder instanceof HeaderVH) {
                ((HeaderVH) holder).bind((Header) row);
            } else {
                ((FileVH) holder).bind((MediaItem) row);
            }
        }
    }

    private final class HeaderVH extends RecyclerView.ViewHolder {
        final TextView title, info;

        HeaderVH(View v) {
            super(v);
            title = v.findViewById(R.id.txtDupGroupTitle);
            info = v.findViewById(R.id.txtDupGroupInfo);
        }

        void bind(Header h) {
            title.setText(getString(R.string.dup_group_title, h.number));
            String count = Fmt.plural(DuplicatesActivity.this, R.plurals.items_count,
                    h.group.items.size());
            if (h.group.exact) {
                // Точные копии: размер у всех одинаковый — «по 1,1 МБ»
                info.setText(getString(R.string.dup_group_info_exact,
                        count, Fmt.size(h.group.size)));
            } else {
                // Похожие: размеры разные — показываем суммарный
                long total = 0;
                for (MediaItem it : h.group.items) total += it.size;
                info.setText(getString(R.string.dup_group_info_similar,
                        count, Fmt.size(total)));
            }
        }
    }

    private final class FileVH extends RecyclerView.ViewHolder {
        final ImageView cover, check, info;
        final TextView name, folder, meta, badge;
        final View row;

        FileVH(View v) {
            super(v);
            row = v.findViewById(R.id.rowDupFile);
            cover = v.findViewById(R.id.imgDupCover);
            check = v.findViewById(R.id.imgDupCheck);
            info = v.findViewById(R.id.btnDupInfo);
            name = v.findViewById(R.id.txtDupName);
            folder = v.findViewById(R.id.txtDupFolder);
            meta = v.findViewById(R.id.txtDupMeta);
            badge = v.findViewById(R.id.txtDupBadge);
            Press.attach(info);
        }

        void bind(MediaItem it) {
            name.setText(it.name);
            folder.setText(it.folderPath);
            // Разрешение (если известно) · размер · дата; у видео — ещё длительность
            String m = Fmt.size(it.size) + " · " + Fmt.dateTimeShort(it.dateModified);
            if (it.isVideo && it.duration > 0) {
                m = Fmt.duration(it.duration) + " · " + m;
            }
            if (it.width > 0 && it.height > 0) {
                m = it.width + "×" + it.height + " · " + m;
            }
            meta.setText(m);
            DuplicateFinder.Group g = groupByPath.get(it.path);
            boolean best = g != null && !g.items.isEmpty() && g.items.get(0) == it;
            badge.setVisibility(best ? View.VISIBLE : View.GONE);
            if (best && g != null) {
                // У точных копий лучший = самый старый; у похожих = лучшее качество
                badge.setText(g.exact ? R.string.dup_badge_oldest : R.string.dup_badge_best);
            }

            Glide.with(cover)
                    .load(new File(it.path))
                    .signature(new ObjectKey(it.dateModified))
                    .placeholder(R.drawable.placeholder_media_square)
                    .centerCrop()
                    .into(cover);

            boolean sel = selected.contains(it.path);
            if (sel) check.setImageResource(R.drawable.ic_check);
            else check.setImageDrawable(null);
            check.setBackgroundResource(sel ? R.drawable.bg_check_badge
                    : R.drawable.bg_check_off);

            row.setOnClickListener(v -> toggle(it));
            check.setOnClickListener(v -> toggle(it));
            cover.setOnClickListener(v -> openViewer(it));
            info.setOnClickListener(v -> Actions.showInfo(DuplicatesActivity.this, it));
        }

        void toggle(MediaItem it) {
            if (!selected.remove(it.path)) selected.add(it.path);
            int pos = getBindingAdapterPosition();
            if (pos != RecyclerView.NO_POSITION) adapter.notifyItemChanged(pos);
            updateDeleteBar();
        }
    }
}
