/* Runs before first paint: marks JS as available and restores the visitor's
   colour theme (the footer "Signal" switcher). Kept as a file, not inline,
   so the Content-Security-Policy can stay script-src 'self'. */
(function () {
  var root = document.documentElement;
  root.classList.add('js');
  try {
    var t = window.localStorage.getItem('clx-theme');
    if (t === 'acid' || t === 'infrared' || t === 'hazard' || t === 'volt') root.setAttribute('data-theme', t);
  } catch (e) {
    /* Storage blocked: keep the default theme. */
  }
})();
