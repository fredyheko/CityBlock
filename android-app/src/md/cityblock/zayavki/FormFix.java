package md.cityblock.zayavki;

/**
 * Делает таблицы (grid) в формах PM читаемыми на телефоне: таблица получает ширину
 * по числу колонок и прокручивается пальцем влево-вправо, поля не обрезаются.
 * Таблицы PM находятся по классу .pmdynaform-field-grid, прочие — по строкам из видимых
 * ячеек в одну линию; число колонок — по видимым полям в одной строке.
 * Элементы страницы НЕ переносятся (только стили): PM может ещё дорисовывать форму.
 */
final class FormFix {
    private FormFix() {}

    static final String JS =
            "(function(){"
            + "var COL=140;"
            + "function vis(e){return !!(e&&e.offsetWidth>0&&e.offsetHeight>0);}"
            + "function field(e){var t=(e.type||'').toLowerCase();return e.tagName==='SELECT'||e.tagName==='TEXTAREA'||(e.tagName==='INPUT'&&t!=='hidden'&&t!=='button'&&t!=='submit');}"
            + "function textField(e){var t=(e.type||'').toLowerCase();return field(e)&&t!=='checkbox'&&t!=='radio';}"
            + "function kids(p){return [].filter.call(p.children,vis);}"
            + "function cols(g){var f=g.querySelectorAll('input,select,textarea'),lines={},m=0;"
            + "for(var i=0;i<f.length;i++){var e=f[i];if(!field(e)||!vis(e))continue;var t=Math.round(e.getBoundingClientRect().top/12);lines[t]=(lines[t]||0)+1;if(lines[t]>m)m=lines[t];}return m;}"
            + "function rowOf(inp){var el=inp;for(var d=0;d<7&&el.parentElement;d++){var p=el.parentElement,k=kids(p);"
            + "if(k.length>=3&&p.offsetHeight>0&&p.offsetHeight<200){var a=k[0].getBoundingClientRect().top,b=k[k.length-1].getBoundingClientRect().top;if(Math.abs(a-b)<20)return p;}el=p;}return null;}"
            + "function gridOf(row){var g=row.parentElement&&row.parentElement.parentElement;"
            + "if(!g||/^(BODY|HTML|FORM)$/.test(g.tagName)||g.querySelectorAll('input,select').length>300)g=row.parentElement;return g;}"
            + "function widen(doc,g,vw){if(!g||g.dataset.zgrid||(g.parentElement&&g.parentElement.closest&&g.parentElement.closest('[data-zgrid]')))return;"
            + "var n=cols(g);if(n<3)return;var w=Math.max(n*COL,vw);if(w<=g.offsetWidth+20){g.dataset.zgrid='ok';return;}"
            + "g.dataset.zgrid='1';g.classList.add('zgrid-wide','zgrid-scroll');"
            + "for(var i=0;i<g.children.length;i++){g.children[i].style.minWidth=w+'px';}}"
            + "function fix(doc){try{"
            + "if(!doc.getElementById('zformfix')){var st=doc.createElement('style');st.id='zformfix';"
            + "st.textContent='.zgrid-scroll{overflow-x:auto!important;-webkit-overflow-scrolling:touch;max-width:100%;}.zgrid-scroll>*{box-sizing:border-box;}.zgrid-wide input,.zgrid-wide select,.zgrid-wide textarea{text-overflow:clip!important;}';"
            + "(doc.head||doc.documentElement).appendChild(st);}"
            + "var vw=doc.documentElement.clientWidth||360;"
            + "var known=doc.querySelectorAll('.pmdynaform-field-grid');"
            + "for(var i=0;i<known.length;i++)widen(doc,known[i],vw);"
            + "var f=doc.querySelectorAll('input,select,textarea');"
            + "for(var j=0;j<f.length;j++){var inp=f[j];if(!textField(inp)||!vis(inp)||inp.offsetWidth>=110)continue;"
            + "if(inp.closest&&inp.closest('[data-zgrid]'))continue;var row=rowOf(inp);if(!row)continue;widen(doc,gridOf(row),vw);}"
            + "}catch(e){}}"
            + "function sig(d){return d.getElementsByTagName('input').length+':'+d.getElementsByTagName('select').length+':'+(d.body?d.body.scrollHeight:0);}"
            + "function scan(w){try{var d=w.document,s=sig(d);if(d.__zsig!==s){d.__zsig=s;fix(d);}for(var i=0;i<w.frames.length;i++)scan(w.frames[i]);}catch(e){}}"
            + "scan(window);if(!window.__zfixT)window.__zfixT=setInterval(function(){scan(window);},2000);"
            + "})();";
}
