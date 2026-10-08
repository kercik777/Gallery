package com.premiumlab.galleryx;

import android.animation.Animator;
import android.animation.AnimatorListenerAdapter;
import android.content.Intent;
import android.os.Bundle;
import android.view.View;
import android.view.WindowManager;
import android.widget.ImageView;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.app.AppCompatActivity;
import androidx.viewpager2.widget.ViewPager2;

import com.premiumlab.galleryx.data.FavStore;
import com.premiumlab.galleryx.data.MediaItem;
import com.premiumlab.galleryx.data.SafeStore;
import com.premiumlab.galleryx.data.TrashStore;
import com.premiumlab.galleryx.ui.Actions;
import com.premiumlab.galleryx.ui.adapter.PhotoPagerAdapter;
import com.premiumlab.galleryx.ui.dialog.DestSheet;
import com.premiumlab.galleryx.ui.dialog.OpProgressDialog;
import com.premiumlab.galleryx.util.FileOp;
import com.premiumlab.galleryx.util.Fmt;

import java.io.File;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Полноэкранный просмотрщик фотографий с зумом.
 * Режимы: 0 — обычный, 1 — корзина, 2 — сейф.
 */
public class PhotoViewerActivity extends AppCompatActivity {

    public static final int MODE_NORMAL = 0;
    public static final int MODE_TRASH = 1;
    public static final int MODE_SAFE = 2;

    private static final int REQ_ROOT = 601;

    private ViewPager2 pager;
    private PhotoPagerAdapter adapter;
    private View topBar, bottomBar;
    private TextView txtTitle, txtSub;
    private ImageView btnFav, btnMove, btnSafe, btnRestore, btnDelete, btnShare;
    private int mode = MODE_NORMAL;
    private boolean barsVisible = true;
    private Runnable pendingAfterRoot;
    private List<MediaItem> pendingMoveItem;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        if (com.premiumlab.galleryx.data.Prefs.flagSecure()) {
            getWindow().addFlags(WindowManager.LayoutParams.FLAG_SECURE);
        }
        setContentView(R.layout.activity_photo_viewer);

        ArrayList<String> paths = getIntent().getStringArrayListExtra("paths");
        int index = getIntent().getIntExtra("index", 0);
        mode = getIntent().getIntExtra("mode", MODE_NORMAL);
        if (paths == null || paths.isEmpty()) {
            finish();
            return;
        }

        pager = findViewById(R.id.pager);
        topBar = findViewById(R.id.viewerTopBar);
        bottomBar = findViewById(R.id.viewerBottomBar);
        txtTitle = findViewById(R.id.txtViewerTitle);
        txtSub = findViewById(R.id.txtViewerSub);
        btnFav = findViewById(R.id.btnVwFavorite);
        btnShare = findViewById(R.id.btnVwShare);
        btnMove = findViewById(R.id.btnVwMove);
        btnSafe = findViewById(R.id.btnVwSafe);
        btnRestore = findViewById(R.id.btnVwRestore);
        btnDelete = findViewById(R.id.btnVwDelete);

        findViewById(R.id.btnViewerBack).setOnClickListener(v -> finish());

        adapter = new PhotoPagerAdapter(this, () -> toggleBars());
        adapter.submit(paths);
        pager.setAdapter(adapter);
        pager.setCurrentItem(Math.min(index, paths.size() - 1), false);
        pager.registerOnPageChangeCallback(new ViewPager2.OnPageChangeCallback() {
            @Override
            public void onPageSelected(int position) {
                updateToolbar(position);
            }
        });

        setupActions();
        updateToolbar(pager.getCurrentItem());
    }

    private void setupActions() {
        if (mode == MODE_NORMAL) {
            btnRestore.setVisibility(View.GONE);
            btnFav.setOnClickListener(v -> {
                MediaItem it = current();
                if (it == null) return;
                boolean nowFav = FavStore.toggle(it.path);
                Toast.makeText(this, nowFav ? R.string.fav_added : R.string.fav_removed,
                        Toast.LENGTH_SHORT).show();
                updateFavIcon(it.path);
            });
            btnShare.setOnClickListener(v -> {
                MediaItem it = current();
                if (it != null) Actions.share(this, Collections.singletonList(it));
            });
            btnMove.setOnClickListener(v -> moveCurrent());
            btnSafe.setOnClickListener(v -> {
                MediaItem it = current();
                if (it == null) return;
                Actions.confirmSafe(this, Collections.singletonList(it), this::finish);
            });
            btnDelete.setOnClickListener(v -> {
                MediaItem it = current();
                if (it == null) return;
                Actions.confirmTrash(this, Collections.singletonList(it), this::finish);
            });
        } else {
            btnFav.setVisibility(View.GONE);
            btnMove.setVisibility(View.GONE);
            btnSafe.setVisibility(View.GONE);
            btnShare.setOnClickListener(v -> {
                MediaItem it = current();
                if (it != null) Actions.share(this, Collections.singletonList(it));
            });
            btnRestore.setVisibility(View.VISIBLE);
            btnRestore.setOnClickListener(v -> restoreCurrent());
            btnDelete.setOnClickListener(v -> {
                MediaItem it = current();
                if (it == null) return;
                Actions.confirmDeleteForever(this, Collections.singletonList(it),
                        this::finish);
            });
        }
        findViewById(R.id.btnVwInfo).setOnClickListener(v -> {
            MediaItem it = current();
            if (it != null) Actions.showInfo(this, decorateName(it));
        });
    }

    private MediaItem current() {
        String path = adapter.getPath(pager.getCurrentItem());
        if (path == null) return null;
        return MediaItem.fromFile(new File(path));
    }

    /** В сейфе/корзине показывает оригинальное имя файла. */
    private MediaItem decorateName(MediaItem it) {
        if (mode == MODE_TRASH) {
            for (TrashStore.Entry e : TrashStore.get().entries()) {
                if (e.path.equals(it.path)) {
                    it.name = e.name;
                    break;
                }
            }
        } else if (mode == MODE_SAFE) {
            for (SafeStore.Entry e : SafeStore.get().entries()) {
                if (e.path.equals(it.path)) {
                    it.name = e.name;
                    break;
                }
            }
        }
        return it;
    }

    private void updateToolbar(int position) {
        MediaItem it = current();
        if (it == null) return;
        it = decorateName(it);
        txtTitle.setText(it.name);
        txtSub.setText(Fmt.dateFull(it.dateModified) + "  ·  " + Fmt.size(it.size));
        updateFavIcon(it.path);
    }

    private void updateFavIcon(String path) {
        if (mode != MODE_NORMAL) return;
        btnFav.setImageResource(FavStore.isFav(path)
                ? R.drawable.ic_star_filled : R.drawable.ic_star);
        btnFav.setColorFilter(0xFFFFC53D);
    }

    private void moveCurrent() {
        MediaItem it = current();
        if (it == null) return;
        if (com.premiumlab.galleryx.data.Prefs.rootPath() == null) {
            pendingMoveItem = Collections.singletonList(it);
            showRootNeeded();
            return;
        }
        DestSheet sheet = DestSheet.newInstance(false);
        sheet.setListener(new DestSheet.Listener() {
            @Override
            public void onDestPicked(File dir) {
                runCopyMove(Collections.singletonList(current()), dir);
            }

            @Override
            public void onNewFolderRequested() {
                pendingMoveItem = Collections.singletonList(current());
                com.premiumlab.galleryx.ui.dialog.CreateFolderDialog.show(
                        PhotoViewerActivity.this, null,
                        folder -> runCopyMove(pendingMoveItem, folder));
            }
        });
        sheet.show(getSupportFragmentManager(), "dest");
    }

    private void runCopyMove(List<MediaItem> items, File dir) {
        if (items == null || items.isEmpty()) return;
        FileOp op = new FileOp(this);
        OpProgressDialog dlg = OpProgressDialog.show(this,
                getString(R.string.op_move), op::cancel);
        op.move(items, dir, dlg, (ok, fail, cancelled) -> {
            dlg.dismiss();
            Toast.makeText(this, Fmt.plural(this, R.plurals.result_moved, ok),
                    Toast.LENGTH_SHORT).show();
            finish();
        });
    }

    private void restoreCurrent() {
        String path = adapter.getPath(pager.getCurrentItem());
        if (path == null) return;
        OpProgressDialog dlg = OpProgressDialog.show(this,
                getString(R.string.op_restore), null);
        FileOp op = new FileOp(this);
        if (mode == MODE_TRASH) {
            List<TrashStore.Entry> entries = new ArrayList<>();
            for (TrashStore.Entry e : TrashStore.get().entries()) {
                if (e.path.equals(path)) entries.add(e);
            }
            op.restoreTrash(entries, dlg, (ok, fail, cancelled) -> {
                dlg.dismiss();
                Toast.makeText(this, Fmt.plural(this, R.plurals.result_restored, ok),
                        Toast.LENGTH_SHORT).show();
                finish();
            });
        } else {
            List<SafeStore.Entry> entries = new ArrayList<>();
            for (SafeStore.Entry e : SafeStore.get().entries()) {
                if (e.path.equals(path)) entries.add(e);
            }
            op.restoreSafe(entries, dlg, (ok, fail, cancelled) -> {
                dlg.dismiss();
                Toast.makeText(this, Fmt.plural(this, R.plurals.result_restored, ok),
                        Toast.LENGTH_SHORT).show();
                finish();
            });
        }
    }

    private void showRootNeeded() {
        new com.google.android.material.dialog.MaterialAlertDialogBuilder(this)
                .setTitle(R.string.root_needed_title)
                .setMessage(R.string.root_needed_desc)
                .setPositiveButton(R.string.continue_btn, (d, w) ->
                        startActivityForResult(new Intent(this,
                                RootPickerActivity.class), REQ_ROOT))
                .setNegativeButton(R.string.cancel, null)
                .show();
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode == REQ_ROOT && resultCode == RESULT_OK && pendingMoveItem != null) {
            List<MediaItem> items = pendingMoveItem;
            pendingMoveItem = null;
            runCopyMove(items, new File(com.premiumlab.galleryx.data.Prefs.rootPath()));
        }
    }

    private void toggleBars() {
        barsVisible = !barsVisible;
        float target = barsVisible ? 1f : 0f;
        topBar.animate().alpha(target).setDuration(200)
                .setListener(new AnimatorListenerAdapter() {
                    @Override
                    public void onAnimationEnd(Animator a) {
                        topBar.setVisibility(barsVisible ? View.VISIBLE : View.GONE);
                    }
                }).start();
        bottomBar.animate().alpha(target).setDuration(200)
                .setListener(new AnimatorListenerAdapter() {
                    @Override
                    public void onAnimationEnd(Animator a) {
                        bottomBar.setVisibility(barsVisible ? View.VISIBLE : View.GONE);
                    }
                }).start();
    }

    @Override
    public void onBackPressed() {
        super.onBackPressed();
    }

    @Override
    public void onWindowFocusChanged(boolean hasFocus) {
        super.onWindowFocusChanged(hasFocus);
        if (hasFocus) {
            androidx.core.view.WindowCompat.setDecorFitsSystemWindows(getWindow(), false);
            androidx.core.view.WindowInsetsControllerCompat ic =
                    androidx.core.view.WindowCompat.getInsetsController(
                            getWindow(), getWindow().getDecorView());
            if (ic != null) {
                if (barsVisible) {
                    ic.show(androidx.core.view.WindowInsetsCompat.Type.systemBars());
                    ic.setAppearanceLightStatusBars(false);
                } else {
                    ic.hide(androidx.core.view.WindowInsetsCompat.Type.systemBars());
                }
            }
        }
    }
}
