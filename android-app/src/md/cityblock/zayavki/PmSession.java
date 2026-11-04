package md.cityblock.zayavki;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import android.webkit.WebSettings;

import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

/**
 * Работа с сайтом для экранов и фоновой службы. Вызывать НЕ из главного потока.
 * Вход выполняется один раз, даже если его одновременно запросили несколько потоков.
 */
final class PmSession {
    private static final Object LOGIN_LOCK = new Object();

    static void init(Context ctx) {
        if (Net.userAgent == null) {
            try {
                Net.userAgent = WebSettings.getDefaultUserAgent(ctx);
            } catch (Exception ignored) {
            }
        }
    }

    /** Список заявок; при истёкшей сессии входит заново. */
    static List<PmCore.Case> cases(Context ctx, String action) throws Exception {
        init(ctx);
        Prefs p = new Prefs(ctx);
        if (!p.configured()) throw new Exception("Укажите логин и пароль в настройках");
        String base = p.base();
        try {
            return Net.fetchCases(base, action);
        } catch (PmCore.SessionExpired e) {
            login(ctx, base, p);
            return Net.fetchCases(base, action);
        }
    }

    /** Догрузить клиента, телефон, описание и заметки. Ошибки игнорируются. */
    static void details(Context ctx, PmCore.Case c, String action) {
        init(ctx);
        Net.fetchDetails(new Prefs(ctx).base(), c, action, null);
    }

    static void login(Context ctx, String base, Prefs p) throws Exception {
        synchronized (LOGIN_LOCK) {
            // пока ждали, мог войти другой поток
            try {
                Net.fetchCases(base, "unassigned");
                return;
            } catch (PmCore.SessionExpired ignored) {
            } catch (Exception ignored) {
            }
            final CountDownLatch latch = new CountDownLatch(1);
            final String[] error = {null};
            final boolean[] ok = {false};
            new Handler(Looper.getMainLooper()).post(() ->
                    WebLogin.run(ctx, PmCore.loginUrl(base), p.user(), p.pass(), (success, err) -> {
                        ok[0] = success;
                        error[0] = err;
                        latch.countDown();
                    }));
            if (!latch.await(90, TimeUnit.SECONDS)) throw new Exception("Вход занял слишком много времени");
            if (!ok[0]) throw new Exception(error[0]);
        }
    }
}
