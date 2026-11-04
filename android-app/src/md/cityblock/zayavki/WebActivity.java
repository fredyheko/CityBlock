package md.cityblock.zayavki;

import android.annotation.SuppressLint;
import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.os.Bundle;
import android.view.Menu;
import android.view.MenuItem;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.Toast;

/** Сайт ProcessMaker внутри приложения (уже с выполненным входом) и формы заявок на весь экран. */
public class WebActivity extends Activity {
    static final String EXTRA_URL = "url";
    static final String EXTRA_FORM_UID = "formUid";
    static final String EXTRA_FORM_DEL = "formDel";
    static final String EXTRA_NUMBER = "number";
    static final String EXTRA_FORM_ACTION = "formAction";
    private WebView web;

    // режим формы: этапы заявки на весь экран, без оболочки PM
    private String formUid;
    private String formDel;
    private String formAction = "todo";
    private String number;
    private boolean checkingNext = false;
    private boolean wide = false; // «как на компьютере»

    static Intent intent(Context ctx, String url) {
        return new Intent(ctx, WebActivity.class).putExtra(EXTRA_URL, url);
    }

    static Intent formIntent(Context ctx, PmCore.Case c) {
        return formIntent(ctx, c, "todo");
    }

    static Intent formIntent(Context ctx, PmCore.Case c, String action) {
        return new Intent(ctx, WebActivity.class)
                .putExtra(EXTRA_FORM_UID, c.uid)
                .putExtra(EXTRA_FORM_DEL, c.delIndex)
                .putExtra(EXTRA_NUMBER, c.number)
                .putExtra(EXTRA_FORM_ACTION, action);
    }

    @SuppressLint("SetJavaScriptEnabled")
    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        formUid = getIntent().getStringExtra(EXTRA_FORM_UID);
        formDel = getIntent().getStringExtra(EXTRA_FORM_DEL);
        number = getIntent().getStringExtra(EXTRA_NUMBER);
        String fa = getIntent().getStringExtra(EXTRA_FORM_ACTION);
        if (fa != null) formAction = fa;
        if (b != null) {
            formUid = b.getString(EXTRA_FORM_UID, formUid);
            formDel = b.getString(EXTRA_FORM_DEL, formDel);
            formAction = b.getString(EXTRA_FORM_ACTION, formAction);
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
                if (formUid != null && !wide) view.evaluateJavascript(FormFix.JS, null);
            }
        });
        setContentView(web);
        updateTitle();
        if (b != null) web.restoreState(b);
        else if (formUid != null) {
            web.loadUrl(PmCore.formUrl(new Prefs(this).base(), formUid, formDel, formAction));
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
                    formAction = "todo";
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
        if (formUid != null) setTitle(number == null || number.isEmpty() ? "Новая заявка" : "Заявка #" + number);
        else setTitle("ProcessMaker");
    }

    @Override
    public boolean onCreateOptionsMenu(Menu m) {
        m.add(0, 3, 0, "Обновить страницу");
        if (formUid != null) {
            m.add(0, 5, 0, wide ? "📱 Мобильный вид" : "↔ Широкий вид (как на компьютере)");
            m.add(0, 4, 0, "Открыть в полном PM");
        }
        return true;
    }

    @Override
    public boolean onPrepareOptionsMenu(Menu m) {
        MenuItem w = m.findItem(5);
        if (w != null) w.setTitle(wide ? "📱 Мобильный вид" : "↔ Широкий вид (как на компьютере)");
        updateTitle();
        return super.onPrepareOptionsMenu(m);
    }

    @Override
    public boolean onOptionsItemSelected(MenuItem item) {
        switch (item.getItemId()) {
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
                String url = PmCore.openCaseUrl(new Prefs(this).base(), c, formAction);
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

    @Override
    protected void onSaveInstanceState(Bundle out) {
        super.onSaveInstanceState(out);
        web.saveState(out);
        if (formUid != null) {
            out.putString(EXTRA_FORM_UID, formUid);
            out.putString(EXTRA_FORM_DEL, formDel);
            out.putString(EXTRA_FORM_ACTION, formAction);
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
