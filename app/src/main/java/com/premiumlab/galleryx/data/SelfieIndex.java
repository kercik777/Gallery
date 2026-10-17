package com.premiumlab.galleryx.data;

import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import android.database.sqlite.SQLiteOpenHelper;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Matrix;
import android.graphics.PointF;
import androidx.exifinterface.media.ExifInterface;
import android.media.FaceDetector;
import android.os.Handler;
import android.os.Looper;

import java.io.File;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Индекс селфи — как в GorillaGallery, по лицам на снимке, но без внешних
 * библиотек: встроенный {@link FaceDetector} на уменьшенной копии фото.
 * <p>
 * Селфи = есть лицо, оно достаточно крупное (площадь ≥ 5.5% кадра) и близко к
 * центру (смещение ≤ 48% полудиагонали). Результаты кэшируются в SQLite в
 * приватной папке приложения (путь + время изменения файла), поэтому каждый
 * файл анализируется один раз.
 */
public final class SelfieIndex {

    public interface Listener {
        /** Индекс изменился (добавились новые результаты). Вызывается в главном потоке. */
        void onSelfieIndexChanged();
    }

    private static final float MIN_FACE_RATIO = 0.055f;
    private static final float MAX_CENTER_OFFSET = 0.48f;
    private static final int MAX_SIDE = 480;
    private static final int BATCH = 40;

    private static final ExecutorService IO = Executors.newSingleThreadExecutor();
    private static final Handler MAIN = new Handler(Looper.getMainLooper());
    private static final List<Listener> listeners = new CopyOnWriteArrayList<>();
    private static final AtomicBoolean running = new AtomicBoolean(false);

    private static Helper helper;
    /** path → mtime уже проанализированных файлов */
    private static final HashMap<String, Long> known = new HashMap<>();
    private static final Set<String> selfies = Collections.synchronizedSet(new HashSet<>());
    private static volatile boolean loaded = false;
    private static volatile int pending = 0;

    private SelfieIndex() {
    }

    private static final class Helper extends SQLiteOpenHelper {
        Helper(Context ctx) {
            super(ctx.getApplicationContext(), "selfie_index.db", null, 1);
        }

        @Override
        public void onCreate(SQLiteDatabase db) {
            db.execSQL("CREATE TABLE faces (path TEXT PRIMARY KEY, mtime INTEGER, selfie INTEGER)");
        }

        @Override
        public void onUpgrade(SQLiteDatabase db, int oldV, int newV) {
            db.execSQL("DROP TABLE IF EXISTS faces");
            onCreate(db);
        }
    }

    public static void addListener(Listener l) {
        if (!listeners.contains(l)) listeners.add(l);
    }

    public static void removeListener(Listener l) {
        listeners.remove(l);
    }

    /** Сколько файлов ещё ждут анализа (для подписи «Анализ…»). */
    public static int pending() {
        return pending;
    }

    public static boolean isIndexing() {
        return running.get();
    }

    private static synchronized void ensureLoaded(Context ctx) {
        if (loaded) return;
        helper = new Helper(ctx);
        try (Cursor c = helper.getReadableDatabase()
                .rawQuery("SELECT path, mtime, selfie FROM faces", null)) {
            while (c.moveToNext()) {
                String p = c.getString(0);
                known.put(p, c.getLong(1));
                if (c.getInt(2) == 1) selfies.add(p);
            }
        } catch (Exception ignored) {
        }
        loaded = true;
    }

    /** Быстрая проверка из памяти (после первой загрузки индекса). */
    public static boolean isSelfie(String path) {
        return selfies.contains(path);
    }

    public static boolean isLoaded() {
        return loaded;
    }

    /** Синхронная загрузка кэша (дёшево) — чтобы счётчики были верны сразу. */
    public static void warmUp(Context ctx) {
        ensureLoaded(ctx);
    }

    /**
     * Ставит в очередь анализ всех фото, которых ещё нет в индексе
     * (или которые изменились). Повторные вызовы безопасны.
     */
    public static void ensureIndexed(Context ctx, List<MediaItem> items) {
        final Context app = ctx.getApplicationContext();
        final List<MediaItem> photos = new ArrayList<>();
        for (MediaItem it : items) if (!it.isVideo) photos.add(it);
        IO.execute(() -> {
            ensureLoaded(app);
            List<MediaItem> todo = new ArrayList<>();
            for (MediaItem it : photos) {
                Long m = known.get(it.path);
                if (m == null || m != it.dateModified) todo.add(it);
            }
            if (todo.isEmpty()) return;
            if (!running.compareAndSet(false, true)) return;
            pending = todo.size();
            try {
                FaceDetector detector = null;
                int detW = 0, detH = 0;
                SQLiteDatabase db = helper.getWritableDatabase();
                int inBatch = 0;
                boolean changed = false;
                db.beginTransaction();
                for (MediaItem it : todo) {
                    boolean selfie;
                    try {
                        Boolean quick = quickHeuristic(it);
                        if (quick != null) {
                            selfie = quick;
                        } else {
                            Bitmap b = decodeForFaces(it.path);
                            if (b == null) {
                                selfie = false;
                            } else {
                                if (detector == null || detW != b.getWidth() || detH != b.getHeight()) {
                                    detW = b.getWidth();
                                    detH = b.getHeight();
                                    detector = new FaceDetector(detW, detH, 4);
                                }
                                selfie = hasSelfieFace(detector, b);
                                b.recycle();
                            }
                        }
                    } catch (Throwable t) {
                        selfie = false;
                    }
                    ContentValues cv = new ContentValues();
                    cv.put("path", it.path);
                    cv.put("mtime", it.dateModified);
                    cv.put("selfie", selfie ? 1 : 0);
                    db.insertWithOnConflict("faces", null, cv, SQLiteDatabase.CONFLICT_REPLACE);
                    known.put(it.path, it.dateModified);
                    if (selfie) {
                        selfies.add(it.path);
                        changed = true;
                    } else if (selfies.remove(it.path)) {
                        changed = true;
                    }
                    pending--;
                    if (++inBatch >= BATCH) {
                        db.setTransactionSuccessful();
                        db.endTransaction();
                        db.beginTransaction();
                        inBatch = 0;
                        if (changed) {
                            changed = false;
                            notifyChanged();
                        }
                    }
                }
                db.setTransactionSuccessful();
                db.endTransaction();
                notifyChanged();
            } catch (Exception ignored) {
            } finally {
                pending = 0;
                running.set(false);
            }
        });
    }

    private static void notifyChanged() {
        MAIN.post(() -> {
            for (Listener l : listeners) l.onSelfieIndexChanged();
        });
    }

    /** Явные признаки без анализа картинки: папка/имя селфи, фронтальная камера в EXIF. */
    private static Boolean quickHeuristic(MediaItem it) {
        String p = it.path.toLowerCase(Locale.US);
        String n = it.name.toLowerCase(Locale.US);
        if (p.contains("/selfie") || p.contains("/селфи") || n.contains("selfie")
                || n.contains("селфи") || n.contains("_front") || n.startsWith("img_front")) {
            return Boolean.TRUE;
        }
        if (SmartAlbum.isScreenshot(it)) return Boolean.FALSE;
        try {
            ExifInterface exif = new ExifInterface(it.path);
            String lens = exif.getAttribute(ExifInterface.TAG_LENS_MODEL);
            if (lens != null && lens.toLowerCase(Locale.US).contains("front")) return Boolean.TRUE;
            String comment = exif.getAttribute(ExifInterface.TAG_USER_COMMENT);
            if (comment != null && comment.toLowerCase(Locale.US).contains("front")) {
                return Boolean.TRUE;
            }
        } catch (Exception ignored) {
        }
        return null;
    }

    /** Уменьшенная копия в RGB_565 с чётной шириной — требование FaceDetector. */
    private static Bitmap decodeForFaces(String path) {
        BitmapFactory.Options o = new BitmapFactory.Options();
        o.inJustDecodeBounds = true;
        BitmapFactory.decodeFile(path, o);
        if (o.outWidth <= 0 || o.outHeight <= 0) return null;
        int sample = 1;
        while (o.outWidth / sample > MAX_SIDE * 2 || o.outHeight / sample > MAX_SIDE * 2) sample *= 2;
        BitmapFactory.Options d = new BitmapFactory.Options();
        d.inSampleSize = sample;
        d.inPreferredConfig = Bitmap.Config.RGB_565;
        Bitmap b = BitmapFactory.decodeFile(path, d);
        if (b == null) return null;
        float scale = Math.min(1f, MAX_SIDE / (float) Math.max(b.getWidth(), b.getHeight()));
        int w = Math.max(2, Math.round(b.getWidth() * scale));
        int h = Math.max(2, Math.round(b.getHeight() * scale));
        if (w % 2 == 1) w--;
        int rot = 0;
        try {
            int or = new ExifInterface(path).getAttributeInt(ExifInterface.TAG_ORIENTATION,
                    ExifInterface.ORIENTATION_NORMAL);
            if (or == ExifInterface.ORIENTATION_ROTATE_90) rot = 90;
            else if (or == ExifInterface.ORIENTATION_ROTATE_180) rot = 180;
            else if (or == ExifInterface.ORIENTATION_ROTATE_270) rot = 270;
        } catch (Exception ignored) {
        }
        Matrix m = new Matrix();
        m.postScale(w / (float) b.getWidth(), h / (float) b.getHeight());
        if (rot != 0) m.postRotate(rot);
        Bitmap out = Bitmap.createBitmap(b, 0, 0, b.getWidth(), b.getHeight(), m, true);
        if (out != b) b.recycle();
        if (out.getConfig() != Bitmap.Config.RGB_565 || out.getWidth() % 2 == 1) {
            int ow = out.getWidth() % 2 == 1 ? out.getWidth() - 1 : out.getWidth();
            Bitmap conv = Bitmap.createBitmap(ow, out.getHeight(), Bitmap.Config.RGB_565);
            android.graphics.Canvas c = new android.graphics.Canvas(conv);
            c.drawBitmap(out, 0, 0, null);
            out.recycle();
            out = conv;
        }
        return out;
    }

    private static boolean hasSelfieFace(FaceDetector detector, Bitmap b) {
        FaceDetector.Face[] faces = new FaceDetector.Face[4];
        int n = detector.findFaces(b, faces);
        if (n <= 0) return false;
        float w = b.getWidth(), h = b.getHeight();
        float best = 0f;
        float bestOffset = 1f;
        PointF mid = new PointF();
        for (int i = 0; i < n; i++) {
            FaceDetector.Face f = faces[i];
            if (f == null || f.confidence() < 0.3f) continue;
            float eyes = f.eyesDistance();
            // Ширина лица ≈ 2.3 расстояния между глазами, высота ≈ 1.35 ширины
            float fw = eyes * 2.3f;
            float fh = fw * 1.35f;
            float ratio = (fw * fh) / (w * h);
            f.getMidPoint(mid);
            float offset = (float) Math.hypot(mid.x - w / 2f, mid.y - h / 2f)
                    / (float) Math.hypot(w / 2f, h / 2f);
            if (ratio > best) {
                best = ratio;
                bestOffset = offset;
            }
        }
        return best >= MIN_FACE_RATIO && bestOffset <= MAX_CENTER_OFFSET;
    }

    /** Все известные селфи (копия). */
    public static Set<String> all() {
        synchronized (selfies) {
            return new HashSet<>(selfies);
        }
    }

    /** Сбросить результаты по удалённым файлам (вызывать изредка). */
    public static void prune(List<MediaItem> existing) {
        IO.execute(() -> {
            if (!loaded || helper == null) return;
            HashSet<String> live = new HashSet<>();
            for (MediaItem it : existing) live.add(it.path);
            List<String> dead = new ArrayList<>();
            for (String p : known.keySet()) if (!live.contains(p) && !new File(p).exists()) dead.add(p);
            if (dead.isEmpty()) return;
            SQLiteDatabase db = helper.getWritableDatabase();
            for (String p : dead) {
                db.delete("faces", "path=?", new String[]{p});
                known.remove(p);
                selfies.remove(p);
            }
        });
    }
}
