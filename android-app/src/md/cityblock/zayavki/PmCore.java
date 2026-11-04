package md.cityblock.zayavki;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Set;

/** Логика ProcessMaker без зависимостей от Android (тестируется на обычной JVM). */
public final class PmCore {
    private PmCore() {}

    public static final class Case {
        public String key, number, title, process, task, sentBy, due;
    }

    /** Сессия истекла — нужно войти заново. */
    public static final class SessionExpired extends Exception {
        public SessionExpired() { super("Сессия истекла"); }
    }

    public static String base(String host, String workspace) {
        String h = host == null ? "" : host.trim();
        while (h.endsWith("/")) h = h.substring(0, h.length() - 1);
        if (!h.startsWith("http://") && !h.startsWith("https://")) h = "https://" + h;
        return h + "/sys" + workspace.trim() + "/en/neoclassic";
    }

    public static String loginUrl(String base) { return base + "/login/login"; }

    public static String homeUrl(String base) { return base + "/cases/main"; }

    public static String listUrl(String base, String action) {
        return base + "/cases/proxyCasesList?action=" + action
                + "&start=0&limit=100&sort=APP_NUMBER&dir=DESC";
    }

    public static boolean isLoginUrl(String url) {
        return url != null && url.contains("/login/");
    }

    /** Разобрать ответ proxyCasesList. */
    public static List<Case> parse(String body, String finalUrl) throws Exception {
        if (isLoginUrl(finalUrl)) throw new SessionExpired();
        String t = body == null ? "" : body.trim();
        if (!t.startsWith("{") && !t.startsWith("[")) {
            String low = t.toLowerCase();
            if (low.contains("login") || low.contains("usr_username") || low.contains("password")) {
                throw new SessionExpired();
            }
            throw new Exception("Неожиданный ответ сервера: "
                    + t.substring(0, Math.min(150, t.length())));
        }
        JSONArray rows;
        if (t.startsWith("[")) {
            rows = new JSONArray(t);
        } else {
            JSONObject o = new JSONObject(t);
            rows = o.optJSONArray("data");
            if (rows == null) {
                String msg = o.optString("message", o.optString("msg", ""));
                String low = (msg + " " + t).toLowerCase();
                if (low.contains("session") || low.contains("login")) throw new SessionExpired();
                throw new Exception("В ответе нет списка заявок: "
                        + t.substring(0, Math.min(150, t.length())));
            }
        }
        List<Case> out = new ArrayList<>();
        for (int i = 0; i < rows.length(); i++) {
            JSONObject r = rows.optJSONObject(i);
            if (r != null) out.add(normalize(r));
        }
        return out;
    }

    static Case normalize(JSONObject r) {
        Case c = new Case();
        c.number = pick(r, "APP_NUMBER", "app_number");
        String uid = pick(r, "APP_UID", "app_uid");
        String delIndex = pick(r, "DEL_INDEX", "del_index");
        c.title = pick(r, "APP_TITLE", "CASE_TITLE", "APP_TITLE_SHORT");
        c.process = pick(r, "APP_PRO_TITLE", "PRO_TITLE");
        c.task = pick(r, "APP_TAS_TITLE", "TAS_TITLE");
        c.sentBy = pick(r, "APP_DEL_PREVIOUS_USER", "PREVIOUS_USR_NAME", "PREVIOUS_USR_USERNAME");
        c.due = pick(r, "DEL_TASK_DUE_DATE", "DEL_TASK_DUE_DATE_LABEL");
        c.key = (uid.isEmpty() ? c.number : uid) + ":" + delIndex;
        return c;
    }

    private static String pick(JSONObject r, String... keys) {
        for (String k : keys) {
            if (r.has(k) && !r.isNull(k)) {
                String v = r.optString(k, "").trim();
                if (!v.isEmpty()) return v;
            }
        }
        return "";
    }

    /** Заявки, которых не было при прошлой проверке (от старых к новым). */
    public static List<Case> newCases(List<Case> cases, Set<String> seen) {
        List<Case> out = new ArrayList<>();
        for (int i = cases.size() - 1; i >= 0; i--) {
            if (!seen.contains(cases.get(i).key)) out.add(cases.get(i));
        }
        return out;
    }

    public static Set<String> keys(Collection<Case> cases, Set<String> into) {
        for (Case c : cases) into.add(c.key);
        return into;
    }

    public static String details(Case c) {
        StringBuilder sb = new StringBuilder();
        line(sb, "📍 ", c.title);
        line(sb, "⚙️ ", c.process);
        line(sb, "📝 ", c.task);
        line(sb, "👤 ", c.sentBy);
        line(sb, "⏰ Срок: ", c.due);
        return sb.toString().trim();
    }

    public static String telegramHtml(Case c, String homeUrl) {
        return "🔔 <b>Новая заявка #" + esc(c.number) + "</b>\n" + esc(details(c))
                + "\n\n<a href=\"" + esc(homeUrl) + "\">Открыть ProcessMaker</a>";
    }

    private static void line(StringBuilder sb, String prefix, String v) {
        if (v != null && !v.isEmpty()) sb.append(prefix).append(v).append('\n');
    }

    public static String esc(String s) {
        if (s == null) return "";
        return s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")
                .replace("\"", "&quot;");
    }
}
