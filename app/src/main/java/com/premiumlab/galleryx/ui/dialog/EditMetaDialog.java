package com.premiumlab.galleryx.ui.dialog;

import com.premiumlab.galleryx.util.Toasts;
import android.app.DatePickerDialog;
import android.app.TimePickerDialog;
import android.content.Context;
import android.media.ExifInterface;
import android.view.LayoutInflater;
import android.view.View;
import android.widget.EditText;
import android.widget.TextView;
import android.widget.Toast;

import androidx.fragment.app.FragmentActivity;

import com.google.android.material.dialog.MaterialAlertDialogBuilder;
import com.premiumlab.galleryx.R;
import com.premiumlab.galleryx.data.FavStore;
import com.premiumlab.galleryx.data.MediaEngine;
import com.premiumlab.galleryx.data.MediaItem;
import com.premiumlab.galleryx.data.NoteStore;
import com.premiumlab.galleryx.util.Fmt;
import com.premiumlab.galleryx.util.Scan;

import java.io.File;
import java.text.SimpleDateFormat;
import java.util.Calendar;
import java.util.Date;
import java.util.Locale;
import java.util.concurrent.Executors;

/**
 * «Изменить» из свойств файла: новое имя (без расширения) и дата/время съёмки.
 * Дата пишется в EXIF (JPEG) и в lastModified файла, чтобы лента пересортировалась;
 * избранное и заметки переезжают на новый путь.
 */
public final class EditMetaDialog {

    public interface Listener {
        /** @param newPath путь после переименования (может совпадать со старым). */
        void onChanged(String newPath, long newDate);
    }

    private EditMetaDialog() {
    }

    public static void show(FragmentActivity act, MediaItem item, long currentDate,
                            Listener listener) {
        View v = LayoutInflater.from(act).inflate(R.layout.dialog_edit_meta, null, false);
        EditText editName = v.findViewById(R.id.editMetaName);
        EditText editExt = v.findViewById(R.id.editMetaExt);
        TextView txtDate = v.findViewById(R.id.txtMetaDate);
        TextView txtTime = v.findViewById(R.id.txtMetaTime);
        View rowDate = v.findViewById(R.id.rowMetaDate);

        String name = new File(item.path).getName();
        int dot = name.lastIndexOf('.');
        String base = dot > 0 ? name.substring(0, dot) : name;
        String ext = dot > 0 ? name.substring(dot) : "";
        editName.setText(base);
        editName.setSelection(base.length());
        String extNoDot = ext.startsWith(".") ? ext.substring(1) : ext;
        editExt.setText(extNoDot);

        Calendar cal = Calendar.getInstance();
        cal.setTimeInMillis(currentDate > 0 ? currentDate : System.currentTimeMillis());
        Runnable refresh = () -> {
            txtDate.setText(new SimpleDateFormat(Fmt.locale().getLanguage().equals("ru") ? "d MMMM yyyy" : "MMMM d, yyyy", Fmt.locale())
                    .format(cal.getTime()));
            txtTime.setText(Fmt.timeShort(cal.getTimeInMillis()));
        };
        refresh.run();
        rowDate.setOnClickListener(x -> new DatePickerDialog(act, (dp, y, m, d) -> {
            cal.set(Calendar.YEAR, y);
            cal.set(Calendar.MONTH, m);
            cal.set(Calendar.DAY_OF_MONTH, d);
            refresh.run();
        }, cal.get(Calendar.YEAR), cal.get(Calendar.MONTH), cal.get(Calendar.DAY_OF_MONTH))
                .show());
        txtTime.setOnClickListener(x -> new TimePickerDialog(act, (tp, h, min) -> {
            cal.set(Calendar.HOUR_OF_DAY, h);
            cal.set(Calendar.MINUTE, min);
            cal.set(Calendar.SECOND, 0);
            refresh.run();
        }, cal.get(Calendar.HOUR_OF_DAY), cal.get(Calendar.MINUTE), true).show());

        new MaterialAlertDialogBuilder(act)
                .setTitle(R.string.meta_edit_title)
                .setView(v)
                .setNegativeButton(R.string.cancel, null)
                .setPositiveButton(R.string.save, (d, w) -> {
                    String newBase = editName.getText().toString().trim()
                            .replace('/', '_').replace('\\', '_');
                    if (newBase.isEmpty()) newBase = base;
                    String newExt = sanitizeExt(editExt.getText().toString());
                    String newName = newBase + (newExt.isEmpty() ? "" : "." + newExt);
                    long when = cal.getTimeInMillis();
                    if (!newExt.equalsIgnoreCase(extNoDot)) {
                        // Расширение меняется: честно предупреждаем, что это
                        // только переименование, а не конвертация формата
                        new MaterialAlertDialogBuilder(act)
                                .setTitle(R.string.meta_ext_warn_title)
                                .setMessage(act.getString(R.string.meta_ext_warn,
                                        extDisplay(act, extNoDot), extDisplay(act, newExt)))
                                .setNegativeButton(R.string.cancel, null)
                                .setPositiveButton(R.string.meta_ext_continue,
                                        (dd, ww) -> apply(act, item, newName, when, listener))
                                .show();
                    } else {
                        apply(act, item, newName, when, listener);
                    }
                })
                .show();
    }

    /** Расширение из поля ввода: без точки, без пробелов и служебных символов. */
    private static String sanitizeExt(String raw) {
        if (raw == null) return "";
        String s = raw.trim().toLowerCase(Locale.US);
        while (s.startsWith(".")) s = s.substring(1);
        s = s.replaceAll("[^a-z0-9]", "");
        return s.length() > 12 ? s.substring(0, 12) : s;
    }

    /** «.mp4» или «без расширения» — для текста предупреждения. */
    private static String extDisplay(Context ctx, String extNoDot) {
        return extNoDot == null || extNoDot.isEmpty()
                ? ctx.getString(R.string.meta_ext_none) : "." + extNoDot;
    }

    private static void apply(FragmentActivity act, MediaItem item, String newName,
                              long newDate, Listener listener) {
        Context app = act.getApplicationContext();
        File src = new File(item.path);
        File dst = new File(src.getParentFile(), newName);
        boolean rename = !newName.equals(src.getName());
        if (rename && dst.exists()) {
            Toasts.makeText(act, R.string.meta_name_exists, Toast.LENGTH_SHORT).show();
            return;
        }
        Executors.newSingleThreadExecutor().execute(() -> {
            boolean ok = true;
            File target = src;
            try {
                if (rename) {
                    if (src.renameTo(dst)) {
                        target = dst;
                        FavStore.rewritePrefix(src.getAbsolutePath(), dst.getAbsolutePath());
                        NoteStore.rewritePrefix(src.getAbsolutePath(), dst.getAbsolutePath());
                    } else {
                        ok = false;
                    }
                }
                if (ok) {
                    writeDate(target, newDate, item.isVideo);
                }
            } catch (Exception e) {
                ok = false;
            }
            final boolean fOk = ok;
            final File fTarget = target;
            String[] scan = rename
                    ? new String[]{src.getAbsolutePath(), fTarget.getAbsolutePath()}
                    : new String[]{fTarget.getAbsolutePath()};
            Scan.files(app, scan);
            MediaEngine.invalidateAll();
            act.runOnUiThread(() -> {
                if (act.isFinishing() || act.isDestroyed()) return;
                Toasts.makeText(act, fOk ? R.string.meta_saved : R.string.meta_failed,
                        Toast.LENGTH_SHORT).show();
                if (fOk && listener != null) {
                    listener.onChanged(fTarget.getAbsolutePath(), newDate);
                }
            });
        });
    }

    /** Дата съёмки: EXIF для JPEG + lastModified для любого файла. */
    private static void writeDate(File f, long millis, boolean video) {
        if (!video) {
            String n = f.getName().toLowerCase(Locale.US);
            if (n.endsWith(".jpg") || n.endsWith(".jpeg")) {
                try {
                    ExifInterface exif = new ExifInterface(f.getAbsolutePath());
                    String s = new SimpleDateFormat("yyyy:MM:dd HH:mm:ss", Locale.US)
                            .format(new Date(millis));
                    exif.setAttribute(ExifInterface.TAG_DATETIME_ORIGINAL, s);
                    exif.setAttribute(ExifInterface.TAG_DATETIME, s);
                    exif.saveAttributes();
                } catch (Exception ignored) {
                }
            }
        }
        //noinspection ResultOfMethodCallIgnored
        f.setLastModified(millis);
    }
}
