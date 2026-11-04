package md.cityblock.zayavki;

import android.annotation.SuppressLint;
import android.content.Context;
import android.net.http.SslError;
import android.os.Handler;
import android.os.Looper;
import android.webkit.CookieManager;
import android.webkit.SslErrorHandler;
import android.webkit.WebResourceError;
import android.webkit.WebResourceRequest;
import android.webkit.WebResourceResponse;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;

/**
 * Вход в ProcessMaker через невидимый WebView: открывает настоящую страницу входа,
 * вписывает логин/пароль и нажимает кнопку — как человек. Куки сессии остаются
 * в общем CookieManager и используются для запросов списка заявок.
 * Вызывать только из главного потока.
 */
final class WebLogin {
    interface Callback { void done(boolean ok, String error); }

    private static final long TIMEOUT_MS = 60_000;

    @SuppressLint("SetJavaScriptEnabled")
    static void run(Context ctx, String loginUrl, String user, String pass, Callback cb) {
        final Handler h = new Handler(Looper.getMainLooper());
        final WebView wv = new WebView(ctx.getApplicationContext());
        WebSettings s = wv.getSettings();
        s.setJavaScriptEnabled(true);
        s.setDomStorageEnabled(true);
        CookieManager.getInstance().setAcceptCookie(true);

        final int[] state = {0};       // 0 — ждём страницу входа, 1 — пароль отправлен
        final int[] fillTries = {0};
        final boolean[] finished = {false};
        final Object token = new Object();

        final class Finisher {
            void finish(boolean ok, String err) {
                if (finished[0]) return;
                finished[0] = true;
                h.removeCallbacksAndMessages(token);
                CookieManager.getInstance().flush();
                h.post(() -> { wv.stopLoading(); wv.destroy(); });
                cb.done(ok, err);
            }
        }
        final Finisher fin = new Finisher();

        h.postAtTime(() -> fin.finish(false, state[0] == 0
                        ? "Страница входа не загрузилась (нет связи с сайтом?)"
                        : "Сайт не ответил после ввода пароля"),
                token, android.os.SystemClock.uptimeMillis() + TIMEOUT_MS);

        wv.setWebViewClient(new WebViewClient() {
            @Override
            public void onPageFinished(WebView view, String url) {
                if (finished[0]) return;
                if (state[0] == 0) tryFill(view, url);
                else afterSubmit(view, url);
            }

            void tryFill(WebView view, String url) {
                view.evaluateJavascript(LoginJs.fill(user, pass), raw -> {
                    if (finished[0]) return;
                    String r = LoginJs.result(raw);
                    if ("submitted".equals(r)) {
                        state[0] = 1;
                    } else if (!PmCore.isLoginUrl(url)) {
                        fin.finish(true, null); // уже вошли (куки ещё действуют)
                    } else if (++fillTries[0] < 6) {
                        // страница могла ещё не дорисоваться
                        h.postAtTime(() -> { if (!finished[0]) tryFill(view, view.getUrl()); },
                                token, android.os.SystemClock.uptimeMillis() + 1500);
                    } else {
                        fin.finish(false, "На странице не найдены поля логина/пароля");
                    }
                });
            }

            void afterSubmit(WebView view, String url) {
                if (!PmCore.isLoginUrl(url)) {
                    fin.finish(true, null);
                    return;
                }
                // всё ещё страница входа: если снова видна форма — пароль не подошёл
                view.evaluateJavascript(LoginJs.HAS_PASSWORD, has -> {
                    if (finished[0] || !"yes".equals(LoginJs.result(has))) return; // ждём редирект
                    view.evaluateJavascript(LoginJs.ERROR, msg -> {
                        String m = LoginJs.result(msg);
                        fin.finish(false, "Неверный логин или пароль" + (m.isEmpty() ? "" : " (" + m + ")"));
                    });
                });
            }

            @Override
            public void onReceivedError(WebView view, WebResourceRequest req, WebResourceError err) {
                if (req.isForMainFrame()) {
                    fin.finish(false, "Ошибка сети: " + err.getDescription());
                }
            }

            @Override
            public void onReceivedHttpError(WebView view, WebResourceRequest req, WebResourceResponse resp) {
                if (req.isForMainFrame() && resp.getStatusCode() >= 400) {
                    fin.finish(false, "Сайт ответил ошибкой " + resp.getStatusCode()
                            + (resp.getStatusCode() == 403 ? " (доступ запрещён — нет доступа из этой сети?)" : ""));
                }
            }

            @Override
            public void onReceivedSslError(WebView view, SslErrorHandler handler, SslError error) {
                handler.cancel();
                fin.finish(false, "Ошибка SSL-сертификата сайта");
            }
        });
        wv.loadUrl(loginUrl);
    }
}
