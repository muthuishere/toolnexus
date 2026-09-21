// The on-page view: the board on the left, the judge's reasoning on the right.
// Visual grammar carried over from the dino build (sorted probability bars, a
// runner-up ring, a live ms counter against a rAF deadline bar, a badged decision
// feed) — with one addition this game earns: the board is drawn from the SAME
// state object the sentences were generated from, so you can see the sentence and
// the square it describes at once.
export const PAGE = `<!doctype html><meta charset=utf-8><title>jev plays snake</title>
<style>
 :root{color-scheme:dark}
 body{margin:0;background:#0d1117;color:#c9d1d9;font:12px/1.5 ui-monospace,SFMono-Regular,Menlo,monospace;display:flex;height:100vh}
 #left{flex:1;display:flex;flex-direction:column;align-items:center;justify-content:center;gap:14px}
 #grid{display:grid;grid-template-columns:repeat(12,34px);grid-template-rows:repeat(12,34px);gap:2px}
 .c{background:#161b22;border-radius:3px}
 .c.body{background:#2f6f4f}.c.head{background:#3fb950}.c.food{background:#f0883e}
 #score{font-size:15px;color:#e6edf3;letter-spacing:.06em}
 #dead{color:#f85149;height:16px}
 #right{width:430px;border-left:1px solid #30363d;display:flex;flex-direction:column}
 .sec{padding:10px 12px;border-bottom:1px solid #21262d}
 .hd{padding:10px 12px;border-bottom:1px solid #30363d;display:flex;justify-content:space-between}
 .arm{font-weight:700;letter-spacing:.08em;text-transform:uppercase;color:#58a6ff}
 .lbl{color:#8b949e;font-size:10px;letter-spacing:.14em;text-transform:uppercase;margin-bottom:6px}
 .state div{margin-bottom:3px;color:#adbac7}
 .opt{margin-bottom:7px}.optrow{display:flex;justify-content:space-between;margin-bottom:2px}
 .bar{height:7px;background:#21262d;border-radius:3px;overflow:hidden}
 .bar i{display:block;height:100%;background:#3fb950;transition:width .18s}
 .opt.ru .bar{outline:1px dotted #8b949e;outline-offset:1px}.opt.lose .bar i{background:#30475e}
 .sent{color:#6e7681;font-size:10.5px;margin-top:2px}
 .dl{height:9px;background:#21262d;border-radius:4px;overflow:hidden}
 .dl i{display:block;height:100%;background:#3fb950}
 .dl.warn i{background:#d29922}.dl.danger i{background:#f85149}
 .feed{flex:1;overflow:auto;padding:8px 12px}
 .fr{display:flex;gap:6px;margin-bottom:3px;font-size:11px}
 .bg{padding:0 5px;border-radius:3px;font-size:9.5px;font-weight:700}
 .APPLIED{background:#1f6f3f;color:#d6ffe4}.FORCED{background:#3d3567;color:#d8d0ff}
 .LATE{background:#7d5a10;color:#ffeab8}.ERROR{background:#7d1f1f;color:#ffd6d6}
 .ft{padding:8px 12px;border-top:1px solid #30363d;color:#6e7681;font-size:10px}
</style>
<div id=left><div id=score>0 apples</div><div id=grid></div><div id=dead></div></div>
<div id=right>
 <div class=hd><span class=arm id=arm>-</span><span id=tick></span></div>
 <div class=sec><div class=lbl>what it is shown &mdash; no digits, ever</div><div class=state id=state></div></div>
 <div class=sec><div class=lbl>one full second per move</div><div class=dl id=dl><i style="width:100%"></i></div>
  <div style="display:flex;justify-content:space-between;margin-top:4px">
   <span id=think style="color:#58a6ff">&nbsp;</span><span id=conf style="color:#8b949e"></span></div></div>
 <div class=sec><div class=lbl>the deck &mdash; only moves that do not kill it this step</div><div id=opts></div></div>
 <div class=sec><div class=lbl>speculative side-questions</div><div id=nouls style="color:#adbac7"></div></div>
 <div class=feed><div class=lbl>decisions</div><div id=feed></div></div>
 <div class=ft>FLOOD FILL OURS &middot; RANKING ITS &middot; IT NEVER SEES A COORDINATE</div>
</div>
<script>
 const $=(i)=>document.getElementById(i)
 const grid=$('grid'); const cells=[]
 for(let i=0;i<144;i++){const d=document.createElement('div');d.className='c';grid.appendChild(d);cells.push(d)}
 let t0=null,budget=1000,raf=null
 const tickBar=()=>{ if(t0===null)return
   const used=performance.now()-t0, left=Math.max(0,1-used/budget)
   $('think').textContent='thinking '+Math.round(used)+' ms'
   $('dl').firstElementChild.style.width=(left*100).toFixed(1)+'%'
   $('dl').className='dl'+(left<0.15?' danger':left<0.4?' warn':'')
   raf=requestAnimationFrame(tickBar) }
 window.__v={
  arm:(n)=>{$('arm').textContent=n},
  board:(b)=>{
    cells.forEach(c=>c.className='c')
    b.snake.forEach((s,i)=>{const c=cells[s.y*12+s.x]; if(c)c.className='c '+(i?'body':'head')})
    if(b.food){const c=cells[b.food.y*12+b.food.x]; if(c)c.className='c food'}
    $('score').textContent=b.apples+(b.apples===1?' apple':' apples')
    $('tick').textContent='move '+b.moves
    $('dead').textContent=b.dead?('dead — '+b.dead):'' },
  begin:({state,criteria,budget:bd})=>{
    $('state').innerHTML=Object.values(state).map(s=>'<div>'+s+'</div>').join('')
    $('opts').innerHTML=Object.entries(criteria).map(([id,s])=>
      '<div class=opt data-id='+id+'><div class=optrow><b>'+id+'</b><span>·</span></div>'+
      '<div class=bar><i style="width:0%"></i></div><div class=sent>'+s+'</div></div>').join('')
    $('nouls').textContent=''
    budget=bd; t0=performance.now(); if(raf)cancelAnimationFrame(raf); tickBar() },
  answer:(d)=>{
    if(raf)cancelAnimationFrame(raf); raf=null; t0=null
    $('think').textContent=d.outcome==='forced'?'no call — only one legal move'
      :d.latency!=null?('answered in '+Math.round(d.latency)+' ms of the '+budget+' ms tick'):'ranked by code, no call'
    $('conf').textContent=d.confidence!=null?('confidence '+d.confidence.toFixed(2)):''
    const p=d.probabilities||{}
    document.querySelectorAll('#opts .opt').forEach(o=>{
      const id=o.dataset.id,v=p[id]
      o.className='opt'+(id===d.chosen?'':id===d.runnerUp?' ru lose':' lose')
      o.querySelector('i').style.width=((v??(id===d.chosen?1:0))*100).toFixed(1)+'%'
      o.querySelector('span').textContent=v!=null?v.toFixed(2):(id===d.chosen?'picked':'') })
    if(d.nouls)$('nouls').innerHTML=Object.entries(d.nouls)
      .map(([k,v])=>k.replace(/_/g,' ')+': <b>'+(v==null?'n/a':v.toFixed(2))+'</b>').join(' &nbsp; ')
    const badge=d.outcome==='error'?'ERROR':d.outcome==='late'?'LATE':d.outcome==='forced'?'FORCED':'APPLIED'
    const r=document.createElement('div');r.className='fr'
    r.innerHTML='<span class="bg '+badge+'">'+badge+'</span><span>'+d.chosen+' · '+Math.round(d.latency||0)+' ms</span>'
    $('feed').insertBefore(r,$('feed').firstChild)
    while($('feed').children.length>40)$('feed').removeChild($('feed').lastChild) },
 }
</script>`
