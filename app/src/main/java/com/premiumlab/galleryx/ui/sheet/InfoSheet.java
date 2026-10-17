package com.premiumlab.galleryx.ui.sheet;

import com.premiumlab.galleryx.util.Toasts;
import android.app.Dialog;
import android.content.Context;
import android.content.Intent;
import android.graphics.BitmapFactory;
import android.media.ExifInterface;
import android.media.MediaMetadataRetriever;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.google.android.material.bottomsheet.BottomSheetBehavior;
import com.google.android.material.bottomsheet.BottomSheetDialog;
import com.google.android.material.bottomsheet.BottomSheetDialogFragment;
import com.premiumlab.galleryx.R;
import com.premiumlab.galleryx.data.MediaItem;
import com.premiumlab.galleryx.ui.dialog.EditMetaDialog;
import com.premiumlab.galleryx.util.Fmt;
import com.premiumlab.galleryx.util.Press;

import java.io.File;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Шторка «Свойства файла»: заголовок с бейджем формата, карточка «Файл»
 * (разрешение · мегапиксели/длительность · размер), карточка «Камера» (EXIF:
 * модель, диафрагма, выдержка, ISO, фокусное), «Место съёмки» (координаты + Карты)
 * и строки «Альбом», «Путь» (тап — копировать).
 */
public class InfoSheet extends BottomSheetDialogFragment {

    private static final ExecutorService IO = Executors.newSingleThreadExecutor();
    private static final Handler MAIN = new Handler(Looper.getMainLooper());

    private MediaItem item;
    private View content;
    private Meta lastMeta;

    public void setItem(MediaItem item) {
        this.item = item;
    }

    /** Результат фонового чтения метаданных. */
    private static class Meta {
        int width, height;
        long duration;
        long dateTaken;
        String make, model, aperture, shutter, iso, focal;
        double lat = Double.NaN, lng = Double.NaN;

        boolean hasCamera() {
            return model != null || aperture != null || shutter != null
                    || iso != null || focal != null;
        }

        boolean hasLocation() {
            return !Double.isNaN(lat) && !Double.isNaN(lng);
        }
    }

    @NonNull
    @Override
    public Dialog onCreateDialog(Bundle savedInstanceState) {
        BottomSheetDialog dialog = (BottomSheetDialog) super.onCreateDialog(savedInstanceState);
        if (item == null) {
            // Пересоздание после смерти процесса — данных нет
            dismissAllowingStateLoss();
            return dialog;
        }
        Context ctx = requireContext();
        View v = LayoutInflater.from(ctx).inflate(R.layout.sheet_info, null, false);
        dialog.setContentView(v);
        content = v;

        TextView txtTitle = v.findViewById(R.id.txtInfoTitle);
        TextView txtSub = v.findViewById(R.id.txtInfoSub);
        TextView txtBadge = v.findViewById(R.id.txtInfoBadge);
        LinearLayout rows = v.findViewById(R.id.layoutInfoRows);
        LinearLayout fileTiles = v.findViewById(R.id.rowFileTiles);

        View btnAdjust = v.findViewById(R.id.btnInfoAdjustDate);
        View btnLens = v.findViewById(R.id.btnInfoSearchImage);
        Press.attach(btnAdjust, 0.94f);
        Press.attach(btnLens, 0.94f);
        btnAdjust.setOnClickListener(x -> {
            if (getActivity() == null) return;
            long cur = lastMeta != null && lastMeta.dateTaken > 0
                    ? lastMeta.dateTaken : item.dateModified;
            EditMetaDialog.show(requireActivity(), item, cur, (newPath, newDate) -> {
                item.path = newPath;
                item.name = new File(newPath).getName();
                item.dateModified = newDate;
                if (lastMeta != null) lastMeta.dateTaken = newDate;
                if (content != null) {
                    txtTitle.setText(item.name);
                    txtSub.setText(Fmt.dateFull(newDate) + " · " + typeLabelOf(ctx));
                }
            });
        });
        btnLens.setOnClickListener(x -> searchImage());

        String typeLabel = ctx.getString(item.isVideo
                ? R.string.info_type_video : R.string.info_type_photo);
        txtTitle.setText(item.name);
        txtSub.setText(Fmt.dateFull(item.dateModified) + " · " + typeLabel);
        String ext = extensionOf(item);
        if (ext.isEmpty()) {
            txtBadge.setVisibility(View.GONE);
        } else {
            txtBadge.setText(ext);
        }

        // Предварительные плитки — пока читаем метаданные
        fillTiles(fileTiles, new String[][]{
                {ctx.getString(R.string.info_dims), "…"},
                {ctx.getString(item.isVideo ? R.string.info_duration : R.string.info_megapixels),
                        item.isVideo && item.duration > 0 ? Fmt.duration(item.duration) : "…"},
                {ctx.getString(R.string.info_size), Fmt.size(item.size)},
        });

        File parent = new File(item.path).getParentFile();
        addRow(ctx, rows, R.string.info_type, typeLabel
                + (item.mime == null || item.mime.isEmpty() ? "" : " · " + item.mime), null);
        addRow(ctx, rows, R.string.info_album, parent == null ? "" : parent.getName(), null);
        addRow(ctx, rows, R.string.info_path, item.path, item.path);

        final MediaItem it = item;
        IO.execute(() -> {
            Meta m = readMeta(it);
            MAIN.post(() -> applyMeta(m));
        });
        return dialog;
    }

    private static String extensionOf(MediaItem it) {
        String n = it.name == null ? "" : it.name;
        int dot = n.lastIndexOf('.');
        if (dot > 0 && dot < n.length() - 1 && n.length() - dot <= 6) {
            return n.substring(dot + 1).toUpperCase(Locale.US);
        }
        if (it.mime != null && it.mime.contains("/")) {
            String sub = it.mime.substring(it.mime.indexOf('/') + 1);
            if (sub.equals("jpeg")) sub = "jpg";
            return sub.toUpperCase(Locale.US);
        }
        return "";
    }

    // ---------- Чтение метаданных ----------

    private static Meta readMeta(MediaItem it) {
        Meta m = new Meta();
        m.duration = it.duration;
        try {
            if (it.isVideo) {
                MediaMetadataRetriever r = new MediaMetadataRetriever();
                try {
                    r.setDataSource(it.path);
                    m.width = parseInt(r.extractMetadata(
                            MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH));
                    m.height = parseInt(r.extractMetadata(
                            MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT));
                    int rot = parseInt(r.extractMetadata(
                            MediaMetadataRetriever.METADATA_KEY_VIDEO_ROTATION));
                    if (rot == 90 || rot == 270) {
                        int t = m.width;
                        m.width = m.height;
                        m.height = t;
                    }
                    if (m.duration <= 0) {
                        m.duration = parseLong(r.extractMetadata(
                                MediaMetadataRetriever.METADATA_KEY_DURATION));
                    }
                    parseIsoLocation(m, r.extractMetadata(
                            MediaMetadataRetriever.METADATA_KEY_LOCATION));
                } finally {
                    try {
                        r.release();
                    } catch (Exception ignored) {
                    }
                }
            } else {
                BitmapFactory.Options o = new BitmapFactory.Options();
                o.inJustDecodeBounds = true;
                BitmapFactory.decodeFile(it.path, o);
                m.width = Math.max(0, o.outWidth);
                m.height = Math.max(0, o.outHeight);
                readExif(m, it.path);
            }
        } catch (Throwable ignored) {
        }
        return m;
    }

    private static void readExif(Meta m, String path) {
        try {
            ExifInterface exif = new ExifInterface(path);
            int orientation = exif.getAttributeInt(ExifInterface.TAG_ORIENTATION,
                    ExifInterface.ORIENTATION_NORMAL);
            if (orientation == ExifInterface.ORIENTATION_ROTATE_90
                    || orientation == ExifInterface.ORIENTATION_ROTATE_270
                    || orientation == ExifInterface.ORIENTATION_TRANSPOSE
                    || orientation == ExifInterface.ORIENTATION_TRANSVERSE) {
                int t = m.width;
                m.width = m.height;
                m.height = t;
            }
            m.make = trim(exif.getAttribute(ExifInterface.TAG_MAKE));
            m.model = trim(exif.getAttribute(ExifInterface.TAG_MODEL));
            double f = exif.getAttributeDouble(ExifInterface.TAG_F_NUMBER, 0);
            if (f > 0) m.aperture = "ƒ/" + trimZeros(String.format(Locale.US, "%.1f", f));
            double exp = exif.getAttributeDouble(ExifInterface.TAG_EXPOSURE_TIME, 0);
            if (exp > 0) m.shutter = formatShutter(exp);
            String iso = exif.getAttribute("ISOSpeedRatings");
            if (iso == null || iso.isEmpty()) iso = exif.getAttribute("PhotographicSensitivity");
            if (iso != null && !iso.isEmpty()) m.iso = iso;
            double focal = exif.getAttributeDouble(ExifInterface.TAG_FOCAL_LENGTH, 0);
            if (focal > 0) {
                m.focal = trimZeros(String.format(Locale.US, "%.1f", focal)) + " "
                        + Fmt.resCtx().getString(R.string.unit_mm);
            }
            String dt = exif.getAttribute(ExifInterface.TAG_DATETIME_ORIGINAL);
            if (dt == null) dt = exif.getAttribute(ExifInterface.TAG_DATETIME);
            if (dt != null) {
                try {
                    Date d = new SimpleDateFormat("yyyy:MM:dd HH:mm:ss", Locale.US).parse(dt);
                    if (d != null) m.dateTaken = d.getTime();
                } catch (Exception ignored) {
                }
            }
            float[] ll = new float[2];
            if (exif.getLatLong(ll)) {
                m.lat = ll[0];
                m.lng = ll[1];
            }
        } catch (Throwable ignored) {
        }
    }

    /** ISO 6709: "+55.7558+037.6173/" → широта, долгота. */
    private static void parseIsoLocation(Meta m, String s) {
        if (s == null) return;
        try {
            java.util.regex.Matcher mt = java.util.regex.Pattern
                    .compile("([+-]\\d+(?:\\.\\d+)?)([+-]\\d+(?:\\.\\d+)?)").matcher(s);
            if (mt.find()) {
                m.lat = Double.parseDouble(mt.group(1));
                m.lng = Double.parseDouble(mt.group(2));
            }
        } catch (Exception ignored) {
        }
    }

    private static String formatShutter(double sec) {
        String unit = " " + Fmt.resCtx().getString(R.string.unit_sec);
        if (sec >= 1) return trimZeros(String.format(Locale.US, "%.1f", sec)) + unit;
        long denom = Math.round(1.0 / sec);
        return "1/" + denom + unit;
    }

    private static String trimZeros(String s) {
        return s.endsWith(".0") ? s.substring(0, s.length() - 2) : s;
    }

    @Nullable
    private static String trim(@Nullable String s) {
        if (s == null) return null;
        s = s.trim();
        return s.isEmpty() ? null : s;
    }

    private static int parseInt(String s) {
        try {
            return s == null ? 0 : Integer.parseInt(s.trim());
        } catch (Exception e) {
            return 0;
        }
    }

    private static long parseLong(String s) {
        try {
            return s == null ? 0 : Long.parseLong(s.trim());
        } catch (Exception e) {
            return 0;
        }
    }

    // ---------- Заполнение ----------

    private void applyMeta(Meta m) {
        if (content == null || !isAdded() || getContext() == null) return;
        Context ctx = requireContext();
        LinearLayout fileTiles = content.findViewById(R.id.rowFileTiles);

        String dims = m.width > 0 && m.height > 0 ? m.width + " × " + m.height : "—";
        String second;
        if (item.isVideo) {
            second = m.duration > 0 ? Fmt.duration(m.duration) : "—";
        } else {
            double mp = m.width * (double) m.height / 1_000_000.0;
            second = mp > 0 ? String.format(Locale.getDefault(), mp >= 10 ? "%.0f %s" : "%.1f %s", mp,
                    ctx.getString(R.string.unit_mp)) : "—";
        }
        fillTiles(fileTiles, new String[][]{
                {ctx.getString(R.string.info_dims), dims},
                {ctx.getString(item.isVideo ? R.string.info_duration : R.string.info_megapixels), second},
                {ctx.getString(R.string.info_size), Fmt.size(item.size)},
        });

        lastMeta = m;
        if (m.dateTaken > 0) {
            TextView txtSub = content.findViewById(R.id.txtInfoSub);
            txtSub.setText(Fmt.dateFull(m.dateTaken) + " · " + ctx.getString(
                    item.isVideo ? R.string.info_type_video : R.string.info_type_photo));
        }

        if (m.hasCamera()) {
            View card = content.findViewById(R.id.cardCamera);
            TextView txtModel = content.findViewById(R.id.txtCameraModel);
            LinearLayout tiles = content.findViewById(R.id.rowCameraTiles);
            String model = m.model == null ? "" : m.model;
            if (m.make != null && !model.toLowerCase(Locale.US)
                    .contains(m.make.toLowerCase(Locale.US))) {
                model = (m.make + " " + model).trim();
            }
            if (model.isEmpty()) {
                txtModel.setVisibility(View.GONE);
            } else {
                txtModel.setText(model);
            }
            fillTiles(tiles, new String[][]{
                    {ctx.getString(R.string.info_aperture), m.aperture == null ? "—" : m.aperture},
                    {ctx.getString(R.string.info_shutter), m.shutter == null ? "—" : m.shutter},
                    {ctx.getString(R.string.info_iso), m.iso == null ? "—" : m.iso},
                    {ctx.getString(R.string.info_focal), m.focal == null ? "—" : m.focal},
            });
            card.setVisibility(View.VISIBLE);
        }

        if (m.hasLocation()) {
            View card = content.findViewById(R.id.cardLocation);
            TextView txt = content.findViewById(R.id.txtLocation);
            View btn = content.findViewById(R.id.btnOpenMaps);
            String coords = String.format(Locale.US, "%.5f, %.5f", m.lat, m.lng);
            txt.setText(coords);
            View.OnClickListener open = v -> {
                try {
                    Uri uri = Uri.parse(String.format(Locale.US,
                            "geo:%f,%f?q=%f,%f", m.lat, m.lng, m.lat, m.lng));
                    startActivity(new Intent(Intent.ACTION_VIEW, uri));
                } catch (Exception e) {
                    Toasts.makeText(ctx, R.string.info_no_maps, Toast.LENGTH_SHORT).show();
                }
            };
            btn.setOnClickListener(open);
            card.setOnClickListener(open);
            card.setVisibility(View.VISIBLE);
        }
    }

    /** Ряд плиток «метка / значение» одинаковой ширины с зазором 8dp. */
    private void fillTiles(LinearLayout row, String[][] tagValues) {
        row.removeAllViews();
        LayoutInflater inf = LayoutInflater.from(row.getContext());
        int gap = Math.round(8 * row.getResources().getDisplayMetrics().density);
        for (int i = 0; i < tagValues.length; i++) {
            View tile = inf.inflate(R.layout.item_metric_tile, row, false);
            ((TextView) tile.findViewById(R.id.txtMetricTag)).setText(tagValues[i][0]);
            ((TextView) tile.findViewById(R.id.txtMetricValue)).setText(tagValues[i][1]);
            LinearLayout.LayoutParams lp = (LinearLayout.LayoutParams) tile.getLayoutParams();
            if (i > 0) lp.setMarginStart(gap);
            row.addView(tile, lp);
        }
    }

    private void addRow(Context ctx, LinearLayout parent, int labelRes, String value,
                        @Nullable String copyValue) {
        View row = LayoutInflater.from(ctx).inflate(R.layout.item_info_row, parent, false);
        TextView label = row.findViewById(R.id.txtInfoLabel);
        TextView val = row.findViewById(R.id.txtInfoValue);
        label.setText(labelRes);
        val.setText(value == null ? "" : value);
        if (copyValue != null) {
            // Подсветка нажатия без собственной формы: строка остаётся частью общей
            // группы «Тип / Альбом / Путь» с единым скруглением
            android.util.TypedValue tv = new android.util.TypedValue();
            if (ctx.getTheme().resolveAttribute(android.R.attr.selectableItemBackground, tv, true)) {
                row.setBackgroundResource(tv.resourceId);
            }
            row.setOnClickListener(v -> {
                android.content.ClipboardManager cm =
                        (android.content.ClipboardManager) ctx.getSystemService(Context.CLIPBOARD_SERVICE);
                if (cm != null) {
                    cm.setPrimaryClip(android.content.ClipData.newPlainText("path", copyValue));
                    Toasts.makeText(ctx, R.string.info_path_copied, Toast.LENGTH_SHORT).show();
                }
            });
        }
        parent.addView(row);
    }

    @Override
    public void onStart() {
        super.onStart();
        Dialog d = getDialog();
        if (d != null) {
            View bottom = d.findViewById(com.google.android.material.R.id.design_bottom_sheet);
            if (bottom != null) {
                ViewGroup.LayoutParams lp = bottom.getLayoutParams();
                lp.height = WindowManager.LayoutParams.WRAP_CONTENT;
                bottom.setLayoutParams(lp);
                BottomSheetBehavior<View> behavior = BottomSheetBehavior.from(bottom);
                behavior.setPeekHeight(ViewGroup.LayoutParams.WRAP_CONTENT);
                behavior.setSkipCollapsed(true);
                behavior.setState(BottomSheetBehavior.STATE_EXPANDED);
            }
        }
    }

    private String typeLabelOf(Context ctx) {
        return ctx.getString(item.isVideo ? R.string.info_type_video : R.string.info_type_photo);
    }

    /** «Поиск по картинке»: отдаём кадр в Google Lens; для видео — извлекаем кадр во временный файл. */
    private void searchImage() {
        Context ctx = getContext();
        if (ctx == null) return;
        IO.execute(() -> {
            File f = new File(item.path);
            if (item.isVideo) {
                try {
                    MediaMetadataRetriever r = new MediaMetadataRetriever();
                    r.setDataSource(item.path);
                    android.graphics.Bitmap b = r.getFrameAtTime(-1);
                    r.release();
                    if (b != null) {
                        File dir = new File(ctx.getCacheDir(), "lens");
                        //noinspection ResultOfMethodCallIgnored
                        dir.mkdirs();
                        f = new File(dir, "frame.jpg");
                        try (java.io.FileOutputStream out = new java.io.FileOutputStream(f)) {
                            b.compress(android.graphics.Bitmap.CompressFormat.JPEG, 92, out);
                        }
                    }
                } catch (Exception ignored) {
                }
            }
            final File share = f;
            MAIN.post(() -> {
                if (getContext() == null) return;
                try {
                    Uri uri = androidx.core.content.FileProvider.getUriForFile(ctx,
                            ctx.getPackageName() + ".fileprovider", share);
                    Intent i = new Intent(Intent.ACTION_SEND);
                    i.setType("image/*");
                    i.putExtra(Intent.EXTRA_STREAM, uri);
                    i.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
                    i.setClassName("com.google.android.googlequicksearchbox",
                            "com.google.android.apps.search.lens.LensShareEntryPointActivity");
                    try {
                        startActivity(i);
                    } catch (Exception e) {
                        i.setComponent(null);
                        i.setPackage("com.google.android.googlequicksearchbox");
                        startActivity(i);
                    }
                } catch (Exception e) {
                    Toasts.makeText(ctx, R.string.info_no_lens, Toast.LENGTH_SHORT).show();
                }
            });
        });
    }
}
