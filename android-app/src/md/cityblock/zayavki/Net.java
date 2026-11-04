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

    /** Получить список заявок. Бросает PmCore.SessionExpired, если нужен вход. */
    static List<PmCore.Case> fetchCases(String base, String action) throws Exception {
        CookieManager cm = CookieManager.getInstance();
        String cookie = cm.getCookie(base);
        if (cookie == null || cookie.isEmpty()) throw new PmCore.SessionExpired();

        HttpURLConnection c = (HttpURLConnection) new URL(PmCore.listUrl(base, action)).openConnection();
        try {
            c.setConnectTimeout(20_000);
            c.setReadTimeout(30_000);
            c.setInstanceFollowRedirects(false);
            c.setRequestProperty("Cookie", cookie);
            c.setRequestProperty("X-Requested-With", "XMLHttpRequest");
            c.setRequestProperty("Accept", "application/json, text/javascript, */*");
            if (userAgent != null) c.setRequestProperty("User-Agent", userAgent);

            int code = c.getResponseCode();
            storeCookies(c, base);
            if (code >= 300 && code < 400) {
                String loc = c.getHeaderField("Location");
                if (loc == null || PmCore.isLoginUrl(loc) || loc.contains("login")) {
                    throw new PmCore.SessionExpired();
                }
                throw new Exception("Сайт перенаправил на " + loc);
            }
            String body = read(code >= 400 ? c.getErrorStream() : c.getInputStream());
            if (code == 401) throw new PmCore.SessionExpired();
            if (code >= 400) {
                if (body.toLowerCase().contains("login")) throw new PmCore.SessionExpired();
                throw new Exception("Сайт ответил ошибкой " + code);
            }
            return PmCore.parse(body, c.getURL().toString());
        } finally {
            c.disconnect();
        }
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
