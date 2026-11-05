package md.cityblock.zayavki;

import java.nio.charset.StandardCharsets;
import java.security.KeyFactory;
import java.security.PrivateKey;
import java.security.PublicKey;
import java.security.Signature;
import java.security.spec.PKCS8EncodedKeySpec;
import java.security.spec.X509EncodedKeySpec;
import java.time.LocalDate;
import java.util.Base64;

/**
 * Лицензионные ключи (без Android — тестируется на обычной JVM).
 *
 * Ключ: "PM1-" + base64url("имя|код_устройства|ГГГГ-ММ-ДД") + "." + base64url(подпись ECDSA P-256).
 * Подписывает владелец своим секретным ключом; в приложении — только открытый ключ для проверки.
 * Код устройства "*" — ключ подходит для любого телефона.
 */
public final class LicenseCore {
    private LicenseCore() {}

    /** Открытый ключ владельца (X.509, base64). Секретный ключ в приложении не хранится. */
    public static final String PUBLIC_KEY =
            "MFkwEwYHKoZIzj0CAQYIKoZIzj0DAQcDQgAEjez5ifCzz22x2ITd2v8VTGIs762vBAK+V6PB2zKiPLumFtNz+xvGneEYfCjx8Sn4aJJZKaheV5Nqv2fWjhjV4w==";

    static final String PREFIX = "PM1-";
    static final String OWNER_PREFIX = "PMOWNER-";
    public static final String ANY_DEVICE = "*";
    public static final String FOREVER = "2099-12-31";
    private static final String ALG = "SHA256withECDSA";

    public static final class Info {
        public String name = "", device = "", expiry = "";
    }

    /** Результат проверки: ok или текст ошибки. */
    public static final class Check {
        public boolean ok;
        public String error = "";
        public Info info;
    }

    // ------------------------------------------------------------------ проверка
    public static Check check(String key, String deviceCode, LocalDate today) {
        return check(key, deviceCode, today, publicKey(PUBLIC_KEY));
    }

    static Check check(String key, String deviceCode, LocalDate today, PublicKey pub) {
        Check c = new Check();
        try {
            String k = key == null ? "" : key.replaceAll("\\s+", "");
            if (k.isEmpty()) return fail(c, "Введите лицензионный ключ");
            if (!k.startsWith(PREFIX) || !k.contains(".")) return fail(c, "Это не ключ «Заявки PM»");
            String body = k.substring(PREFIX.length());
            String payloadB64 = body.substring(0, body.indexOf('.'));
            String sigB64 = body.substring(body.indexOf('.') + 1);
            byte[] payload = Base64.getUrlDecoder().decode(payloadB64);
            byte[] sig = Base64.getUrlDecoder().decode(sigB64);

            Signature s = Signature.getInstance(ALG);
            s.initVerify(pub);
            s.update(payload);
            if (!s.verify(sig)) return fail(c, "Ключ недействителен (подпись не совпадает)");

            String[] f = new String(payload, StandardCharsets.UTF_8).split("\\|", -1);
            if (f.length != 3) return fail(c, "Ключ повреждён");
            Info info = new Info();
            info.name = f[0];
            info.device = f[1];
            info.expiry = f[2];
            c.info = info;

            if (!ANY_DEVICE.equals(info.device) && !normalizeDevice(info.device).equals(normalizeDevice(deviceCode))) {
                return fail(c, "Ключ выдан для другого телефона (" + info.device + ")");
            }
            if (today.isAfter(LocalDate.parse(info.expiry))) {
                return fail(c, "Срок ключа закончился " + info.expiry);
            }
            c.ok = true;
            return c;
        } catch (IllegalArgumentException e) {
            return fail(c, "Ключ повреждён — скопируйте его целиком");
        } catch (Exception e) {
            return fail(c, "Не удалось проверить ключ: " + e.getMessage());
        }
    }

    private static Check fail(Check c, String msg) {
        c.ok = false;
        c.error = msg;
        return c;
    }

    public static String normalizeDevice(String d) {
        return d == null ? "" : d.replaceAll("[^A-Za-z0-9*]", "").toUpperCase();
    }

    // ------------------------------------------------------------------ владелец
    /** Создать ключ (только владелец — нужен секретный ключ). */
    public static String make(String name, String device, String expiry, PrivateKey priv) throws Exception {
        String clean = name.replace("|", "/").trim();
        String dev = ANY_DEVICE.equals(device.trim()) ? ANY_DEVICE : formatDevice(normalizeDevice(device));
        LocalDate.parse(expiry); // проверка формата
        byte[] payload = (clean + "|" + dev + "|" + expiry).getBytes(StandardCharsets.UTF_8);
        Signature s = Signature.getInstance(ALG);
        s.initSign(priv);
        s.update(payload);
        Base64.Encoder e = Base64.getUrlEncoder().withoutPadding();
        return PREFIX + e.encodeToString(payload) + "." + e.encodeToString(s.sign());
    }

    /** Разобрать секретный ключ владельца «PMOWNER-…» и убедиться, что он парный к открытому. */
    public static PrivateKey ownerKey(String text) throws Exception {
        String t = text == null ? "" : text.replaceAll("\\s+", "");
        if (!t.startsWith(OWNER_PREFIX)) throw new Exception("Это не ключ владельца");
        byte[] der = Base64.getDecoder().decode(t.substring(OWNER_PREFIX.length()));
        PrivateKey priv = KeyFactory.getInstance("EC").generatePrivate(new PKCS8EncodedKeySpec(der));
        // проверка пары: подписать и проверить открытым ключом приложения
        Signature s = Signature.getInstance(ALG);
        s.initSign(priv);
        s.update(new byte[]{1, 2, 3});
        byte[] sig = s.sign();
        Signature v = Signature.getInstance(ALG);
        v.initVerify(publicKey(PUBLIC_KEY));
        v.update(new byte[]{1, 2, 3});
        if (!v.verify(sig)) throw new Exception("Ключ владельца не подходит к этому приложению");
        return priv;
    }

    static PublicKey publicKey(String b64) {
        try {
            return KeyFactory.getInstance("EC").generatePublic(new X509EncodedKeySpec(Base64.getDecoder().decode(b64)));
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    /** ABCD1234 → ABCD-1234 */
    public static String formatDevice(String code) {
        String c = normalizeDevice(code);
        return c.length() == 8 ? c.substring(0, 4) + "-" + c.substring(4) : c;
    }

    private static final char[] ALPHABET = "ABCDEFGHJKLMNPQRSTUVWXYZ23456789".toCharArray(); // без 0/O, 1/I

    /** Код устройства из уникального идентификатора: 8 символов, без путаницы 0/O и 1/I. */
    public static String deviceCodeFrom(String id) {
        try {
            byte[] h = java.security.MessageDigest.getInstance("SHA-256")
                    .digest(("zayavki-pm:" + id).getBytes(StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder();
            for (int i = 0; i < 8; i++) sb.append(ALPHABET[(h[i] & 0xff) % ALPHABET.length]);
            return formatDevice(sb.toString());
        } catch (Exception e) {
            return "UNKNOWN";
        }
    }
}
