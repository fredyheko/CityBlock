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
        public String key, uid, delIndex, number, title, process, task, sentBy, due;
        /** Заполняются отдельно (CaseDetails) — для новых заявок. */
        public List<String> phones = new ArrayList<>();
        public String description = "";
        public String client = "";
        public List<String> notes = new ArrayList<>();
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

    /** Открыть заявку в PM (там же кнопка Claim для Unassigned). */
    public static String openCaseUrl(String base, Case c, String action) {
        return base + "/cases/open?APP_UID=" + c.uid + "&DEL_INDEX=" + c.delIndex + "&action=" + action;
    }

    /** Страница, которую PM открывает перед Claim (запоминает заявку в сессии). */
    public static String catchOpenUrl(String base, Case c) {
        return base + "/cases/cases_Open?APP_UID=" + c.uid + "&DEL_INDEX=" + c.delIndex + "&action=unassigned";
    }

    public static String catchExecutePath() { return "/cases/cases_CatchExecute"; }

    /** Тело запроса Claim — как отправляет кнопка «Claim this case» на сайте. */
    public static String claimPost(Case c) {
        return "notValidateThisFields=" + enc("[]") + "&DynaformRequiredFields=" + enc("[]")
                + "&DynaformName=cases_Resume"
                + "&" + enc("form[APP_UID]") + "=" + enc(c.uid)
                + "&" + enc("form[APP_NUMBER]") + "=" + enc(c.number)
                + "&" + enc("form[BTN_CATCH]") + "=" + enc("Claim this case");
    }

    /** Форма текущего этапа на весь экран, без оболочки PM. */
    public static String formUrl(String base, String uid, String delIndex) {
        return formUrl(base, uid, delIndex, "todo");
    }

    public static String formUrl(String base, String uid, String delIndex, String action) {
        return base + "/cases/cases_Open?APP_UID=" + uid + "&DEL_INDEX=" + delIndex + "&action=" + action;
    }

    // ------------------------------------------------------------------ New case
    public static final class StartItem {
        public String category = "", title = "", proUid = "", tasUid = "";
    }

    public static String startAjaxPath() { return "/cases/casesStartPage_Ajax"; }

    /** Страница «New case» самого PM — запасной вариант. */
    public static String startPageUrl(String base) { return base + "/cases/casesStartPage?action=startCase"; }

    public static String startListPost() { return "action=getProcessList&node=root"; }

    public static String startCasePost(StartItem it) {
        return "action=startCase&processId=" + enc(it.proUid) + "&taskId=" + enc(it.tasUid);
    }

    /** Список процессов из ответа getProcessList (дерево «категория → процесс»). */
    public static List<StartItem> parseStartList(String body) throws Exception {
        String t = body == null ? "" : body.trim();
        if (!t.startsWith("[") && !t.startsWith("{")) throw new Exception("Неожиданный ответ: "
                + t.substring(0, Math.min(120, t.length())));
        Object root = t.startsWith("[") ? new JSONArray(t) : new JSONObject(t);
        List<StartItem> out = new ArrayList<>();
        walkStart(root, "", out);
        return out;
    }

    private static void walkStart(Object o, String category, List<StartItem> out) {
        if (o instanceof JSONArray) {
            JSONArray a = (JSONArray) o;
            for (int i = 0; i < a.length(); i++) walkStart(a.opt(i), category, out);
            return;
        }
        if (!(o instanceof JSONObject)) return;
        JSONObject j = (JSONObject) o;
        String text = pick(j, "text", "label", "title", "PRO_TITLE", "name");
        String tas = pick(j, "tas_uid", "TAS_UID", "taskId", "task_uid");
        String pro = pick(j, "pro_uid", "PRO_UID", "processId", "process_uid");
        if (!tas.isEmpty() && !pro.isEmpty()) {
            StartItem it = new StartItem();
            it.category = category;
            it.title = text;
            it.tasUid = tas;
            it.proUid = pro;
            out.add(it);
        }
        String childCat = (tas.isEmpty() && !text.isEmpty()) ? text : category;
        for (String k : new String[]{"children", "data", "processes", "items"}) {
            Object ch = j.opt(k);
            if (ch != null) walkStart(ch, childCat, out);
        }
    }

    private static final java.util.regex.Pattern APP_UID_RE =
            java.util.regex.Pattern.compile("APP_UID[\"'=:\\s]+([0-9a-f]{32})", java.util.regex.Pattern.CASE_INSENSITIVE);
    private static final java.util.regex.Pattern DEL_RE =
            java.util.regex.Pattern.compile("DEL_INDEX[\"'=:\\s]+(\\d+)", java.util.regex.Pattern.CASE_INSENSITIVE);

    /** Из ответа startCase достать {APP_UID, DEL_INDEX} новой заявки, или сообщение об ошибке. */
    public static String[] parseStarted(String body) throws Exception {
        String t = body == null ? "" : body.replace("\\/", "/");
        java.util.regex.Matcher a = APP_UID_RE.matcher(t);
        if (a.find()) {
            java.util.regex.Matcher d = DEL_RE.matcher(t);
            return new String[]{a.group(1), d.find() ? d.group(1) : "1"};
        }
        String msg = "";
        try {
            JSONObject o = new JSONObject(t.trim());
            msg = o.optString("message", o.optString("msg", ""));
        } catch (Exception ignored) {
        }
        throw new Exception(msg.isEmpty() ? "Сайт не вернул номер новой заявки" : msg);
    }

    /** PM вернулся к списку заявок — значит, этап отправлен (Continue). */
    public static boolean isCaseListUrl(String url) {
        return url != null && (url.contains("/cases/casesListExtJs") || url.contains("/cases/main")
                || url.contains("/cases/casesList"));
    }

    public static Case findByUid(List<Case> cases, String uid) {
        for (Case c : cases) if (uid != null && uid.equals(c.uid)) return c;
        return null;
    }

    static String enc(String s) {
        try {
            return java.net.URLEncoder.encode(s == null ? "" : s, "UTF-8");
        } catch (Exception e) {
            return s;
        }
    }

    /** Заголовок — просто номер («#1669021» или пусто), без адреса и описания. */
    public static boolean isBareTitle(String title) {
        String t = title == null ? "" : title.trim();
        return t.isEmpty() || t.matches("#?\\s*\\d+");
    }

    /** Части заголовка «client02 : RISCANI, STR…, 13, 1 : Aditional : 4 noiembrie 2026 | 14-18». */
    public static final class Title {
        public String account = "", address = "", type = "", date = "", slot = "";
    }

    public static Title title(String t) {
        Title r = new Title();
        if (t == null) return r;
        String[] seg = t.split("\\s+:\\s+");
        if (seg.length < 2) {
            r.address = t.trim();
            return r;
        }
        r.account = seg[0].trim();
        r.address = seg[1].trim();
        if (seg.length > 2) r.type = seg[2].trim();
        if (seg.length > 3) {
            StringBuilder rest = new StringBuilder();
            for (int i = 3; i < seg.length; i++) rest.append(i > 3 ? " : " : "").append(seg[i]);
            String[] ds = rest.toString().split("\\|");
            r.date = ds[0].trim();
            if (ds.length > 1) r.slot = ds[1].trim();
        }
        return r;
    }

    // ------------------------------------------------------------------ передача между экранами
    public static JSONObject toJson(Case c) {
        JSONObject o = new JSONObject();
        try {
            o.put("key", c.key).put("uid", c.uid).put("delIndex", c.delIndex).put("number", c.number)
                    .put("title", c.title).put("process", c.process).put("task", c.task)
                    .put("sentBy", c.sentBy).put("due", c.due).put("description", c.description)
                    .put("client", c.client).put("phones", new JSONArray(c.phones))
                    .put("notes", new JSONArray(c.notes));
        } catch (Exception ignored) {
        }
        return o;
    }

    public static Case fromJson(JSONObject o) {
        Case c = new Case();
        c.key = o.optString("key"); c.uid = o.optString("uid"); c.delIndex = o.optString("delIndex");
        c.number = o.optString("number"); c.title = o.optString("title"); c.process = o.optString("process");
        c.task = o.optString("task"); c.sentBy = o.optString("sentBy"); c.due = o.optString("due");
        c.description = o.optString("description"); c.client = o.optString("client");
        JSONArray ph = o.optJSONArray("phones");
        for (int i = 0; ph != null && i < ph.length(); i++) c.phones.add(ph.optString(i));
        JSONArray n = o.optJSONArray("notes");
        for (int i = 0; n != null && i < n.length(); i++) c.notes.add(n.optString(i));
        return c;
    }

    /** Подробности уже загружены (чтобы не грузить повторно). */
    public static boolean hasDetails(Case c) {
        return !c.description.isEmpty() || !c.phones.isEmpty() || !c.notes.isEmpty();
    }

    public static void copyDetails(Case from, Case to) {
        to.description = from.description;
        to.client = from.client;
        to.phones = new ArrayList<>(from.phones);
        to.notes = new ArrayList<>(from.notes);
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
        c.uid = uid;
        c.delIndex = delIndex.isEmpty() ? "1" : delIndex;
        c.title = pick(r, "APP_TITLE", "CASE_TITLE", "APP_TITLE_SHORT");
        c.process = pick(r, "APP_PRO_TITLE", "PRO_TITLE");
        c.task = pick(r, "APP_TAS_TITLE", "TAS_TITLE");
        c.sentBy = pick(r, "APP_DEL_PREVIOUS_USER", "PREVIOUS_USR_NAME", "PREVIOUS_USR_USERNAME");
        c.due = pick(r, "DEL_TASK_DUE_DATE", "DEL_TASK_DUE_DATE_LABEL");
        c.description = pick(r, "APP_DESCRIPTION", "CASE_DESCRIPTION", "DESCRIPTION");
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

    public static String details(Case c) { return details(c, false); }

    /** compactPhones: +37368000102 без пробелов — так Telegram на Android делает номер нажимаемым. */
    public static String details(Case c, boolean compactPhones) {
        StringBuilder sb = new StringBuilder();
        line(sb, "📍 ", c.title);
        line(sb, "👤 Клиент: ", c.client);
        for (String ph : c.phones) {
            line(sb, "📞 ", compactPhones ? CaseDetails.international(ph) : CaseDetails.pretty(ph));
        }
        List<String> comments = CaseDetails.comments(c.description);
        if (!comments.isEmpty()) {
            for (String cm : comments) line(sb, "📋 ", CaseDetails.shorten(cm, 500));
        } else if (c.client.isEmpty() && c.phones.isEmpty()) {
            // описание нестандартное — показываем как есть, чтобы ничего не потерять
            line(sb, "📋 ", CaseDetails.shorten(c.description, 700));
        }
        int shown = 0;
        for (int i = c.notes.size() - 1; i >= 0 && shown < 2; i--, shown++) {
            line(sb, "💬 ", CaseDetails.shorten(c.notes.get(i), 400));
        }
        return sb.toString().trim();
    }

    public static String telegramHtml(Case c, String homeUrl) {
        return "🔔 <b>Новая заявка #" + esc(c.number) + "</b>\n" + esc(details(c, true))
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
