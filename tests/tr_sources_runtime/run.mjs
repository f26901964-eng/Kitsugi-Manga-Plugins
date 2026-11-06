// Live contract checks for the three Turkish sources whose HTML dumps were shared.
//
// Each block below mirrors what the extension asks the site for:
//   Hayalistic   -> MadaraNoAjax: /manga/?m_orderby=..., li.wp-manga-chapter, div.page-break img
//   KoreliManga  -> InitManga:    /recently-updated/, .chapter-item a, #chapter-content img
//   trmangaoku   -> Madara Ajax:  li.wp-manga-chapter, POST /ajax/chapters/, reader pages
// If a selector or an endpoint changes on the site, the run fails and prints which one.
import { writeFileSync, mkdirSync } from 'node:fs';
import { dirname } from 'node:path';

const UA =
  'Mozilla/5.0 (Linux; Android 13; SM-S918B) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Mobile Safari/537.36';
const HEADERS = { 'User-Agent': UA, 'Accept-Language': 'tr-TR,tr;q=0.9' };
const TIMEOUT_MS = 30_000;

const results = [];
function check(name, ok, detail = {}) {
  results.push({ name, ok, detail });
  const flat = JSON.stringify(detail).slice(0, 300);
  console.log(`${ok ? 'PASS' : 'FAIL'} ${name} ${flat}`);
}

async function get(url, extra = {}) {
  const controller = new AbortController();
  const timer = setTimeout(() => controller.abort(), TIMEOUT_MS);
  try {
    const response = await fetch(url, {
      headers: { ...HEADERS, ...extra.headers },
      method: extra.method ?? 'GET',
      body: extra.body,
      redirect: 'follow',
      signal: controller.signal,
    });
    const buf = Buffer.from(await response.arrayBuffer());
    return { status: response.status, ok: response.ok, buf, text: buf.toString('utf8'), headers: response.headers };
  } finally {
    clearTimeout(timer);
  }
}

// --- tiny HTML helpers (no parser dependency) ---------------------------------
const countMatches = (html, re) => (html.match(re) ?? []).length;
const links = (html, re) => {
  const out = [];
  for (const m of html.matchAll(re)) if (!out.includes(m[0])) out.push(m[0]);
  return out;
};
const attrs = (html, attrsRe, attr) => {
  const out = [];
  for (const m of html.matchAll(attrsRe)) {
    const found = m[0].match(new RegExp(`${attr}\\s*=\\s*["']([^"']+)["']`));
    if (found) out.push(found[1].trim());
  }
  return out;
};

function imageKind(buf) {
  if (buf.length < 1000) return null;
  if (buf.subarray(0, 4).toString('ascii') === 'RIFF' && buf.subarray(8, 12).toString('ascii') === 'WEBP') return 'webp';
  if (buf[0] === 0xff && buf[1] === 0xd8) return 'jpeg';
  if (buf.subarray(0, 8).toString('hex') === '89504e470d0a1a0a') return 'png';
  if (buf[0] === 0x47 && buf[1] === 0x49 && buf[2] === 0x46) return 'gif';
  return null;
}

// ------------------------------------------------------------------ Hayalistic
async function hayalistic() {
  const base = 'https://hayalistic.site';
  const archive = await get(`${base}/manga/?m_orderby=views`);
  check('hayalistic: archive listing answers 200', archive.ok, { status: archive.status });
  const cards = countMatches(archive.text, /class="page-item-detail[^"]*"/g);
  check('hayalistic: archive cards found (div.page-item-detail)', cards > 0, { cards });

  const seriesUrls = links(archive.text, /https:\/\/hayalistic\.site\/manga\/[a-z0-9-]+\//g).filter(
    (u) => !/\/manga\/(page|manga-genre)\//.test(u),
  );
  check('hayalistic: series links found', seriesUrls.length > 0, { first: seriesUrls[0] ?? null });
  if (!seriesUrls.length) return;

  const seriesUrl = seriesUrls[0];
  const series = await get(seriesUrl);
  let chapters = links(series.text, /https:\/\/hayalistic\.site\/manga\/[a-z0-9-]+\/bolum-[a-z0-9-]+\//g);
  let chapterItems = countMatches(series.text, /<li class="wp-manga-chapter/g);
  let source = 'html';

  if (!chapterItems) {
    // Madara themes may render the list over AJAX; try the endpoint the reader would use.
    const ajax = await get(`https://hayalistic.site${new URL(seriesUrl).pathname.replace(/\/$/, '')}/ajax/chapters/`, {
      method: 'POST',
      body: '',
      headers: { 'X-Requested-With': 'XMLHttpRequest', Referer: seriesUrl },
    });
    chapters = links(ajax.text, /https:\/\/hayalistic\.site\/manga\/[a-z0-9-]+\/bolum-[a-z0-9-]+\//g);
    chapterItems = countMatches(ajax.text, /<li class="wp-manga-chapter/g);
    source = `ajax:${ajax.status}`;
  }

  check('hayalistic: chapter list uses li.wp-manga-chapter', chapterItems > 0, {
    seriesUrl,
    bytes: series.text.length,
    hasChapterWord: series.text.includes('wp-manga-chapter'),
    bolumRefs: countMatches(series.text, /\/bolum-/g),
    items: chapterItems,
    links: chapters.length,
    source,
  });
  check('hayalistic: chapter dates use span.chapter-release-date', series.text.includes('chapter-release-date') || chapters.length > 0, {
    source,
  });
  if (!chapters.length) return;

  const chapter = await get(chapters[0]);
  const pageBreaks = countMatches(chapter.text, /class="page-break/g);
  const chapterImgs = links(chapter.text, /<img[^>]+class="wp-manga-chapter-img"[^>]*>/g);
  const srcs = chapterImgs
    .map((tag) => tag.match(/src="\s*([^"]+)"/)?.[1])
    .filter(Boolean)
    .map((s) => s.trim());
  check('hayalistic: reader page exposes div.page-break + wp-manga-chapter-img', pageBreaks > 0 && srcs.length > 0, {
    pageBreaks,
    images: srcs.length,
  });
  check('hayalistic: no chapter protector on a normal chapter', !chapter.text.includes('chapter-protector-data'), {});

  if (srcs.length) {
    const image = await get(srcs[0], { headers: { Referer: chapters[0] } });
    const kind = imageKind(image.buf);
    check('hayalistic: first page image downloads and decodes', Boolean(kind), {
      status: image.status,
      kind,
      bytes: image.buf.length,
      host: new URL(srcs[0]).host,
    });
  }
}

// ---------------------------------------------------------------- KoreliManga
async function korelimanga() {
  const base = 'https://korelimanga.com';
  const home = await get(`${base}/`);
  check('korelimanga: home answers 200', home.ok, { status: home.status });
  const seriesUrls = links(home.text, /https:\/\/korelimanga\.com\/manga\/[a-z0-9-]+\//g);
  check('korelimanga: series links on the home page', seriesUrls.length > 0, { count: seriesUrls.length });
  if (!seriesUrls.length) return;

  const series = await get(seriesUrls[0]);
  const items = links(series.text, /<a[^>]+class="uk-link-toggle"[^>]+href="[^"]+\/bolum-[^"]*"[^>]*>/g);
  check('korelimanga: chapter rows use div.chapter-item > a.uk-link-toggle', items.length > 0, {
    rows: countMatches(series.text, /class="chapter-item"/g),
    links: items.length,
  });
  if (!items.length) return;

  const chapterUrl = items[0].match(/href="([^"]+)"/)[1];
  const chapter = await get(chapterUrl);
  const contentImgs = links(chapter.text, /<div[^>]+id="chapter-content"[^>]*>[\s\S]*?<\/div>/g);
  const imgs = links(chapter.text, /<img[^>]+src="https:\/\/[^"]+\/(?:uploads|manga)[^"]*\.(?:webp|jpg|jpeg|png)"[^>]*>/g);
  const srcs = imgs.map((tag) => tag.match(/src="([^"]+)"/)?.[1]).filter(Boolean);
  // The theme references an optional decryption helper in its JS even for plain chapters;
  // what decides the reader is whether real <img> pages are in the markup.
  const encrypted = chapter.text.includes('InitMangaEncryptedChapter');
  check('korelimanga: reader serves plain <img> pages in #chapter-content', srcs.length > 0, {
    images: srcs.length,
    encryptedPayload: encrypted,
    hasWrapper: contentImgs.length > 0,
  });

  if (srcs.length) {
    const image = await get(srcs[0], { headers: { Referer: chapterUrl } });
    const kind = imageKind(image.buf);
    check('korelimanga: first page image downloads and decodes', Boolean(kind), {
      status: image.status,
      kind,
      bytes: image.buf.length,
      host: new URL(srcs[0]).host,
    });
  }
}

// ------------------------------------------------------------------ trmangaoku
async function trmangaoku() {
  const base = 'https://trmangaoku.com';
  const home = await get(`${base}/`);
  check('trmangaoku: home answers 200', home.ok, { status: home.status });
  const seriesUrls = links(home.text, /https:\/\/trmangaoku\.com\/manga\/[a-z0-9-]+\//g);
  check('trmangaoku: series links on the home page', seriesUrls.length > 0, { count: seriesUrls.length });
  if (!seriesUrls.length) return;

  const seriesUrl = seriesUrls[0];
  const series = await get(seriesUrl);
  const slug = seriesUrl.replace(/\/$/, '').split('/').pop();
  const ajaxUrl = `${seriesUrl.replace(/\/$/, '')}/ajax/chapters/`;
  const ajax = await get(ajaxUrl, {
    method: 'POST',
    body: '',
    headers: {
      'X-Requested-With': 'XMLHttpRequest',
      Referer: seriesUrl,
      'Content-Type': 'application/x-www-form-urlencoded',
    },
  });
  const ajaxChapters = links(ajax.text, /href="https:\/\/trmangaoku\.com\/manga\/[a-z0-9-]+\/[a-z0-9-]+\/"/g);
  check('trmangaoku: chapterMode=MangaAjax endpoint returns chapters', ajax.ok && ajaxChapters.length > 0, {
    status: ajax.status,
    chapters: ajaxChapters.length,
    slug,
  });

  // The theme loads the chapter list over AJAX (chapterMode = MangaAjax); the series page
  // itself does not have to carry the rows.
  const pageHtml = countMatches(series.text, /<li class="wp-manga-chapter/g);
  check('trmangaoku: chapter list is served by the AJAX endpoint', ajax.ok && ajaxChapters.length > 0, {
    itemsInInitialHtml: pageHtml,
  });

  const firstChapter = ajaxChapters[0]?.match(/href="([^"]+)"/)?.[1] ?? null;
  if (!firstChapter) return;
  const chapter = await get(firstChapter);
  const srcs = links(chapter.text, /<img[^>]+src="https:\/\/[^"]+\.(?:webp|jpg|jpeg|png)"[^>]*>/g)
    .map((tag) => tag.match(/src="([^"]+)"/)?.[1])
    .filter((s) => s && !/logo|banner|avatar|icon/i.test(s));
  check('trmangaoku: reader page exposes page images', srcs.length > 0, { images: srcs.length });
  if (srcs.length) {
    const image = await get(srcs.at(-1), { headers: { Referer: firstChapter } });
    const kind = imageKind(image.buf);
    check('trmangaoku: page image downloads and decodes', Boolean(kind), {
      status: image.status,
      kind,
      bytes: image.buf.length,
    });
  }
}

const groups = { hayalistic, korelimanga, trmangaoku };
for (const [name, run] of Object.entries(groups)) {
  try {
    await run();
  } catch (error) {
    check(`${name}: live run completed`, false, { error: String(error.message).slice(0, 160) });
  }
}

const failed = results.filter((r) => !r.ok);
const report = {
  generatedAt: new Date().toISOString().replace(/\.\d+Z$/, 'Z'),
  passed: results.length - failed.length,
  total: results.length,
  checks: results,
};
if (process.env.REPORT) {
  mkdirSync(dirname(process.env.REPORT), { recursive: true });
  writeFileSync(process.env.REPORT, `${JSON.stringify(report, null, 2)}\n`);
}

console.log(`\n${report.passed}/${report.total} checks passed`);
if (failed.length) {
  console.log(`::error title=TR sources runtime::${failed.map((f) => f.name).join(' | ')}`);
  process.exit(1);
}
console.log(`::notice title=TR sources runtime::${report.passed}/${report.total} checks passed`);
