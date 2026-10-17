package com.premiumlab.galleryx.util;

import android.content.Context;
import android.media.MediaScannerConnection;

import com.premiumlab.galleryx.data.MediaEngine;

/**
 * Обновление системного индекса MediaStore после файловых операций.
 */
public final class Scan {

    private Scan() {
    }

    public static void files(Context ctx, String... paths) {
        if (paths == null || paths.length == 0) return;
        // Файловая система изменилась — кэш списка устарел
        MediaEngine.invalidateAll();
        try {
            MediaScannerConnection.scanFile(ctx.getApplicationContext(), paths, null, null);
        } catch (Exception ignored) {
        }
    }
}
