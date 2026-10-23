package com.premiumlab.galleryx.ui.sheet;

import android.app.Dialog;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.os.Bundle;
import android.util.Base64;
import android.view.LayoutInflater;
import android.view.View;
import android.widget.ImageView;
import android.widget.ProgressBar;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;

import com.bumptech.glide.Glide;
import com.google.android.material.bottomsheet.BottomSheetDialog;
import com.google.android.material.bottomsheet.BottomSheetDialogFragment;
import com.premiumlab.galleryx.R;
import com.premiumlab.galleryx.util.Fmt;
import com.premiumlab.galleryx.util.Toasts;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;

/**
 * Конвертер фото в Base64: текстовая копия изображения, которую можно
 * вставить в чат нейросети, если та не принимает сами файлы. Режимы:
 * «Оригинал» — байт в байт исходный файл (восстанавливается точная
 * копия), «Уменьшенная» — лёгкий JPEG до 1280 px, если оригинал велик.
 * Опция «data:» добавляет префикс data:image/…;base64, перед текстом.
 */
public class Base64Sheet extends BottomSheetDialogFragment {

    private static final String ARG_PATH = "path";
    private static final String ARG_NAME = "name";

    private String path;
    private boolean smallMode = false;
    private boolean withPrefix = false;
    /** Поколение конвертации: устаревшие результаты отбрасываются. */
    private int generation = 0;
    private volatile String resultBase64;
    private volatile String resultMime = "image/jpeg";

    private TextView txtMetaFile, txtMetaResult, txtPreview, btnCopy;
    private TextView chipOriginal, chipSmall, chipPrefix;
    private ProgressBar progress;

    public static Base64Sheet newInstance(String path, String displayName) {
        Base64Sheet s = new Base64Sheet();
        Bundle b = new Bundle();
        b.putString(ARG_PATH, path);
        b.putString(ARG_NAME, displayName);
        s.setArguments(b);
        return s;
    }

    @NonNull
    @Override
    public Dialog onCreateDialog(Bundle savedInstanceState) {
        BottomSheetDialog dialog = (BottomSheetDialog) super.onCreateDialog(savedInstanceState);
        View v = LayoutInflater.from(getContext()).inflate(R.layout.sheet_base64, null, false);
        dialog.setContentView(v);

        path = requireArguments().getString(ARG_PATH, "");
        String name = requireArguments().getString(ARG_NAME, new File(path).getName());

        TextView txtName = v.findViewById(R.id.txtB64Name);
        txtName.setText(name);
        ImageView preview = v.findViewById(R.id.imgB64Preview);
        if (!path.isEmpty()) {
            Glide.with(this).load(new File(path)).centerCrop().into(preview);
        }
        txtMetaFile = v.findViewById(R.id.txtB64MetaFile);
        txtMetaResult = v.findViewById(R.id.txtB64MetaResult);
        txtPreview = v.findViewById(R.id.txtB64Preview);
        btnCopy = v.findViewById(R.id.btnB64Copy);
        chipOriginal = v.findViewById(R.id.chipB64Original);
        chipSmall = v.findViewById(R.id.chipB64Small);
        chipPrefix = v.findViewById(R.id.chipB64Prefix);
        progress = v.findViewById(R.id.progressB64);

        File f = new File(path);
        txtMetaFile.setText(getString(R.string.b64_meta_file,
                f.exists() ? Fmt.size(f.length()) : "—"));

        chipOriginal.setOnClickListener(x -> {
            if (smallMode) {
                smallMode = false;
                styleChips();
                startConvert();
            }
        });
        chipSmall.setOnClickListener(x -> {
            if (!smallMode) {
                smallMode = true;
                styleChips();
                startConvert();
            }
        });
        chipPrefix.setOnClickListener(x -> {
            withPrefix = !withPrefix;
            styleChips();
        });
        btnCopy.setOnClickListener(x -> copyResult());

        styleChips();
        startConvert();
        return dialog;
    }

    private void styleChips() {
        chipOriginal.setBackgroundResource(smallMode
                ? R.drawable.bg_chip : R.drawable.bg_chip_active);
        chipSmall.setBackgroundResource(smallMode
                ? R.drawable.bg_chip_active : R.drawable.bg_chip);
        chipPrefix.setBackgroundResource(withPrefix
                ? R.drawable.bg_chip_active : R.drawable.bg_chip);
    }

    /** Запуск конвертации в фоне: большие фото кодируются не мгновенно. */
    private void startConvert() {
        final int gen = ++generation;
        final boolean small = smallMode;
        final String srcPath = path;
        resultBase64 = null;
        progress.setVisibility(View.VISIBLE);
        btnCopy.setEnabled(false);
        btnCopy.setAlpha(0.45f);
        txtMetaResult.setText(R.string.b64_converting);
        txtPreview.setText("");
        new Thread(() -> {
            String b64 = null;
            String mime = "image/jpeg";
            String error = null;
            try {
                byte[] bytes;
                if (small) {
                    bytes = makeSmallJpeg(srcPath);
                    if (bytes == null) throw new Exception("decode");
                } else {
                    bytes = readFile(srcPath);
                    mime = mimeFor(srcPath);
                }
                b64 = Base64.encodeToString(bytes, Base64.NO_WRAP);
            } catch (Throwable t) {
                error = t.getMessage();
            }
            final String outB64 = b64;
            final String outMime = mime;
            Context ctx = getContext();
            if (ctx == null) return;
            new android.os.Handler(android.os.Looper.getMainLooper()).post(() -> {
                if (!isAdded() || gen != generation) return;
                progress.setVisibility(View.GONE);
                if (outB64 == null) {
                    txtMetaResult.setText(R.string.b64_error);
                    return;
                }
                resultBase64 = outB64;
                resultMime = outMime;
                txtMetaResult.setText(getString(R.string.b64_meta_result,
                        formatCount(outB64.length())));
                txtPreview.setText(outB64.substring(0, Math.min(600, outB64.length())) + "…");
                btnCopy.setEnabled(true);
                btnCopy.setAlpha(1f);
            });
        }, "base64-convert").start();
    }

    private void copyResult() {
        final String b64 = resultBase64;
        if (b64 == null || getContext() == null) return;
        final String text = withPrefix
                ? "data:" + resultMime + ";base64," + b64 : b64;
        final Context app = getContext().getApplicationContext();
        // Копия может весить мегабайты — в буфер кладём не с главного потока
        new Thread(() -> {
            try {
                ClipboardManager cm = (ClipboardManager)
                        app.getSystemService(Context.CLIPBOARD_SERVICE);
                if (cm != null) {
                    cm.setPrimaryClip(ClipData.newPlainText("base64", text));
                }
            } catch (Throwable ignored) {
            }
            new android.os.Handler(android.os.Looper.getMainLooper()).post(() -> {
                if (isAdded()) {
                    Toasts.makeText(getContext(), getString(R.string.b64_copied,
                            formatCount(text.length())), Toast.LENGTH_LONG).show();
                }
            });
        }, "base64-copy").start();
    }

    private static byte[] readFile(String p) throws Exception {
        File f = new File(p);
        byte[] buf = new byte[(int) f.length()];
        FileInputStream in = new FileInputStream(f);
        try {
            int read = 0;
            while (read < buf.length) {
                int n = in.read(buf, read, buf.length - read);
                if (n < 0) break;
                read += n;
            }
            if (read != buf.length) throw new Exception("short read");
        } finally {
            in.close();
        }
        return buf;
    }

    /** Лёгкая копия: декодирование с уменьшением до 1280 px и JPEG 85%. */
    private static byte[] makeSmallJpeg(String p) {
        BitmapFactory.Options bounds = new BitmapFactory.Options();
        bounds.inJustDecodeBounds = true;
        BitmapFactory.decodeFile(p, bounds);
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null;
        int sample = 1;
        int max = Math.max(bounds.outWidth, bounds.outHeight);
        while (max / (sample * 2) >= 1280) sample *= 2;
        BitmapFactory.Options o = new BitmapFactory.Options();
        o.inSampleSize = sample;
        Bitmap bmp = BitmapFactory.decodeFile(p, o);
        if (bmp == null) return null;
        try {
            int w = bmp.getWidth(), h = bmp.getHeight();
            int m = Math.max(w, h);
            if (m > 1280) {
                float k = 1280f / m;
                Bitmap scaled = Bitmap.createScaledBitmap(
                        bmp, Math.round(w * k), Math.round(h * k), true);
                if (scaled != bmp) {
                    bmp.recycle();
                    bmp = scaled;
                }
            }
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            bmp.compress(Bitmap.CompressFormat.JPEG, 85, out);
            return out.toByteArray();
        } finally {
            bmp.recycle();
        }
    }

    private static String mimeFor(String p) {
        String n = p.toLowerCase(java.util.Locale.US);
        if (n.endsWith(".png")) return "image/png";
        if (n.endsWith(".webp")) return "image/webp";
        if (n.endsWith(".gif")) return "image/gif";
        return "image/jpeg";
    }

    private static String formatCount(int n) {
        return java.text.NumberFormat.getInstance().format(n);
    }
}
