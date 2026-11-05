package md.cityblock.zayavki;

import java.nio.file.Files;
import java.nio.file.Paths;
import java.security.KeyPairGenerator;
import java.security.PrivateKey;
import java.security.spec.ECGenParameterSpec;
import java.time.LocalDate;

/** java ... LicenseTest <файл с ключом владельца> (секретный ключ в репозиторий не кладём). */
public class LicenseTest {
    static int fails = 0;
    static void check(boolean ok, String name) {
        System.out.println((ok ? "  ok   " : "  FAIL ") + name);
        if (!ok) fails++;
    }

    public static void main(String[] a) throws Exception {
        PrivateKey owner = LicenseCore.ownerKey(new String(Files.readAllBytes(Paths.get(a[0]))).trim());
        LocalDate today = LocalDate.of(2026, 11, 5);
        String dev = LicenseCore.deviceCodeFrom("android-id-123");
        check(dev.matches("[A-Z2-9]{4}-[A-Z2-9]{4}"), "device code format: " + dev);
        check(dev.equals(LicenseCore.deviceCodeFrom("android-id-123")) && !dev.equals(LicenseCore.deviceCodeFrom("other")), "device code stable & unique");

        String key = LicenseCore.make("Иван Петров", dev, "2027-05-05", owner);
        System.out.println("  key (" + key.length() + " chars): " + key);
        LicenseCore.Check c = LicenseCore.check(key, dev, today);
        check(c.ok && c.info.name.equals("Иван Петров") && c.info.expiry.equals("2027-05-05"), "valid key accepted");
        check(LicenseCore.check(" " + key.substring(0, 20) + "\n" + key.substring(20) + " ", dev.replace("-", "").toLowerCase(), today).ok, "spaces/newlines and code format tolerated");
        check(!LicenseCore.check(key, LicenseCore.deviceCodeFrom("other"), today).ok, "other device rejected: " + LicenseCore.check(key, "AAAA-BBBB", today).error);
        check(!LicenseCore.check(key, dev, LocalDate.of(2027, 5, 6)).ok, "expired rejected");
        check(LicenseCore.check(key, dev, LocalDate.of(2027, 5, 5)).ok, "valid on last day");

        // подделка: поменять дату в теле
        String body = key.substring(4, key.indexOf('.'));
        String forged = new String(java.util.Base64.getUrlDecoder().decode(body), "UTF-8").replace("2027-05-05", "2099-12-31");
        String forgedKey = "PM1-" + java.util.Base64.getUrlEncoder().withoutPadding().encodeToString(forged.getBytes("UTF-8")) + key.substring(key.indexOf('.'));
        check(!LicenseCore.check(forgedKey, dev, today).ok, "forged expiry rejected: " + LicenseCore.check(forgedKey, dev, today).error);

        // чужой секретный ключ
        KeyPairGenerator g = KeyPairGenerator.getInstance("EC");
        g.initialize(new ECGenParameterSpec("secp256r1"));
        PrivateKey other = g.generateKeyPair().getPrivate();
        String fake = LicenseCore.make("Хакер", LicenseCore.ANY_DEVICE, LicenseCore.FOREVER, other);
        check(!LicenseCore.check(fake, dev, today).ok, "key signed by someone else rejected");
        try {
            LicenseCore.ownerKey("PMOWNER-" + java.util.Base64.getEncoder().encodeToString(other.getEncoded()));
            check(false, "foreign owner key rejected");
        } catch (Exception e) { check(true, "foreign owner key rejected: " + e.getMessage()); }

        String any = LicenseCore.make("Склад", "*", LicenseCore.FOREVER, owner);
        check(LicenseCore.check(any, "ZZZZ-ZZZZ", today).ok, "any-device forever key");
        check(!LicenseCore.check("", dev, today).ok && !LicenseCore.check("hello", dev, today).ok
            && !LicenseCore.check("PM1-@@@.###", dev, today).ok, "garbage rejected without crash");

        System.out.println(fails == 0 ? "ALL PASSED" : fails + " FAILED");
        System.exit(fails == 0 ? 0 : 1);
    }
}
