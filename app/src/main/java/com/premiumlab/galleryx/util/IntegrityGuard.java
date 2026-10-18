package com.premiumlab.galleryx.util;

import android.content.Context;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.content.pm.Signature;
import android.content.pm.SigningInfo;
import android.os.Build;

import java.security.MessageDigest;
import java.util.Locale;

/**
 * Анти-тампер: проверка подписи APK при запуске.
 *
 * Зачем: если кто-то декомпилирует приложение, что-то в нём меняет
 * (реклама, малварь, «взломанная версия») и пересобирает — у такого APK
 * будет ДРУГОЙ сертификат подписи. Приложение с несовпавшей подписью
 * просто не запустится.
 *
 * КАК ВКЛЮЧИТЬ (один раз):
 * 1. Соберите release хотя бы один раз.
 * 2. Узнайте SHA-256 вашего сертификата подписи:
 *      keytool -list -v -keystore %USERPROFILE%\.android\debug.keystore -alias androiddebugkey -storepass android
 *    (строка «SHA256: AA:BB:CC:...») — либо в Android Studio:
 *    Gradle → app → Tasks → android → signingReport.
 * 3. Впишите хэш (можно с двоеточиями, регистр не важен) в EXPECTED_SHA256 ниже.
 * 4. Пересоберите. Готово — чужие пересборки запускаться не будут.
 *
 * Пока EXPECTED_SHA256 пустой — проверка полностью отключена и ни на что
 * не влияет (можно спокойно собирать debug и release).
 */
public final class IntegrityGuard {

    /** SHA-256 сертификата подписи. Пусто = проверка выключена. */
    private static final String EXPECTED_SHA256 = "";

    private IntegrityGuard() {
    }

    /**
     * @return true — всё в порядке (или проверка выключена);
     *         false — подпись APK не совпала с ожидаемой.
     */
    public static boolean verify(Context ctx) {
        String expected = EXPECTED_SHA256.replace(":", "").trim().toLowerCase(Locale.US);
        if (expected.isEmpty()) return true;
        try {
            String actual = certSha256(ctx);
            return actual != null && actual.equals(expected);
        } catch (Exception e) {
            // Не смогли проверить (экзотическая прошивка и т.п.) — не блокируем:
            // ложное срабатывание для владельца хуже, чем пропущенная проверка.
            return true;
        }
    }

    private static String certSha256(Context ctx) throws Exception {
        PackageManager pm = ctx.getPackageManager();
        Signature sig;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            PackageInfo pi = pm.getPackageInfo(ctx.getPackageName(),
                    PackageManager.GET_SIGNING_CERTIFICATES);
            SigningInfo si = pi.signingInfo;
            if (si == null) return null;
            Signature[] sigs = si.hasMultipleSigners()
                    ? si.getApkContentsSigners() : si.getSigningCertificateHistory();
            if (sigs == null || sigs.length == 0) return null;
            sig = sigs[0];
        } else {
            @SuppressWarnings("deprecation")
            PackageInfo pi = pm.getPackageInfo(ctx.getPackageName(),
                    PackageManager.GET_SIGNATURES);
            if (pi.signatures == null || pi.signatures.length == 0) return null;
            sig = pi.signatures[0];
        }
        byte[] digest = MessageDigest.getInstance("SHA-256").digest(sig.toByteArray());
        StringBuilder sb = new StringBuilder(digest.length * 2);
        for (byte b : digest) sb.append(String.format(Locale.US, "%02x", b & 0xFF));
        return sb.toString();
    }
}
