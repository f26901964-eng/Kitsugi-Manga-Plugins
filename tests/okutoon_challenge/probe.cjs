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
  '/api/v1/series/chainsaw-man/chapters/bolum-1',
  '/api/v1/series/chainsaw-man/chapters/bolum-2',
  '/api/v1/series/one-piece/chapters/bolum-1197',
];
const report = { date: new Date().toISOString(), note: 'Hosted CI IP; desktop Chromium is not Android WebView. No cookies captured.', http: [], browser: {} };
async function request(url, headers) {
  try {
    const r = await fetch(url, { headers, signal: AbortSignal.timeout(10000), redirect: 'follow' });
    const bytes = new Uint8Array(await r.arrayBuffer());
    const text = new TextDecoder().decode(bytes.slice(0, 512));
    let schema;
    if ((r.headers.get('content-type') || '').includes('application/json')) {
      try {
        const json = JSON.parse(new TextDecoder().decode(bytes));
        const walk = (value, depth = 0) => {
          if (depth > 4) return Array.isArray(value) ? `[array:${value.length}]` : typeof value;
          if (Array.isArray(value)) return { length: value.length, sample: value.length ? walk(value[0], depth + 1) : null };
          if (value && typeof value === 'object') return Object.fromEntries(Object.entries(value).slice(0, 80).map(([key, v]) => [key, walk(v, depth + 1)]));
          if (typeof value === 'string') return value.startsWith('https://om-cdn.okutoon.com/chapters/') ? value : `string(${value.length})`;
          return value;
        };
        schema = walk(json);
      } catch (_) {}
    }
    return { schema, status: r.status, challenge: r.headers.get('cf-mitigated'), type: r.headers.get('content-type'), bytes: bytes.length,
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
  for (const path of candidates) {
    const detail = { path, ...await request(origin + path, headers) };
    if (detail.status === 200 && detail.json) {
      try {
        const api = await fetch(origin + path, { headers, signal: AbortSignal.timeout(10000) });
        const data = (await api.json()).data;
        const pages = data.pages;
        detail.pageCount = pages.length;
        detail.images = [];
        for (const i of [...new Set([0, Math.floor(pages.length / 2), pages.length - 1])]) {
          const raw = pages[i].image_url;
          const url = new URL(raw, origin);
          const imageResult = await request(url.toString(), { ...headers, referer: chapter });
          detail.images.push({ index: i, pageNumber: pages[i].page_number, scheme: url.protocol, host: url.hostname,
            extension: url.pathname.split('.').pop(), ...imageResult });
        }
      } catch (e) { detail.sampleError = e.name; }
    }
    report.http.push(detail);
  }
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
