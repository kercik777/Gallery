package com.premiumlab.galleryx.editor;

import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.ColorMatrixColorFilter;
import android.graphics.Matrix;
import android.graphics.Paint;
import android.graphics.drawable.GradientDrawable;
import androidx.exifinterface.media.ExifInterface;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.Gravity;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.SeekBar;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.app.AppCompatActivity;

import com.google.android.material.dialog.MaterialAlertDialogBuilder;
import com.premiumlab.galleryx.R;
import com.premiumlab.galleryx.data.MediaEngine;
import com.premiumlab.galleryx.data.Prefs;
import com.premiumlab.galleryx.util.Press;
import com.premiumlab.galleryx.util.Scan;
import com.premiumlab.galleryx.util.Toasts;

import java.io.File;
import java.io.FileOutputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Встроенный редактор фото в духе GorillaGallery: Правка (коррекции и поворот),
 * Рисование, Фильтры, Кадр, Текст; отмена/повтор; «Сохранить копию» / «Перезаписать».
 */
public class EditorActivity extends AppCompatActivity {

    public static final String EXTRA_PATH = "path";
    public static final String RESULT_PATH = "result_path";

    private static final int PREVIEW_MAX = 1600;
    private static final ExecutorService IO = Executors.newSingleThreadExecutor();
    private final Handler main = new Handler(Looper.getMainLooper());

    private static final int MODE_TUNE = 0, MODE_DOODLE = 1, MODE_FILTERS = 2, MODE_CROP = 3,
            MODE_TEXT = 4;

    private String path;
    private Bitmap preview;
    private EditorCanvasView canvas;
    private EditState state = new EditState();
    private final List<EditState> undo = new ArrayList<>();
    private final List<EditState> redo = new ArrayList<>();

    private int mode = MODE_TUNE;
    private int tuneOption = EditState.OPT_EXPOSURE;
    private final List<TextView> tuneChips = new ArrayList<>();
    private final List<View> modeTabs = new ArrayList<>();
    private final List<View> filterTiles = new ArrayList<>();
    private final List<View> aspectChips = new ArrayList<>();
    private final List<View> doodleToolChips = new ArrayList<>();
    private final List<View> doodleColorDots = new ArrayList<>();
    private final List<View> textColorDots = new ArrayList<>();

    private int brushColor = 0xFFFFFFFF;
    private int brushTool = 0; // 0 перо, 1 карандаш, 2 ластик
    private int textColor = 0xFFFFFFFF;

    private View panelTune, panelFilters, panelCrop, panelDoodle, panelText, progress;
    private TextView txtHint, txtTuneValue;
    private SeekBar seekTune, seekBrush, seekTextSize;
    private ImageView btnUndo, btnRedo;

    private String[] tuneNames;
    private static final int[] COLORS = {0xFFFFFFFF, 0xFF000000, 0xFFFF3B30, 0xFFFF9F0A,
            0xFFFFD60A, 0xFF30D158, 0xFF00C7BE, 0xFF0A84FF, 0xFF5E5CE6, 0xFFBF5AF2, 0xFFFF2D55};
    private String[] aspectNames;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        com.premiumlab.galleryx.util.BackCompat.install(this, this::handleBack);
        com.premiumlab.galleryx.util.Theming.apply(this);
        if (Prefs.flagSecure()) getWindow().addFlags(WindowManager.LayoutParams.FLAG_SECURE);
        setContentView(R.layout.activity_editor);
        getWindow().setStatusBarColor(0xFF0B0B10);
        getWindow().setNavigationBarColor(0xFF141420);
        path = getIntent().getStringExtra(EXTRA_PATH);
        if (path == null) {
            finish();
            return;
        }
        canvas = findViewById(R.id.editorCanvas);
        panelTune = findViewById(R.id.panelTune);
        panelFilters = findViewById(R.id.panelFilters);
        panelCrop = findViewById(R.id.panelCrop);
        panelDoodle = findViewById(R.id.panelDoodle);
        panelText = findViewById(R.id.panelText);
        progress = findViewById(R.id.layoutEdProgress);
        txtHint = findViewById(R.id.txtEdHint);
        txtTuneValue = findViewById(R.id.txtTuneValue);
        seekTune = findViewById(R.id.seekTune);
        seekBrush = findViewById(R.id.seekBrush);
        seekTextSize = findViewById(R.id.seekTextSize);
        btnUndo = findViewById(R.id.btnEdUndo);
        btnRedo = findViewById(R.id.btnEdRedo);
        // Заголовок — метка «ФОТО», как в GorillaGallery (имя файла видно в просмотрщике)

        View close = findViewById(R.id.btnEdClose);
        Press.attach(close, 0.88f);
        close.setOnClickListener(v -> getOnBackPressedDispatcher().onBackPressed());
        btnUndo.setOnClickListener(v -> doUndo());
        btnRedo.setOnClickListener(v -> doRedo());
        View save = findViewById(R.id.btnEdSave);
        Press.attach(save, 0.94f);
        save.setOnClickListener(v -> askSave());

        canvas.setListener(new EditorCanvasView.Listener() {
            @Override
            public void onEditCommitted() {
                // Жест завершён: состояние уже изменено внутри холста — фиксируем в истории
                pushHistoryFromCanvas();
            }

            @Override
            public void onLabelSelected(int index) {
                updateTextPanel();
            }

            @Override
            public void onLabelDoubleTap(int index) {
                editLabelText(index);
            }
        });

        buildModeTabs();
        buildTunePanel();
        buildCropPanel();
        buildDoodlePanel();
        buildTextPanel();
        setMode(MODE_TUNE);
        loadPreview();
    }

    // ---------- Загрузка ----------

    private void loadPreview() {
        progress.setVisibility(View.VISIBLE);
        IO.execute(() -> {
            Bitmap b = decode(path, PREVIEW_MAX);
            main.post(() -> {
                progress.setVisibility(View.GONE);
                if (b == null) {
                    Toasts.makeText(this, R.string.editor_load_failed, Toast.LENGTH_SHORT).show();
                    finish();
                    return;
                }
                preview = b;
                canvas.setPreview(b);
                canvas.setState(state);
                buildFilterPanel();
            });
        });
    }

    /** Декодирует с учётом EXIF-ориентации; maxSide ≤ 0 — полный размер. */
    static Bitmap decode(String path, int maxSide) {
        try {
            BitmapFactory.Options o = new BitmapFactory.Options();
            o.inJustDecodeBounds = true;
            BitmapFactory.decodeFile(path, o);
            if (o.outWidth <= 0) return null;
            int sample = 1;
            if (maxSide > 0) {
                while (o.outWidth / (sample * 2) >= maxSide || o.outHeight / (sample * 2) >= maxSide) {
                    sample *= 2;
                }
            }
            BitmapFactory.Options d = new BitmapFactory.Options();
            d.inSampleSize = sample;
            d.inPreferredConfig = Bitmap.Config.ARGB_8888;
            Bitmap b = BitmapFactory.decodeFile(path, d);
            if (b == null) return null;
            int or = new ExifInterface(path).getAttributeInt(ExifInterface.TAG_ORIENTATION,
                    ExifInterface.ORIENTATION_NORMAL);
            Matrix m = new Matrix();
            switch (or) {
                case ExifInterface.ORIENTATION_ROTATE_90: m.postRotate(90); break;
                case ExifInterface.ORIENTATION_ROTATE_180: m.postRotate(180); break;
                case ExifInterface.ORIENTATION_ROTATE_270: m.postRotate(270); break;
                case ExifInterface.ORIENTATION_FLIP_HORIZONTAL: m.postScale(-1, 1); break;
                case ExifInterface.ORIENTATION_FLIP_VERTICAL: m.postScale(1, -1); break;
                case ExifInterface.ORIENTATION_TRANSPOSE: m.postRotate(90); m.postScale(-1, 1); break;
                case ExifInterface.ORIENTATION_TRANSVERSE: m.postRotate(270); m.postScale(-1, 1); break;
                default: return b;
            }
            Bitmap r = Bitmap.createBitmap(b, 0, 0, b.getWidth(), b.getHeight(), m, true);
            if (r != b) b.recycle();
            return r;
        } catch (Throwable t) {
            return null;
        }
    }

    // ---------- Режимы ----------

    private void buildModeTabs() {
        LinearLayout row = findViewById(R.id.rowModes);
        int[] icons = {R.drawable.ic_tune, R.drawable.ic_brush, R.drawable.ic_filter,
                R.drawable.ic_crop, R.drawable.ic_text_fields};
        String[] names = getResources().getStringArray(R.array.editor_mode_names);
        for (int i = 0; i < icons.length; i++) {
            final int idx = i;
            LinearLayout tab = new LinearLayout(this);
            tab.setOrientation(LinearLayout.VERTICAL);
            tab.setGravity(Gravity.CENTER);
            tab.setLayoutParams(new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1f));
            ImageView img = new ImageView(this);
            img.setImageResource(icons[i]);
            img.setLayoutParams(new LinearLayout.LayoutParams(dp(24), dp(24)));
            TextView t = new TextView(this);
            t.setText(names[i]);
            t.setTextSize(11f);
            t.setPadding(0, dp(3), 0, 0);
            // Подпись строго по центру под иконкой: при крупном системном шрифте
            // текст шире вкладки и прижимался к левому краю — иконка казалась
            // смещённой вправо. Теперь подпись в одну строку, по центру, ужимается.
            t.setGravity(Gravity.CENTER_HORIZONTAL);
            t.setSingleLine(true);
            t.setIncludeFontPadding(false);
            t.setLayoutParams(new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
            androidx.core.widget.TextViewCompat.setAutoSizeTextTypeUniformWithConfiguration(
                    t, 8, 11, 1, android.util.TypedValue.COMPLEX_UNIT_SP);
            LinearLayout.LayoutParams ilp = new LinearLayout.LayoutParams(dp(24), dp(24));
            ilp.gravity = Gravity.CENTER_HORIZONTAL;
            img.setLayoutParams(ilp);
            tab.addView(img);
            tab.addView(t);
            tab.setOnClickListener(v -> setMode(idx));
            Press.attach(tab, 0.92f);
            row.addView(tab);
            modeTabs.add(tab);
        }
    }

    private void setMode(int m) {
        if (mode == MODE_CROP && m != MODE_CROP) applyCropFromCanvas();
        mode = m;
        panelTune.setVisibility(m == MODE_TUNE ? View.VISIBLE : View.GONE);
        panelFilters.setVisibility(m == MODE_FILTERS ? View.VISIBLE : View.GONE);
        panelCrop.setVisibility(m == MODE_CROP ? View.VISIBLE : View.GONE);
        panelDoodle.setVisibility(m == MODE_DOODLE ? View.VISIBLE : View.GONE);
        panelText.setVisibility(m == MODE_TEXT ? View.VISIBLE : View.GONE);
        canvas.setMode(m == MODE_CROP ? EditorCanvasView.MODE_CROP
                : m == MODE_DOODLE ? EditorCanvasView.MODE_DOODLE
                : m == MODE_TEXT ? EditorCanvasView.MODE_TEXT : EditorCanvasView.MODE_VIEW);
        for (int i = 0; i < modeTabs.size(); i++) {
            LinearLayout tab = (LinearLayout) modeTabs.get(i);
            int color = i == m ? com.premiumlab.galleryx.util.Theming.accent(this) : 0xFF9999B0;
            ((ImageView) tab.getChildAt(0)).setColorFilter(color);
            ((TextView) tab.getChildAt(1)).setTextColor(color);
        }
        String hint;
        switch (m) {
            case MODE_DOODLE: hint = getString(R.string.editor_hint_doodle); break;
            case MODE_CROP: hint = getString(R.string.editor_hint_crop); break;
            case MODE_TEXT: hint = getString(R.string.editor_hint_text); break;
            case MODE_FILTERS: hint = getString(R.string.editor_hint_compare); break;
            default: hint = getString(R.string.editor_hint_compare); break;
        }
        txtHint.setText(hint);
        if (m == MODE_TEXT) updateTextPanel();
        updateUndoRedo();
    }

    // ---------- История ----------

    private void commit(EditState next) {
        undo.add(state);
        if (undo.size() > 60) undo.remove(0);
        redo.clear();
        state = next;
        lastSnapshot = state.copy();
        canvas.setState(state);
        updateUndoRedo();
        if (mode == MODE_TUNE) syncTuneSlider();
        if (mode == MODE_FILTERS) highlightFilter();
    }

    /** Копия состояния после последнего зафиксированного шага — «до» следующего жеста. */
    private EditState lastSnapshot = new EditState();

    /** Холст изменил state «по месту» (штрих, перенос надписи) — кладём в историю снимок «до». */
    private void pushHistoryFromCanvas() {
        if (canvas.getMode() == EditorCanvasView.MODE_CROP) return; // кадр фиксируется при выходе
        undo.add(lastSnapshot);
        if (undo.size() > 60) undo.remove(0);
        redo.clear();
        lastSnapshot = state.copy();
        updateUndoRedo();
    }

    private void doUndo() {
        if (undo.isEmpty()) return;
        redo.add(state.copy());
        state = undo.remove(undo.size() - 1);
        lastSnapshot = state.copy();
        canvas.setState(state);
        afterHistoryChange();
    }

    private void doRedo() {
        if (redo.isEmpty()) return;
        undo.add(state.copy());
        state = redo.remove(redo.size() - 1);
        lastSnapshot = state.copy();
        canvas.setState(state);
        afterHistoryChange();
    }

    private void afterHistoryChange() {
        updateUndoRedo();
        syncTuneSlider();
        highlightFilter();
        updateTextPanel();
    }

    private void updateUndoRedo() {
        btnUndo.setAlpha(undo.isEmpty() ? 0.35f : 1f);
        btnRedo.setAlpha(redo.isEmpty() ? 0.35f : 1f);
    }

    // ---------- Правка ----------

    private void buildTunePanel() {
        LinearLayout row = findViewById(R.id.rowTuneOptions);
        // Первый чип — поворот (как у Gorilla: Rotate стоит первым в списке правок)
        TextView rotate = chip(getString(R.string.video_editor_rotate));
        rotate.setOnClickListener(v -> {
            EditState n = state.copy();
            n.rotateCw();
            commit(n);
        });
        row.addView(rotate);
        tuneNames = getResources().getStringArray(R.array.editor_tune_names);
        for (int i = 0; i < tuneNames.length; i++) {
            final int opt = i;
            TextView c = chip(tuneNames[i]);
            c.setOnClickListener(v -> {
                tuneOption = opt;
                highlightTuneChip();
                syncTuneSlider();
            });
            row.addView(c);
            tuneChips.add(c);
        }
        highlightTuneChip();
        seekTune.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            EditState before;

            @Override
            public void onProgressChanged(SeekBar sb, int p, boolean fromUser) {
                if (!fromUser) return;
                float v = (p - 100) / 100f;
                if (tuneOption == EditState.OPT_VIGNETTE || tuneOption == EditState.OPT_SHARPNESS) {
                    v = Math.max(0f, v);
                }
                state.set(tuneOption, v);
                txtTuneValue.setText(String.format(Locale.US, "%+d", Math.round(v * 100)));
                canvas.invalidate();
            }

            @Override
            public void onStartTrackingTouch(SeekBar sb) {
                before = state.copy();
            }

            @Override
            public void onStopTrackingTouch(SeekBar sb) {
                if (before != null) {
                    EditState now = state;
                    state = before;
                    commit(now);
                    before = null;
                }
            }
        });
        findViewById(R.id.btnTuneReset).setOnClickListener(v -> {
            EditState n = state.copy();
            n.set(tuneOption, 0f);
            commit(n);
        });
        syncTuneSlider();
    }

    private void highlightTuneChip() {
        for (int i = 0; i < tuneChips.size(); i++) {
            styleChip(tuneChips.get(i), i == tuneOption);
        }
    }

    private void syncTuneSlider() {
        float v = state.get(tuneOption);
        seekTune.setProgress(Math.round(v * 100) + 100);
        txtTuneValue.setText(String.format(Locale.US, "%+d", Math.round(v * 100)));
    }

    // ---------- Фильтры ----------

    private void buildFilterPanel() {
        LinearLayout row = findViewById(R.id.rowFilters);
        row.removeAllViews();
        filterTiles.clear();
        if (preview == null) return;
        // Маленькая квадратная копия для миниатюр фильтров
        int s = dp(64);
        Bitmap thumb = Bitmap.createBitmap(s, s, Bitmap.Config.ARGB_8888);
        Canvas c = new Canvas(thumb);
        float scale = Math.max(s / (float) preview.getWidth(), s / (float) preview.getHeight());
        Matrix m = new Matrix();
        m.postScale(scale, scale);
        m.postTranslate((s - preview.getWidth() * scale) / 2f, (s - preview.getHeight() * scale) / 2f);
        c.drawBitmap(preview, m, new Paint(Paint.FILTER_BITMAP_FLAG));
        String[] filterNames = Filters.names(this);
        for (int i = 0; i < filterNames.length; i++) {
            final int idx = i;
            LinearLayout tile = new LinearLayout(this);
            tile.setOrientation(LinearLayout.VERTICAL);
            tile.setGravity(Gravity.CENTER_HORIZONTAL);
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT);
            lp.setMarginEnd(dp(10));
            tile.setLayoutParams(lp);
            ImageView img = new ImageView(this);
            img.setLayoutParams(new LinearLayout.LayoutParams(s, s));
            img.setImageBitmap(thumb);
            img.setColorFilter(new ColorMatrixColorFilter(Filters.matrix(i)));
            img.setClipToOutline(true);
            GradientDrawable bg = new GradientDrawable();
            bg.setCornerRadius(dp(14));
            bg.setColor(0xFF000000);
            img.setBackground(bg);
            TextView t = new TextView(this);
            t.setText(filterNames[i]);
            t.setTextSize(11f);
            t.setPadding(0, dp(4), 0, 0);
            tile.addView(img);
            tile.addView(t);
            tile.setOnClickListener(v -> {
                EditState n = state.copy();
                n.preset = idx;
                commit(n);
            });
            Press.attach(tile, 0.92f);
            row.addView(tile);
            filterTiles.add(tile);
        }
        highlightFilter();
    }

    private void highlightFilter() {
        for (int i = 0; i < filterTiles.size(); i++) {
            LinearLayout tile = (LinearLayout) filterTiles.get(i);
            boolean on = i == state.preset;
            ImageView img = (ImageView) tile.getChildAt(0);
            GradientDrawable bg = (GradientDrawable) img.getBackground();
            bg.setStroke(dp(2), on ? com.premiumlab.galleryx.util.Theming.accent(this) : 0x00000000);
            img.setPadding(on ? dp(2) : 0, on ? dp(2) : 0, on ? dp(2) : 0, on ? dp(2) : 0);
            ((TextView) tile.getChildAt(1)).setTextColor(on ? com.premiumlab.galleryx.util.Theming.accent(this) : 0xFF9999B0);
        }
    }

    // ---------- Кадр ----------

    private void buildCropPanel() {
        LinearLayout row = findViewById(R.id.rowCropAspects);
        aspectNames = getResources().getStringArray(R.array.editor_aspect_names);
        for (int i = 0; i < aspectNames.length; i++) {
            final int idx = i;
            TextView c = chip(aspectNames[i]);
            c.setOnClickListener(v -> {
                for (int k = 0; k < aspectChips.size(); k++) styleChip((TextView) aspectChips.get(k), k == idx);
                canvas.setCropAspect(aspectValue(idx));
            });
            row.addView(c);
            aspectChips.add(c);
        }
        styleChip((TextView) aspectChips.get(0), true);
        findViewById(R.id.btnCropRotate).setOnClickListener(v -> {
            applyCropFromCanvas();
            EditState n = state.copy();
            n.rotateCw();
            commit(n);
            canvas.setMode(EditorCanvasView.MODE_CROP);
        });
        findViewById(R.id.btnCropFlip).setOnClickListener(v -> {
            applyCropFromCanvas();
            EditState n = state.copy();
            n.flipHorizontal();
            commit(n);
            canvas.setMode(EditorCanvasView.MODE_CROP);
        });
        findViewById(R.id.btnCropReset).setOnClickListener(v -> {
            canvas.resetCrop();
            for (int k = 0; k < aspectChips.size(); k++) styleChip((TextView) aspectChips.get(k), k == 0);
            canvas.setCropAspect(0f);
        });
        for (int id : new int[]{R.id.btnCropRotate, R.id.btnCropFlip, R.id.btnCropReset}) {
            Press.attach(findViewById(id), 0.9f);
        }
    }

    private float aspectValue(int idx) {
        switch (idx) {
            case 1: {
                if (preview == null) return 0f;
                float w = state.orientedWidth(preview.getWidth(), preview.getHeight());
                float h = state.orientedHeight(preview.getWidth(), preview.getHeight());
                return w / h;
            }
            case 2: return 1f;
            case 3: return 4f / 5f;
            case 4: return 9f / 16f;
            case 5: return 4f / 3f;
            case 6: return 16f / 9f;
            case 7: return 3f / 2f;
            default: return 0f;
        }
    }

    /** Переносит кадр с холста в состояние (с записью в историю, если изменился). */
    private void applyCropFromCanvas() {
        android.graphics.RectF r = canvas.cropResult();
        boolean same = (r == null && state.crop == null)
                || (r != null && state.crop != null && Math.abs(r.left - state.crop.left) < 1e-4
                && Math.abs(r.top - state.crop.top) < 1e-4 && Math.abs(r.right - state.crop.right) < 1e-4
                && Math.abs(r.bottom - state.crop.bottom) < 1e-4);
        if (same) return;
        EditState n = state.copy();
        n.crop = r;
        commit(n);
    }

    // ---------- Рисование ----------

    private void buildDoodlePanel() {
        LinearLayout tools = findViewById(R.id.rowDoodleTools);
        String[] names = getResources().getStringArray(R.array.editor_brush_names);
        for (int i = 0; i < names.length; i++) {
            final int idx = i;
            TextView c = chip(names[i]);
            c.setOnClickListener(v -> {
                brushTool = idx;
                for (int k = 0; k < doodleToolChips.size(); k++) styleChip((TextView) doodleToolChips.get(k), k == idx);
                applyBrush();
            });
            tools.addView(c);
            doodleToolChips.add(c);
        }
        styleChip((TextView) doodleToolChips.get(0), true);
        LinearLayout colors = findViewById(R.id.rowDoodleColors);
        for (int col : COLORS) {
            View dot = colorDot(col, col == brushColor);
            dot.setOnClickListener(v -> {
                brushColor = col;
                for (View d : doodleColorDots) ((GradientDrawable) d.getBackground()).setStroke(
                        dp(2), ((Integer) d.getTag()) == col ? 0xFFFFFFFF : 0x33FFFFFF);
                if (brushTool == 2) {
                    brushTool = 0;
                    for (int k = 0; k < doodleToolChips.size(); k++) styleChip((TextView) doodleToolChips.get(k), k == 0);
                }
                applyBrush();
            });
            colors.addView(dot);
            doodleColorDots.add(dot);
        }
        seekBrush.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override
            public void onProgressChanged(SeekBar sb, int p, boolean fromUser) {
                applyBrush();
            }

            @Override
            public void onStartTrackingTouch(SeekBar sb) {
            }

            @Override
            public void onStopTrackingTouch(SeekBar sb) {
            }
        });
        findViewById(R.id.btnDoodleUndo).setOnClickListener(v -> {
            if (state.strokes.isEmpty()) return;
            EditState n = state.copy();
            n.strokes.remove(n.strokes.size() - 1);
            commit(n);
        });
        applyBrush();
    }

    private void applyBrush() {
        float width = 0.003f + seekBrush.getProgress() / 100f * 0.06f;
        canvas.setBrush(brushColor, width, brushTool == 2, brushTool == 1);
    }

    // ---------- Текст ----------

    private void buildTextPanel() {
        LinearLayout tools = findViewById(R.id.rowTextTools);
        TextView add = chip(getString(R.string.editor_add));
        add.setOnClickListener(v -> addLabel());
        TextView edit = chip(getString(R.string.editor_edit));
        edit.setOnClickListener(v -> {
            int i = canvas.getSelectedLabel();
            if (i >= 0) editLabelText(i);
        });
        TextView bgT = chip(getString(R.string.editor_backdrop));
        bgT.setOnClickListener(v -> {
            int i = canvas.getSelectedLabel();
            if (i < 0) return;
            EditState n = state.copy();
            n.labels.get(i).background = !n.labels.get(i).background;
            commit(n);
            canvas.setSelectedLabel(i);
        });
        TextView del = chip(getString(R.string.delete));
        del.setOnClickListener(v -> {
            int i = canvas.getSelectedLabel();
            if (i < 0) return;
            EditState n = state.copy();
            n.labels.remove(i);
            commit(n);
            canvas.setSelectedLabel(-1);
            updateTextPanel();
        });
        tools.addView(add);
        tools.addView(edit);
        tools.addView(bgT);
        tools.addView(del);
        styleChip(add, true);
        LinearLayout colors = findViewById(R.id.rowTextColors);
        for (int col : COLORS) {
            View dot = colorDot(col, col == textColor);
            dot.setOnClickListener(v -> {
                textColor = col;
                for (View d : textColorDots) ((GradientDrawable) d.getBackground()).setStroke(
                        dp(2), ((Integer) d.getTag()) == col ? 0xFFFFFFFF : 0x33FFFFFF);
                int i = canvas.getSelectedLabel();
                if (i >= 0) {
                    EditState n = state.copy();
                    n.labels.get(i).color = col;
                    commit(n);
                    canvas.setSelectedLabel(i);
                        }
            });
            colors.addView(dot);
            textColorDots.add(dot);
        }
        seekTextSize.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            EditState before;

            @Override
            public void onProgressChanged(SeekBar sb, int p, boolean fromUser) {
                if (!fromUser) return;
                int i = canvas.getSelectedLabel();
                if (i < 0) return;
                state.labels.get(i).size = 0.02f + p / 100f * 0.2f;
                canvas.invalidate();
            }

            @Override
            public void onStartTrackingTouch(SeekBar sb) {
                before = state.copy();
            }

            @Override
            public void onStopTrackingTouch(SeekBar sb) {
                if (before != null && canvas.getSelectedLabel() >= 0) {
                    int i = canvas.getSelectedLabel();
                    EditState now = state;
                    state = before;
                    commit(now);
                    canvas.setSelectedLabel(i);
                }
                before = null;
            }
        });
    }

    private void addLabel() {
        EditText input = new EditText(this);
        input.setHint(R.string.editor_text_hint);
        input.setMinLines(1);
        FrameLayout wrap = new FrameLayout(this);
        wrap.setPadding(dp(24), dp(8), dp(24), 0);
        wrap.addView(input);
        new MaterialAlertDialogBuilder(this)
                .setTitle(R.string.editor_text_title)
                .setView(wrap)
                .setPositiveButton(R.string.ok, (d, w) -> {
                    String t = input.getText().toString().trim();
                    if (t.isEmpty()) return;
                    EditState n = state.copy();
                    EditState.Label l = new EditState.Label();
                    l.text = t;
                    l.color = textColor;
                    l.size = 0.02f + seekTextSize.getProgress() / 100f * 0.2f;
                    n.labels.add(l);
                    commit(n);
                    canvas.setSelectedLabel(n.labels.size() - 1);
                            updateTextPanel();
                })
                .setNegativeButton(R.string.cancel, null)
                .show();
    }

    private void editLabelText(int index) {
        if (index < 0 || index >= state.labels.size()) return;
        EditText input = new EditText(this);
        input.setText(state.labels.get(index).text);
        input.setSelection(input.getText().length());
        FrameLayout wrap = new FrameLayout(this);
        wrap.setPadding(dp(24), dp(8), dp(24), 0);
        wrap.addView(input);
        new MaterialAlertDialogBuilder(this)
                .setTitle(R.string.editor_text_title)
                .setView(wrap)
                .setPositiveButton(R.string.ok, (d, w) -> {
                    String t = input.getText().toString().trim();
                    EditState n = state.copy();
                    if (t.isEmpty()) n.labels.remove(index);
                    else n.labels.get(index).text = t;
                    commit(n);
                    canvas.setSelectedLabel(t.isEmpty() ? -1 : index);
                            updateTextPanel();
                })
                .setNegativeButton(R.string.cancel, null)
                .show();
    }

    private void updateTextPanel() {
        int i = canvas.getSelectedLabel();
        boolean has = i >= 0 && i < state.labels.size();
        seekTextSize.setEnabled(has);
        if (has) {
            EditState.Label l = state.labels.get(i);
            seekTextSize.setProgress(Math.round((l.size - 0.02f) / 0.2f * 100f));
            textColor = l.color;
            for (View d : textColorDots) ((GradientDrawable) d.getBackground()).setStroke(
                    dp(2), ((Integer) d.getTag()) == l.color ? 0xFFFFFFFF : 0x33FFFFFF);
        }
    }

    // ---------- Сохранение ----------

    private void askSave() {
        if (mode == MODE_CROP) applyCropFromCanvas();
        if (state.isIdentity()) {
            Toasts.makeText(this, R.string.editor_nothing, Toast.LENGTH_SHORT).show();
            return;
        }
        new MaterialAlertDialogBuilder(this)
                .setTitle(R.string.editor_save_title)
                .setItems(new String[]{getString(R.string.editor_save_copy),
                        getString(R.string.editor_overwrite)}, (d, which) -> save(which == 1))
                .setNegativeButton(R.string.cancel, null)
                .show();
    }

    private void save(boolean overwrite) {
        progress.setVisibility(View.VISIBLE);
        final EditState st = state.copy();
        IO.execute(() -> {
            String out = null;
            try {
                Bitmap full = decode(path, 0);
                if (full == null) throw new IllegalStateException("decode");
                Bitmap result = EditRenderer.render(full, st);
                if (result != full) full.recycle();
                File src = new File(path);
                String name = src.getName();
                int dot = name.lastIndexOf('.');
                String base = dot > 0 ? name.substring(0, dot) : name;
                String ext = dot > 0 ? name.substring(dot).toLowerCase(Locale.US) : ".jpg";
                boolean png = ext.equals(".png");
                if (!png && !ext.equals(".jpg") && !ext.equals(".jpeg")) ext = ".jpg";
                File target;
                if (overwrite) {
                    target = src;
                } else {
                    target = com.premiumlab.galleryx.util.Fmt.uniqueFile(src.getParentFile(),
                            base + "_edited" + ext);
                }
                File tmp = new File(target.getParentFile(), "." + target.getName() + ".gxpart");
                try (FileOutputStream fos = new FileOutputStream(tmp)) {
                    result.compress(png ? Bitmap.CompressFormat.PNG : Bitmap.CompressFormat.JPEG,
                            95, fos);
                }
                result.recycle();
                if (overwrite && !src.delete()) {
                    // Продолжаем — rename перезапишет
                }
                if (!tmp.renameTo(target)) throw new IllegalStateException("rename");
                if (!png) copyExif(path, target.getAbsolutePath());
                //noinspection ResultOfMethodCallIgnored
                target.setLastModified(System.currentTimeMillis());
                Scan.files(getApplicationContext(), target.getAbsolutePath());
                MediaEngine.invalidateAll();
                out = target.getAbsolutePath();
            } catch (Throwable t) {
                out = null;
            }
            final String fOut = out;
            main.post(() -> {
                progress.setVisibility(View.GONE);
                if (fOut == null) {
                    Toasts.makeText(this, R.string.editor_save_failed, Toast.LENGTH_SHORT).show();
                    return;
                }
                Toasts.makeText(this, R.string.editor_saved, Toast.LENGTH_SHORT).show();
                Intent data = new Intent();
                data.putExtra(RESULT_PATH, fOut);
                data.putExtra("overwrite", overwrite);
                setResult(RESULT_OK, data);
                finish();
            });
        });
    }

    /** Переносим основные EXIF-поля (дата, камера, GPS), ориентацию сбрасываем. */
    private static void copyExif(String from, String to) {
        try {
            ExifInterface a = new ExifInterface(from);
            ExifInterface b = new ExifInterface(to);
            String[] tags = {ExifInterface.TAG_DATETIME, ExifInterface.TAG_DATETIME_ORIGINAL,
                    ExifInterface.TAG_DATETIME_DIGITIZED, ExifInterface.TAG_MAKE, ExifInterface.TAG_MODEL,
                    ExifInterface.TAG_F_NUMBER, ExifInterface.TAG_EXPOSURE_TIME,
                    ExifInterface.TAG_ISO_SPEED_RATINGS, ExifInterface.TAG_FOCAL_LENGTH,
                    ExifInterface.TAG_GPS_LATITUDE, ExifInterface.TAG_GPS_LATITUDE_REF,
                    ExifInterface.TAG_GPS_LONGITUDE, ExifInterface.TAG_GPS_LONGITUDE_REF};
            for (String t : tags) {
                String v = a.getAttribute(t);
                if (v != null) b.setAttribute(t, v);
            }
            b.setAttribute(ExifInterface.TAG_ORIENTATION,
                    String.valueOf(ExifInterface.ORIENTATION_NORMAL));
            b.saveAttributes();
        } catch (Exception ignored) {
        }
    }

    // ---------- Выход ----------

    /** «Назад»: регистрируется через BackCompat (предиктивный жест, targetSdk 36+). */
    private boolean handleBack() {
        if (undo.isEmpty() && state.isIdentity()) {
            return false;
        }
        new MaterialAlertDialogBuilder(this)
                .setTitle(R.string.editor_discard_title)
                .setMessage(R.string.editor_discard_msg)
                .setPositiveButton(R.string.editor_discard, (d, w) -> finish())
                .setNegativeButton(R.string.cancel, null)
                .show();
        return true;
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        if (preview != null && !preview.isRecycled()) preview.recycle();
    }

    // ---------- UI-помощники ----------

    private TextView chip(String text) {
        TextView t = new TextView(this);
        t.setText(text);
        t.setTextSize(13f);
        t.setMaxLines(1);
        t.setPadding(dp(14), dp(8), dp(14), dp(8));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.setMarginEnd(dp(8));
        t.setLayoutParams(lp);
        styleChip(t, false);
        Press.attach(t, 0.94f);
        return t;
    }

    private void styleChip(TextView t, boolean active) {
        GradientDrawable bg = new GradientDrawable();
        bg.setCornerRadius(dp(999));
        bg.setColor(active ? 0x33B79CFF : 0x1AFFFFFF);
        bg.setStroke(dp(1), active ? 0x80B79CFF : 0x26FFFFFF);
        t.setBackground(bg);
        t.setTextColor(active ? com.premiumlab.galleryx.util.Theming.accent(this) : 0xFFE8E8F0);
    }

    private View colorDot(int color, boolean selected) {
        View v = new View(this);
        int s = dp(30);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(s, s);
        lp.setMarginEnd(dp(10));
        v.setLayoutParams(lp);
        GradientDrawable bg = new GradientDrawable();
        bg.setShape(GradientDrawable.OVAL);
        bg.setColor(color);
        bg.setStroke(dp(2), selected ? 0xFFFFFFFF : 0x33FFFFFF);
        v.setBackground(bg);
        v.setTag(color);
        Press.attach(v, 0.85f);
        return v;
    }

    private int dp(int v) {
        return Math.round(v * getResources().getDisplayMetrics().density);
    }
}
