package md.cityblock.zayavki;

/**
 * Делает таблицы (grid) в формах PM читаемыми на телефоне: таблица получает ширину
 * по числу колонок и прокручивается пальцем влево-вправо, поля не обрезаются.
 */
final class FormFix {
    private FormFix() {}

    /** Минимальная ширина одной колонки таблицы, px. */
    static final int COL_PX = 140;

    static final String JS = "(function(){"
            + "var COL=" + COL_PX + ";"
            + "function isField(e){var t=(e.type||'').toLowerCase();"
            + "return (e.tagName==='SELECT'||e.tagName==='TEXTAREA'||(e.tagName==='INPUT'&&t!=='hidden'&&t!=='checkbox'&&t!=='radio'&&t!=='button'&&t!=='submit'));}"
            // строка таблицы: ближайший предок, у которого >=3 дочерних «ячеек» в одну линию
            + "function rowOf(inp){var el=inp;for(var d=0;d<6&&el.parentElement;d++){var p=el.parentElement;"
            + "if(p.children.length>=3&&p.offsetHeight>0&&p.offsetHeight<140){"
            + "var a=p.children[0].getBoundingClientRect(),b=p.children[p.children.length-1].getBoundingClientRect();"
            + "if(Math.abs(a.top-b.top)<20)return p;}el=p;}return null;}"
            // таблица целиком (заголовки + строки): сначала известные классы PM, иначе «дедушка» строки
            + "function gridOf(row){var g=row.closest&&(row.closest('.pmdynaform-field-grid')||row.closest('.pmdynaform-grid'));"
            + "if(g)return g;g=row.parentElement&&row.parentElement.parentElement;"
            + "if(!g||/^(BODY|HTML|FORM)$/.test(g.tagName)||g.querySelectorAll('input,select').length>200)g=row.parentElement;"
            + "return g;}"
            + "function fix(doc){try{"
            + "if(!doc.getElementById('zformfix')){var st=doc.createElement('style');st.id='zformfix';"
            + "st.textContent='.zgrid-scroll{overflow-x:auto!important;-webkit-overflow-scrolling:touch;}"
            + ".zgrid-wide input,.zgrid-wide select,.zgrid-wide textarea{text-overflow:clip!important;}';"
            + "(doc.head||doc.documentElement).appendChild(st);}"
            + "var vw=doc.documentElement.clientWidth||360;"
            + "var f=doc.querySelectorAll('input,select,textarea');"
            + "for(var i=0;i<f.length;i++){var inp=f[i];if(!isField(inp)||inp.offsetWidth===0||inp.offsetWidth>=110)continue;"
            + "var row=rowOf(inp);if(!row)continue;var g=gridOf(row);if(!g||g.dataset.zgrid)continue;"
            + "var n=row.children.length;var w=Math.max(n*COL,vw);if(w<=g.offsetWidth+20){g.dataset.zgrid='ok';continue;}"
            + "g.dataset.zgrid='1';g.classList.add('zgrid-wide');g.style.minWidth=w+'px';"
            // своя прокрутка только для таблицы: оборачиваем её в контейнер
            + "var wrap=doc.createElement('div');wrap.className='zgrid-scroll';g.parentNode.insertBefore(wrap,g);wrap.appendChild(g);}"
            + "}catch(e){}}"
            + "function scan(w){try{fix(w.document);for(var i=0;i<w.frames.length;i++)scan(w.frames[i]);}catch(e){}}"
            + "scan(window);if(!window.__zfixT)window.__zfixT=setInterval(function(){scan(window);},1500);"
            + "})();";
}
