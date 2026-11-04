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

    /** Страница списка; при истёкшей сессии входит заново. */
    static PmCore.Page page(Context ctx, String action, int start, int limit) throws Exception {
        init(ctx);
        Prefs p = new Prefs(ctx);
        if (!p.configured()) throw new Exception("Укажите логин и пароль в настройках");
        String base = p.base();
        try {
            return Net.fetchPage(base, action, start, limit);
        } catch (PmCore.SessionExpired e) {
            login(ctx, base, p);
            return Net.fetchPage(base, action, start, limit);
        }
    }

    /** Догрузить клиента, телефон, описание и заметки. Ошибки игнорируются. */
    static void details(Context ctx, PmCore.Case c, String action) {
        init(ctx);
        Net.fetchDetails(new Prefs(ctx).base(), c, action, null);
    }

    /**
     * Взять заявку (Claim) — те же запросы, что делает сайт: открыть заявку, нажать «Claim this case».
     * Успех проверяется по списку Inbox. Возвращает заявку из Inbox (с новым DEL_INDEX).
     */
    static PmCore.Case claim(Context ctx, PmCore.Case c) throws Exception {
        init(ctx);
        Prefs p = new Prefs(ctx);
        String base = p.base();
        // заодно входит на сайт, если сессия истекла
        PmCore.Case fresh = PmCore.findByUid(cases(ctx, "unassigned"), c.uid);
        if (fresh == null) {
            PmCore.Case mine = PmCore.findByUid(Net.fetchCases(base, "todo"), c.uid);
            if (mine != null) return mine; // уже ваша
            throw new Exception("Заявки уже нет в Unassigned — похоже, её взял кто-то другой.");
        }
        c.delIndex = fresh.delIndex;
        Net.Resp open = Net.request(base, PmCore.catchOpenUrl(base, c), null);
        if (open.code >= 400) throw new Exception("Сайт не открыл заявку (ошибка " + open.code + ")");
        Net.Resp r = Net.request(base, PmCore.catchExecutePath(), PmCore.claimPost(c));
        if (r.code >= 400) throw new Exception("Сайт ответил ошибкой " + r.code);

        PmCore.Case mine = PmCore.findByUid(Net.fetchCases(base, "todo"), c.uid);
        if (mine == null) {
            throw new Exception("Заявка не появилась в вашем Inbox. Возможно, её уже взял кто-то другой "
                    + "или сайт ответил иначе — откройте её в PM.");
        }
        PmCore.copyDetails(c, mine);
        return mine;
    }

    /** Процессы, которые можно начать (New case). */
    static List<PmCore.StartItem> startList(Context ctx) throws Exception {
        init(ctx);
        Prefs p = new Prefs(ctx);
        cases(ctx, "draft"); // проверка входа
        Net.Resp r = Net.request(p.base(), PmCore.startAjaxPath(), PmCore.startListPost());
        if (r.code != 200) throw new Exception("Сайт ответил ошибкой " + r.code);
        List<PmCore.StartItem> items = PmCore.parseStartList(r.body);
        if (items.isEmpty()) throw new Exception("Список процессов пуст");
        return items;
    }

    /** Начать заявку (Start Case). Возвращает {APP_UID, DEL_INDEX}. */
    static String[] startCase(Context ctx, PmCore.StartItem it) throws Exception {
        init(ctx);
        Prefs p = new Prefs(ctx);
        Net.Resp r = Net.request(p.base(), PmCore.startAjaxPath(), PmCore.startCasePost(it));
        if (r.code >= 300 && r.code < 400 && r.location != null) return PmCore.parseStarted(r.location);
        if (r.code != 200) throw new Exception("Сайт ответил ошибкой " + r.code);
        return PmCore.parseStarted(r.body);
    }

    /** Заявка в моём Inbox (после отправки этапа — с новым DEL_INDEX), или null. */
    static PmCore.Case findMine(Context ctx, String uid) {
        try {
            return PmCore.findByUid(cases(ctx, "todo"), uid);
        } catch (Exception e) {
            return null;
        }
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
