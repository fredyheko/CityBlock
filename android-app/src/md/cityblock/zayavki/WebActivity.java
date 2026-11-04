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
    static final String EXTRA_FORM_UID = "formUid";
    static final String EXTRA_FORM_DEL = "formDel";
    static final String EXTRA_NUMBER = "number";
    private WebView web;

    // режим формы: этапы заявки на весь экран, без оболочки PM
    private String formUid;
    private String formDel;
    private String number;
    private boolean checkingNext = false;
    private boolean wide = false; // «как на компьютере»

    static Intent intent(Context ctx, String url) {
        return new Intent(ctx, WebActivity.class).putExtra(EXTRA_URL, url);
    }

    static Intent formIntent(Context ctx, PmCore.Case c) {
        return new Intent(ctx, WebActivity.class)
                .putExtra(EXTRA_FORM_UID, c.uid)
                .putExtra(EXTRA_FORM_DEL, c.delIndex)
                .putExtra(EXTRA_NUMBER, c.number);
    }

    @SuppressLint({"SetJavaScriptEnabled", "AddJavascriptInterface"})
    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        formUid = getIntent().getStringExtra(EXTRA_FORM_UID);
        formDel = getIntent().getStringExtra(EXTRA_FORM_DEL);
        number = getIntent().getStringExtra(EXTRA_NUMBER);
        if (b != null) {
            formUid = b.getString(EXTRA_FORM_UID, formUid);
            formDel = b.getString(EXTRA_FORM_DEL, formDel);
        }
        web = new WebView(this);
        WebSettings s = web.getSettings();
        s.setJavaScriptEnabled(true);
        s.setDomStorageEnabled(true);
        s.setBuiltInZoomControls(true);
        s.setDisplayZoomControls(false);
        boolean form = formUid != null;
        // формы PM адаптивные: ширина = ширина телефона; оболочка PM — «как на компьютере»
        s.setLoadWithOverviewMode(!form);
        s.setUseWideViewPort(!form);
        web.addJavascriptInterface(new Recorder.Bridge(), "ZRec");
        web.setWebViewClient(new WebViewClient() {
            @Override
            public void onPageStarted(WebView view, String url, android.graphics.Bitmap favicon) {
                if (formUid != null && PmCore.isCaseListUrl(url)) {
                    view.stopLoading();
                    afterStepSent();
                }
            }

            @Override
            public void onPageFinished(WebView view, String url) {
                if (Recorder.on) view.evaluateJavascript(Recorder.HOOK_JS, null);
                if (formUid != null && !wide) view.evaluateJavascript(FormFix.JS, null);
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
        else if (formUid != null) {
            web.loadUrl(PmCore.formUrl(new Prefs(this).base(), formUid, formDel));
        } else {
            String url = getIntent().getStringExtra(EXTRA_URL);
            web.loadUrl(url != null ? url : PmCore.homeUrl(new Prefs(this).base()));
        }
    }

    /** Этап отправлен (PM ушёл к списку заявок): если следующий этап снова ваш — открыть его. */
    private void afterStepSent() {
        if (checkingNext) return;
        checkingNext = true;
        setTitle("Проверяю следующий этап…");
        final String uid = formUid;
        final String prevDel = formDel;
        new Thread(() -> {
            PmCore.Case mine = PmSession.findMine(this, uid);
            runOnUiThread(() -> {
                checkingNext = false;
                if (isFinishing()) return;
                if (mine != null && !mine.delIndex.equals(prevDel)) {
                    formDel = mine.delIndex;
                    Toast.makeText(this, "Следующий этап", Toast.LENGTH_SHORT).show();
                    web.loadUrl(PmCore.formUrl(new Prefs(this).base(), uid, formDel));
                    updateTitle();
                } else {
                    Toast.makeText(this, mine == null ? "Заявка отправлена ✓" : "Готово", Toast.LENGTH_LONG).show();
                    finish();
                }
            });
        }).start();
    }

    private void updateTitle() {
        if (Recorder.on) setTitle("● Запись: " + Recorder.size());
        else if (formUid != null) setTitle("Заявка #" + (number == null ? "" : number));
        else setTitle("ProcessMaker");
    }

    @Override
    public boolean onCreateOptionsMenu(Menu m) {
        m.add(0, 1, 0, Recorder.on ? "⏹ Остановить запись" : "⏺ Записать действия");
        m.add(0, 2, 0, "Показать запись");
        m.add(0, 3, 0, "Обновить страницу");
        if (formUid != null) {
            m.add(0, 5, 0, wide ? "📱 Мобильный вид" : "↔ Широкий вид (как на компьютере)");
            m.add(0, 4, 0, "Открыть в полном PM");
        }
        return true;
    }

    @Override
    public boolean onPrepareOptionsMenu(Menu m) {
        m.findItem(1).setTitle(Recorder.on ? "⏹ Остановить запись" : "⏺ Записать действия");
        MenuItem w = m.findItem(5);
        if (w != null) w.setTitle(wide ? "📱 Мобильный вид" : "↔ Широкий вид (как на компьютере)");
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
            case 5:
                wide = !wide;
                web.getSettings().setUseWideViewPort(wide);
                web.getSettings().setLoadWithOverviewMode(wide);
                web.reload();
                return true;
            case 4: {
                PmCore.Case c = new PmCore.Case();
                c.uid = formUid;
                c.delIndex = formDel;
                String url = PmCore.openCaseUrl(new Prefs(this).base(), c, "todo");
                formUid = null; // дальше — обычный режим PM
                web.getSettings().setUseWideViewPort(true);
                web.getSettings().setLoadWithOverviewMode(true);
                web.loadUrl(url);
                updateTitle();
                invalidateOptionsMenu();
                return true;
            }
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
        if (formUid != null) {
            out.putString(EXTRA_FORM_UID, formUid);
            out.putString(EXTRA_FORM_DEL, formDel);
        }
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
