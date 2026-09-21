// The HUD, injected into the page. Visual grammar lifted from the corpus:
// sorted probability bars with the winner highlighted and a dotted ring on the
// runner-up (jev-tetris), a live ms counter that RUNS while thinking and freezes
// on the answer (jev-for-chrome), a rAF deadline bar with ok/warn/danger/missed
// (typesafe-snake), a cumulative cost counter, the literal state on screen, and a
// decision feed badged APPLIED / FORCED / LATE / ERROR (pokemon-red).
export const HUD_SOURCE = `(() => {
  if (window.__hud) return
  const css = \`
  #jevhud{position:fixed;top:0;right:0;width:430px;height:100vh;overflow:hidden;z-index:2147483647;
    background:#0d1117;color:#c9d1d9;font:12px/1.5 ui-monospace,SFMono-Regular,Menlo,monospace;
    border-left:1px solid #30363d;display:flex;flex-direction:column}
  #jevhud .hd{padding:10px 12px;border-bottom:1px solid #30363d;display:flex;justify-content:space-between;align-items:baseline}
  #jevhud .arm{font-weight:700;letter-spacing:.08em;text-transform:uppercase;color:#58a6ff}
  #jevhud .sec{padding:10px 12px;border-bottom:1px solid #21262d}
  #jevhud .lbl{color:#8b949e;font-size:10px;letter-spacing:.14em;text-transform:uppercase;margin-bottom:6px}
  #jevhud .state div{margin-bottom:3px;color:#adbac7}
  #jevhud .opt{margin-bottom:7px}
  #jevhud .optrow{display:flex;justify-content:space-between;margin-bottom:2px}
  #jevhud .bar{height:7px;background:#21262d;border-radius:3px;overflow:hidden}
  #jevhud .bar i{display:block;height:100%;background:#3fb950;transition:width .18s}
  #jevhud .opt.ru .bar{outline:1px dotted #8b949e;outline-offset:1px}
  #jevhud .opt.lose .bar i{background:#30475e}
  #jevhud .sent{color:#6e7681;font-size:10.5px;margin-top:2px}
  #jevhud .dl{height:9px;background:#21262d;border-radius:4px;overflow:hidden}
  #jevhud .dl i{display:block;height:100%;background:#3fb950;transition:width .05s linear}
  #jevhud .dl.warn i{background:#d29922}#jevhud .dl.danger i{background:#f85149}
  #jevhud .dl.missed i{background:#f85149;width:100%!important}
  #jevhud .feed{flex:1;overflow:auto;padding:8px 12px}
  #jevhud .fr{display:flex;gap:6px;align-items:center;margin-bottom:3px;font-size:11px}
  #jevhud .bg{padding:0 5px;border-radius:3px;font-size:9.5px;font-weight:700;letter-spacing:.06em}
  #jevhud .APPLIED{background:#1f6f3f;color:#d6ffe4}#jevhud .FORCED{background:#3d3567;color:#d8d0ff}
  #jevhud .LATE{background:#7d5a10;color:#ffeab8}#jevhud .ERROR{background:#7d1f1f;color:#ffd6d6}
  #jevhud .ft{padding:8px 12px;border-top:1px solid #30363d;color:#6e7681;font-size:10px;letter-spacing:.06em}
  #jevhud .big{font-size:15px;color:#e6edf3}\`
  const el = document.createElement('style'); el.textContent = css; document.head.appendChild(el)
  const d = document.createElement('div'); d.id = 'jevhud'
  d.innerHTML = \`
   <div class=hd><span class=arm id=h-arm>-</span><span id=h-dist class=big>0 m</span></div>
   <div class=sec><div class=lbl>what it is shown &mdash; no digits, ever</div><div class=state id=h-state></div></div>
   <div class=sec><div class=lbl>deadline &mdash; last frame a jump still clears</div>
     <div class=dl id=h-dl><i style="width:100%"></i></div>
     <div style="display:flex;justify-content:space-between;margin-top:4px">
       <span id=h-think style="color:#58a6ff">thinking 0 ms</span><span id=h-budget style="color:#8b949e"></span></div></div>
   <div class=sec><div class=lbl>the deck &mdash; it can only pick from here</div><div id=h-opts></div></div>
   <div class=sec><div class=lbl>speculative side-questions</div><div id=h-nouls style="color:#adbac7"></div></div>
   <div class=feed><div class=lbl>decisions</div><div id=h-feed></div></div>
   <div class=ft><div id=h-stats></div>ARITHMETIC OURS &middot; RANKING ITS &middot; IT NEVER SEES A NUMBER</div>\`
  document.body.appendChild(d)
  const $ = (id) => document.getElementById(id)
  let thinkStart = null, budget = 0, raf = null
  const tickBar = () => {
    if (thinkStart === null) return
    const used = performance.now() - thinkStart
    $('h-think').textContent = 'thinking ' + Math.round(used) + ' ms'
    const left = Math.max(0, 1 - used / budget)
    const bar = $('h-dl'); bar.firstElementChild.style.width = (left * 100).toFixed(1) + '%'
    bar.className = 'dl' + (left < 0.15 ? ' danger' : left < 0.4 ? ' warn' : '')
    if (left <= 0) bar.className = 'dl missed'
    raf = requestAnimationFrame(tickBar)
  }
  window.__hud = {
    arm: (n) => { $('h-arm').textContent = n },
    dist: (m) => { $('h-dist').textContent = m + ' m' },
    begin: (state, criteria, budgetMs) => {
      $('h-state').innerHTML = Object.values(state).map((s) => '<div>' + s + '</div>').join('')
      $('h-opts').innerHTML = Object.entries(criteria).map(([id, s]) =>
        '<div class=opt data-id=' + id + '><div class=optrow><b>' + id + '</b><span>&middot;</span></div>' +
        '<div class=bar><i style="width:0%"></i></div><div class=sent>' + s + '</div></div>').join('')
      $('h-nouls').textContent = ''
      budget = Math.max(1, budgetMs); thinkStart = performance.now()
      if (raf) cancelAnimationFrame(raf); tickBar()
    },
    answer: (d) => {
      if (raf) cancelAnimationFrame(raf); raf = null; thinkStart = null
      if (d.latency != null) $('h-think').textContent = 'answered in ' + Math.round(d.latency) + ' ms'
      $('h-budget').textContent = d.confidence != null ? 'confidence ' + d.confidence.toFixed(2) : ''
      const p = d.probabilities || {}
      document.querySelectorAll('#h-opts .opt').forEach((o) => {
        const id = o.dataset.id, v = p[id]
        o.className = 'opt' + (id === d.chosen ? '' : id === d.runnerUp ? ' ru lose' : ' lose')
        o.querySelector('i').style.width = ((v ?? (id === d.chosen ? 1 : 0)) * 100).toFixed(1) + '%'
        o.querySelector('span').textContent = v != null ? v.toFixed(2) : (id === d.chosen ? 'picked' : '')
      })
      if (d.nouls) $('h-nouls').innerHTML = Object.entries(d.nouls)
        .map(([k, v]) => k.replace(/_/g, ' ') + ': <b>' + (v == null ? 'n/a' : v.toFixed(2)) + '</b>').join(' &nbsp; ')
    },
    feed: (badge, text) => {
      const r = document.createElement('div'); r.className = 'fr'
      r.innerHTML = '<span class="bg ' + badge + '">' + badge + '</span><span>' + text + '</span>'
      const f = $('h-feed'); f.insertBefore(r, f.firstChild)
      while (f.children.length > 40) f.removeChild(f.lastChild)
    },
    stats: (t) => { $('h-stats').textContent = t },
  }
})()`
