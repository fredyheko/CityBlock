package md.cityblock.zayavki;

import android.webkit.JavascriptInterface;

import org.json.JSONObject;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.regex.Pattern;

/**
 * Запись действий в PM: какие запросы уходят на сайт, когда вы берёте и заполняете заявку.
 * Нужна, чтобы сделать для этого свои экраны. Пароли не записываются.
 */
final class Recorder {
    private static final int MAX = 500;
    private static final List<String> LOG = new ArrayList<>();
    private static final Pattern STATIC = Pattern.compile(
            "(?i)\\.(js|css|png|gif|jpe?g|svg|woff2?|ttf|eot|ico|map)(\\?|$)");
    static volatile boolean on = false;

    /** Перехват XHR, fetch, отправки форм и нажатий — во всех фреймах страницы. */
    static final String HOOK_JS = "(function(){"
            + "function L(o){try{ZRec.log(JSON.stringify(o));}catch(e){}}"
            + "function hook(w){try{if(!w||w.__zrec)return;w.__zrec=1;"
            + "var X=w.XMLHttpRequest;if(X){var o=X.prototype.open,s=X.prototype.send;"
            + "X.prototype.open=function(m,u){this.__z={m:m,u:String(u)};return o.apply(this,arguments);};"
            + "X.prototype.send=function(b){var z=this.__z||{},x=this;z.b=(b==null?'':String(b));"
            + "x.addEventListener('loadend',function(){var r='';try{r=String(x.responseText||'');}catch(e){}"
            + "var u=z.u;try{u=new w.URL(z.u,w.location.href).href;}catch(e){}"
            + "L({t:'XHR',m:z.m,u:u,b:z.b.slice(0,4000),s:x.status,r:r.slice(0,2000)});});"
            + "return s.apply(this,arguments);};}"
            + "if(w.fetch){var f=w.fetch;w.fetch=function(i,init){var u=(typeof i==='string')?i:(i&&i.url);"
            + "var b=(init&&init.body)?String(init.body):'';var m=(init&&init.method)||'GET';"
            + "return f.apply(this,arguments).then(function(res){try{res.clone().text().then(function(t){"
            + "L({t:'FETCH',m:m,u:String(u),b:b.slice(0,4000),s:res.status,r:t.slice(0,2000)});});}catch(e){}return res;});};}"
            + "w.document.addEventListener('submit',function(e){try{var fm=e.target,d=[];"
            + "for(var i=0;i<fm.elements.length;i++){var el=fm.elements[i];if(!el.name||el.type==='password')continue;"
            + "if((el.type==='checkbox'||el.type==='radio')&&!el.checked)continue;"
            + "d.push(el.name+'='+String(el.value).slice(0,300));}"
            + "L({t:'FORM',m:fm.method||'GET',u:fm.action,b:d.join('&').slice(0,6000)});}catch(x){}},true);"
            + "w.document.addEventListener('click',function(e){try{var el=e.target&&e.target.closest?"
            + "e.target.closest('button,input[type=button],input[type=submit],a,.x-btn,[role=button]'):null;"
            + "if(!el)return;var t=(el.innerText||el.value||el.title||'').trim().slice(0,80);"
            + "if(t)L({t:'CLICK',u:w.location.href,b:t});}catch(x){}},true);"
            + "}catch(e){}}"
            + "function scan(w){hook(w);try{for(var i=0;i<w.frames.length;i++)scan(w.frames[i]);}catch(e){}}"
            + "scan(window);if(!window.__zrecT)window.__zrecT=setInterval(function(){scan(window);},1000);"
            + "})();";

    /** Мост для JavaScript. */
    static final class Bridge {
        @JavascriptInterface
        public void log(String json) {
            if (!on) return;
            try {
                JSONObject o = new JSONObject(json);
                StringBuilder sb = new StringBuilder();
                sb.append(o.optString("t")).append(' ');
                if (o.has("m")) sb.append(o.optString("m").toUpperCase(Locale.ROOT)).append(' ');
                sb.append(o.optString("u"));
                String b = o.optString("b");
                if ("CLICK".equals(o.optString("t"))) {
                    add("👆 нажато «" + b + "»  (" + o.optString("u") + ")");
                    return;
                }
                if (!b.isEmpty()) sb.append("\n   отправлено: ").append(b);
                if (o.has("s")) sb.append("\n   ответ ").append(o.optInt("s")).append(": ")
                        .append(o.optString("r").replace('\n', ' '));
                add(sb.toString());
            } catch (Exception e) {
                add(json);
            }
        }
    }

    /** Запрос страницы/фрейма (без тела) — из WebViewClient. */
    static void request(String method, String url) {
        if (!on || url == null || STATIC.matcher(url).find() || url.startsWith("data:")) return;
        add("➡️ " + method + " " + url);
    }

    static synchronized void add(String s) {
        if (LOG.size() >= MAX) LOG.remove(0);
        LOG.add(new SimpleDateFormat("HH:mm:ss", Locale.ROOT).format(new Date()) + "  " + s);
    }

    static synchronized void clear() { LOG.clear(); }

    static synchronized int size() { return LOG.size(); }

    static synchronized String text() {
        StringBuilder sb = new StringBuilder();
        for (String s : LOG) sb.append(s).append("\n\n");
        return sb.toString();
    }
}
