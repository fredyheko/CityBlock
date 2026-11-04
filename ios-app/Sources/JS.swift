import Foundation

/// JavaScript для страниц PM — тот же, что в Android-версии (проверен в Chromium).
enum JS {
    static let loginError = ##"(function(){var s='.x-form-invalid-msg,#errorMessage,"##
    static let hasPassword = ##"(function(){return [].slice.call(document.querySelectorAll('input[type=password]')).some(function(x){"##
    /// Функция (u, p) — заполнить логин/пароль и нажать «Войти».
    private static let fillFn = ##"(function(u,p){function vis(e){return !!(e&&(e.offsetWidth||e.offsetHeight||e.getClientRects().length));}function q(s){try{return [].slice.call(document.querySelectorAll(s)).filter(vis);}catch(x){return [];}}function setv(e,v){e.focus();var d=Object.getOwnPropertyDescriptor(HTMLInputElement.prototype,'value');d.set.call(e,v);e.dispatchEvent(new Event('input',{bubbles:true}));e.dispatchEvent(new Event('change',{bubbles:true}));e.dispatchEvent(new KeyboardEvent('keyup',{bubbles:true}));if(e.blur)e.blur();}var pw=q('input[type=password]')[0];if(!pw)return 'nofields';var sel=['input[name="form[USR_USERNAME]"]','input[id="form[USR_USERNAME]"]','input[name*="USERNAME" i]','input[name*="user" i]','input[type=email]','input[type=text]','input:not([type])'];var us=null;for(var i=0;i<sel.length&&!us;i++){us=q(sel[i])[0]||null;}if(!us)return 'nofields';setv(us,u);setv(pw,p);var f=pw.form;var b=q('input[id="form[BSUBMIT]"],button[id="form[BSUBMIT]"]')[0]||(f?[].slice.call(f.querySelectorAll('button[type=submit],input[type=submit],button:not([type])')).filter(vis)[0]:null)||q('button[type=submit],input[type=submit]')[0];setTimeout(function(){if(b){b.click();}else if(f){if(f.requestSubmit)f.requestSubmit();else f.submit();}},300);return 'submitted';})"##

    static func fill(user: String, pass: String) -> String {
        "\(fillFn)(\(quote(user)),\(quote(pass)))"
    }

    /// Таблицы в формах: ширина по числу колонок + своя горизонтальная прокрутка.
    static let formFix = ##"(function(){var COL=140;function isField(e){var t=(e.type||'').toLowerCase();return (e.tagName==='SELECT'||e.tagName==='TEXTAREA'||(e.tagName==='INPUT'&&t!=='hidden'&&t!=='checkbox'&&t!=='radio'&&t!=='button'&&t!=='submit'));}function rowOf(inp){var el=inp;for(var d=0;d<6&&el.parentElement;d++){var p=el.parentElement;if(p.children.length>=3&&p.offsetHeight>0&&p.offsetHeight<140){var a=p.children[0].getBoundingClientRect(),b=p.children[p.children.length-1].getBoundingClientRect();if(Math.abs(a.top-b.top)<20)return p;}el=p;}return null;}function gridOf(row){var g=row.closest&&(row.closest('.pmdynaform-field-grid')||row.closest('.pmdynaform-grid'));if(g)return g;g=row.parentElement&&row.parentElement.parentElement;if(!g||/^(BODY|HTML|FORM)$/.test(g.tagName)||g.querySelectorAll('input,select').length>200)g=row.parentElement;return g;}function fix(doc){try{if(!doc.getElementById('zformfix')){var st=doc.createElement('style');st.id='zformfix';st.textContent='.zgrid-scroll{overflow-x:auto!important;-webkit-overflow-scrolling:touch;}.zgrid-wide input,.zgrid-wide select,.zgrid-wide textarea{text-overflow:clip!important;}';(doc.head||doc.documentElement).appendChild(st);}var vw=doc.documentElement.clientWidth||360;var f=doc.querySelectorAll('input,select,textarea');for(var i=0;i<f.length;i++){var inp=f[i];if(!isField(inp)||inp.offsetWidth===0||inp.offsetWidth>=110)continue;var row=rowOf(inp);if(!row)continue;var g=gridOf(row);if(!g||g.dataset.zgrid)continue;var n=row.children.length;var w=Math.max(n*COL,vw);if(w<=g.offsetWidth+20){g.dataset.zgrid='ok';continue;}g.dataset.zgrid='1';g.classList.add('zgrid-wide');g.style.minWidth=w+'px';var wrap=doc.createElement('div');wrap.className='zgrid-scroll';g.parentNode.insertBefore(wrap,g);wrap.appendChild(g);}}catch(e){}}function scan(w){try{fix(w.document);for(var i=0;i<w.frames.length;i++)scan(w.frames[i]);}catch(e){}}scan(window);if(!window.__zfixT)window.__zfixT=setInterval(function(){scan(window);},1500);})();"##

    static let mobileViewport = ##"(function(){var m=document.querySelector('meta[name=viewport]');if(!m){m=document.createElement('meta');m.name='viewport';(document.head||document.documentElement).appendChild(m);}m.content='width=device-width, initial-scale=1';})();"##
    static let wideViewport = ##"(function(){var m=document.querySelector('meta[name=viewport]');if(!m){m=document.createElement('meta');m.name='viewport';(document.head||document.documentElement).appendChild(m);}m.content='width=1100';})();"##

    static func quote(_ s: String) -> String {
        if let d = try? JSONSerialization.data(withJSONObject: s, options: .fragmentsAllowed),
           let q = String(data: d, encoding: .utf8) { return q }
        return "\"\""
    }
}
