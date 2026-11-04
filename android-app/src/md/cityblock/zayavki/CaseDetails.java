package md.cityblock.zayavki;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Достаёт из ответов сайта телефон клиента и описание заявки
 * (без зависимостей от Android — тестируется на обычной JVM).
 */
public final class CaseDetails {
    private CaseDetails() {}

    /** Запросы, которыми получаем подробности заявки (относительно base). */
    public static final class Req {
        public final String name, path, post;
        Req(String name, String path, String post) { this.name = name; this.path = path; this.post = post; }
    }

    public static List<Req> requests(PmCore.Case c, String action) {
        String q = "APP_UID=" + c.uid + "&DEL_INDEX=" + c.delIndex;
        List<Req> r = new ArrayList<>();
        r.add(new Req("notes", "/appProxy/getNotesList", "appUid=" + c.uid + "&start=0&limit=50"));
        r.add(new Req("summaryAjax", "/cases/summaryAjax", "action=getDataSummary&" + q));
        r.add(new Req("summary", "/cases/summary?" + q + "&action=" + action, null));
        return r;
    }

    // Молдавские номера: +373 / 373 / 0, затем 8 цифр (мобильные 6x/7x, городские 2x).
    private static final Pattern PHONE = Pattern.compile(
            "(?<![\\w+])(?:\\+?373|0)[\\s\\-.]?\\(?([2-9]\\d)\\)?((?:[\\s\\-.]?\\d){6})(?!\\w)");
    // 8 цифр без префикса, начинаются на 6 или 7 (мобильный, записанный без 0)
    private static final Pattern PHONE_SHORT = Pattern.compile("(?<![\\w+\\-.])([67]\\d{7})(?!\\w|[\\-.]\\d)");

    private static final Pattern DESC_KEY = Pattern.compile(
            "descr|coment|comment|observ|nota|note|problem|sarcin|lucrar|detal|info|mesaj|motiv|"
                    + "описан|задач|коммент|примеч|проблем",
            Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE);
    private static final Pattern PHONE_KEY = Pattern.compile(
            "tel|phone|mobil|contact|nr_?cl|телеф", Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE);
    private static final Pattern NOT_DESC_KEY = Pattern.compile(
            "_label$|^lbl|title|uid|date|data_|_date|usr|user|tas_|pro_|dyn|grid|id$|^id",
            Pattern.CASE_INSENSITIVE);
    private static final Pattern JSON_PAIR = Pattern.compile(
            "\"([A-Za-z0-9_\\-. ]{2,60})\"\\s*:\\s*\"((?:[^\"\\\\]|\\\\.){3,2000})\"");
    private static final Pattern LABEL_LINE = Pattern.compile(
            "^\\s*([^:\\n]{2,40}):\\s*(.{3,1000})$", Pattern.MULTILINE);

    /** Разобрать ответы и записать телефоны/описание/заметки в заявку. */
    public static void fill(PmCore.Case c, List<String> names, List<String> bodies) {
        Set<String> phones = new LinkedHashSet<>();
        Set<String> desc = new LinkedHashSet<>();
        List<String> notes = new ArrayList<>();

        for (int i = 0; i < bodies.size(); i++) {
            String body = bodies.get(i);
            if (body == null || body.isEmpty()) continue;
            if ("notes".equals(names.get(i))) {
                notes.addAll(parseNotes(body));
                continue;
            }
            String text = toText(body);
            phones.addAll(keyedPhones(body));
            phones.addAll(findPhones(text));
            desc.addAll(keyedDescriptions(body));
            desc.addAll(labeledDescriptions(text));
        }
        for (String n : notes) phones.addAll(findPhones(n));

        c.phones = new ArrayList<>(phones);
        c.description = join(desc, "\n");
        c.notes = notes;
    }

    // ------------------------------------------------------------------ телефоны
    public static List<String> findPhones(String text) {
        Set<String> out = new LinkedHashSet<>();
        if (text == null) return new ArrayList<>(out);
        Matcher m = PHONE.matcher(text);
        while (m.find()) out.add("0" + m.group(1) + digits(m.group(2)));
        Matcher s = PHONE_SHORT.matcher(text);
        while (s.find()) out.add("0" + s.group(1));
        return new ArrayList<>(out);
    }

    private static String digits(String s) { return s.replaceAll("\\D", ""); }

    /** 069123456 → +373 69 123 456 */
    public static String pretty(String phone) {
        String d = digits(phone);
        if (d.startsWith("0") && d.length() == 9) d = d.substring(1);
        if (d.length() != 8) return phone;
        return "+373 " + d.substring(0, 2) + " " + d.substring(2, 5) + " " + d.substring(5);
    }

    // ------------------------------------------------------------------ заметки
    static List<String> parseNotes(String body) {
        List<String> out = new ArrayList<>();
        try {
            String t = body.trim();
            JSONArray arr = t.startsWith("[") ? new JSONArray(t) : null;
            if (arr == null) {
                JSONObject o = new JSONObject(t);
                arr = o.optJSONArray("notes");
                if (arr == null) arr = o.optJSONArray("data");
            }
            if (arr == null) return out;
            for (int i = 0; i < arr.length(); i++) {
                JSONObject n = arr.optJSONObject(i);
                if (n == null) continue;
                String content = first(n, "NOTE_CONTENT", "note_content", "content");
                if (content.isEmpty()) continue;
                String who = (first(n, "USR_FIRSTNAME") + " " + first(n, "USR_LASTNAME")).trim();
                if (who.isEmpty()) who = first(n, "USR_USERNAME", "USR_NAME");
                String date = first(n, "NOTE_DATE", "note_date");
                String head = (who + (date.isEmpty() ? "" : ", " + date)).trim();
                out.add((head.isEmpty() ? "" : head + ": ") + toText(content));
            }
        } catch (Exception ignored) {
        }
        return out;
    }

    // ------------------------------------------------------------------ описание
    static List<String> keyedDescriptions(String body) {
        List<String> out = new ArrayList<>();
        Matcher m = JSON_PAIR.matcher(body);
        while (m.find()) {
            String key = m.group(1);
            if (!DESC_KEY.matcher(key).find() || NOT_DESC_KEY.matcher(key).find()) continue;
            String v = toText(unescapeJson(m.group(2))).trim();
            if (looksLikeValue(v)) out.add(v);
        }
        return out;
    }

    /** Телефоны из полей формы с «телефонным» именем (в т.ч. внутри <script>). */
    static List<String> keyedPhones(String body) {
        List<String> out = new ArrayList<>();
        Matcher m = JSON_PAIR.matcher(body);
        while (m.find()) {
            if (!PHONE_KEY.matcher(m.group(1)).find() || m.group(1).toLowerCase().endsWith("label")) continue;
            String v = unescapeJson(m.group(2));
            List<String> found = findPhones(v);
            if (found.isEmpty()) {
                String d = digits(v);
                if (d.length() == 8 && d.matches("[2-9].*")) found.add("0" + d);
                else if (d.length() == 9 && d.startsWith("0")) found.add(d);
            }
            out.addAll(found);
        }
        return out;
    }

    static List<String> labeledDescriptions(String text) {
        List<String> out = new ArrayList<>();
        Matcher m = LABEL_LINE.matcher(text);
        while (m.find()) {
            if (DESC_KEY.matcher(m.group(1)).find()) {
                String v = m.group(2).trim();
                if (looksLikeValue(v)) out.add(v);
            }
        }
        return out;
    }

    private static boolean looksLikeValue(String v) {
        if (v.length() < 3) return false;
        if (v.matches("[0-9a-f]{32}")) return false;      // UID
        if (v.matches("[\\d\\-: ]+")) return false;      // дата/число
        return !v.startsWith("http") && !v.contains("{") && !v.contains("function");
    }

    // ------------------------------------------------------------------ утилиты
    static String unescapeJson(String s) {
        try {
            return new JSONArray("[\"" + s + "\"]").getString(0);
        } catch (Exception e) {
            return s;
        }
    }

    /** HTML/JSON → обычный текст. */
    public static String toText(String s) {
        if (s == null) return "";
        String t = s.replaceAll("(?is)<(script|style)[^>]*>.*?</\\1>", " ")
                .replaceAll("(?i)<br\\s*/?>|</(p|div|tr|li|h\\d)>", "\n")
                .replaceAll("(?i)</t[dh]>", " ")
                .replaceAll("<[^>]+>", " ")
                .replace("&nbsp;", " ").replace("&amp;", "&").replace("&lt;", "<")
                .replace("&gt;", ">").replace("&quot;", "\"").replace("&#39;", "'");
        t = t.replaceAll("[ \\t\\x0B\\f\\r]+", " ").replaceAll(" *\\n[ \\n]*", "\n");
        return t.trim();
    }

    private static String first(JSONObject o, String... keys) {
        for (String k : keys) {
            String v = o.optString(k, "").trim();
            if (!v.isEmpty() && !"null".equals(v)) return v;
        }
        return "";
    }

    private static String join(Iterable<String> it, String sep) {
        StringBuilder sb = new StringBuilder();
        for (String s : it) {
            if (sb.length() > 0) sb.append(sep);
            sb.append(s);
        }
        return sb.toString();
    }

    public static String shorten(String s, int max) {
        if (s == null) return "";
        return s.length() <= max ? s : s.substring(0, max - 1) + "…";
    }
}
