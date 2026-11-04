package md.cityblock.zayavki;

import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.*;

/** Тесты логики на обычной JVM: java -cp json.jar:... md.cityblock.zayavki.CoreTest <dir> */
public class CoreTest {
    static int fails = 0;
    static void check(boolean ok, String name) {
        System.out.println((ok ? "  ok   " : "  FAIL ") + name);
        if (!ok) fails++;
    }

    public static void main(String[] a) throws Exception {
        String base = PmCore.base("pm1.starnet.md/", "starnet");
        check(base.equals("https://pm1.starnet.md/sysstarnet/en/neoclassic"), "base url: " + base);
        check(PmCore.listUrl(base, "unassigned").endsWith("/cases/proxyCasesList?action=unassigned&start=0&limit=100&sort=APP_NUMBER&dir=DESC"), "list url");

        String json = "{\"totalCount\":2,\"data\":[" +
            "{\"APP_UID\":\"u2\",\"APP_NUMBER\":16602,\"DEL_INDEX\":2,\"APP_TITLE\":\"client03 : RISCANI, STR. TEILOR, 11\",\"APP_PRO_TITLE\":\"Conectare Client Home (Epicor)\",\"APP_TAS_TITLE\":\"Selectare Operatiune\",\"APP_DEL_PREVIOUS_USER\":\"Dispecer, Test\",\"DEL_TASK_DUE_DATE\":\"2026-11-04 17:00:00\"}," +
            "{\"APP_UID\":\"u1\",\"APP_NUMBER\":\"16601\",\"DEL_INDEX\":\"2\",\"APP_TITLE\":\"client01 : RISCANI <b>\",\"APP_PRO_TITLE\":null}]}";
        List<PmCore.Case> cs = PmCore.parse(json, base + "/cases/proxyCasesList");
        check(cs.size() == 2, "parse 2 cases");
        check(cs.get(0).number.equals("16602") && cs.get(0).key.equals("u2:2"), "numeric fields -> strings, key");
        check(cs.get(1).process.isEmpty(), "null field -> empty");

        Set<String> seen = new HashSet<>(Collections.singleton("u1:2"));
        List<PmCore.Case> fresh = PmCore.newCases(cs, seen);
        check(fresh.size() == 1 && fresh.get(0).number.equals("16602"), "only new case reported");
        check(PmCore.newCases(cs, PmCore.keys(cs, new HashSet<>())).isEmpty(), "no repeats on next check");
        List<PmCore.Case> all = PmCore.newCases(cs, new HashSet<>());
        check(all.get(0).number.equals("16601"), "new cases ordered oldest first");

        check(PmCore.telegramHtml(cs.get(1), "https://x").contains("RISCANI &lt;b&gt;"), "telegram html escaped");
        System.out.println(PmCore.details(cs.get(0)).replace("\n", " | "));

        check(PmCore.parse("{\"data\":[]}", base).isEmpty(), "empty list");
        check(throwsExpired("<html><form><input name=\"form[USR_USERNAME]\"></form></html>", base), "login html -> session expired");
        check(throwsExpired("{\"data\":[]}", base + "/login/login"), "redirected to login -> session expired");
        check(throwsExpired("{\"success\":false,\"message\":\"Session expired, please login\"}", base), "json session msg -> expired");
        try { PmCore.parse("Internal error", base); check(false, "garbage -> error"); }
        catch (PmCore.SessionExpired e) { check(false, "garbage -> error"); }
        catch (Exception e) { check(true, "garbage -> error: " + e.getMessage()); }

        check(LoginJs.result("\"submitted\"").equals("submitted"), "js result decode");
        check(LoginJs.result("null").isEmpty(), "js null");
        check(LoginJs.result("\"a\\\"b\\nc\"").equals("a\"b\nc"), "js escapes");

        // --- телефоны и описание
        List<String> ph = CaseDetails.findPhones("Tel: 069 123 456, +373 79-876-543; fix (022) 12-34-56; mob 68123456. "
            + "Data 2026-11-04 17:00:00, caz 166012, uid 0a67812345b9c, str. 16, 1 : 4 noiembrie 2026 | 08-14");
        check(ph.equals(Arrays.asList("069123456", "079876543", "022123456", "068123456")), "phones: " + ph);
        check(CaseDetails.pretty("069123456").equals("+373 69 123 456"), "pretty phone");
        check(CaseDetails.findPhones("2026-11-04 08:09:34 client01 166012 3456789012").isEmpty(), "no false phones");

        String notes = "{\"success\":true,\"notes\":[{\"USR_FIRSTNAME\":\"Test\",\"USR_LASTNAME\":\"Dispecer\","
            + "\"NOTE_DATE\":\"2026-11-04 08:09\",\"NOTE_CONTENT\":\"Client nou, sunati la 060 111 222<br>Instalare router\"}]}";
        String summary = "<script>var x={\"descriere_lucrari\":\"Conectare internet 100Mb + TV\",\"APP_UID\":\"0123456789abcdef0123456789abcdef\","
            + "\"descriere_label\":\"Descriere\",\"telefon_client\":\"+37369000111\",\"DEL_INIT_DATE\":\"2026-11-04\"};</script>"
            + "<table><tr><td>Comentariu:</td><td>Etajul 3, interfon 12</td></tr></table>";
        PmCore.Case k = cs.get(0);
        CaseDetails.fill(k, Arrays.asList("notes", "summaryAjax", "summary"), Arrays.asList(notes, "", summary));
        check(k.phones.equals(Arrays.asList("069000111", "060111222")), "case phones: " + k.phones);
        check(k.description.equals("Conectare internet 100Mb + TV\nEtajul 3, interfon 12"), "description: " + k.description.replace("\n", " | "));
        check(k.notes.size() == 1 && k.notes.get(0).startsWith("Test Dispecer, 2026-11-04 08:09: Client nou"), "notes: " + k.notes);
        String det = PmCore.details(k);
        check(det.contains("📞 +373 69 000 111") && det.contains("📋 Conectare") && det.contains("💬 Test"), "details text");
        System.out.println(det);
        k.description = "";
        CaseDetails.fill(k, Arrays.asList("notes", "summaryAjax", "summary"), Arrays.asList("<html>login</html>", "", null));
        check(k.phones.isEmpty() && k.description.isEmpty() && k.notes.isEmpty(), "garbage responses -> empty, no crash");

        // --- Case Description из окна Summary (как на скриншоте)
        String desc = "Ionescu Ion | 069000101 | FTTH RISCANI, STR. EXEMPLU, 16, 1 | RS1.1.D2, port 21 on Switch RS1.1.D2-01-SPL01, "
            + "ODF: RS1.1.D2-ODF2 port 21, Cable RS1.1.D2-CAB2 fiber 9, Dulap RS1.1.D2.C2 port 9, Shelter: Riskani 2026-10-30 15:09:23 "
            + "operator1 Клиент не отвечает 2026-10-29 10:07:14 operator2 La moment resemnarii nu a fost schimbat echipament";
        org.json.JSONArray data = new org.json.JSONArray()
            .put(new org.json.JSONObject().put("label", "Process").put("value", "Conectare Client Home (Epicor)").put("section", "Case Properties"))
            .put(new org.json.JSONObject().put("label", "Case Uid").put("value", "aaaabbbbccccdddd0000111122223333"))
            .put(new org.json.JSONObject().put("label", "Create Date").put("value", "2026-10-29 10:06:09"))
            .put(new org.json.JSONObject().put("label", "Case Description").put("value", desc));
        String sumJson = new org.json.JSONObject().put("success", true).put("data", data).toString();
        check(CaseDetails.caseDescription(sumJson).equals(desc), "case description from {label,value} JSON");
        String sumHtml = "<table><tr><td class=l>Case Number</td><td>1666663</td></tr><tr><td class=l>Case Description</td><td>"
            + desc.replace("Клиент", "Клиент&nbsp;") + "</td></tr></table>";
        check(CaseDetails.caseDescription(sumHtml).startsWith("Ionescu Ion | 069000101 |"), "case description from HTML table");
        check(CaseDetails.caseDescription("{\"APP_DESCRIPTION\":\"Ion | 079111222\"}").equals("Ion | 079111222"), "APP_DESCRIPTION key");
        PmCore.Case d = PmCore.parse("{\"data\":[{\"APP_UID\":\"1983\",\"APP_NUMBER\":1666663,\"DEL_INDEX\":2,\"APP_TITLE\":\"client01 : RISCANI, STR. EXEMPLU, 16, 1 : Aditional : 4 noiembrie 2026 | 08-14\",\"APP_DEL_PREVIOUS_USER\":\"Dispecer, Test\",\"DEL_TASK_DUE_DATE\":\"2026-11-04 17:00:00\"}]}", base).get(0);
        CaseDetails.fill(d, Arrays.asList("getSummary", "summaryAjax", "notes"), Arrays.asList(sumJson, "", notes));
        check(d.client.equals("Ionescu Ion"), "client name: " + d.client);
        check(d.phones.equals(Collections.singletonList("069000101")), "phone from description only: " + d.phones);
        check(d.description.equals(desc), "full description kept");
        System.out.println("────────\n🔔 Новая заявка #" + d.number + "\n" + PmCore.details(d) + "\n────────");

        if (a.length > 0) {
            Files.write(Paths.get(a[0], "fill_ok.js"), LoginJs.fill("tehnician.test", "se\"cr'et\\").getBytes("UTF-8"));
            Files.write(Paths.get(a[0], "fill_bad.js"), LoginJs.fill("tehnician.test", "wrong").getBytes("UTF-8"));
            Files.write(Paths.get(a[0], "has_pw.js"), LoginJs.HAS_PASSWORD.getBytes("UTF-8"));
            Files.write(Paths.get(a[0], "error.js"), LoginJs.ERROR.getBytes("UTF-8"));
        }
        System.out.println(fails == 0 ? "ALL PASSED" : fails + " FAILED");
        System.exit(fails == 0 ? 0 : 1);
    }

    static boolean throwsExpired(String body, String url) {
        try { PmCore.parse(body, url); return false; }
        catch (PmCore.SessionExpired e) { return true; }
        catch (Exception e) { return false; }
    }
}
