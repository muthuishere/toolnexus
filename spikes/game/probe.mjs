// Drivability probe: can we read tiles and type guesses on a REAL Wordle site?
// No toolnexus, no Jev. One question only: does the site cooperate.
import { chromium } from '/Users/muthuishere/.npm/_npx/e41f203b7505f1fb/node_modules/playwright/index.mjs'

const SITES = [
  { name: 'wordlegame.org',      url: 'https://wordlegame.org/' },
  { name: 'wordleunlimited.org', url: 'https://wordleunlimited.org/' },
  { name: 'nytimes wordle',      url: 'https://www.nytimes.com/games/wordle/index.html' },
]

const CHROME = '/Users/muthuishere/Library/Caches/ms-playwright/chromium-1243/chrome-mac-arm64/Google Chrome for Testing.app/Contents/MacOS/Google Chrome for Testing'
const browser = await chromium.launch({
  headless: false,
  executablePath: CHROME,
  args: ['--window-position=-1728,0', '--window-size=1400,1040'],
})
const ctx = await browser.newContext({ viewport: { width: 1380, height: 950 } })
const page = await ctx.newPage()

for (const site of SITES) {
  const out = { site: site.name }
  try {
    await page.goto(site.url, { waitUntil: 'domcontentloaded', timeout: 25000 })
    await page.waitForTimeout(3500)
    out.title = (await page.title()).slice(0, 60)
    // What does the board look like in the DOM?
    out.probe = await page.evaluate(() => {
      const count = (sel) => document.querySelectorAll(sel).length
      const cands = {
        '[class*="tile" i]': count('[class*="tile" i]'),
        '[class*="cell" i]': count('[class*="cell" i]'),
        '[class*="letter" i]': count('[class*="letter" i]'),
        '[data-state]': count('[data-state]'),
        '[class*="row" i]': count('[class*="row" i]'),
        '[class*="key" i]': count('[class*="key" i]'),
        'game-tile': count('game-tile'),
      }
      // any dialog/consent in the way?
      const blockers = [...document.querySelectorAll('dialog,[role="dialog"],[id*="consent" i],[class*="consent" i],[id*="onetrust" i],[class*="modal" i]')]
        .filter(e => e.offsetParent !== null).map(e => (e.id || e.className || e.tagName).toString().slice(0, 50))
      return { counts: cands, blockers: blockers.slice(0, 4), bodyLen: document.body.innerText.length }
    })
  } catch (e) { out.error = String(e).split('\n')[0].slice(0, 110) }
  console.log(JSON.stringify(out))
}
await page.waitForTimeout(1000)
await browser.close()
