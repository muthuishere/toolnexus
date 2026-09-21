import { chromium } from '/Users/muthuishere/.npm/_npx/e41f203b7505f1fb/node_modules/playwright/index.mjs'
const CHROME = '/Users/muthuishere/Library/Caches/ms-playwright/chromium-1243/chrome-mac-arm64/Google Chrome for Testing.app/Contents/MacOS/Google Chrome for Testing'
const SITES = [
  { n:'chromedino.com',  u:'https://chromedino.com/' },
  { n:'elgoog t-rex',    u:'https://elgoog.im/t-rex/' },
  { n:'flappybird',      u:'https://flappybird.io/' },
]
const b = await chromium.launch({ headless:false, executablePath:CHROME,
  args:['--window-position=-1728,0','--window-size=1400,1040'] })
const p = await (await b.newContext({viewport:{width:1380,height:900}})).newPage()
for (const s of SITES) {
  const o = { site:s.n }
  try {
    await p.goto(s.u, { waitUntil:'domcontentloaded', timeout:25000 }); await p.waitForTimeout(3000)
    o.state = await p.evaluate(() => {
      const g = {}
      // does the game expose internals we can read each frame?
      for (const k of ['Runner','GameRunner','game','Game','FlappyBird','__game'])
        if (window[k]) g[k] = typeof window[k]
      const R = window.Runner && (window.Runner.instance_ || (window.Runner.prototype && window.Runner))
      let dino = null
      if (R) dino = { hasObstacles: !!(R.horizon && R.horizon.obstacles),
                      obstacles: R.horizon?.obstacles?.length ?? null,
                      speed: typeof R.currentSpeed, crashed: typeof R.crashed,
                      distance: typeof R.distanceRan }
      return { globals:g, dino, canvases: document.querySelectorAll('canvas').length }
    })
  } catch(e){ o.err = String(e).split('\n')[0].slice(0,90) }
  console.log(JSON.stringify(o))
}
await b.close()
