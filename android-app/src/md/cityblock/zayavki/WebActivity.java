package md.cityblock.zayavki;

import android.annotation.SuppressLint;
import android.app.Activity;
import android.app.AlertDialog;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.Intent;
import android.os.Bundle;
import android.view.Menu;
import android.view.MenuItem;
import android.webkit.WebResourceRequest;
import android.webkit.WebResourceResponse;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

/** Сайт ProcessMaker внутри приложения (уже с выполненным входом) + запись действий. */
public class WebActivity extends Activity {
    static final String EXTRA_URL = "url";
    private WebView web;

    static Intent intent(Context ctx, String url) {
        return new Intent(ctx, WebActivity.class).putExtra(EXTRA_URL, url);
    }

    @SuppressLint({"SetJavaScriptEnabled", "AddJavascriptInterface"})
    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        web = new WebView(this);
        WebSettings s = web.getSettings();
        s.setJavaScriptEnabled(true);
        s.setDomStorageEnabled(true);
        s.setBuiltInZoomControls(true);
        s.setDisplayZoomControls(false);
        s.setLoadWithOverviewMode(true);
        s.setUseWideViewPort(true);
        web.addJavascriptInterface(new Recorder.Bridge(), "ZRec");
        web.setWebViewClient(new WebViewClient() {
            @Override
            public void onPageFinished(WebView view, String url) {
                if (Recorder.on) view.evaluateJavascript(Recorder.HOOK_JS, null);
            }

            @Override
            public WebResourceResponse shouldInterceptRequest(WebView view, WebResourceRequest req) {
                Recorder.request(req.getMethod(), req.getUrl().toString());
                return null;
            }
        });
        setContentView(web);
        updateTitle();
        if (b != null) web.restoreState(b);
        else {
            String url = getIntent().getStringExtra(EXTRA_URL);
            web.loadUrl(url != null ? url : PmCore.homeUrl(new Prefs(this).base()));
        }
    }

    private void updateTitle() {
        setTitle(Recorder.on ? "● Запись: " + Recorder.size() : "ProcessMaker");
    }

    @Override
    public boolean onCreateOptionsMenu(Menu m) {
        m.add(0, 1, 0, Recorder.on ? "⏹ Остановить запись" : "⏺ Записать действия");
        m.add(0, 2, 0, "Показать запись");
        m.add(0, 3, 0, "Обновить страницу");
        return true;
    }

    @Override
    public boolean onPrepareOptionsMenu(Menu m) {
        m.findItem(1).setTitle(Recorder.on ? "⏹ Остановить запись" : "⏺ Записать действия");
        updateTitle();
        return super.onPrepareOptionsMenu(m);
    }

    @Override
    public boolean onOptionsItemSelected(MenuItem item) {
        switch (item.getItemId()) {
            case 1:
                if (Recorder.on) {
                    Recorder.on = false;
                    updateTitle();
                    showLog();
                } else {
                    new AlertDialog.Builder(this)
                            .setTitle("Запись действий")
                            .setMessage("Возьмите заявку и заполните её как обычно. Приложение запишет, "
                                    + "какие запросы уходят на сайт (пароли не записываются). "
                                    + "Потом: меню → «Остановить запись» → «Копировать» и пришлите текст разработчику.")
                            .setPositiveButton("Начать", (d, w) -> {
                                Recorder.clear();
                                Recorder.on = true;
                                Recorder.add("Начало записи: " + web.getUrl());
                                web.evaluateJavascript(Recorder.HOOK_JS, null);
                                updateTitle();
                                Toast.makeText(this, "Запись идёт", Toast.LENGTH_SHORT).show();
                            })
                            .setNegativeButton("Отмена", null)
                            .show();
                }
                return true;
            case 2:
                showLog();
                return true;
            case 3:
                web.reload();
                return true;
        }
        return super.onOptionsItemSelected(item);
    }

    private void showLog() {
        final String text = Recorder.text();
        TextView tv = new TextView(this);
        tv.setText(text.isEmpty() ? "Запись пуста" : text);
        tv.setTextIsSelectable(true);
        tv.setTextSize(11);
        int p = Ui.dp(this, 14);
        tv.setPadding(p, p / 2, p, p / 2);
        ScrollView sv = new ScrollView(this);
        sv.addView(tv);
        new AlertDialog.Builder(this)
                .setTitle("Запись (" + Recorder.size() + ")")
                .setView(sv)
                .setPositiveButton("Копировать", (d, w) -> {
                    getSystemService(ClipboardManager.class)
                            .setPrimaryClip(ClipData.newPlainText("pm-record", text));
                    Toast.makeText(this, "Скопировано — вставьте в чат", Toast.LENGTH_SHORT).show();
                })
                .setNeutralButton("Поделиться", (d, w) -> startActivity(Intent.createChooser(
                        new Intent(Intent.ACTION_SEND).setType("text/plain")
                                .putExtra(Intent.EXTRA_TEXT, text), "Отправить запись")))
                .setNegativeButton("Закрыть", null)
                .show();
    }

    @Override
    protected void onSaveInstanceState(Bundle out) {
        super.onSaveInstanceState(out);
        web.saveState(out);
    }

    @Override
    public void onBackPressed() {
        if (web.canGoBack()) web.goBack();
        else super.onBackPressed();
    }

    @Override
    protected void onDestroy() {
        web.destroy();
        super.onDestroy();
    }
}
