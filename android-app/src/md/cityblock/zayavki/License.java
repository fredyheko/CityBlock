package md.cityblock.zayavki;

import android.content.Context;
import android.content.SharedPreferences;
import android.provider.Settings;

import java.security.PrivateKey;
import java.time.LocalDate;

/** Лицензия на этом телефоне: код устройства, сохранённый ключ, режим владельца. */
final class License {
    private static SharedPreferences sp(Context ctx) {
        return ctx.getApplicationContext().getSharedPreferences("license", Context.MODE_PRIVATE);
    }

    /** Код устройства, который человек присылает владельцу. */
    static String deviceCode(Context ctx) {
        String id = Settings.Secure.getString(ctx.getContentResolver(), Settings.Secure.ANDROID_ID);
        return LicenseCore.deviceCodeFrom(id == null ? "" : id);
    }

    static String key(Context ctx) { return sp(ctx).getString("key", ""); }

    static void saveKey(Context ctx, String key) {
        sp(ctx).edit().putString("key", key.replaceAll("\\s+", "")).apply();
    }

    /** Проверка текущей лицензии. Владелец (с секретным ключом) допущен всегда. */
    static LicenseCore.Check check(Context ctx) {
        if (isOwner(ctx)) {
            LicenseCore.Check c = new LicenseCore.Check();
            c.ok = true;
            c.info = new LicenseCore.Info();
            c.info.name = "Владелец";
            c.info.device = LicenseCore.ANY_DEVICE;
            c.info.expiry = LicenseCore.FOREVER;
            return c;
        }
        return LicenseCore.check(key(ctx), deviceCode(ctx), LocalDate.now());
    }

    static boolean ok(Context ctx) { return check(ctx).ok; }

    // ------------------------------------------------------------------ владелец
    static boolean isOwner(Context ctx) { return ownerKey(ctx) != null; }

    static PrivateKey ownerKey(Context ctx) {
        String t = sp(ctx).getString("owner", "");
        if (t.isEmpty()) return null;
        try {
            return LicenseCore.ownerKey(t);
        } catch (Exception e) {
            return null;
        }
    }

    /** Сохранить секретный ключ владельца (проверяется, что он парный к приложению). */
    static void setOwnerKey(Context ctx, String text) throws Exception {
        LicenseCore.ownerKey(text);
        sp(ctx).edit().putString("owner", text.replaceAll("\\s+", "")).apply();
    }

    static void removeOwnerKey(Context ctx) { sp(ctx).edit().remove("owner").apply(); }
}
