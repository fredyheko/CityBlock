package md.cityblock.zayavki;

import android.webkit.CookieManager;

import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;

/** HTTP-запросы: список заявок (с куками WebView) и Telegram. */
final class Net {
    static String userAgent = null;

    static final class Resp {
        int code;
        String body = "";
        String location;
        String url;
    }

    /** Запрос к сайту с куками сессии. post == null → GET, иначе POST (form-urlencoded). */
    static Resp request(String base, String pathOrUrl, String post) throws Exception {
        CookieManager cm = CookieManager.getInstance();
        String cookie = cm.getCookie(base);
        if (cookie == null || cookie.isEmpty()) throw new PmCore.SessionExpired();
        String url = pathOrUrl.startsWith("http") ? pathOrUrl : base + pathOrUrl;
        HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
        try {
            c.setConnectTimeout(20_000);
            c.setReadTimeout(30_000);
            c.setInstanceFollowRedirects(false);
            c.setRequestProperty("Cookie", cookie);
            c.setRequestProperty("X-Requested-With", "XMLHttpRequest");
            c.setRequestProperty("Accept", "application/json, text/javascript, text/html, */*");
            if (userAgent != null) c.setRequestProperty("User-Agent", userAgent);
            if (post != null) {
                c.setRequestMethod("POST");
                c.setDoOutput(true);
                c.setRequestProperty("Content-Type", "application/x-www-form-urlencoded; charset=UTF-8");
                try (OutputStream os = c.getOutputStream()) {
                    os.write(post.getBytes(StandardCharsets.UTF_8));
                }
            }
            Resp r = new Resp();
            r.code = c.getResponseCode();
            r.url = url;
            storeCookies(c, base);
            r.location = c.getHeaderField("Location");
            r.body = read(r.code >= 400 ? c.getErrorStream() : c.getInputStream());
            return r;
        } finally {
            c.disconnect();
        }
    }

    /** Получить список заявок. Бросает PmCore.SessionExpired, если нужен вход. */
    static List<PmCore.Case> fetchCases(String base, String action) throws Exception {
        Resp r = request(base, PmCore.listUrl(base, action), null);
        if (r.code >= 300 && r.code < 400) {
            if (r.location == null || r.location.contains("login")) throw new PmCore.SessionExpired();
            throw new Exception("Сайт перенаправил на " + r.location);
        }
        if (r.code == 401) throw new PmCore.SessionExpired();
        if (r.code >= 400) {
            if (r.body.toLowerCase().contains("login")) throw new PmCore.SessionExpired();
            throw new Exception("Сайт ответил ошибкой " + r.code);
        }
        return PmCore.parse(r.body, r.url);
    }

    /**
     * Догрузить телефон/описание/заметки заявки. Ошибки не прерывают работу.
     * Если diag != null — пишет туда сырые ответы сайта (для диагностики).
     */
    static void fetchDetails(String base, PmCore.Case c, String action, StringBuilder diag) {
        List<String> names = new java.util.ArrayList<>();
        List<String> bodies = new java.util.ArrayList<>();
        for (CaseDetails.Req q : CaseDetails.requests(c, action)) {
            names.add(q.name);
            try {
                Resp r = request(base, q.path, q.post);
                boolean ok = r.code == 200;
                bodies.add(ok ? r.body : "");
                if (diag != null) {
                    diag.append("\n━━ ").append(q.name).append(" → HTTP ").append(r.code)
                            .append(r.location != null ? " → " + r.location : "").append('\n')
                            .append(CaseDetails.shorten(CaseDetails.toText(r.body), 2500)).append('\n');
                }
            } catch (Exception e) {
                bodies.add("");
                if (diag != null) diag.append("\n━━ ").append(q.name).append(" → ").append(e).append('\n');
            }
        }
        CaseDetails.fill(c, names, bodies);
    }

    private static void storeCookies(HttpURLConnection c, String base) {
        Map<String, List<String>> h = c.getHeaderFields();
        for (Map.Entry<String, List<String>> e : h.entrySet()) {
            if (e.getKey() != null && e.getKey().equalsIgnoreCase("Set-Cookie")) {
                for (String v : e.getValue()) CookieManager.getInstance().setCookie(base, v);
            }
        }
    }

    static String read(InputStream in) throws Exception {
        if (in == null) return "";
        try (InputStream is = in) {
            ByteArrayOutputStream bo = new ByteArrayOutputStream();
            byte[] buf = new byte[8192];
            int n;
            while ((n = is.read(buf)) > 0) bo.write(buf, 0, n);
            return bo.toString("UTF-8");
        }
    }

    /** Отправить сообщение в Telegram (если настроен). Ошибки возвращает текстом. */
    static String telegram(String token, String chats, String html) {
        if (token.isEmpty() || chats.isEmpty()) return null;
        String err = null;
        for (String chat : chats.split(",")) {
            chat = chat.trim();
            if (chat.isEmpty()) continue;
            try {
                HttpURLConnection c = (HttpURLConnection)
                        new URL("https://api.telegram.org/bot" + token + "/sendMessage").openConnection();
                c.setConnectTimeout(15_000);
                c.setReadTimeout(20_000);
                c.setRequestMethod("POST");
                c.setDoOutput(true);
                c.setRequestProperty("Content-Type", "application/json; charset=utf-8");
                JSONObject body = new JSONObject()
                        .put("chat_id", chat).put("text", html)
                        .put("parse_mode", "HTML").put("disable_web_page_preview", true);
                try (OutputStream os = c.getOutputStream()) {
                    os.write(body.toString().getBytes(StandardCharsets.UTF_8));
                }
                int code = c.getResponseCode();
                if (code != 200) {
                    err = "Telegram " + code + ": " + read(c.getErrorStream());
                }
                c.disconnect();
            } catch (Exception e) {
                err = "Telegram: " + e.getMessage();
            }
        }
        return err;
    }
}
