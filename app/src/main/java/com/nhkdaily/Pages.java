package com.nhkdaily;

/** The three HTML screens the app shows: loading, the article, and an error. */
final class Pages {

    private Pages() {
    }

    private static String vars(boolean dark) {
        return dark
                ? ":root{--bg:#16161a;--fg:#e6e3dc;--mu:#8a877f;--line:#2c2b30;--ac:#e0654a;--sel:rgba(224,101,74,.35);--card:#222227}"
                : ":root{--bg:#faf8f3;--fg:#222;--mu:#8a857a;--line:#e6e1d6;--ac:#b5452f;--sel:rgba(181,69,47,.22);--card:#ffffff}";
    }

    private static final String HEAD =
            "<!doctype html><html><head><meta charset='utf-8'>"
                    + "<meta name='viewport' content='width=device-width,initial-scale=1'>";

    private static final String BRAND_CSS =
            "html,body{margin:0;height:100%;background:var(--bg);color:var(--fg);}"
                    + "body{font-family:sans-serif;display:flex;align-items:center;justify-content:center;"
                    + "text-align:center;-webkit-user-select:none;user-select:none;}"
                    + ".w{width:100%;max-width:360px;padding:0 28px;box-sizing:border-box;}"
                    + ".k{font-family:'Noto Serif CJK JP','Noto Serif JP',serif;font-size:64px;font-weight:600;"
                    + "letter-spacing:.06em;line-height:1;}"
                    + ".n{font-size:11px;letter-spacing:.42em;color:var(--mu);margin:14px 0 0 .42em;}";

    // ---- Loading -----------------------------------------------------------

    static String loading(boolean dark) {
        return HEAD + "<style>" + vars(dark) + BRAND_CSS
                + ".bar{position:relative;height:3px;border-radius:3px;background:var(--line);"
                + "margin:56px auto 22px;width:220px;overflow:hidden;}"
                + ".fill{position:absolute;left:0;top:0;bottom:0;width:2%;background:var(--ac);"
                + "border-radius:3px;transition:width .7s cubic-bezier(.2,.7,.2,1);}"
                + ".shine{position:absolute;top:0;bottom:0;width:60px;left:-60px;"
                + "background:linear-gradient(90deg,transparent,rgba(255,255,255,.55),transparent);"
                + "animation:sh 1.6s ease-in-out infinite;}"
                + "@keyframes sh{to{left:100%}}"
                + ".jp{font-size:16px;min-height:24px;transition:opacity .25s;}"
                + ".en{font-size:13px;color:var(--mu);margin-top:6px;min-height:18px;transition:opacity .25s;}"
                + ".t{font-variant-numeric:tabular-nums;}"
                + "</style></head><body><div class='w'>"
                + "<div class='k'>日刊</div><div class='n'>NIKKAN</div>"
                + "<div class='bar'><div class='fill' id='f'><div class='shine'></div></div></div>"
                + "<div class='jp' id='jp'>準備しています</div>"
                + "<div class='en'><span id='en'>Getting ready</span><span class='t' id='t'></span></div>"
                + "</div><script>"
                + "var t0=Date.now();"
                + "function setStep(f,jp,en){"
                + "document.getElementById('f').style.width=Math.max(2,Math.min(100,f*100))+'%';"
                + "swap('jp',jp);swap('en',en);}"
                + "function swap(id,v){var e=document.getElementById(id);if(e.textContent===v)return;"
                + "e.style.opacity=0;setTimeout(function(){e.textContent=v;e.style.opacity=1;},180);}"
                + "setInterval(function(){var s=Math.floor((Date.now()-t0)/1000);"
                + "document.getElementById('t').textContent=s>=3?' · '+s+'s':'';},500);"
                + "</script></body></html>";
    }

    // ---- Error -------------------------------------------------------------

    static String error(boolean dark, String retryUrl, String detail) {
        return HEAD + "<style>" + vars(dark) + BRAND_CSS
                + "a{position:fixed;inset:0;color:inherit;text-decoration:none;display:flex;"
                + "align-items:center;justify-content:center;}"
                + ".jp{font-size:16px;margin-top:56px;}"
                + ".en{font-size:13px;color:var(--mu);margin-top:6px;line-height:1.6;}"
                + ".d{font-size:12px;color:var(--mu);opacity:.7;margin-top:14px;word-break:break-word;}"
                + ".b{display:inline-block;margin-top:36px;padding:12px 26px;border:1px solid var(--line);"
                + "border-radius:999px;font-size:14px;}"
                + "</style></head><body><a href='" + retryUrl + "'><div class='w'>"
                + "<div class='k'>日刊</div><div class='n'>NIKKAN</div>"
                + "<div class='jp'>今日の記事を読み込めませんでした</div>"
                + "<div class='en'>Couldn't load today's article.</div>"
                + "<div class='d'>" + escape(detail) + "</div>"
                + "<div class='b'>もう一度 · Try again</div>"
                + "</div></a></body></html>";
    }

    // ---- Reader ------------------------------------------------------------

    static String reader(String content, boolean dark, String furiganaCall) {
        return HEAD + "<style>" + vars(dark)
                + "html,body{margin:0;background:var(--bg);color:var(--fg);}"
                + "body{font-family:'Noto Serif CJK JP','Noto Serif JP',serif;font-size:21px;line-height:2.1;"
                + "-webkit-text-size-adjust:100%;}"
                + "::selection{background:var(--sel);}"
                + ".r{max-width:640px;margin:0 auto;padding:56px 22px 96px;}"
                + ".dt{font-family:sans-serif;font-size:13px;color:var(--mu);letter-spacing:.05em;margin-bottom:10px;}"
                + "h1{font-size:26px;line-height:1.9;margin:0 0 28px;font-weight:700;}"
                + "p{margin:0 0 1.3em;}"
                // Furigana is drawn from a data attribute (rt::before), so it isn't real text
                // and never ends up in a selection: Translate / Copy get just the Japanese.
                + "ruby{ruby-align:center;}rt{font-size:.5em;letter-spacing:-.03em;color:var(--mu);}rt::before{content:attr(data-r);}rp{display:none;}"
                + "html.nofuri rt,html.nofuri rp,ruby.known rt,ruby.known rp{display:none;}"
                + "img{display:block;width:100%;height:auto;border-radius:8px;margin:0 0 28px;}"
                + "figure{margin:0;}figcaption{font-family:sans-serif;font-size:13px;color:var(--mu);"
                + "line-height:1.6;margin:-18px 0 28px;}"
                // Translation island: slides up from the bottom when text is selected.
                + "#is{position:fixed;left:12px;right:12px;bottom:calc(14px + env(safe-area-inset-bottom));"
                + "max-width:616px;margin:0 auto;box-sizing:border-box;background:var(--card);color:var(--fg);"
                + "border:1px solid var(--line);border-radius:16px;box-shadow:0 8px 28px rgba(0,0,0,.18);"
                + "padding:10px 16px 12px;font:15px/1.5 sans-serif;max-height:38vh;overflow:auto;"
                + "transform:translateY(calc(100% + 40px));opacity:0;"
                + "transition:transform .28s cubic-bezier(.2,.8,.2,1),opacity .2s;"
                + "-webkit-user-select:none;user-select:none;}"
                + "#is.on{transform:none;opacity:1;}"
                + "#is{display:flex;align-items:center;gap:12px;}#is .col{flex:1;min-width:0;}"
                + "#sp{flex:none;width:42px;height:42px;border-radius:50%;border:1px solid var(--line);"
                + "background:transparent;color:var(--fg);display:flex;align-items:center;justify-content:center;"
                + "padding:0;position:relative;-webkit-tap-highlight-color:transparent;}"
                + "#sp svg{width:20px;height:20px;}#sp .st{display:none;}"
                + "#sp.playing{background:var(--ac);border-color:var(--ac);color:#fff;}"
                + "#sp.playing .sk{display:none;}#sp.playing .st{display:block;}"
                + "#sp.loading::after{content:'';position:absolute;inset:-1px;border-radius:50%;"
                + "border:2px solid transparent;border-top-color:var(--ac);animation:spin .8s linear infinite;}"
                + "@keyframes spin{to{transform:rotate(360deg)}}"
                // Our own selection menu (replaces Android's): just Copy for now.
                + "#mn{position:fixed;z-index:5;display:none;background:var(--fg);color:var(--bg);"
                + "border-radius:10px;box-shadow:0 6px 20px rgba(0,0,0,.25);overflow:hidden;"
                + "-webkit-user-select:none;user-select:none;}"
                + "#mn button{font:500 14px/1 sans-serif;padding:11px 18px;background:transparent;color:inherit;"
                + "border:0;-webkit-tap-highlight-color:transparent;}"
                + "#mn button+button{border-left:1px solid rgba(127,127,127,.35);}"
                + "#is .lb{font-size:10px;letter-spacing:.16em;text-transform:uppercase;color:var(--mu);"
                + "margin-bottom:2px;min-height:15px;}"
                + "#is .tx{transition:opacity .2s;}#is.wait .tx{opacity:.45;}"
                + "</style></head><body><div class='r'>" + content + "</div>"
                + "<div id='mn'><button data-a='copy'>Copy</button></div>"
                + "<div id='is'><div class='col'><div class='lb'></div><div class='tx'></div></div>"
                + "<button id='sp' aria-label='Read aloud'>"
                + "<svg class='sk' viewBox='0 0 24 24' fill='none' stroke='currentColor' stroke-width='1.8' "
                + "stroke-linecap='round' stroke-linejoin='round'><path d='M11 5 6 9H3v6h3l5 4z'/>"
                + "<path d='M15.5 8.5a5 5 0 0 1 0 7'/><path d='M18.5 5.5a9 9 0 0 1 0 13'/></svg>"
                + "<svg class='st' viewBox='0 0 24 24'><rect x='7' y='7' width='10' height='10' rx='2' "
                + "fill='currentColor'/></svg></button></div><script>"
                // mode: 'all' | 'wk' | 'none'. In 'wk', a word loses its furigana only
                // when every kanji in it is in `known`.
                + "document.querySelectorAll('rt').forEach(function(rt){"
                + "rt.setAttribute('data-r',rt.textContent);rt.textContent='';});"
                + "function applyFuri(mode,known){"
                + "document.documentElement.classList.toggle('nofuri',mode==='none');"
                + "var set={};for(var i=0;i<known.length;i++)set[known[i]]=1;"
                + "document.querySelectorAll('ruby').forEach(function(r){"
                + "var base='';r.childNodes.forEach(function(n){"
                + "if(n.nodeName!=='RT'&&n.nodeName!=='RP')base+=n.textContent;});"
                + "var ks=base.match(/[\\u3400-\\u4dbf\\u4e00-\\u9fff\\uf900-\\ufaff]/g)||[];"
                + "var k=mode==='wk'&&ks.length>0&&ks.every(function(c){return set[c];});"
                + "r.classList.toggle('known',k);});}"
                // Selecting text asks the app (window.Nikkan) to translate it; the app
                // answers via showTranslation(). Tapping the article dismisses the island.
                + "var isl=document.getElementById('is'),itx=isl.querySelector('.tx'),last='',seq=0,tm;"
                + "document.addEventListener('selectionchange',function(){clearTimeout(tm);tm=setTimeout(function(){"
                + "var s=(window.getSelection()+'').replace(/\\s+/g,' ').trim();"
                + "if(!s||s===last)return;if(s.length>800)s=s.slice(0,800);"
                + "last=s;seq++;isl.className='on wait';if(!itx.textContent)itx.textContent='…';"
                + "if(window.Nikkan)Nikkan.translate(seq,s);},350);});"
                + "function showTranslation(id,t,lb){if(id!==seq)return;itx.textContent=t;"
                + "if(lb)isl.querySelector('.lb').textContent=lb;isl.className='on';}"
                // Buttons act on touchstart and cancel it, so tapping them keeps the selection.
                + "function press(el,fn){el.addEventListener('touchstart',function(e){e.preventDefault();fn(e);},"
                + "{passive:false});el.addEventListener('mousedown',function(e){e.preventDefault();fn(e);});}"
                + "var sp=document.getElementById('sp');"
                + "press(sp,function(){if(!window.Nikkan)return;"
                + "if(sp.className)Nikkan.stopSpeaking();else if(last)Nikkan.speak(last);});"
                + "function setSpeech(st){sp.className=st==='idle'?'':st;}"
                + "var mn=document.getElementById('mn'),mt;"
                + "function placeMenu(){var s=window.getSelection(),t=(s+'').trim();"
                + "if(!s||s.isCollapsed||!t){mn.style.display='none';return;}"
                + "var r=s.getRangeAt(0).getBoundingClientRect();mn.style.display='flex';"
                + "var w=mn.offsetWidth,h=mn.offsetHeight,x=r.left+r.width/2-w/2;"
                + "x=Math.max(8,Math.min(innerWidth-w-8,x));var y=r.top-h-14;"
                + "if(y<8)y=r.bottom+44;mn.style.left=x+'px';mn.style.top=y+'px';}"
                + "document.addEventListener('selectionchange',function(){clearTimeout(mt);mt=setTimeout(placeMenu,150);});"
                + "window.addEventListener('scroll',function(){if(mn.style.display!=='none')placeMenu();},{passive:true});"
                + "press(mn,function(e){var b=e.target.closest('button');if(!b)return;"
                + "var t=(window.getSelection()+'').replace(/\\s+/g,' ').trim();if(!t)return;"
                + "if(b.dataset.a==='copy'&&window.Nikkan){Nikkan.copy(t);b.textContent='Copied';"
                + "setTimeout(function(){b.textContent='Copy';},1200);}});"
                + "function hideIsland(){isl.className='';last='';seq++;"
                + "if(sp.className&&window.Nikkan)Nikkan.stopSpeaking();"
                + "var sel=window.getSelection();if(sel)sel.removeAllRanges();"
                + "setTimeout(function(){if(!isl.className)itx.textContent='';},300);}"
                + "document.addEventListener('click',function(e){"
                + "if(isl.className.indexOf('on')<0||isl.contains(e.target))return;hideIsland();});"
                + furiganaCall + ";"
                + "</script></body></html>";
    }

    private static String escape(String s) {
        return s == null ? "" : s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;");
    }
}
