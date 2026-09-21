import { chromium } from '/Users/muthuishere/.npm/_npx/e41f203b7505f1fb/node_modules/playwright/index.mjs'
const CHROME = '/Users/muthuishere/Library/Caches/ms-playwright/chromium-1243/chrome-mac-arm64/Google Chrome for Testing.app/Contents/MacOS/Google Chrome for Testing'
const AD = /doubleclick|googlesyndication|googletagmanager|google-analytics|adservice|taboola|outbrain|testlibrary|pubmatic|criteo|amazon-adsystem|adnxs/i

const browser = await chromium.launch({ headless: false, executablePath: CHROME,
  args: ['--window-position=-1728,0', '--window-size=1400,1040'] })
const ctx = await browser.newContext({ viewport: { width: 1380, height: 950 } })
await ctx.route('**/*', r => AD.test(r.request().url()) ? r.abort() : r.continue())
const page = await ctx.newPage()

await page.goto('https://wordlegame.org/unlimited', { waitUntil: 'domcontentloaded', timeout: 30000 })
await page.waitForTimeout(2500)
// kill the Play modal + any leftover ad frames
try { await page.getByRole('button', { name: /^play$/i }).first().click({ timeout: 4000 }) } catch {}
await page.waitForTimeout(700)
await page.evaluate(() => {
  document.querySelectorAll('iframe,[id*="ad" i],[class*="ad-" i],[class*="banner" i]').forEach(e => e.remove())
})

const board = () => page.evaluate(() => [...document.querySelectorAll('[class*="letter" i]')].slice(0,30)
  .map(e => ({ l: (e.textContent||'').trim().charAt(0).toUpperCase(), c: e.className.toString() })))

const guess = async (w) => {
  for (const ch of w) { await page.keyboard.press(ch); await page.waitForTimeout(110) }
  await page.keyboard.press('Enter'); await page.waitForTimeout(2200)
  const b = await board()
  const row = b.slice((guess.n||0)*5, (guess.n||0)*5+5)
  guess.n = (guess.n||0)+1
  return row.map(t => t.c.includes('correct') ? 'G' : t.c.includes('elsewhere') ? 'Y' : t.c.includes('absent') ? '.' : '?').join('')
}

const seen = new Set()
for (const w of ['CRANE','SLOTH','PUDGY','MIRTH','BLANK','QUEST']) {
  const pat = await guess(w)
  ;(await board()).forEach(t => t.c.split(/\s+/).forEach(c => c.startsWith('letter-') && seen.add(c)))
  console.log(`${w} -> ${pat}`)
  if (pat === 'GGGGG') { console.log('SOLVED'); break }
}
console.log('state classes seen:', [...seen].join(' '))
await page.screenshot({ path: '/tmp/wordle_play.png' })
await page.waitForTimeout(1200)
await browser.close()
