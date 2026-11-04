package md.cityblock.zayavki;

import org.json.JSONArray;
import org.json.JSONObject;

/** JavaScript, который выполняется на странице входа (без зависимостей от Android). */
final class LoginJs {
    private LoginJs() {}

    static final String ERROR = "(function(){var s='.x-form-invalid-msg,#errorMessage,"
            + ".errorMessage,.error,.alert,.message,[class*=error],[class*=Error]';"
            + "var e=[].slice.call(document.querySelectorAll(s)).filter(function(x){"
            + "return x.offsetWidth||x.offsetHeight;})[0];"
            + "return e?e.innerText.trim().slice(0,200):'';})()";

    static final String HAS_PASSWORD = "(function(){return [].slice.call("
            + "document.querySelectorAll('input[type=password]')).some(function(x){"
            + "return x.offsetWidth||x.offsetHeight;})?'yes':'no';})()";

    static String fill(String user, String pass) {
        return "(function(u,p){"
                + "function vis(e){return !!(e&&(e.offsetWidth||e.offsetHeight||e.getClientRects().length));}"
                + "function q(s){try{return [].slice.call(document.querySelectorAll(s)).filter(vis);}catch(x){return [];}}"
                + "function setv(e,v){e.focus();"
                + "var d=Object.getOwnPropertyDescriptor(HTMLInputElement.prototype,'value');d.set.call(e,v);"
                + "e.dispatchEvent(new Event('input',{bubbles:true}));"
                + "e.dispatchEvent(new Event('change',{bubbles:true}));"
                + "e.dispatchEvent(new KeyboardEvent('keyup',{bubbles:true}));if(e.blur)e.blur();}"
                + "var pw=q('input[type=password]')[0];if(!pw)return 'nofields';"
                + "var sel=['input[name=\"form[USR_USERNAME]\"]','input[id=\"form[USR_USERNAME]\"]',"
                + "'input[name*=\"USERNAME\" i]','input[name*=\"user\" i]','input[type=email]',"
                + "'input[type=text]','input:not([type])'];"
                + "var us=null;for(var i=0;i<sel.length&&!us;i++){us=q(sel[i])[0]||null;}"
                + "if(!us)return 'nofields';"
                + "setv(us,u);setv(pw,p);var f=pw.form;"
                + "var b=q('input[id=\"form[BSUBMIT]\"],button[id=\"form[BSUBMIT]\"]')[0]"
                + "||(f?[].slice.call(f.querySelectorAll('button[type=submit],input[type=submit],button:not([type])')).filter(vis)[0]:null)"
                + "||q('button[type=submit],input[type=submit]')[0];"
                + "setTimeout(function(){if(b){b.click();}else if(f){if(f.requestSubmit)f.requestSubmit();else f.submit();}},300);"
                + "return 'submitted';})(" + JSONObject.quote(user) + "," + JSONObject.quote(pass) + ")";
    }

    /** Значение, которое вернул evaluateJavascript (JSON), превратить в строку. */
    static String result(String raw) {
        if (raw == null || raw.equals("null")) return "";
        try {
            return new JSONArray("[" + raw + "]").optString(0, "");
        } catch (Exception e) {
            return raw;
        }
    }
}
