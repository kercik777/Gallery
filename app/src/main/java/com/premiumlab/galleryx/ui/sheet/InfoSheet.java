package com.premiumlab.galleryx.ui.sheet;

import android.app.Dialog;
import android.content.Context;
import android.graphics.BitmapFactory;
import android.media.MediaMetadataRetriever;
import android.os.AsyncTask;
import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;

import com.google.android.material.bottomsheet.BottomSheetBehavior;
import com.google.android.material.bottomsheet.BottomSheetDialog;
import com.google.android.material.bottomsheet.BottomSheetDialogFragment;
import com.premiumlab.galleryx.R;
import com.premiumlab.galleryx.data.MediaItem;
import com.premiumlab.galleryx.util.Fmt;

import java.io.File;

/**
 * Нижний лист со свойствами файла: имя, тип, размер, разрешение, дата, альбом.
 */
public class InfoSheet extends BottomSheetDialogFragment {

    private MediaItem item;

    public void setItem(MediaItem item) {
        this.item = item;
    }

    @NonNull
    @Override
    public Dialog onCreateDialog(Bundle savedInstanceState) {
        BottomSheetDialog dialog = (BottomSheetDialog) super.onCreateDialog(savedInstanceState);
        View v = LayoutInflater.from(getContext()).inflate(R.layout.sheet_info, null, false);
        dialog.setContentView(v);

        TextView txtTitle = v.findViewById(R.id.txtInfoTitle);
        TextView txtSub = v.findViewById(R.id.txtInfoSub);
        LinearLayout rows = v.findViewById(R.id.layoutInfoRows);

        Context ctx = requireContext();
        txtTitle.setText(item.name);
        txtSub.setText(item.isVideo
                ? ctx.getString(R.string.info_type_video)
                : ctx.getString(R.string.info_type_photo));

        addRow(ctx, rows, R.string.info_name, item.name, false);
        addRow(ctx, rows, R.string.info_type, (item.isVideo
                ? ctx.getString(R.string.info_type_video)
                : ctx.getString(R.string.info_type_photo)) + (item.mime == null || item.mime.isEmpty() ? "" : " · " + item.mime), false);
        addRow(ctx, rows, R.string.info_size, Fmt.size(item.size), false);
        addRow(ctx, rows, R.string.info_date, Fmt.dateFull(item.dateModified), false);

        File parent = new File(item.path).getParentFile();
        addRow(ctx, rows, R.string.info_album,
                parent == null ? "" : parent.getName(), false);
        addRow(ctx, rows, R.string.info_path, item.path, true);

        // Разрешение и длительность — асинхронно
        new ResolveTask(ctx, rows, item).execute();

        return dialog;
    }

    private void addRow(Context ctx, LinearLayout parent, int labelRes, String value, boolean copyable) {
        View row = LayoutInflater.from(ctx).inflate(R.layout.item_info_row, parent, false);
        TextView label = row.findViewById(R.id.txtInfoLabel);
        TextView val = row.findViewById(R.id.txtInfoValue);
        label.setText(labelRes);
        val.setText(value == null ? "" : value);
        if (copyable) {
            row.setOnClickListener(v -> {
                android.content.ClipboardManager cm =
                        (android.content.ClipboardManager) ctx.getSystemService(Context.CLIPBOARD_SERVICE);
                if (cm != null) {
                    cm.setPrimaryClip(android.content.ClipData.newPlainText("path", value));
                    Toast.makeText(ctx, R.string.info_path_copied, Toast.LENGTH_SHORT).show();
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
                behavior.setState(BottomSheetBehavior.STATE_EXPANDED);
            }
        }
    }

    /** Фоновое определение разрешения изображения и длительности видео. */
    private static class ResolveTask extends AsyncTask<Void, Void, String[]> {
        final Context ctx;
        final LinearLayout rows;
        final MediaItem item;

        ResolveTask(Context ctx, LinearLayout rows, MediaItem item) {
            this.ctx = ctx.getApplicationContext();
            this.rows = rows;
            this.item = item;
        }

        @Override
        protected String[] doInBackground(Void... voids) {
            String dims = null;
            long duration = item.duration;
            try {
                if (item.isVideo) {
                    MediaMetadataRetriever r = new MediaMetadataRetriever();
                    r.setDataSource(item.path);
                    String w = r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH);
                    String h = r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT);
                    if (w != null && h != null) dims = w + " × " + h;
                    if (duration <= 0) {
                        try {
                            duration = Long.parseLong(r.extractMetadata(
                                    MediaMetadataRetriever.METADATA_KEY_DURATION));
                        } catch (Exception ignored) {
                        }
                    }
                    r.release();
                } else {
                    BitmapFactory.Options o = new BitmapFactory.Options();
                    o.inJustDecodeBounds = true;
                    BitmapFactory.decodeFile(item.path, o);
                    if (o.outWidth > 0) dims = o.outWidth + " × " + o.outHeight;
                }
            } catch (Exception ignored) {
            }
            return new String[]{dims, String.valueOf(duration)};
        }

        @Override
        protected void onPostExecute(String[] result) {
            if (!dimsRowValid()) return;
            String dims = result[0];
            long duration = 0;
            try {
                duration = Long.parseLong(result[1]);
            } catch (Exception ignored) {
            }
            if (dims != null) {
                View row = LayoutInflater.from(ctx).inflate(R.layout.item_info_row, rows, false);
                TextView label = row.findViewById(R.id.txtInfoLabel);
                TextView val = row.findViewById(R.id.txtInfoValue);
                label.setText(R.string.info_dims);
                val.setText(dims);
                rows.addView(row);
            }
            if (item.isVideo && duration > 0) {
                View row = LayoutInflater.from(ctx).inflate(R.layout.item_info_row, rows, false);
                TextView label = row.findViewById(R.id.txtInfoLabel);
                TextView val = row.findViewById(R.id.txtInfoValue);
                label.setText(R.string.info_duration);
                val.setText(Fmt.duration(duration));
                rows.addView(row);
            }
        }

        private boolean dimsRowValid() {
            return rows.getParent() != null && !item.path.isEmpty();
        }
    }
}
