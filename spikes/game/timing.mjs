import { chromium } from '/Users/muthuishere/.npm/_npx/e41f203b7505f1fb/node_modules/playwright/index.mjs'
const CHROME='/Users/muthuishere/Library/Caches/ms-playwright/chromium-1243/chrome-mac-arm64/Google Chrome for Testing.app/Contents/MacOS/Google Chrome for Testing'
const b=await chromium.launch({headless:false,executablePath:CHROME,args:['--window-position=-1728,0','--window-size=1400,1040']})
const p=await (await b.newContext({viewport:{width:1380,height:900}})).newPage()
await p.goto('https://chromedino.com/',{waitUntil:'domcontentloaded',timeout:25000}); await p.waitForTimeout(2500)
await p.evaluate(()=>document.querySelectorAll('iframe,[id*="ad" i]').forEach(e=>e.remove()))
await p.keyboard.press('Space'); await p.waitForTimeout(600)

// read latency of one evaluate round-trip
const t0=Date.now(); for(let i=0;i<20;i++) await p.evaluate(()=>Runner.instance_.distanceRan); const rtt=(Date.now()-t0)/20

// play with a pure-code reflex; log obstacle lead time
const seen=new Map(); const leads=[]; let ticks=0; const start=Date.now()
while(Date.now()-start<25000){
  const s=await p.evaluate(()=>{const R=Runner.instance_
    return {crashed:R.crashed,speed:R.currentSpeed,d:Math.round(R.distanceRan),
      obs:(R.horizon?.obstacles||[]).slice(0,2).map(o=>({x:Math.round(o.xPos),y:Math.round(o.yPos),w:o.width,t:o.typeConfig?.type||'?'}))}})
  ticks++
  if(s.crashed) break
  const o=s.obs[0]
  if(o){
    const key=`${o.t}@${s.d-Math.round(o.x)}`
    // lead time = how long until the obstacle reaches the dino (x≈44), at current speed
    const px=o.x-44, ms=px>0? (px/(s.speed*(1000/60)))*1000 : 0
    if(!seen.has(key)){ seen.set(key,true); if(ms>0&&ms<6000) leads.push(Math.round(ms)) }
    if(px<140&&px>0) { await p.keyboard.press('Space') }   // dumb reflex, code only
  }
}
const fin=await p.evaluate(()=>({d:Math.round(Runner.instance_.distanceRan),crashed:Runner.instance_.crashed}))
leads.sort((a,b)=>a-b)
const pct=q=>leads.length?leads[Math.floor(leads.length*q)]:null
console.log(JSON.stringify({evaluate_rtt_ms:+rtt.toFixed(1), ticks, tick_hz:+(ticks/((Date.now()-start)/1000)).toFixed(1),
  obstacles_seen:leads.length, lead_ms:{p10:pct(.1),p50:pct(.5),p90:pct(.9),max:leads[leads.length-1]},
  final_distance:fin.d, crashed:fin.crashed},null,0))
await b.close()
