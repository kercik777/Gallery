package com.premiumlab.galleryx.resizer;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;

import androidx.annotation.NonNull;

import com.premiumlab.galleryx.data.MediaEngine;
import com.premiumlab.galleryx.util.CrashLog;
import com.premiumlab.galleryx.util.Fmt;
import com.premiumlab.galleryx.util.OpService;
import com.premiumlab.galleryx.util.Scan;

import java.io.File;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Пакетная обработка: файлы идут по одному (в памяти не больше одного кадра),
 * прогресс и итог приходят в главный поток. Операцию можно отменить —
 * уже сохранённые файлы остаются.
 */
public final class ResizeJob {

    public interface Listener {
        void onProgress(int done, int total, String name);

        void onFinished(@NonNull Summary summary);
    }

    /** Итог по одному файлу. */
    public static final class Item {
        public String srcPath;
        public String outPath;
        public long srcBytes;
        public long outBytes;
        public int width, height;
        public boolean targetMissed;
        public int errorCode;   // 0 — успех, иначе ImageResizer.ResizeException.*

        public boolean ok() {
            return errorCode == 0;
        }
    }

    public static final class Summary {
        public final List<Item> items = new ArrayList<>();
        public boolean cancelled;
        public File dir;

        public int okCount() {
            int n = 0;
            for (Item i : items) if (i.ok()) n++;
            return n;
        }

        public int failCount() {
            int n = 0;
            for (Item i : items) if (!i.ok()) n++;
            return n;
        }

        public int missedCount() {
            int n = 0;
            for (Item i : items) if (i.ok() && i.targetMissed) n++;
            return n;
        }

        public long srcBytes() {
            long n = 0;
            for (Item i : items) if (i.ok()) n += i.srcBytes;
            return n;
        }

        public long outBytes() {
            long n = 0;
            for (Item i : items) if (i.ok()) n += i.outBytes;
            return n;
        }

        public List<String> outPaths() {
            List<String> out = new ArrayList<>();
            for (Item i : items) if (i.ok() && i.outPath != null) out.add(i.outPath);
            return out;
        }
    }

    private static final ExecutorService EXEC = Executors.newSingleThreadExecutor();
    private final Handler main = new Handler(Looper.getMainLooper());
    private volatile boolean cancelled;

    public void cancel() {
        cancelled = true;
    }

    public boolean isCancelled() {
        return cancelled;
    }

    public void start(@NonNull Context ctx, @NonNull List<String> paths,
                      @NonNull ResizeOptions options, @NonNull File outDir,
                      @NonNull Listener listener) {
        final Context app = ctx.getApplicationContext();
        final ResizeOptions o = options.copy();
        final String title = app.getString(com.premiumlab.galleryx.R.string.resizer_op_title);
        OpService.begin(app, title);
        EXEC.execute(() -> {
            Summary sum = new Summary();
            sum.dir = outDir;
            try {
                for (int i = 0; i < paths.size(); i++) {
                    if (cancelled) {
                        sum.cancelled = true;
                        break;
                    }
                    String path = paths.get(i);
                    Item it = new Item();
                    it.srcPath = path;
                    sum.items.add(it);
                    final int idx = i;
                    final String name = new File(path).getName();
                    main.post(() -> listener.onProgress(idx, paths.size(), name));
                    OpService.progress(name, paths.size() == 0 ? 0 : idx * 100 / paths.size());
                    try {
                        ImageResizer.Source src = ImageResizer.probe(path);
                        it.srcBytes = src.bytes;
                        ImageResizer.Result r = ImageResizer.render(src, o, 0, this::isCancelled);
                        String base = ImageResizer.buildName(o.namePattern, src.name,
                                r.width, r.height, i + 1);
                        File target = Fmt.uniqueFile(outDir, base + o.extension());
                        ImageResizer.write(r, target, src, o);
                        it.outPath = target.getAbsolutePath();
                        it.outBytes = target.length();
                        it.width = r.width;
                        it.height = r.height;
                        it.targetMissed = r.targetMissed;
                        Scan.files(app, it.outPath);
                    } catch (ImageResizer.ResizeException e) {
                        it.errorCode = e.code;
                        if (e.code == ImageResizer.ResizeException.CANCELLED) {
                            sum.items.remove(it);
                            sum.cancelled = true;
                            break;
                        }
                    } catch (OutOfMemoryError e) {
                        it.errorCode = ImageResizer.ResizeException.OUT_OF_MEMORY;
                    } catch (Throwable t) {
                        CrashLog.record("ResizeJob", t);
                        it.errorCode = ImageResizer.ResizeException.WRITE_FAILED;
                    }
                }
            } finally {
                MediaEngine.invalidateAll();
                OpService.end(app);
                final Summary fs = sum;
                main.post(() -> listener.onFinished(fs));
            }
        });
    }
}
