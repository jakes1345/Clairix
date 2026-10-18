#!/usr/bin/env node
// Screenshots brand/tools/street/sheet.html to brand/previews/street-concepts.png.
//   python3 brand/tools/street/build.py && python3 brand/tools/street/sheet.py && node brand/tools/street/render.cjs
const path = require('node:path');
const { execSync } = require('node:child_process');
let pw;
try { pw = require('playwright'); } catch { pw = require(path.join(execSync('npm root -g').toString().trim(), 'playwright')); }
(async () => {
  const browser = await pw.chromium.launch(process.env.CHROMIUM_PATH ? { executablePath: process.env.CHROMIUM_PATH } : {});
  const page = await browser.newPage({ viewport: { width: 2400, height: 1200 }, deviceScaleFactor: 1 });
  await page.goto('file://' + path.join(__dirname, 'sheet.html'));
  await page.evaluate(() => document.fonts.ready);
  await page.waitForTimeout(200);
  const out = path.resolve(__dirname, '../../previews/street-concepts.png');
  await page.screenshot({ path: out, fullPage: true });
  await browser.close();
  console.log('wrote', out);
})();
