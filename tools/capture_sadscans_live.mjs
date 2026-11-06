// sadscans.net'in GERÇEK tarayıcıdaki hâlini kaydeder.
//
// Amaç: eklenti kararlarını tahmin yerine canlı DOM'a dayandırmak. Chromium ile siteye girer,
// client-side render sonrası HTML'i ve seçici sayaçlarını kaydeder; sayfa içi tRPC isteğinin
// gerçekten çalışıp çalışmadığını ölçer.
//
// Çıktı: docs/html-dumps/sadscans/*.html, *.png ve reports/sadscans-capture.json
import fs from 'node:fs';
import path from 'node:path';
import {chromium} from 'playwright';

const BASE = 'https://sadscans.net';
const OUT = 'docs/html-dumps/sadscans';
const REPORT = 'reports/sadscans-capture.json';
const UA = 'Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0.0.0 Safari/537.36';

const report = {base: BASE, pages: {}, transport: {}, notes: []};

fs.mkdirSync(OUT, {recursive: true});
fs.mkdirSync(path.dirname(REPORT), {recursive: true});

const save = async (page, name) => {
  try {
    fs.writeFileSync(path.join(OUT, `${name}.html`), await page.content(), 'utf8');
  } catch (error) {
    report.notes.push(`${name}: HTML yazılamadı: ${error}`);
  }
  try {
    await page.screenshot({path: path.join(OUT, `${name}.png`), fullPage: false});
  } catch (error) {
    report.notes.push(`${name}: ekran görüntüsü alınamadı: ${error}`);
  }
};

const isBlocked = async (page) => {
  try {
    const text = (await page.innerText('body')).slice(0, 4000);
    return text.includes('Sorry, you have been blocked') || text.includes('Attention Required');
  } catch {
    return false;
  }
};

const settle = async (page, seconds) => {
  const deadline = Date.now() + seconds * 1000;
  while (Date.now() < deadline) {
    if (await isBlocked(page)) {
      await page.waitForTimeout(2000);
      continue;
    }
    return;
  }
};

const run = async () => {
  const browser = await chromium.launch({headless: true, args: ['--no-sandbox', '--disable-dev-shm-usage']});
  const context = await browser.newContext({
    userAgent: UA,
    locale: 'tr-TR',
    viewport: {width: 1366, height: 900},
  });
  const page = await context.newPage();

  // ------------------------------------------------------------------ /seriler
  const entry = {};
  try {
    const response = await page.goto(`${BASE}/seriler`, {waitUntil: 'domcontentloaded', timeout: 60000});
    entry.status = response ? response.status() : null;
    await settle(page, 45);
    await page.waitForTimeout(9000);
    entry.blocked = await isBlocked(page);
    entry.title = await page.title();
    entry.search_inputs = await page.$$eval('input', els => els.map(e => ({placeholder: e.placeholder, name: e.name, type: e.type})));
    entry.dropdown_containers = await page.$$eval('.dropdown-container', els => els.map(e => e.innerText.trim().slice(0, 80)));
    entry.card_selectors = {
      'a[href^="/seriler/"] h3': (await page.$$('a[href^="/seriler/"] h3')).length,
      'a[href*="/seriler/"]': (await page.$$('a[href*="/seriler/"]')).length,
      h3: (await page.$$('h3')).length,
    };
    entry.first_cards = await page.$$eval('a[href*="/seriler/"]', els => els.slice(0, 10).map(a => ({href: a.getAttribute('href'), text: a.innerText.trim().slice(0, 60)})));
    entry.sentinel = (await page.$$('div[aria-hidden="true"].h-px.w-full')).length;
    entry.body_head = (await page.innerText('body')).slice(0, 500);
    await save(page, 'seriler');

    try {
      const box = page.locator('input[placeholder*="Aramak"], input[placeholder*="ara"]').first();
      await box.click({timeout: 5000});
      await box.fill('');
      await box.type('chainsaw', {delay: 120});
      await page.waitForTimeout(9000);
      entry.search_cards = await page.$$eval('a[href*="/seriler/"]', els => els.slice(0, 10).map(a => a.getAttribute('href')));
      entry.search_url = page.url();
      entry.search_body_head = (await page.innerText('body')).slice(0, 500);
      await save(page, 'seriler-arama');
    } catch (error) {
      entry.search_error = String(error);
    }

    try {
      report.transport.page_fetch_getSeries = await page.evaluate(async () => {
        const url = '/api/trpc/_s4d7g2k9m0.getSeries?batch=1&input=' +
          encodeURIComponent(JSON.stringify({0: {json: {page: 1, limit: 15, sortBy: '-views'}}}));
        try {
          const response = await fetch(url, {credentials: 'same-origin', headers: {accept: 'application/json'}});
          const text = await response.text();
          return {status: response.status, length: text.length, head: text.slice(0, 400)};
        } catch (error) {
          return {error: String(error)};
        }
      });
    } catch (error) {
      report.transport.page_fetch_getSeries = {error: String(error)};
    }
  } catch (error) {
    entry.error = String(error);
  }
  report.pages.seriler = entry;

  // ------------------------------------------------------------------ seri detayı
  const detail = {};
  const href = (entry.first_cards || []).map(c => c.href || '').find(h => h.startsWith('/seriler/') && h.split('/').filter(Boolean).length >= 2);
  if (href) {
    try {
      const response = await page.goto(BASE + href, {waitUntil: 'domcontentloaded', timeout: 60000});
      detail.status = response ? response.status() : null;
      await settle(page, 30);
      await page.waitForTimeout(9000);
      detail.blocked = await isBlocked(page);
      detail.href = href;
      detail.title = await page.title();
      detail.body_head = (await page.innerText('body')).slice(0, 700);
      detail.chapter_links = {
        'a[href*="/reader/"]': (await page.$$('a[href*="/reader/"]')).length,
        'a[href*="/reader/"] h3': (await page.$$('a[href*="/reader/"] h3')).length,
      };
      detail.first_chapters = await page.$$eval('a[href*="/reader/"]', els => els.slice(0, 8).map(a => ({href: a.getAttribute('href'), text: a.innerText.trim().slice(0, 50)})));
      detail.pager_buttons = await page.$$eval('button', els => els.map(e => (e.getAttribute('aria-label') || e.innerText || '').trim()).filter(Boolean).slice(0, 25));
      await save(page, 'seri-detay');
    } catch (error) {
      detail.error = String(error);
    }
  } else {
    detail.skipped = 'kart bağlantısı bulunamadı';
  }
  report.pages.detail = detail;

  // ------------------------------------------------------------------ okuyucu
  const reader = {};
  const chapter = (detail.first_chapters || []).map(c => c.href || '').find(h => h.startsWith('/reader/'));
  if (chapter) {
    try {
      const response = await page.goto(BASE + chapter, {waitUntil: 'domcontentloaded', timeout: 60000});
      reader.status = response ? response.status() : null;
      await settle(page, 30);
      for (let i = 0; i < 6; i++) {
        await page.waitForTimeout(2500);
        await page.mouse.wheel(0, 2200);
      }
      reader.blocked = await isBlocked(page);
      reader.href = chapter;
      reader.title = await page.title();
      reader.images = await page.$$eval('img', els => els.slice(0, 40).map(img => ({
        src: img.getAttribute('src'),
        current: img.currentSrc || null,
        data_src: img.getAttribute('data-src'),
        data_lazy: img.getAttribute('data-lazy-src'),
        srcset: img.getAttribute('srcset'),
        loading: img.getAttribute('loading'),
        width: img.naturalWidth,
        height: img.naturalHeight,
        alt: (img.getAttribute('alt') || '').slice(0, 40),
      })));
      reader.img_count = reader.images.length;
      reader.body_head = (await page.innerText('body')).slice(0, 400);
      await save(page, 'okuyucu');
    } catch (error) {
      reader.error = String(error);
    }
  } else {
    reader.skipped = 'bölüm bağlantısı bulunamadı';
  }
  report.pages.reader = reader;

  await context.close();
  await browser.close();
};

try {
  await run();
} catch (error) {
  report.notes.push(`genel hata: ${error}`);
}

fs.writeFileSync(REPORT, JSON.stringify(report, null, 2) + '\n', 'utf8');
console.log(JSON.stringify(report, null, 2).slice(0, 8000));
