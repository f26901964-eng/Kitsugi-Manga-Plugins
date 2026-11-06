// Controlled, read-only diagnostic. No challenge solving service, credentials or cookies are logged.
const { createRequire } = require('node:module');
const { writeFileSync, mkdirSync } = require('node:fs');
const { dirname } = require('node:path');
const localRequire = createRequire(process.env.PROBE_NODE_DIR + '/package.json');
const { chromium } = localRequire('playwright');
const origin = 'https://okutoon.com';
const chapter = `${origin}/chainsaw-man/bolum-1`;
const image = 'https://om-cdn.okutoon.com/chapters/503/56171/chainsaw-man_bolum-1_001.webp';
const ua = 'Mozilla/5.0 (Linux; Android 13; SM-S918B) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Mobile Safari/537.36';
const candidates = [
  '/api/v1/series/chainsaw-man',
  '/api/v1/series/chainsaw-man/chapters',
  '/api/v1/chapters/56171',
  '/api/v1/chapters/56171/pages',
  '/api/v1/series/chainsaw-man/chapters/1',
];
const report = { date: new Date().toISOString(), note: 'Hosted CI IP; desktop Chromium is not Android WebView. No cookies captured.', http: [], browser: {} };
async function request(url, headers) {
  try {
    const r = await fetch(url, { headers, signal: AbortSignal.timeout(10000), redirect: 'follow' });
    const bytes = new Uint8Array(await r.arrayBuffer());
    const text = new TextDecoder().decode(bytes.slice(0, 512));
    return { status: r.status, challenge: r.headers.get('cf-mitigated'), type: r.headers.get('content-type'), bytes: bytes.length,
      webp: bytes.length > 12 && String.fromCharCode(...bytes.slice(0,4)) === 'RIFF' && String.fromCharCode(...bytes.slice(8,12)) === 'WEBP',
      json: (r.headers.get('content-type') || '').includes('application/json'),
      // Error message only, to distinguish generic 404s; never record tokens or cookies.
      generic404: /not found|bulunamadı/i.test(text) };
  } catch (e) { return { error: e.name }; }
}
(async () => {
  const headers = { 'user-agent': ua, 'accept-language': 'tr-TR,tr;q=0.9' };
  report.http.push({ path: '/chainsaw-man/bolum-1', ...await request(chapter, headers) });
  report.http.push({ path: 'first CDN image', ...await request(image, { ...headers, referer: chapter }) });
  for (const path of candidates) report.http.push({ path, ...await request(origin + path, headers) });
  let browser;
  try {
    // Real installed browser identity, headed under Xvfb; no challenge manipulation.
    browser = await chromium.launch({ headless: false, executablePath: process.env.CHROME_PATH || '/usr/bin/google-chrome', args: ['--no-sandbox'] });
    const page = await browser.newPage();
    const response = await page.goto(chapter, { waitUntil: 'domcontentloaded', timeout: 30000 });
    report.browser.initialStatus = response?.status();
    report.browser.initialChallenge = response?.headers()['cf-mitigated'] || null;
    try { await page.waitForSelector('#readerPages img.reader-page', { timeout: 30000, state: 'attached' }); } catch (_) {}
    report.browser.pageCount = await page.locator('#readerPages img.reader-page').count();
    report.browser.title = await page.title();
    report.browser.finalHost = new URL(page.url()).hostname;
    report.browser.challengeOptionsPresent = await page.evaluate(() => typeof window._cf_chl_opt !== 'undefined');
  } catch (e) {
    report.browser.error = e.name;
  } finally {
    if (browser) await browser.close();
  }
  const output = process.env.REPORT || 'reports/okutoon-challenge-probe.json';
  mkdirSync(dirname(output), { recursive: true });
  writeFileSync(output, JSON.stringify(report, null, 2) + '\n');
  console.log(JSON.stringify(report, null, 2));
})().catch(e => { console.error(e); process.exit(1); });
