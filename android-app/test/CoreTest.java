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
        check(det.contains("📞 +373 69 000 111") && det.contains("💬 Test"), "details text");
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
        check(CaseDetails.comments(desc).equals(Arrays.asList("operator1 Клиент не отвечает",
            "operator2 La moment resemnarii nu a fost schimbat echipament")), "comments after timestamps: " + CaseDetails.comments(desc));
        String det2 = PmCore.details(d);
        check(!det2.contains("FTTH") && !det2.contains("Срок") && !det2.contains("Отправил") && !det2.contains("Selectare"), "no duplicated/extra lines");
        check(CaseDetails.comments("Popescu Ana | 068000102 | FTTH RISCANI | Shelter: Riskani 2026-11-03 15:52:23 operator3 Modificare abonament ;schimb echipament")
            .equals(Collections.singletonList("operator3 Modificare abonament ;schimb echipament")), "single comment");
        String tg = PmCore.telegramHtml(d, "https://x");
        check(tg.contains("📞 +37369000101\n"), "telegram phone compact");
        System.out.println("── telegram ──\n" + tg);
        System.out.println("────────\n🔔 Новая заявка #" + d.number + "\n" + PmCore.details(d) + "\n────────");

        // --- для экранов приложения
        PmCore.Title tt = PmCore.title("client02 : RISCANI, STR-LA LALELELOR,  13, 1 : Aditional : 4 noiembrie 2026  |  14-18");
        check(tt.account.equals("client02") && tt.address.equals("RISCANI, STR-LA LALELELOR,  13, 1") && tt.type.equals("Aditional")
            && tt.date.equals("4 noiembrie 2026") && tt.slot.equals("14-18"), "title parts");
        PmCore.Title t2 = PmCore.title("199073 : NIHORENI, STR. VIILOR,  4, 1 : Aditional : 2026-11-04 08:47:34");
        check(t2.address.startsWith("NIHORENI") && t2.slot.isEmpty() && t2.date.equals("2026-11-04 08:47:34"), "title without slot");
        check(PmCore.title("Просто текст").address.equals("Просто текст"), "title fallback");
        String d2 = "Popescu Ana | 068000102 | FTTH RISCANI, STR-LA LALELELOR, 13, 1 | RS1.3.D2, port 7 on Switch RS1.3.D2-01-SPL02, ODF: RS1.3.D2-ODF3 port 15, Cable RS1.3.D2-CAB4 fiber 3, Dulap RS1.3.D2.C4 port 3, Shelter: Riskani 2026-11-03 15:52:23 operator3 Modificare abonament ;schimb echipament";
        check(CaseDetails.service(d2).equals("FTTH RISCANI, STR-LA LALELELOR, 13, 1"), "service");
        check(CaseDetails.techLines(d2).equals(Arrays.asList("RS1.3.D2", "port 7 on Switch RS1.3.D2-01-SPL02", "ODF: RS1.3.D2-ODF3 port 15",
            "Cable RS1.3.D2-CAB4 fiber 3", "Dulap RS1.3.D2.C4 port 3", "Shelter: Riskani")), "tech lines: " + CaseDetails.techLines(d2));
        List<CaseDetails.Comment> ce = CaseDetails.commentEntries(desc);
        check(ce.size() == 2 && ce.get(0).author.equals("operator1") && ce.get(0).text.equals("Клиент не отвечает")
            && ce.get(0).when.equals("2026-10-30 15:09:23") && ce.get(1).author.equals("operator2"), "comment entries");
        check(CaseDetails.commentEntries("x 2026-11-03 15:52 Schimb echipament").get(0).author.isEmpty(), "no author when capitalised");
        PmCore.Case back = PmCore.fromJson(PmCore.toJson(d));
        check(back.key.equals(d.key) && back.phones.equals(d.phones) && back.description.equals(d.description)
            && back.notes.equals(d.notes) && back.client.equals(d.client), "json round trip");
        check(PmCore.openCaseUrl(base, d, "unassigned").endsWith("/cases/open?APP_UID=1983&DEL_INDEX=2&action=unassigned"), "open case url");

        // --- взять / заполнить
        PmCore.Case cl = new PmCore.Case();
        cl.uid = "11112222333344445555666677778888"; cl.delIndex = "4"; cl.number = "1669260";
        check(PmCore.claimPost(cl).equals("notValidateThisFields=%5B%5D&DynaformRequiredFields=%5B%5D&DynaformName=cases_Resume"
            + "&form%5BAPP_UID%5D=11112222333344445555666677778888&form%5BAPP_NUMBER%5D=1669260&form%5BBTN_CATCH%5D=Claim+this+case"),
            "claim post: " + PmCore.claimPost(cl));
        check(PmCore.catchOpenUrl(base, cl).endsWith("/cases/cases_Open?APP_UID=11112222333344445555666677778888&DEL_INDEX=4&action=unassigned"), "catch open url");
        check(PmCore.formUrl(base, cl.uid, "5").endsWith("/cases/cases_Open?APP_UID=11112222333344445555666677778888&DEL_INDEX=5&action=todo"), "form url");
        check(PmCore.isCaseListUrl(base + "/cases/casesListExtJs") && !PmCore.isCaseListUrl(base + "/cases/cases_Step?TYPE=ASSIGN_TASK"), "case list url");
        check(PmCore.findByUid(Arrays.asList(cl), cl.uid) == cl && PmCore.findByUid(Arrays.asList(cl), "x") == null, "find by uid");

        // --- New case
        String tree = "[{\"text\":\"Dashboard\",\"id\":\"cat1\",\"children\":["
            + "{\"text\":\"Cabinet Personal (Vizualizare date personale)\",\"pro_uid\":\"p1\",\"tas_uid\":\"t1\",\"leaf\":true},"
            + "{\"text\":\"Diagnostica (Diagnostica Client)\",\"pro_uid\":\"p2\",\"tas_uid\":\"t2\",\"leaf\":true}]},"
            + "{\"text\":\"Warehouse management\",\"children\":[{\"text\":\"EQMovement (Versiunea noua)\",\"pro_uid\":\"p3\",\"tas_uid\":\"t3\"}]}]";
        List<PmCore.StartItem> st = PmCore.parseStartList(tree);
        check(st.size() == 3 && st.get(0).category.equals("Dashboard") && st.get(0).tasUid.equals("t1")
            && st.get(2).category.equals("Warehouse management") && st.get(2).title.equals("EQMovement (Versiunea noua)"), "start list");
        check(PmCore.startCasePost(st.get(1)).equals("action=startCase&processId=p2&taskId=t2"), "start post");
        String[] started = PmCore.parseStarted("{\"status\":\"success\",\"openCase\":{\"PAGE\":\"..\\/cases\\/open?APP_UID=0123456789abcdef0123456789abcdef&DEL_INDEX=1&action=draft\"}}");
        check(started[0].equals("0123456789abcdef0123456789abcdef") && started[1].equals("1"), "parse started");
        String[] started2 = PmCore.parseStarted("{\"APPLICATION\":\"x\",\"APP_UID\":\"fedcba9876543210fedcba9876543210\",\"DEL_INDEX\":\"3\"}");
        check(started2[0].startsWith("fedcba") && started2[1].equals("3"), "parse started json fields");
        try { PmCore.parseStarted("{\"status\":\"failure\",\"message\":\"No permission\"}"); check(false, "start error"); }
        catch (Exception e) { check(e.getMessage().equals("No permission"), "start error message"); }
        check(PmCore.formUrl(base, "u", "1", "draft").endsWith("APP_UID=u&DEL_INDEX=1&action=draft"), "draft form url");

        check(PmCore.isBareTitle("#1669021") && PmCore.isBareTitle("1669021") && PmCore.isBareTitle("")
            && !PmCore.isBareTitle("client01 : RISCANI, STR. TEILOR, 11, 1"), "bare title");

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
