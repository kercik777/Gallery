package com.premiumlab.galleryx.resizer;

import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.Color;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.os.Bundle;
import android.os.Environment;
import android.os.Handler;
import android.os.Looper;
import android.text.Editable;
import android.text.TextWatcher;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.widget.EditText;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.SeekBar;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.Nullable;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.content.FileProvider;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowInsetsCompat;

import com.google.android.material.dialog.MaterialAlertDialogBuilder;
import com.google.android.material.materialswitch.MaterialSwitch;
import com.premiumlab.galleryx.R;
import com.premiumlab.galleryx.data.MediaEngine;
import com.premiumlab.galleryx.data.Prefs;
import com.premiumlab.galleryx.ui.dialog.OpProgressDialog;
import com.premiumlab.galleryx.util.CrashLog;
import com.premiumlab.galleryx.util.Fmt;
import com.premiumlab.galleryx.util.Press;
import com.premiumlab.galleryx.util.Theming;
import com.premiumlab.galleryx.util.Toasts;

import java.io.File;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Экран «Изменение размера фото»: один файл или пакет. Настройки — в
 * {@link ResizeOptions}, обработка — в {@link ImageResizer}, пакетное
 * сохранение — в {@link ResizeJob}. Оригиналы не изменяются: результат
 * сохраняется копиями в Pictures/GalleryResizer.
 */
public class ResizerActivity extends AppCompatActivity
        implements com.premiumlab.galleryx.ui.EdgeToEdgeHost {

    public static final String EXTRA_PATH = "path";
    public static final String OUT_DIR_NAME = com.premiumlab.galleryx.data.AppDirs.RESIZER_DIR;

    private static final int REQ_PICK = 61;
    private static final int PREVIEW_SIDE = 900;

    private static final int[][] PIXEL_PRESETS = {
            {320, 240}, {640, 480}, {800, 600}, {1024, 768}, {1280, 720},
            {1280, 960}, {1600, 1200}, {1920, 1080}, {2048, 1152}};
    private static final int[] LONG_PRESETS = {640, 800, 1024, 1280, 1600, 1920, 2560};
    private static final int[] DPI_PRESETS = {72, 150, 300, 600};
    private static final long[] TARGET_PRESETS = {0, 50 * 1024L, 100 * 1024L, 200 * 1024L,
            500 * 1024L, 1024 * 1024L, -1};
    private static final float[] ASPECTS = {0f, 1f, 2f / 3f, 3f / 2f, 4f / 3f, 16f / 9f, -1f};

    private final ExecutorService exec = Executors.newSingleThreadExecutor();
    private final Handler main = new Handler(Looper.getMainLooper());
    private final Map<String, ImageResizer.Source> sources = new HashMap<>();

    private final List<String> paths = new ArrayList<>();
    private int cur = 0;
    private ResizeOptions opts = new ResizeOptions();
    private boolean binding;
    private int refreshSerial = 0;
    private boolean saving;
    private String lastSavedSignature;

    // ---- Виджеты ----
    private TextView txtSub, txtIndex, txtSrcInfo, txtResult, txtWarn, txtQuality, txtPrintHint,
            txtFolder, txtNameExample, btnSave, btnPreview;
    private ImageView imgPreview;
    private View progressPreview;
    private LinearLayout rowPresets, rowSizeMode, rowPixelPresets, rowLongPresets, rowUnit, rowDpi,
            rowAspect, rowFit, rowPad, rowFormat, rowTarget, rowJpegBg;
    private View boxPixels, boxPercent, boxLong, boxPrint, boxAspectCustom, boxPad, boxPadColor,
            boxQuality, boxTargetCustom, boxAlpha, txtTargetNote, viewPadSwatch;
    private EditText editW, editH, editPercent, editLong, editPrintW, editPrintH, editDpi,
            editAspW, editAspH, editPadColor, editTargetKb, editName;
    private SeekBar seekPercent, seekQuality;
    private MaterialSwitch switchAspect;

    // =====================================================================
    // Жизненный цикл
    // =====================================================================

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        Theming.apply(this);
        if (Prefs.flagSecure()) {
            getWindow().addFlags(WindowManager.LayoutParams.FLAG_SECURE);
        }
        setContentView(R.layout.activity_resizer);
        bindViews();
        setupControls();

        List<String> given = ResizeSession.take();
        if (given == null) {
            String one = getIntent().getStringExtra(EXTRA_PATH);
            if (one != null) {
                given = new ArrayList<>();
                given.add(one);
            }
        }
        if (given != null && !given.isEmpty()) {
            setPaths(given);
        } else {
            pick();
        }
    }

    @Override
    protected void onDestroy() {
        exec.shutdownNow();
        super.onDestroy();
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, @Nullable Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode == REQ_PICK) {
            List<String> picked = ResizeSession.take();
            if (resultCode == RESULT_OK && picked != null && !picked.isEmpty()) {
                setPaths(picked);
            } else if (paths.isEmpty()) {
                finish();
            }
        }
    }

    private void pick() {
        Intent i = new Intent(this, PickPhotosActivity.class);
        if (!paths.isEmpty()) {
            i.putStringArrayListExtra(PickPhotosActivity.EXTRA_PRESELECTED, new ArrayList<>(paths));
        }
        startActivityForResult(i, REQ_PICK);
    }

    private void setPaths(List<String> list) {
        paths.clear();
        for (String p : list) {
            if (p == null) continue;
            File f = new File(p);
            if (!f.isFile()) continue;
            if (MediaEngine.isVideoName(f.getName())) continue;
            paths.add(p);
        }
        if (paths.isEmpty()) {
            Toasts.makeText(this, R.string.resizer_pick_none, Toast.LENGTH_SHORT).show();
            pick();
            return;
        }
        cur = 0;
        lastSavedSignature = null;
        txtSub.setText(paths.size() == 1 ? new File(paths.get(0)).getName()
                : Fmt.plural(this, R.plurals.resizer_photos_count, paths.size()));
        txtIndex.setVisibility(paths.size() > 1 ? View.VISIBLE : View.GONE);
        onOptionsChanged();
    }

    // =====================================================================
    // Привязка виджетов
    // =====================================================================

    private void bindViews() {
        txtSub = findViewById(R.id.txtRzSub);
        txtIndex = findViewById(R.id.txtRzIndex);
        txtSrcInfo = findViewById(R.id.txtRzSrcInfo);
        txtResult = findViewById(R.id.txtRzResult);
        txtWarn = findViewById(R.id.txtRzWarn);
        txtQuality = findViewById(R.id.txtRzQuality);
        txtPrintHint = findViewById(R.id.txtRzPrintHint);
        txtFolder = findViewById(R.id.txtRzFolder);
        txtNameExample = findViewById(R.id.txtRzNameExample);
        btnSave = findViewById(R.id.btnRzSave);
        btnPreview = findViewById(R.id.btnRzPreview);
        imgPreview = findViewById(R.id.imgRzPreview);
        progressPreview = findViewById(R.id.progressRzPreview);

        rowPresets = findViewById(R.id.rowRzPresets);
        rowSizeMode = findViewById(R.id.rowRzSizeMode);
        rowPixelPresets = findViewById(R.id.rowRzPixelPresets);
        rowLongPresets = findViewById(R.id.rowRzLongPresets);
        rowUnit = findViewById(R.id.rowRzUnit);
        rowDpi = findViewById(R.id.rowRzDpi);
        rowAspect = findViewById(R.id.rowRzAspect);
        rowFit = findViewById(R.id.rowRzFit);
        rowPad = findViewById(R.id.rowRzPad);
        rowFormat = findViewById(R.id.rowRzFormat);
        rowTarget = findViewById(R.id.rowRzTarget);
        rowJpegBg = findViewById(R.id.rowRzJpegBg);

        boxPixels = findViewById(R.id.boxRzPixels);
        boxPercent = findViewById(R.id.boxRzPercent);
        boxLong = findViewById(R.id.boxRzLong);
        boxPrint = findViewById(R.id.boxRzPrint);
        boxAspectCustom = findViewById(R.id.boxRzAspectCustom);
        boxPad = findViewById(R.id.boxRzPad);
        boxPadColor = findViewById(R.id.boxRzPadColor);
        boxQuality = findViewById(R.id.boxRzQuality);
        boxTargetCustom = findViewById(R.id.boxRzTargetCustom);
        boxAlpha = findViewById(R.id.boxRzAlpha);
        txtTargetNote = findViewById(R.id.txtRzTargetNote);
        viewPadSwatch = findViewById(R.id.viewRzPadSwatch);

        editW = findViewById(R.id.editRzW);
        editH = findViewById(R.id.editRzH);
        editPercent = findViewById(R.id.editRzPercent);
        editLong = findViewById(R.id.editRzLong);
        editPrintW = findViewById(R.id.editRzPrintW);
        editPrintH = findViewById(R.id.editRzPrintH);
        editDpi = findViewById(R.id.editRzDpi);
        editAspW = findViewById(R.id.editRzAspW);
        editAspH = findViewById(R.id.editRzAspH);
        editPadColor = findViewById(R.id.editRzPadColor);
        editTargetKb = findViewById(R.id.editRzTargetKb);
        editName = findViewById(R.id.editRzName);
        seekPercent = findViewById(R.id.seekRzPercent);
        seekQuality = findViewById(R.id.seekRzQuality);
        switchAspect = findViewById(R.id.switchRzAspect);

        // Нижняя панель приподнимается над системной навигацией и над клавиатурой
        // (окно не ужимается само: decorFitsSystemWindows = false)
        View root = findViewById(R.id.rootRz);
        ViewCompat.setOnApplyWindowInsetsListener(root, (v, insets) -> {
            int nav = insets.getInsets(WindowInsetsCompat.Type.systemBars()).bottom;
            int ime = insets.getInsets(WindowInsetsCompat.Type.ime()).bottom;
            v.setPadding(v.getPaddingLeft(), v.getPaddingTop(), v.getPaddingRight(),
                    Math.max(nav, ime));
            return WindowInsetsCompat.CONSUMED;
        });
    }

    private void setupControls() {
        ImageView back = findViewById(R.id.btnRzBack);
        ImageView pickBtn = findViewById(R.id.btnRzPick);
        View btnReset = findViewById(R.id.btnRzReset);
        View btnSwap = findViewById(R.id.btnRzSwap);
        for (View v : new View[]{back, pickBtn, btnSwap}) Press.attach(v);
        for (View v : new View[]{btnReset, btnPreview, btnSave, txtIndex}) Press.attach(v, 0.96f);
        back.setOnClickListener(v -> getOnBackPressedDispatcher().onBackPressed());
        pickBtn.setOnClickListener(v -> pick());
        btnReset.setOnClickListener(v -> reset());
        btnPreview.setOnClickListener(v -> fullPreview());
        btnSave.setOnClickListener(v -> save());
        txtIndex.setOnClickListener(v -> {
            if (paths.size() > 1) {
                cur = (cur + 1) % paths.size();
                onOptionsChanged();
            }
        });
        btnSwap.setOnClickListener(v -> {
            int w = parseInt(editW), h = parseInt(editH);
            if (w > 0 && h > 0) {
                opts.width = h;
                opts.height = w;
                bindSize();
                onOptionsChanged();
            }
        });
        txtFolder.setText(getString(R.string.resizer_folder_value, outDirLabel()));

        // --- Быстрые пресеты ---
        String[] presets = {getString(R.string.resizer_preset_whatsapp),
                getString(R.string.resizer_preset_social), getString(R.string.resizer_preset_email),
                getString(R.string.resizer_preset_web), getString(R.string.resizer_preset_print),
                getString(R.string.resizer_preset_gp_icon),
                getString(R.string.resizer_preset_gp_feature),
                getString(R.string.resizer_preset_gp_shot)};
        chips(rowPresets, presets, -1, this::applyQuickPreset);

        // --- Режим размера ---
        chips(rowSizeMode, new String[]{getString(R.string.resizer_mode_pixels),
                getString(R.string.resizer_mode_percent), getString(R.string.resizer_mode_long),
                getString(R.string.resizer_mode_print)}, opts.sizeMode, i -> {
            opts.sizeMode = i;
            bindSize();
            onOptionsChanged();
        });
        String[] px = new String[PIXEL_PRESETS.length + 1];
        px[0] = getString(R.string.resizer_original);
        for (int i = 0; i < PIXEL_PRESETS.length; i++) {
            px[i + 1] = PIXEL_PRESETS[i][0] + "×" + PIXEL_PRESETS[i][1];
        }
        chips(rowPixelPresets, px, 0, i -> {
            if (i == 0) {
                opts.width = 0;
                opts.height = 0;
            } else {
                opts.width = PIXEL_PRESETS[i - 1][0];
                opts.height = PIXEL_PRESETS[i - 1][1];
            }
            bindSize();
            onOptionsChanged();
        });
        watch(editW, s -> {
            int w = parseInt(editW);
            opts.width = Math.max(0, w);
            if (opts.keepAspect) {
                ImageResizer.Source src = currentSource();
                if (src != null && w > 0) {
                    opts.height = (int) Math.max(1, Math.round(w / ImageResizer.canvasAspect(src, opts)));
                    setTextQuiet(editH, String.valueOf(opts.height));
                }
            }
            select(rowPixelPresets, matchPixelPreset());
            onOptionsChanged();
        });
        watch(editH, s -> {
            int h = parseInt(editH);
            opts.height = Math.max(0, h);
            if (opts.keepAspect) {
                ImageResizer.Source src = currentSource();
                if (src != null && h > 0) {
                    opts.width = (int) Math.max(1, Math.round(h * ImageResizer.canvasAspect(src, opts)));
                    setTextQuiet(editW, String.valueOf(opts.width));
                }
            }
            select(rowPixelPresets, matchPixelPreset());
            onOptionsChanged();
        });
        switchAspect.setOnCheckedChangeListener((b, checked) -> {
            if (binding) return;
            if (!checked) {
                // Поля становятся точным размером — фиксируем то, что показано
                int w = parseInt(editW), h = parseInt(editH);
                if (w > 0) opts.width = w;
                if (h > 0) opts.height = h;
            }
            opts.keepAspect = checked;
            bindSize();
            onOptionsChanged();
        });

        // --- Проценты ---
        seekPercent.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override
            public void onProgressChanged(SeekBar sb, int p, boolean fromUser) {
                if (!fromUser || binding) return;
                opts.percent = p + 1;
                setTextQuiet(editPercent, String.valueOf(opts.percent));
                onOptionsChanged();
            }

            @Override
            public void onStartTrackingTouch(SeekBar sb) {
            }

            @Override
            public void onStopTrackingTouch(SeekBar sb) {
            }
        });
        watch(editPercent, s -> {
            int p = parseInt(editPercent);
            if (p >= 1 && p <= 400) {
                opts.percent = p;
                binding = true;
                seekPercent.setProgress(Math.min(99, p - 1));
                binding = false;
                onOptionsChanged();
            }
        });

        // --- Длинная сторона ---
        String[] longs = new String[LONG_PRESETS.length];
        for (int i = 0; i < LONG_PRESETS.length; i++) longs[i] = String.valueOf(LONG_PRESETS[i]);
        chips(rowLongPresets, longs, indexOf(LONG_PRESETS, opts.longSide), i -> {
            opts.longSide = LONG_PRESETS[i];
            setTextQuiet(editLong, String.valueOf(opts.longSide));
            onOptionsChanged();
        });
        watch(editLong, s -> {
            int v = parseInt(editLong);
            if (v > 0) {
                opts.longSide = Math.min(ResizeOptions.MAX_SIDE, v);
                select(rowLongPresets, indexOf(LONG_PRESETS, opts.longSide));
                onOptionsChanged();
            }
        });

        // --- Печать ---
        chips(rowUnit, new String[]{getString(R.string.resizer_unit_mm),
                getString(R.string.resizer_unit_cm), getString(R.string.resizer_unit_inch)},
                opts.printUnit, i -> {
                    if (i == opts.printUnit) return;
                    // Переводим введённые значения в новые единицы
                    float toMmW = toMm(opts.printWidth, opts.printUnit);
                    float toMmH = toMm(opts.printHeight, opts.printUnit);
                    opts.printUnit = i;
                    opts.printWidth = fromMm(toMmW, i);
                    opts.printHeight = fromMm(toMmH, i);
                    bindSize();
                    onOptionsChanged();
                });
        watch(editPrintW, s -> {
            float v = parseFloat(editPrintW);
            if (v > 0) {
                opts.printWidth = v;
                if (opts.keepAspect) {
                    ImageResizer.Source src = currentSource();
                    if (src != null) {
                        opts.printHeight = (float) (v / ImageResizer.canvasAspect(src, opts));
                        setTextQuiet(editPrintH, fmt(opts.printHeight));
                    }
                }
                onOptionsChanged();
            }
        });
        watch(editPrintH, s -> {
            float v = parseFloat(editPrintH);
            if (v > 0) {
                opts.printHeight = v;
                if (opts.keepAspect) {
                    ImageResizer.Source src = currentSource();
                    if (src != null) {
                        opts.printWidth = (float) (v * ImageResizer.canvasAspect(src, opts));
                        setTextQuiet(editPrintW, fmt(opts.printWidth));
                    }
                }
                onOptionsChanged();
            }
        });
        String[] dpis = new String[DPI_PRESETS.length];
        for (int i = 0; i < DPI_PRESETS.length; i++) dpis[i] = String.valueOf(DPI_PRESETS[i]);
        chips(rowDpi, dpis, indexOf(DPI_PRESETS, opts.dpi), i -> {
            opts.dpi = DPI_PRESETS[i];
            setTextQuiet(editDpi, String.valueOf(opts.dpi));
            onOptionsChanged();
        });
        watch(editDpi, s -> {
            int v = parseInt(editDpi);
            if (v >= 10 && v <= 2400) {
                opts.dpi = v;
                select(rowDpi, indexOf(DPI_PRESETS, v));
                onOptionsChanged();
            }
        });

        // --- Пропорции ---
        chips(rowAspect, new String[]{getString(R.string.resizer_aspect_original), "1:1", "2:3",
                "3:2", "4:3", "16:9", getString(R.string.resizer_custom)}, 0, i -> {
            if (ASPECTS[i] >= 0) {
                opts.aspect = ASPECTS[i];
                boxAspectCustom.setVisibility(View.GONE);
            } else {
                boxAspectCustom.setVisibility(View.VISIBLE);
                applyCustomAspect();
            }
            afterAspectChange();
        });
        watch(editAspW, s -> {
            if (boxAspectCustom.getVisibility() == View.VISIBLE) {
                applyCustomAspect();
                afterAspectChange();
            }
        });
        watch(editAspH, s -> {
            if (boxAspectCustom.getVisibility() == View.VISIBLE) {
                applyCustomAspect();
                afterAspectChange();
            }
        });
        chips(rowFit, new String[]{getString(R.string.resizer_fit_crop),
                getString(R.string.resizer_fit_inside), getString(R.string.resizer_fit_stretch)},
                opts.fitMode, i -> {
                    opts.fitMode = i;
                    bindAspect();
                    afterAspectChange();
                });
        chips(rowPad, new String[]{getString(R.string.resizer_pad_blur),
                getString(R.string.resizer_pad_white), getString(R.string.resizer_pad_black),
                getString(R.string.resizer_pad_custom)}, 0, i -> {
            switch (i) {
                case 0:
                    opts.padMode = ResizeOptions.PAD_BLUR;
                    break;
                case 1:
                    opts.padMode = ResizeOptions.PAD_COLOR;
                    opts.padColor = Color.WHITE;
                    break;
                case 2:
                    opts.padMode = ResizeOptions.PAD_COLOR;
                    opts.padColor = Color.BLACK;
                    break;
                default:
                    opts.padMode = ResizeOptions.PAD_COLOR;
                    Integer c = ImageResizer.parseColor(editPadColor.getText().toString());
                    if (c != null) opts.padColor = c;
            }
            boxPadColor.setVisibility(i == 3 ? View.VISIBLE : View.GONE);
            updateSwatch();
            onOptionsChanged();
        });
        watch(editPadColor, s -> {
            Integer c = ImageResizer.parseColor(s);
            if (c != null && boxPadColor.getVisibility() == View.VISIBLE) {
                opts.padColor = c;
                updateSwatch();
                onOptionsChanged();
            }
        });

        // --- Формат и сжатие ---
        chips(rowFormat, new String[]{"JPEG", "PNG", "WebP"}, opts.format, i -> {
            opts.format = i;
            bindFormat();
            onOptionsChanged();
        });
        seekQuality.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override
            public void onProgressChanged(SeekBar sb, int p, boolean fromUser) {
                opts.quality = p + 1;
                txtQuality.setText(String.valueOf(opts.quality));
                if (fromUser && !binding) onOptionsChanged();
            }

            @Override
            public void onStartTrackingTouch(SeekBar sb) {
            }

            @Override
            public void onStopTrackingTouch(SeekBar sb) {
            }
        });
        chips(rowTarget, new String[]{getString(R.string.resizer_target_none), "50 " + kb(),
                "100 " + kb(), "200 " + kb(), "500 " + kb(), "1 " + getString(R.string.resizer_mb),
                getString(R.string.resizer_custom)}, 0, i -> {
            if (TARGET_PRESETS[i] >= 0) {
                opts.targetBytes = TARGET_PRESETS[i];
                boxTargetCustom.setVisibility(View.GONE);
            } else {
                boxTargetCustom.setVisibility(View.VISIBLE);
                int v = parseInt(editTargetKb);
                opts.targetBytes = v > 0 ? v * 1024L : 0;
            }
            txtTargetNote.setVisibility(opts.targetBytes > 0 ? View.VISIBLE : View.GONE);
            onOptionsChanged();
        });
        watch(editTargetKb, s -> {
            if (boxTargetCustom.getVisibility() != View.VISIBLE) return;
            int v = parseInt(editTargetKb);
            opts.targetBytes = v > 0 ? v * 1024L : 0;
            txtTargetNote.setVisibility(opts.targetBytes > 0 ? View.VISIBLE : View.GONE);
            onOptionsChanged();
        });
        chips(rowJpegBg, new String[]{getString(R.string.resizer_pad_white),
                getString(R.string.resizer_pad_black), getString(R.string.resizer_bg_gray)}, 0, i -> {
            opts.jpegBackground = i == 0 ? Color.WHITE : i == 1 ? Color.BLACK : 0xFF808080;
            onOptionsChanged();
        });

        // --- Имя файла ---
        watch(editName, s -> {
            opts.namePattern = s.trim().isEmpty() ? "{name}_{w}x{h}" : s;
            lastSavedSignature = null;
            updateNameExample();
        });

        bindAll();
    }

    // =====================================================================
    // Привязка значений к виджетам
    // =====================================================================

    private void bindAll() {
        binding = true;
        try {
            select(rowSizeMode, opts.sizeMode);
            select(rowFormat, opts.format);
            select(rowFit, opts.fitMode);
            int ai = -1;
            for (int i = 0; i < ASPECTS.length; i++) {
                if (ASPECTS[i] >= 0 && Math.abs(ASPECTS[i] - opts.aspect) < 0.001f) ai = i;
            }
            if (ai < 0 && opts.aspect > 0) {
                ai = ASPECTS.length - 1;
                boxAspectCustom.setVisibility(View.VISIBLE);
                float fw = parseFloat(editAspW), fh = parseFloat(editAspH);
                if (fw <= 0 || fh <= 0 || Math.abs(fw / fh - opts.aspect) > 0.001f) {
                    int[] parts = ratioParts(opts.aspect);
                    setTextQuiet(editAspW, String.valueOf(parts[0]));
                    setTextQuiet(editAspH, String.valueOf(parts[1]));
                }
            } else {
                boxAspectCustom.setVisibility(View.GONE);
                if (ai < 0) ai = 0;
            }
            select(rowAspect, ai);
            int pi = opts.padMode == ResizeOptions.PAD_BLUR ? 0
                    : opts.padColor == Color.WHITE ? 1 : opts.padColor == Color.BLACK ? 2 : 3;
            select(rowPad, pi);
            boxPadColor.setVisibility(pi == 3 ? View.VISIBLE : View.GONE);
            if (pi == 3) editPadColor.setText(String.format(Locale.US, "#%06X", opts.padColor & 0xFFFFFF));
            updateSwatch();
            int ti = -1;
            for (int i = 0; i < TARGET_PRESETS.length; i++) {
                if (TARGET_PRESETS[i] == opts.targetBytes) ti = i;
            }
            if (ti < 0) {
                ti = TARGET_PRESETS.length - 1;
                boxTargetCustom.setVisibility(View.VISIBLE);
                editTargetKb.setText(String.valueOf(opts.targetBytes / 1024));
            } else {
                boxTargetCustom.setVisibility(View.GONE);
            }
            select(rowTarget, ti);
            txtTargetNote.setVisibility(opts.targetBytes > 0 ? View.VISIBLE : View.GONE);
            seekQuality.setProgress(Math.max(0, Math.min(99, opts.quality - 1)));
            txtQuality.setText(String.valueOf(opts.quality));
            select(rowJpegBg, opts.jpegBackground == Color.WHITE ? 0
                    : opts.jpegBackground == Color.BLACK ? 1 : 2);
            select(rowPresets, -1);
            editName.setText(opts.namePattern);
            bindSize();
            bindAspect();
            bindFormat();
        } finally {
            binding = false;
        }
    }

    /** Блок «Размер»: показывает активный режим и значения. */
    private void bindSize() {
        boolean was = binding;
        binding = true;
        try {
            select(rowSizeMode, opts.sizeMode);
            boxPixels.setVisibility(opts.sizeMode == ResizeOptions.SIZE_PIXELS ? View.VISIBLE : View.GONE);
            boxPercent.setVisibility(opts.sizeMode == ResizeOptions.SIZE_PERCENT ? View.VISIBLE : View.GONE);
            boxLong.setVisibility(opts.sizeMode == ResizeOptions.SIZE_LONG_SIDE ? View.VISIBLE : View.GONE);
            boxPrint.setVisibility(opts.sizeMode == ResizeOptions.SIZE_PRINT ? View.VISIBLE : View.GONE);
            switchAspect.setChecked(opts.keepAspect);
            ImageResizer.Source src = currentSource();
            if (opts.sizeMode == ResizeOptions.SIZE_PIXELS) {
                if (opts.width <= 0 && opts.height <= 0) {
                    // «Оригинал»: показываем реальные размеры холста
                    if (src != null) {
                        int[] t = ImageResizer.targetSize(src, opts);
                        setTextQuiet(editW, String.valueOf(t[0]));
                        setTextQuiet(editH, String.valueOf(t[1]));
                    } else {
                        setTextQuiet(editW, "");
                        setTextQuiet(editH, "");
                    }
                } else if (opts.keepAspect && src != null) {
                    int[] t = ImageResizer.targetSize(src, opts);
                    setTextQuiet(editW, String.valueOf(t[0]));
                    setTextQuiet(editH, String.valueOf(t[1]));
                } else {
                    setTextQuiet(editW, opts.width > 0 ? String.valueOf(opts.width) : "");
                    setTextQuiet(editH, opts.height > 0 ? String.valueOf(opts.height) : "");
                }
                select(rowPixelPresets, matchPixelPreset());
            }
            seekPercent.setProgress(Math.max(0, Math.min(99, opts.percent - 1)));
            setTextQuiet(editPercent, String.valueOf(opts.percent));
            setTextQuiet(editLong, String.valueOf(opts.longSide));
            select(rowLongPresets, indexOf(LONG_PRESETS, opts.longSide));
            select(rowUnit, opts.printUnit);
            setTextQuiet(editPrintW, fmt(opts.printWidth));
            setTextQuiet(editPrintH, fmt(opts.printHeight));
            setTextQuiet(editDpi, String.valueOf(opts.dpi));
            select(rowDpi, indexOf(DPI_PRESETS, opts.dpi));
            updatePrintHint();
        } finally {
            binding = was;
        }
    }

    private void bindAspect() {
        select(rowFit, opts.fitMode);
        boxPad.setVisibility(opts.fitMode == ResizeOptions.FIT_INSIDE ? View.VISIBLE : View.GONE);
    }

    private void bindFormat() {
        select(rowFormat, opts.format);
        boxQuality.setVisibility(opts.format == ResizeOptions.FMT_PNG ? View.GONE : View.VISIBLE);
        ImageResizer.Source src = currentSource();
        boolean alphaWarn = src != null && src.hasAlpha && opts.format == ResizeOptions.FMT_JPEG;
        boxAlpha.setVisibility(alphaWarn ? View.VISIBLE : View.GONE);
    }

    private void applyCustomAspect() {
        float w = parseFloat(editAspW), h = parseFloat(editAspH);
        opts.aspect = (w > 0 && h > 0) ? w / h : 0f;
    }

    private void afterAspectChange() {
        // При сохранении пропорций поля ширины/высоты зависят от пропорции холста
        bindSize();
        onOptionsChanged();
    }

    private void updateSwatch() {
        GradientDrawable d = new GradientDrawable();
        d.setShape(GradientDrawable.OVAL);
        d.setColor(opts.padMode == ResizeOptions.PAD_COLOR ? opts.padColor : Color.TRANSPARENT);
        d.setStroke(Math.round(getResources().getDisplayMetrics().density),
                getColor(R.color.glassStroke));
        viewPadSwatch.setBackground(d);
    }

    private void updatePrintHint() {
        if (opts.sizeMode != ResizeOptions.SIZE_PRINT) return;
        int w = opts.printPixels(opts.printWidth), h = opts.printPixels(opts.printHeight);
        txtPrintHint.setText(getString(R.string.resizer_print_hint, w, h));
    }

    private void updateNameExample() {
        ImageResizer.Source src = currentSource();
        String name = src != null ? src.name : (paths.isEmpty() ? "photo.jpg" : new File(paths.get(cur)).getName());
        int w = 0, h = 0;
        if (src != null) {
            int[] t = ImageResizer.targetSize(src, opts);
            w = t[0];
            h = t[1];
        }
        txtNameExample.setText(getString(R.string.resizer_name_example,
                ImageResizer.buildName(opts.namePattern, name, w, h, 1) + opts.extension()));
    }

    private int matchPixelPreset() {
        if (opts.width <= 0 && opts.height <= 0) return 0;
        for (int i = 0; i < PIXEL_PRESETS.length; i++) {
            int[] p = PIXEL_PRESETS[i];
            if ((p[0] == opts.width && p[1] == opts.height) || (p[1] == opts.width && p[0] == opts.height)) {
                return i + 1;
            }
        }
        return -1;
    }

    // =====================================================================
    // Быстрые пресеты
    // =====================================================================

    private void applyQuickPreset(int i) {
        ResizeOptions o = new ResizeOptions();
        o.namePattern = opts.namePattern;
        switch (i) {
            case 0: // WhatsApp
                o.sizeMode = ResizeOptions.SIZE_LONG_SIDE;
                o.longSide = 1600;
                o.format = ResizeOptions.FMT_JPEG;
                o.quality = 80;
                break;
            case 1: // Соцсети
                o.sizeMode = ResizeOptions.SIZE_LONG_SIDE;
                o.longSide = 1080;
                o.format = ResizeOptions.FMT_JPEG;
                o.quality = 90;
                break;
            case 2: // E-mail
                o.sizeMode = ResizeOptions.SIZE_LONG_SIDE;
                o.longSide = 1024;
                o.format = ResizeOptions.FMT_JPEG;
                o.quality = 85;
                o.targetBytes = 300 * 1024L;
                break;
            case 3: // Веб
                o.sizeMode = ResizeOptions.SIZE_LONG_SIDE;
                o.longSide = 1920;
                o.format = ResizeOptions.FMT_WEBP;
                o.quality = 80;
                break;
            case 4: // Печать 10×15
                o.sizeMode = ResizeOptions.SIZE_PRINT;
                o.printUnit = ResizeOptions.UNIT_MM;
                o.printWidth = 150f;
                o.printHeight = 100f;
                o.dpi = 300;
                o.aspect = 3f / 2f;
                o.fitMode = ResizeOptions.FIT_CROP;
                o.format = ResizeOptions.FMT_JPEG;
                o.quality = 95;
                break;
            case 5: // Google Play: иконка 512×512, 32-бит PNG (прозрачность сохраняется), ≤ 1 МБ
                o.sizeMode = ResizeOptions.SIZE_PIXELS;
                o.width = 512;
                o.height = 512;
                o.keepAspect = false;
                o.aspect = 1f;
                o.fitMode = ResizeOptions.FIT_CROP;
                o.format = ResizeOptions.FMT_PNG;
                break;
            case 6: // Google Play: feature graphic 1024×500, JPEG без прозрачности, ≤ 1 МБ
                o.sizeMode = ResizeOptions.SIZE_PIXELS;
                o.width = 1024;
                o.height = 500;
                o.keepAspect = false;
                o.aspect = 1024f / 500f;
                o.fitMode = ResizeOptions.FIT_CROP;
                o.format = ResizeOptions.FMT_JPEG;
                o.quality = 92;
                o.targetBytes = 1024 * 1024L;
                break;
            default: // Google Play: скриншот телефона — в рамку 1080×1920 (рамка
                // поворачивается под ориентацию), стороны 320…3840, PNG без потерь
                o.sizeMode = ResizeOptions.SIZE_PIXELS;
                o.width = 1080;
                o.height = 1920;
                o.keepAspect = true;
                o.format = ResizeOptions.FMT_PNG;
        }
        opts = o;
        bindAll();
        select(rowPresets, i);
        onOptionsChanged();
    }

    private void reset() {
        opts = new ResizeOptions();
        bindAll();
        lastSavedSignature = null;
        onOptionsChanged();
        Toasts.makeText(this, R.string.resizer_reset_done, Toast.LENGTH_SHORT).show();
    }

    // =====================================================================
    // Предпросмотр и расчёт
    // =====================================================================

    @Nullable
    private ImageResizer.Source currentSource() {
        if (paths.isEmpty()) return null;
        return sources.get(paths.get(cur));
    }

    /** Любое изменение настроек: сбрасываем «уже сохранено» и пересчитываем превью. */
    private void onOptionsChanged() {
        if (binding) return;
        lastSavedSignature = null;
        updatePrintHint();
        updateNameExample();
        scheduleRefresh();
    }

    private final Runnable refreshRunnable = this::refreshPreview;

    private void scheduleRefresh() {
        main.removeCallbacks(refreshRunnable);
        main.postDelayed(refreshRunnable, 220);
    }

    private void refreshPreview() {
        if (paths.isEmpty() || isFinishing()) return;
        final String path = paths.get(cur);
        final ResizeOptions o = opts.copy();
        final int serial = ++refreshSerial;
        final boolean needProbe = !sources.containsKey(path);
        progressPreview.setVisibility(View.VISIBLE);
        txtIndex.setText(getString(R.string.resizer_index, cur + 1, paths.size()));
        exec.execute(() -> {
            ImageResizer.Source src = null;
            ImageResizer.Result r = null;
            int err = 0;
            try {
                src = sources.get(path);
                if (src == null) src = ImageResizer.probe(path);
                r = ImageResizer.render(src, o, PREVIEW_SIDE, null);
            } catch (ImageResizer.ResizeException e) {
                err = e.code;
            } catch (Throwable t) {
                CrashLog.record("Resizer.preview", t);
                err = ImageResizer.ResizeException.UNSUPPORTED;
            }
            final ImageResizer.Source fs = src;
            final ImageResizer.Result fr = r;
            final int ferr = err;
            final Bitmap shown = fr != null ? ImageResizer.decodeForDisplay(fr.data, PREVIEW_SIDE) : null;
            main.post(() -> {
                if (isFinishing() || serial != refreshSerial) return;
                progressPreview.setVisibility(View.GONE);
                if (fs != null) {
                    sources.put(path, fs);
                    txtSrcInfo.setText(getString(R.string.resizer_src_info, fs.width, fs.height,
                            Fmt.size(fs.bytes), formatLabel(fs.mime)));
                    if (needProbe) {
                        // Теперь известны пропорции — поля размеров можно заполнить
                        bindSize();
                        bindFormat();
                        updateNameExample();
                    }
                }
                if (fr != null && shown != null) {
                    imgPreview.setImageBitmap(shown);
                    showEstimate(fs, fr, o);
                } else {
                    imgPreview.setImageDrawable(null);
                    txtResult.setText(getString(R.string.resizer_result_error));
                    txtWarn.setText(errorText(ferr));
                    txtWarn.setVisibility(View.VISIBLE);
                }
            });
        });
    }

    /** Строка «Результат: W×H · ≈ размер · формат» по уменьшенному предпросмотру. */
    private void showEstimate(ImageResizer.Source src, ImageResizer.Result r, ResizeOptions o) {
        int[] t = ImageResizer.targetSize(src, o);
        long estimate;
        double area = (double) t[0] * t[1] / Math.max(1, (double) r.width * r.height);
        estimate = Math.round(r.bytes() * area);
        if (o.targetBytes > 0 && estimate > o.targetBytes && o.format != ResizeOptions.FMT_PNG) {
            // При сохранении качество будет подобрано под цель
            estimate = o.targetBytes;
        }
        txtResult.setText(getString(R.string.resizer_result_estimate, t[0], t[1],
                Fmt.size(estimate), formatName(o.format)));
        StringBuilder warn = new StringBuilder();
        if (t[0] > src.width || t[1] > src.height) {
            warn.append(getString(R.string.resizer_warn_upscale));
        }
        if (o.targetBytes > 0 && o.format == ResizeOptions.FMT_PNG && estimate > o.targetBytes) {
            if (warn.length() > 0) warn.append('\n');
            warn.append(getString(R.string.resizer_warn_png_target));
        }
        if (src.hasAlpha && o.format == ResizeOptions.FMT_JPEG) {
            if (warn.length() > 0) warn.append('\n');
            warn.append(getString(R.string.resizer_alpha_warn));
        }
        txtWarn.setText(warn);
        txtWarn.setVisibility(warn.length() > 0 ? View.VISIBLE : View.GONE);
    }

    /** Полный просчёт текущего файла: точный размер и качество, картинка результата. */
    private void fullPreview() {
        if (paths.isEmpty() || saving) return;
        final String path = paths.get(cur);
        final ResizeOptions o = opts.copy();
        final ResizeJob cancelFlag = new ResizeJob();
        OpProgressDialog dlg = OpProgressDialog.show(this, getString(R.string.resizer_preview),
                cancelFlag::cancel);
        dlg.onProgress(0, 0, new File(path).getName(), 0);
        exec.execute(() -> {
            ImageResizer.Result r = null;
            ImageResizer.Source src = null;
            int err = 0;
            try {
                src = sources.get(path);
                if (src == null) src = ImageResizer.probe(path);
                r = ImageResizer.render(src, o, 0, cancelFlag::isCancelled);
            } catch (ImageResizer.ResizeException e) {
                err = e.code;
            } catch (Throwable t) {
                CrashLog.record("Resizer.fullPreview", t);
                err = ImageResizer.ResizeException.UNSUPPORTED;
            }
            final ImageResizer.Result fr = r;
            final ImageResizer.Source fs = src;
            final int ferr = err;
            final Bitmap shown = fr != null ? ImageResizer.decodeForDisplay(fr.data, PREVIEW_SIDE) : null;
            main.post(() -> {
                dlg.dismiss();
                if (isFinishing()) return;
                if (fr == null || fs == null) {
                    if (ferr != ImageResizer.ResizeException.CANCELLED) {
                        Toasts.makeText(this, errorText(ferr), Toast.LENGTH_LONG).show();
                    }
                    return;
                }
                refreshSerial++; // отменяем фоновую оценку, чтобы не перекрыла точный результат
                if (shown != null) imgPreview.setImageBitmap(shown);
                String line = getString(R.string.resizer_result_exact, fr.width, fr.height,
                        Fmt.size(fr.bytes()), formatName(o.format));
                if (o.format != ResizeOptions.FMT_PNG) {
                    line += " · " + getString(R.string.resizer_quality_value, fr.quality);
                }
                txtResult.setText(line);
                StringBuilder warn = new StringBuilder();
                if (fr.targetMissed) {
                    warn.append(getString(R.string.resizer_warn_target_missed,
                            Fmt.size(o.targetBytes), Fmt.size(fr.bytes())));
                }
                if (fs.hasAlpha && o.format == ResizeOptions.FMT_JPEG) {
                    if (warn.length() > 0) warn.append('\n');
                    warn.append(getString(R.string.resizer_alpha_warn));
                }
                txtWarn.setText(warn);
                txtWarn.setVisibility(warn.length() > 0 ? View.VISIBLE : View.GONE);
                long saved = fs.bytes - fr.bytes();
                Toasts.makeText(this, saved > 0
                        ? getString(R.string.resizer_preview_saved, Fmt.size(fs.bytes),
                        Fmt.size(fr.bytes()), Math.round(100.0 * saved / Math.max(1, fs.bytes)))
                        : getString(R.string.resizer_preview_bigger, Fmt.size(fs.bytes),
                        Fmt.size(fr.bytes())), Toast.LENGTH_LONG).show();
            });
        });
    }

    // =====================================================================
    // Сохранение
    // =====================================================================

    /**
     * Папка результатов: внутри выбранной корневой папки (тогда она — обычный
     * альбом приложения), а без корня — Pictures/GalleryResizer.
     */
    private File outDir() {
        String root = com.premiumlab.galleryx.data.Prefs.rootPath();
        if (root != null && new File(root).isDirectory()) {
            return new File(root, OUT_DIR_NAME);
        }
        File pics = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_PICTURES);
        return new File(pics, OUT_DIR_NAME);
    }

    /** Короткое имя папки результатов для подписей: «Корень/GalleryResizer». */
    private String outDirLabel() {
        File d = outDir();
        File parent = d.getParentFile();
        return (parent != null ? parent.getName() + "/" : "") + d.getName();
    }

    private void save() {
        if (paths.isEmpty() || saving) return;
        String sig = opts.signature() + "#" + paths.hashCode();
        if (sig.equals(lastSavedSignature)) {
            new MaterialAlertDialogBuilder(this)
                    .setTitle(R.string.resizer_already_saved_title)
                    .setMessage(R.string.resizer_already_saved_msg)
                    .setPositiveButton(R.string.resizer_save_again, (d, w) -> doSave(sig))
                    .setNegativeButton(R.string.cancel, null)
                    .show();
            return;
        }
        doSave(sig);
    }

    private void doSave(String sig) {
        File dir = outDir();
        if (!dir.isDirectory() && !dir.mkdirs()) {
            Toasts.makeText(this, getString(R.string.resizer_err_folder, outDirLabel()),
                    Toast.LENGTH_LONG).show();
            return;
        }
        saving = true;
        btnSave.setEnabled(false);
        btnSave.setAlpha(0.6f);
        final ResizeJob job = new ResizeJob();
        final OpProgressDialog dlg = OpProgressDialog.show(this,
                getString(R.string.resizer_op_title), job::cancel);
        final List<String> list = new ArrayList<>(paths);
        job.start(this, list, opts, dir, new ResizeJob.Listener() {
            @Override
            public void onProgress(int done, int total, String name) {
                dlg.onProgress(done, total, name, total == 0 ? 0 : done * 100 / total);
            }

            @Override
            public void onFinished(ResizeJob.Summary s) {
                dlg.dismiss();
                saving = false;
                btnSave.setEnabled(true);
                btnSave.setAlpha(1f);
                if (isFinishing() || isDestroyed()) return;
                if (s.okCount() > 0) lastSavedSignature = sig;
                showSummary(s);
            }
        });
    }

    private void showSummary(ResizeJob.Summary s) {
        int total = s.items.size();
        int ok = s.okCount();
        StringBuilder msg = new StringBuilder();
        msg.append(getString(R.string.resizer_sum_saved, ok, Math.max(total, paths.size())));
        if (s.cancelled) msg.append('\n').append(getString(R.string.resizer_sum_cancelled));
        if (ok > 0) {
            msg.append('\n').append(getString(R.string.resizer_folder_value,
                    s.dir != null ? s.dir.getAbsolutePath() : outDirLabel()));
            long before = s.srcBytes(), after = s.outBytes();
            int pct = before > 0 ? (int) Math.round(100.0 * (before - after) / before) : 0;
            msg.append('\n').append(getString(R.string.resizer_sum_size, Fmt.size(before),
                    Fmt.size(after), pct));
            if (ok == 1) {
                for (ResizeJob.Item it : s.items) {
                    if (it.ok()) {
                        msg.append('\n').append(getString(R.string.resizer_sum_dims, it.width, it.height));
                    }
                }
            }
        }
        int missed = s.missedCount();
        if (missed > 0) {
            msg.append("\n\n").append(getString(R.string.resizer_sum_missed, missed,
                    Fmt.size(opts.targetBytes)));
        }
        if (s.failCount() > 0) {
            msg.append("\n\n").append(getString(R.string.resizer_sum_failed, s.failCount()));
            Map<Integer, Integer> byCode = new HashMap<>();
            for (ResizeJob.Item it : s.items) {
                if (!it.ok()) {
                    Integer n = byCode.get(it.errorCode);
                    byCode.put(it.errorCode, n == null ? 1 : n + 1);
                }
            }
            for (Map.Entry<Integer, Integer> e : byCode.entrySet()) {
                msg.append('\n').append("• ").append(errorText(e.getKey()))
                        .append(" — ").append(e.getValue());
            }
        }
        MaterialAlertDialogBuilder b = new MaterialAlertDialogBuilder(this)
                .setTitle(ok > 0 ? R.string.resizer_sum_title_ok : R.string.resizer_sum_title_fail)
                .setMessage(msg)
                .setNegativeButton(R.string.close, null);
        final List<String> outs = s.outPaths();
        if (!outs.isEmpty()) {
            b.setPositiveButton(R.string.action_share, (d, w) -> share(outs));
            b.setNeutralButton(R.string.resizer_open_folder, (d, w) -> openFolder(s.dir));
        }
        b.show();
    }

    private void share(List<String> outs) {
        try {
            ArrayList<Uri> uris = new ArrayList<>();
            for (String p : outs) {
                uris.add(FileProvider.getUriForFile(this, getPackageName() + ".fileprovider", new File(p)));
            }
            Intent i;
            if (uris.size() == 1) {
                i = new Intent(Intent.ACTION_SEND);
                i.putExtra(Intent.EXTRA_STREAM, uris.get(0));
            } else {
                i = new Intent(Intent.ACTION_SEND_MULTIPLE);
                i.putParcelableArrayListExtra(Intent.EXTRA_STREAM, uris);
            }
            i.setType(opts.mime());
            i.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
            startActivity(Intent.createChooser(i, getString(R.string.action_share)));
        } catch (Exception e) {
            Toasts.makeText(this, R.string.no_app_to_open, Toast.LENGTH_SHORT).show();
        }
    }

    private void openFolder(File dir) {
        if (dir == null) return;
        Intent i = new Intent(this, com.premiumlab.galleryx.FolderActivity.class);
        i.putExtra("path", dir.getAbsolutePath());
        i.putExtra("name", dir.getName());
        startActivity(i);
    }

    // =====================================================================
    // Вспомогательное
    // =====================================================================

    private interface OnChip {
        void pick(int index);
    }

    /** Строит ряд «стеклянных» чипов; выбранный подсвечивается акцентом. */
    private void chips(LinearLayout row, String[] labels, int selected, OnChip cb) {
        row.removeAllViews();
        LayoutInflater inf = LayoutInflater.from(this);
        for (int i = 0; i < labels.length; i++) {
            TextView chip = (TextView) inf.inflate(R.layout.item_chip_subdir, row, false);
            chip.setText(labels[i]);
            final int idx = i;
            chip.setOnClickListener(v -> {
                select(row, idx);
                cb.pick(idx);
            });
            Press.attach(chip, 0.95f);
            row.addView(chip);
        }
        select(row, selected);
    }

    private void select(LinearLayout row, int idx) {
        int accent = Theming.accent(this);
        int secondary = getColor(R.color.textSecondary);
        for (int i = 0; i < row.getChildCount(); i++) {
            View c = row.getChildAt(i);
            boolean on = i == idx;
            c.setBackgroundResource(on ? R.drawable.bg_chip_active : R.drawable.bg_chip);
            if (c instanceof TextView) ((TextView) c).setTextColor(on ? accent : secondary);
        }
    }

    private interface OnText {
        void changed(String s);
    }

    private void watch(EditText e, OnText cb) {
        e.addTextChangedListener(new TextWatcher() {
            @Override
            public void beforeTextChanged(CharSequence s, int a, int b, int c) {
            }

            @Override
            public void onTextChanged(CharSequence s, int a, int b, int c) {
            }

            @Override
            public void afterTextChanged(Editable s) {
                if (binding) return;
                cb.changed(s.toString());
            }
        });
    }

    private void setTextQuiet(EditText e, String text) {
        if (e.getText().toString().equals(text)) return;
        boolean was = binding;
        binding = true;
        e.setText(text);
        if (e.hasFocus()) e.setSelection(e.getText().length());
        binding = was;
    }

    private static int parseInt(EditText e) {
        try {
            return Integer.parseInt(e.getText().toString().trim());
        } catch (Exception ex) {
            return 0;
        }
    }

    private static float parseFloat(EditText e) {
        try {
            return Float.parseFloat(e.getText().toString().trim().replace(',', '.'));
        } catch (Exception ex) {
            return 0f;
        }
    }

    private static String fmt(float v) {
        if (Math.abs(v - Math.round(v)) < 0.005f) return String.valueOf(Math.round(v));
        return String.format(Locale.US, "%.1f", v);
    }

    /** Подбирает небольшие целые W:H для пропорции (1024×500 → 256:125). */
    private static int[] ratioParts(float aspect) {
        for (int h = 1; h <= 2000; h++) {
            double w = aspect * h;
            if (Math.abs(w - Math.round(w)) < 0.0005) {
                return new int[]{(int) Math.round(w), h};
            }
        }
        return new int[]{Math.round(aspect * 1000f), 1000};
    }

    private static int indexOf(int[] arr, int v) {
        for (int i = 0; i < arr.length; i++) if (arr[i] == v) return i;
        return -1;
    }

    private static float toMm(float v, int unit) {
        switch (unit) {
            case ResizeOptions.UNIT_CM:
                return v * 10f;
            case ResizeOptions.UNIT_INCH:
                return v * 25.4f;
            default:
                return v;
        }
    }

    private static float fromMm(float mm, int unit) {
        switch (unit) {
            case ResizeOptions.UNIT_CM:
                return Math.round(mm / 10f * 10f) / 10f;
            case ResizeOptions.UNIT_INCH:
                return Math.round(mm / 25.4f * 100f) / 100f;
            default:
                return Math.round(mm);
        }
    }

    private String kb() {
        return getString(R.string.resizer_kb);
    }

    private String formatName(int format) {
        switch (format) {
            case ResizeOptions.FMT_PNG:
                return "PNG";
            case ResizeOptions.FMT_WEBP:
                return "WebP";
            default:
                return "JPEG";
        }
    }

    private static String formatLabel(String mime) {
        if (mime == null) return "";
        String m = mime.toLowerCase(Locale.US);
        if (m.contains("jpeg") || m.contains("jpg")) return "JPEG";
        if (m.contains("png")) return "PNG";
        if (m.contains("webp")) return "WebP";
        if (m.contains("gif")) return "GIF";
        if (m.contains("heic") || m.contains("heif")) return "HEIC";
        if (m.contains("bmp")) return "BMP";
        int slash = m.indexOf('/');
        return slash >= 0 ? m.substring(slash + 1).toUpperCase(Locale.US) : m.toUpperCase(Locale.US);
    }

    private String errorText(int code) {
        switch (code) {
            case ImageResizer.ResizeException.TOO_LARGE:
                return getString(R.string.resizer_err_too_large);
            case ImageResizer.ResizeException.OUT_OF_MEMORY:
                return getString(R.string.resizer_err_memory);
            case ImageResizer.ResizeException.WRITE_FAILED:
                return getString(R.string.resizer_err_write);
            case ImageResizer.ResizeException.NO_SPACE:
                return getString(R.string.resizer_err_space);
            case ImageResizer.ResizeException.CANCELLED:
                return getString(R.string.op_cancelled);
            default:
                return getString(R.string.resizer_err_unsupported);
        }
    }
}
