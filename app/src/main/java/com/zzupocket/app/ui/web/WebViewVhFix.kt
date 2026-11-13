package com.zzupocket.app.ui.web

import android.webkit.WebView

/** Repairs the observed zero CSS viewport; normal vh layout is left untouched. */
object WebViewVhFix {

    /** 通用版：不依赖任何具体站点的选择器 */
    val JS: String = """
        (function(){
          function px(v){ return Math.round(v * window.innerHeight / 100) + 'px'; }
          function rewrite(text){ return text.replace(/([\d.]+)vh/g, function(_, n){ return px(parseFloat(n)); }); }

          // Probe before changing page styles; a positive CSS viewport needs no repair.
          function vhBroken(){
            if (!document.body || window.innerHeight < 100) return false;
            var d = document.createElement('div');
            d.style.cssText = 'position:absolute;left:-9999px;top:0;width:10px;height:100vh';
            document.body.appendChild(d);
            var h = d.getBoundingClientRect().height;
            d.parentNode.removeChild(d);
            return h < 1;
          }

          function rescue(){
            var H = window.innerHeight;
            if (H < 100) return 0;
            // 先清上一轮补的：视口变了要按新值重算，也避免把旧值留在 DOM 上
            var old = document.querySelectorAll('[data-zzp-fix]');
            for (var i = 0; i < old.length; i++) {
              old[i].style.removeProperty('height');
              old[i].removeAttribute('data-zzp-fix');
            }
            var n = 0, budget = 6000;
            // Nested zero-height toolbars can intentionally contain positioned children.
            var all = document.querySelectorAll('body > div');
            for (var k = 0; k < all.length; k++) {
              var el = all[k];
              var er = el.getBoundingClientRect();
              if (er.height >= 4) continue;          // 没塌陷
              if (budget <= 0) break;
              var kids = el.querySelectorAll('*');
              var positioned = false, spill = false;
              for (var j = 0; j < kids.length; j++) {
                if (--budget < 0) break;
                var kr = kids[j].getBoundingClientRect();
                if (!spill && kr.height > 0 && kr.bottom > er.bottom + 4) spill = true;
                if (!positioned) {
                  var cs = getComputedStyle(kids[j]);
                  if (cs.position === 'absolute' || cs.position === 'fixed') positioned = true;
                }
                if (positioned && spill) break;
              }
              if (!positioned || !spill) continue;
              el.style.setProperty('height', H + 'px', 'important');
              el.setAttribute('data-zzp-fix', '1');
              n++;
            }
            return n;
          }

          function applyOnce(){
            if (!vhBroken()) return 'unchanged';
            var st = document.getElementById('zzp-vhfix');
            if (!st) {
              st = document.createElement('style');
              st.id = 'zzp-vhfix';
              (document.head || document.documentElement).appendChild(st);
            }
            var out = [];
            // 1) 样式表里所有含 vh 的声明 → 像素（含 @media 内的）
            {
              try {
                var walk = function(rules){
                  for (var i = 0; i < rules.length; i++) {
                    var r = rules[i];
                    if (r.cssRules && !r.selectorText) {
                      var nested = [];
                      var previous = out;
                      out = nested;
                      walk(r.cssRules);
                      out = previous;
                      if (nested.length) out.push(r.cssText.slice(0, r.cssText.indexOf('{')) + '{' + nested.join('\n') + '}');
                      continue;
                    }
                    if (!r.style || !r.selectorText) continue;
                    var decls = [];
                    for (var j = 0; j < r.style.length; j++) {
                      var p = r.style[j], v = r.style.getPropertyValue(p);
                      if (v && v.indexOf('vh') >= 0) decls.push(p + ':' + rewrite(v) + ' !important');
                    }
                    if (!decls.length) continue;
                    var body = r.selectorText + '{' + decls.join(';') + '}';
                    out.push(body);
                  }
                };
                for (var i = 0; i < document.styleSheets.length; i++) {
                  var sheet = document.styleSheets[i];
                  if (sheet.ownerNode === st) continue;
                  try { if (sheet.cssRules) walk(sheet.cssRules); } catch (e) {}
                }
              } catch (e) {}
            }
            st.textContent = out.join('\n');
            // Keep each original inline declaration so resize can recalculate it.
            var els = document.querySelectorAll('[style]');
            var n = 0;
            for (var k = 0; k < els.length; k++) {
              var el = els[k], saved = el.__zzpVhInline || (el.__zzpVhInline = {});
              for (var j = 0; j < el.style.length; j++) {
                var prop = el.style[j], value = el.style.getPropertyValue(prop);
                if (value.indexOf('vh') >= 0) saved[prop] = {original:value, priority:el.style.getPropertyPriority(prop)};
              }
              Object.keys(saved).forEach(function(prop) {
                var item = saved[prop], current = el.style.getPropertyValue(prop);
                if (current !== item.original && current !== item.applied) { delete saved[prop]; return; }
                el.style.setProperty(prop, rewrite(item.original), item.priority);
                item.applied = el.style.getPropertyValue(prop);
                n++;
              });
            }
            // 3) 跨域样式表读不到时的结构性兜底（仅当 vh 确实失效）
            var fixed = 0, broken = false;
            try { broken = vhBroken(); } catch (e) {}
            if (broken) { try { fixed = rescue(); } catch (e) {} }
            return 'vhfix h=' + window.innerHeight + ' vh=' + (broken ? 'broken' : 'ok')
                 + ' css=' + out.length + ' inline=' + n + ' rescued=' + fixed;
          }
          window.__zzpVhFix = applyOnce;
          if (!window.__zzpVhHooked) {
            window.__zzpVhHooked = true;
            window.addEventListener('resize', function(){
              setTimeout(function(){ try { window.__zzpVhFix(); } catch (e) {} }, 300);
            });
          }
          try { return applyOnce(); } catch (e) { return 'ERR ' + e.message; }
        })()
    """.trimIndent()

    /** Returns a cancellation handle for navigation, detach and destruction. */
    fun install(view: WebView?, rounds: Int = 6, intervalMs: Long = 700L): () -> Unit {
        val wv = view ?: return {}
        val pageUrl = wv.url
        var left = rounds
        var cancelled = false
        val task = object : Runnable {
            override fun run() {
                if (cancelled || !wv.isAttachedToWindow || wv.url != pageUrl || left-- <= 0) return
                wv.evaluateJavascript(JS, null)
                if (left > 0) wv.postDelayed(this, intervalMs)
            }
        }
        wv.postDelayed(task, 300L)
        return { cancelled = true; wv.removeCallbacks(task) }
    }
}
