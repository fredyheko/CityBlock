package md.cityblock.zayavki;

import android.annotation.SuppressLint;
import android.app.Activity;
import android.os.Bundle;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;

/** Сайт ProcessMaker внутри приложения (уже с выполненным входом). */
public class WebActivity extends Activity {
    static final String EXTRA_URL = "url";
    private WebView web;

    static android.content.Intent intent(android.content.Context ctx, String url) {
        return new android.content.Intent(ctx, WebActivity.class).putExtra(EXTRA_URL, url);
    }

    @SuppressLint("SetJavaScriptEnabled")
    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        setTitle("ProcessMaker");
        web = new WebView(this);
        WebSettings s = web.getSettings();
        s.setJavaScriptEnabled(true);
        s.setDomStorageEnabled(true);
        s.setBuiltInZoomControls(true);
        s.setDisplayZoomControls(false);
        s.setLoadWithOverviewMode(true);
        s.setUseWideViewPort(true);
        web.setWebViewClient(new WebViewClient());
        setContentView(web);
        if (b != null) web.restoreState(b);
        else {
            String url = getIntent().getStringExtra(EXTRA_URL);
            web.loadUrl(url != null ? url : PmCore.homeUrl(new Prefs(this).base()));
        }
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
