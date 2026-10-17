package com.premiumlab.galleryx;

import android.content.ClipData;
import android.content.ContentUris;
import android.content.Intent;
import android.database.Cursor;
import android.net.Uri;
import android.os.Bundle;
import android.provider.MediaStore;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.widget.ImageView;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.content.FileProvider;
import androidx.recyclerview.widget.GridLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.bumptech.glide.Glide;
import com.bumptech.glide.signature.ObjectKey;
import com.premiumlab.galleryx.data.LockStore;
import com.premiumlab.galleryx.data.MaskGuard;
import com.premiumlab.galleryx.data.MediaEngine;
import com.premiumlab.galleryx.data.MediaItem;
import com.premiumlab.galleryx.data.Prefs;
import com.premiumlab.galleryx.util.Fmt;
import com.premiumlab.galleryx.util.Perms;
import com.premiumlab.galleryx.util.Press;
import com.premiumlab.galleryx.util.Toasts;

import java.io.File;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;

/**
 * Системный выбор медиа: другие приложения (мессенджеры, почта, браузер…)
 * запрашивают фото или видео через ACTION_GET_CONTENT / ACTION_PICK, и система
 * предлагает «Галерею» в списке источников. Экран показывает те же файлы, что
 * и сама галерея (с учётом маскировки и заблокированных папок), и возвращает
 * content-URI с правом чтения — через MediaStore, а для файлов, которых нет
 * в индексе системы (например, корневая папка с .nomedia), через FileProvider.
 */
public class PickerActivity extends AppCompatActivity {

    private final List<MediaItem> items = new ArrayList<>();
    private final LinkedHashSet<String> selected = new LinkedHashSet<>();
    private Adapter adapter;
    private TextView txtTitle, txtCount, btnDone, txtEmpty;
    private View progress;
    private ImageView btnAll;

    private boolean wantImages = true;
    private boolean wantVideos = true;
    private boolean multiple;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        com.premiumlab.galleryx.util.Theming.apply(this);
        if (Prefs.flagSecure()) {
            getWindow().addFlags(WindowManager.LayoutParams.FLAG_SECURE);
        }
        setResult(RESULT_CANCELED);
        parseRequest(getIntent());

        if (!Perms.ok(this)) {
            // Без доступа к файлам показать нечего — просим открыть галерею
            Toasts.makeText(this, R.string.picker_no_permission, Toast.LENGTH_LONG).show();
            finish();
            return;
        }

        setContentView(R.layout.activity_picker);
        txtTitle = findViewById(R.id.txtPickerTitle);
        txtCount = findViewById(R.id.txtPickerCount);
        btnDone = findViewById(R.id.btnPickerDone);
        txtEmpty = findViewById(R.id.txtPickerEmpty);
        progress = findViewById(R.id.progressPicker);
        btnAll = findViewById(R.id.btnPickerAll);
        RecyclerView rv = findViewById(R.id.recyclerPicker);
        ImageView btnBack = findViewById(R.id.btnPickerBack);
        Press.attach(btnBack);
        Press.attach(btnAll);
        Press.attach(btnDone, 0.97f);
        btnBack.setOnClickListener(v -> finish());
        btnAll.setOnClickListener(v -> toggleAll());
        btnDone.setOnClickListener(v -> done());

        txtTitle.setText(wantImages && wantVideos ? R.string.picker_title_media
                : wantVideos ? R.string.picker_title_video : R.string.picker_title_photo);
        // Одиночный выбор: нажатие на файл сразу возвращает его
        btnAll.setVisibility(multiple ? View.VISIBLE : View.GONE);
        btnDone.setVisibility(multiple ? View.VISIBLE : View.GONE);

        rv.setLayoutManager(new GridLayoutManager(this, 4));
        adapter = new Adapter();
        rv.setAdapter(adapter);
        rv.setHasFixedSize(true);
        com.premiumlab.galleryx.util.EdgeToEdge.padBottom(rv);
        updateCount();
        load();
    }

    /** Что именно просит другое приложение: фото, видео или всё; один файл или несколько. */
    private void parseRequest(Intent in) {
        if (in == null) return;
        multiple = in.getBooleanExtra(Intent.EXTRA_ALLOW_MULTIPLE, false);
        List<String> types = new ArrayList<>();
        String t = in.getType();
        if (t == null && in.getData() != null) {
            // ACTION_PICK с content://media/external/images/media и т.п.
            String d = in.getData().toString();
            if (d.contains("/images/")) t = "image/*";
            else if (d.contains("/video/")) t = "video/*";
        }
        if (t != null) types.add(t);
        String[] extra = in.getStringArrayExtra(Intent.EXTRA_MIME_TYPES);
        if (extra != null) {
            for (String e : extra) if (e != null) types.add(e);
        }
        boolean img = false, vid = false, any = false;
        for (String s : types) {
            String m = s.trim().toLowerCase();
            if (m.startsWith("image/")) img = true;
            else if (m.startsWith("video/")) vid = true;
            else any = true; // */* и прочее — показываем и фото, и видео
        }
        if (any || (!img && !vid)) {
            wantImages = true;
            wantVideos = true;
        } else {
            wantImages = img;
            wantVideos = vid;
        }
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
                if (it.isVideo ? !wantVideos : !wantImages) continue;
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
                ? getString(R.string.picker_sub, items.size())
                : Fmt.plural(this, R.plurals.selected_count, n));
        btnDone.setEnabled(n > 0);
        btnDone.setAlpha(n > 0 ? 1f : 0.5f);
        btnDone.setText(n > 0 ? getString(R.string.picker_done_n, n)
                : getString(R.string.picker_done));
    }

    private void done() {
        if (selected.isEmpty()) {
            Toasts.makeText(this, R.string.picker_none, Toast.LENGTH_SHORT).show();
            return;
        }
        List<MediaItem> chosen = new ArrayList<>();
        for (MediaItem it : items) {
            if (selected.contains(it.path)) chosen.add(it);
        }
        deliver(chosen);
    }

    /** Возвращает выбранные файлы вызвавшему приложению. */
    private void deliver(List<MediaItem> chosen) {
        Intent result = new Intent();
        ClipData clip = null;
        Uri first = null;
        String firstMime = null;
        for (MediaItem it : chosen) {
            Uri uri = uriFor(it);
            if (uri == null) continue;
            String mime = mimeOf(it);
            if (first == null) {
                first = uri;
                firstMime = mime;
                clip = new ClipData("media", new String[]{mime}, new ClipData.Item(uri));
            } else {
                clip.addItem(new ClipData.Item(uri));
            }
        }
        if (first == null) {
            Toasts.makeText(this, R.string.picker_fail, Toast.LENGTH_SHORT).show();
            return;
        }
        result.setDataAndType(first, firstMime);
        if (clip.getItemCount() > 1 || multiple) {
            result.setClipData(clip);
        }
        result.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
        setResult(RESULT_OK, result);
        finish();
    }

    private static String mimeOf(MediaItem it) {
        if (it.mime != null && it.mime.contains("/")) return it.mime;
        return it.isVideo ? "video/*" : "image/*";
    }

    /** MediaStore-URI, если файл проиндексирован системой, иначе — FileProvider. */
    private Uri uriFor(MediaItem it) {
        Uri fromStore = mediaStoreUri(it);
        if (fromStore != null) return fromStore;
        try {
            return FileProvider.getUriForFile(this,
                    getPackageName() + ".fileprovider", new File(it.path));
        } catch (Exception e) {
            return null;
        }
    }

    private Uri mediaStoreUri(MediaItem it) {
        Uri table = it.isVideo ? MediaStore.Video.Media.EXTERNAL_CONTENT_URI
                : MediaStore.Images.Media.EXTERNAL_CONTENT_URI;
        try (Cursor c = getContentResolver().query(table,
                new String[]{MediaStore.MediaColumns._ID},
                MediaStore.MediaColumns.DATA + "=?", new String[]{it.path}, null)) {
            if (c != null && c.moveToFirst()) {
                return ContentUris.withAppendedId(table, c.getLong(0));
            }
        } catch (Exception ignored) {
        }
        return null;
    }

    // ---------- Сетка ----------

    private final class Adapter extends RecyclerView.Adapter<Adapter.VH> {

        @NonNull
        @Override
        public VH onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
            View v = LayoutInflater.from(parent.getContext())
                    .inflate(R.layout.item_picker, parent, false);
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
            h.play.setVisibility(it.isVideo ? View.VISIBLE : View.GONE);
            boolean sel = multiple && selected.contains(it.path);
            h.check.setVisibility(sel ? View.VISIBLE : View.GONE);
            h.dim.setVisibility(sel ? View.VISIBLE : View.GONE);
            h.card.setOnClickListener(v -> {
                int pos = h.getBindingAdapterPosition();
                if (pos == RecyclerView.NO_POSITION) return;
                MediaItem clicked = items.get(pos);
                if (!multiple) {
                    List<MediaItem> one = new ArrayList<>();
                    one.add(clicked);
                    deliver(one);
                    return;
                }
                if (!selected.remove(clicked.path)) selected.add(clicked.path);
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
            final ImageView img, check, play;

            VH(@NonNull View v) {
                super(v);
                card = v.findViewById(R.id.cardPicker);
                img = v.findViewById(R.id.imgPicker);
                dim = v.findViewById(R.id.viewPickerDim);
                check = v.findViewById(R.id.imgPickerCheck);
                play = v.findViewById(R.id.imgPickerPlay);
            }
        }
    }
}
