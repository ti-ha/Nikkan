package com.nhkdaily;

/** The three HTML screens the app shows: loading, the article, and an error. */
final class Pages {

    private Pages() {
    }

    private static String vars(boolean dark) {
        return dark
                ? ":root{--bg:#16161a;--fg:#e6e3dc;--mu:#8a877f;--line:#2c2b30;--ac:#e0654a;--sel:rgba(224,101,74,.35)}"
                : ":root{--bg:#faf8f3;--fg:#222;--mu:#8a857a;--line:#e6e1d6;--ac:#b5452f;--sel:rgba(181,69,47,.22)}";
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
                + "</style></head><body><div class='r'>" + content + "</div><script>"
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
                + furiganaCall + ";"
                + "</script></body></html>";
    }

    private static String escape(String s) {
        return s == null ? "" : s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;");
    }
}
