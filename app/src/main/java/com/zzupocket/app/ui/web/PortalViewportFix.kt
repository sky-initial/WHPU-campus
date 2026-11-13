package com.zzupocket.app.ui.web

/** Portal's percentage-height root collapses on the observed WebView with a zero CSS viewport. */
object PortalViewportFix {
    val JS = """
        (function() {
          if (window.__whpuPortalViewport) return window.__whpuPortalViewport();
          var targets = null;
          function resize() {
            var html = document.documentElement, body = document.body, h = window.innerHeight;
            if (!body || h < 1) return 'pending';
            if (!targets) {
              if (html.getBoundingClientRect().height > 0 || body.getBoundingClientRect().height > 0)
                return 'unchanged';
              if (getComputedStyle(body).display === 'none' || getComputedStyle(body).visibility !== 'visible')
                return 'unchanged';
              var probe = document.createElement('div');
              probe.style.cssText = 'position:absolute;left:-9999px;height:100vh;width:1px';
              body.appendChild(probe);
              var broken = probe.getBoundingClientRect().height < 1;
              probe.remove();
              if (!broken) return 'unchanged';
              targets = [html, body];
              var roots = Array.from(body.children).filter(function(e) { return e.tagName === 'DIV'; });
              if (roots.length === 1 && roots[0].getBoundingClientRect().height < 1) targets.push(roots[0]);
            }
            targets.forEach(function(e) { e.style.setProperty('height', h + 'px', 'important'); });
            return 'restored';
          }
          window.__whpuPortalViewport = resize;
          window.addEventListener('resize', resize);
          return resize();
        })()
    """.trimIndent()
}
