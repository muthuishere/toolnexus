import { chromium } from '/Users/muthuishere/.npm/_npx/e41f203b7505f1fb/node_modules/playwright/index.mjs'
const CHROME = '/Users/muthuishere/Library/Caches/ms-playwright/chromium-1243/chrome-mac-arm64/Google Chrome for Testing.app/Contents/MacOS/Google Chrome for Testing'

const browser = await chromium.launch({ headless: false, executablePath: CHROME,
  args: ['--window-position=-1728,0', '--window-size=1400,1040'] })
const page = await (await browser.newContext({ viewport: { width: 1380, height: 950 } })).newPage()

await page.goto('https://wordlegame.org/', { waitUntil: 'domcontentloaded', timeout: 30000 })
await page.waitForTimeout(3000)

// close anything covering the board
for (const sel of ['button:has-text("Play")','button:has-text("Accept")','button:has-text("Got it")',
                   '[class*="close" i]','[aria-label*="close" i]']) {
  try { const el = page.locator(sel).first(); if (await el.isVisible({timeout:600})) { await el.click({timeout:1200}); await page.waitForTimeout(500) } } catch {}
}
await page.keyboard.press('Escape').catch(()=>{})
await page.waitForTimeout(800)

const readBoard = () => page.evaluate(() => {
  const els = [...document.querySelectorAll('[class*="letter" i]')]
  return els.slice(0, 30).map(e => ({ t: (e.textContent||'').trim(), c: e.className.toString().slice(0,60) }))
})

console.log('BEFORE:', JSON.stringify((await readBoard()).slice(0,5)))

// type a guess on the real keyboard
for (const ch of 'CRANE') { await page.keyboard.press(ch); await page.waitForTimeout(140) }
await page.waitForTimeout(400)
console.log('TYPED :', JSON.stringify((await readBoard()).slice(0,5)))
await page.keyboard.press('Enter')
await page.waitForTimeout(2600)
const after = await readBoard()
console.log('AFTER :', JSON.stringify(after.slice(0,5)))
console.log('ROW2  :', JSON.stringify(after.slice(5,10)))

await page.screenshot({ path: '/tmp/wordle_after.png' })
await page.waitForTimeout(1500)
await browser.close()
