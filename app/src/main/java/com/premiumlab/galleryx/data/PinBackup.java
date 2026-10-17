package com.premiumlab.galleryx.data;

import org.json.JSONObject;

import java.io.File;
import java.io.FileOutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;

/**
 * Резервная копия PIN-кода в служебной папке корня (<корень>/.GalleryX/pin.json).
 * Хранится только хэш с солью — как и в настройках. После переустановки
 * приложения достаточно выбрать ту же корневую папку, и PIN применится сам.
 */
public final class PinBackup {

    private static final String FILE = "pin.json";

    private PinBackup() {
    }

    private static File file() {
        File hidden = AppDirs.hiddenRoot();
        return hidden == null ? null : new File(hidden, FILE);
    }

    /** Записывает текущий PIN (если он задан) в корень; без корня ничего не делает. */
    public static void save() {
        try {
            File f = file();
            if (f == null) return;
            if (!Prefs.pinSet()) {
                //noinspection ResultOfMethodCallIgnored
                f.delete();
                return;
            }
            File dir = f.getParentFile();
            if (dir != null && !dir.isDirectory() && !dir.mkdirs()) return;
            JSONObject o = new JSONObject();
            o.put("hash", Prefs.pinHash());
            o.put("salt", Prefs.pinSalt() == null ? "" : Prefs.pinSalt());
            o.put("masking_use_pin", Prefs.maskingUsesPin());
            o.put("saved_at", System.currentTimeMillis());
            File tmp = new File(dir, "." + FILE + ".tmp");
            try (FileOutputStream fos = new FileOutputStream(tmp)) {
                fos.write(o.toString().getBytes(StandardCharsets.UTF_8));
                fos.getFD().sync();
            }
            if (!tmp.renameTo(f)) {
                //noinspection ResultOfMethodCallIgnored
                tmp.delete();
            }
        } catch (Exception ignored) {
            // Резервная копия — не критично
        }
    }

    /** Удаляет копию (PIN отключён). */
    public static void clear() {
        try {
            File f = file();
            if (f != null && f.isFile()) {
                //noinspection ResultOfMethodCallIgnored
                f.delete();
            }
        } catch (Exception ignored) {
        }
    }

    /**
     * Если PIN в настройках не задан, а в корне есть копия — применяет её.
     *
     * @return true, если PIN восстановлен
     */
    public static boolean restoreIfNeeded() {
        try {
            if (Prefs.pinSet()) return false;
            File f = file();
            if (f == null || !f.isFile()) return false;
            String json = new String(Files.readAllBytes(f.toPath()), StandardCharsets.UTF_8);
            JSONObject o = new JSONObject(json);
            String hash = o.optString("hash", "");
            if (hash.isEmpty()) return false;
            Prefs.setPinQuiet(hash, o.optString("salt", ""));
            if (o.has("masking_use_pin")) {
                Prefs.setMaskingUsesPin(o.optBoolean("masking_use_pin", true));
            }
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    /** Корень выбран/сменён: свой PIN сохраняем в новый корень, без своего — берём из него. */
    public static void onRootChanged() {
        if (Prefs.pinSet()) {
            save();
        } else {
            restoreIfNeeded();
        }
    }
}
