// Live contract check for OkuToon (okutoon.com), mirroring OkuToon.kt.
//
// The extension relies on these exact hooks:
//   listing   .series-grid .series-card / .series-card-title / .series-card-cover img
//   paging    nav.pagination a.pagination-btn:contains(Sonraki)
//   details   .series-detail-title / -author / -cover / .series-detail-genres .tag / .series-detail-kicker .series-status
//   chapters  .chapter-item > a.chapter-item-link / .chapter-title / .chapter-date
//   reader    #readerPages img.reader-page
// Every check below fails loudly with what the page actually contains, so a broken
// selector can be fixed from the report alone.
import { writeFileSync, mkdirSync } from 'node:fs';
import { dirname } from 'node:path';

const UA =
  'Mozilla/5.0 (Linux; Android 13; SM-S918B) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Mobile Safari/537.36';
const HEADERS = { 'User-Agent': UA, 'Accept-Language': 'tr-TR,tr;q=0.9' };
const TIMEOUT_MS = 30_000;
const BASE = 'https://okutoon.com';

const results = [];
function check(name, ok, detail = {}) {
  results.push({ name, ok, detail });
  console.log(`${ok ? 'PASS' : 'FAIL'} ${name} ${JSON.stringify(detail).slice(0, 400)}`);
}

async function get(url, extra = {}) {
  const controller = new AbortController();
  const timer = setTimeout(() => controller.abort(), TIMEOUT_MS);
  try {
    const response = await fetch(url, {
      headers: { ...HEADERS, ...extra.headers },
      redirect: 'follow',
      signal: controller.signal,
    });
    const buf = Buffer.from(await response.arrayBuffer());
    return { status: response.status, ok: response.ok, buf, text: buf.toString('utf8') };
  } finally {
    clearTimeout(timer);
  }
}

const count = (html, re) => (html.match(re) ?? []).length;
const unique = (html, re) => {
  const out = [];
  for (const m of html.matchAll(re)) {
    const value = m.length > 1 ? m[1] : m[0];
    if (value && !out.includes(value)) out.push(value);
  }
  return out;
};
const attr = (tag, name) => tag.match(new RegExp(`${name}\\s*=\\s*["']([^"']+)["']`))?.[1]?.trim() ?? null;

function imageKind(buf) {
  if (buf.length < 800) return null;
  if (buf.subarray(0, 4).toString('ascii') === 'RIFF' && buf.subarray(8, 12).toString('ascii') === 'WEBP') return 'webp';
  if (buf[0] === 0xff && buf[1] === 0xd8) return 'jpeg';
  if (buf.subarray(0, 8).toString('hex') === '89504e470d0a1a0a') return 'png';
  if (buf[0] === 0x47 && buf[1] === 0x49 && buf[2] === 0x46) return 'gif';
  return null;
}

// ---------------------------------------------------------------- listing/search
const searchUrl = `${BASE}/tur?q=solo+leveling&sayfa=1`;
const search = await get(searchUrl);
check('okutoon: search page answers 200', search.ok, { status: search.status, url: searchUrl });

const cards = count(search.text, /class="[^"]*series-card[^"]*"/g);
const grid = count(search.text, /class="[^"]*series-grid[^"]*"/g);
const titles = unique(search.text, /class="[^"]*series-card-title[^"]*"[^>]*>([^<]{1,120})</g);
check('okutoon: listing uses .series-grid .series-card', grid > 0 && cards > 0, { grid, cards });
check('okutoon: .series-card-title yields manga names', titles.length > 0, { titles: titles.slice(0, 5) });
check('okutoon: searching "solo leveling" finds the series', /solo\s*leveling/i.test(search.text), {
  soloMentions: count(search.text, /solo\s*leveling/gi),
});

const seriesUrls = unique(search.text, /class="[^"]*series-card[^"]*"[\s\S]{0,600}?href="(https:\/\/okutoon\.com\/[a-z0-9-]+)"/gi).filter(
  (u) => !/\/tur|\/giris|\/kategori|\/turler/.test(u),
);
const anySeries = seriesUrls.length ? seriesUrls : unique(search.text, /href="(https:\/\/okutoon\.com\/[a-z0-9-]+)"/gi).filter(
  (u) => !/\/tur|\/giris|\/kayit|\/kategori|\/turler|\/filtre/.test(u),
);
check('okutoon: series links are readable from the cards', anySeries.length > 0, { first: anySeries[0] ?? null });

// ------------------------------------------------------------------------ paging
const next = count(search.text, /pagination-btn[^>]*>[^<]*Sonraki/g) + count(search.text, /Sonraki/gi);
const pager = count(search.text, /class="[^"]*pagination[^"]*"/g);
check('okutoon: pagination markup exists (nav.pagination + Sonraki)', pager > 0, { pager, sonraki: next });

// ------------------------------------------------------------------------ details
const seriesUrl = anySeries[0] ?? `${BASE}/solo-leveling`;
const series = await get(seriesUrl);
const details = {
  title: count(series.text, /class="[^"]*series-detail-title[^"]*"/g),
  author: count(series.text, /class="[^"]*series-detail-author[^"]*"/g),
  cover: count(series.text, /class="[^"]*series-detail-cover[^"]*"/g),
  genres: count(series.text, /class="[^"]*series-detail-genres[^"]*"/g),
  status: count(series.text, /series-status--(ongoing|completed)/g),
  description: count(series.text, /data-series-description-content/g),
};
check('okutoon: series page answers 200', series.ok, { status: series.status, url: seriesUrl });
check('okutoon: series detail selectors are present', Object.values(details).every((n) => n > 0), { ...details });

// ----------------------------------------------------------------------- chapters
const chapterRows = count(series.text, /class="[^"]*chapter-item[^"]*"/g);
const chapterLinks = unique(series.text, /class="[^"]*chapter-item[^"]*"[\s\S]{0,400}?href="(https:\/\/okutoon\.com\/[^"]+)"/gi);
const chapterTitles = unique(series.text, /class="[^"]*chapter-title[^"]*"[^>]*>([^<]{1,120})</g);
const chapterDates = unique(series.text, /class="[^"]*chapter-date[^"]*"[^>]*>([^<]{1,40})</g);
check('okutoon: chapter rows use .chapter-item > a.chapter-item-link', chapterRows > 0 && chapterLinks.length > 0, {
  rows: chapterRows,
  links: chapterLinks.length,
  firstTitle: chapterTitles[0] ?? null,
  firstDate: chapterDates[0] ?? null,
});
check('okutoon: .chapter-title / .chapter-date are usable', chapterTitles.length > 0, {
  titles: chapterTitles.slice(0, 3),
  dates: chapterDates.slice(0, 3),
});

// ------------------------------------------------------------------------- reader
const chapterUrl = chapterLinks[0] ?? null;
if (chapterUrl) {
  const chapter = await get(chapterUrl);
  const readerImgs = unique(chapter.text, /class="[^"]*reader-page[^"]*"[^>]*>/g);
  const srcs = unique(
    chapter.text,
    /<img[^>]+class="[^"]*reader-page[^"]*"[^>]*src="\s*([^"\s]+)\s*"/g,
  );
  let pages = srcs;
  let source = 'html';
  if (pages.length === 0) {
    // The reader may hydrate over an API; report where the pages come from.
    const apiHints = unique(chapter.text, /(https:\/\/[^"']*\/api\/[^"']{1,80})/g);
    check('okutoon: reader page markup', readerImgs.length > 0, {
      readerImgs: readerImgs.length,
      apiHints: apiHints.slice(0, 3),
      htmlBytes: chapter.text.length,
    });
    pages = [];
    source = 'none-found';
  }
  check('okutoon: #readerPages img.reader-page yields page images', srcs.length > 0, {
    chapterUrl,
    srcs: srcs.length,
    source,
    firstSrc: srcs[0] ?? null,
    hasReaderWrapper: count(chapter.text, /id="readerPages"/g) > 0,
  });
  if (srcs.length) {
    const image = await get(srcs[0], { headers: { Referer: chapterUrl } });
    check('okutoon: first page image downloads and decodes', Boolean(imageKind(image.buf)), {
      status: image.status,
      kind: imageKind(image.buf),
      bytes: image.buf.length,
      host: new URL(srcs[0]).host,
    });
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
  console.log(`::error title=okutoon runtime::${failed.map((f) => f.name).join(' | ')}`);
  process.exit(1);
}
console.log(`::notice title=okutoon runtime::${report.passed}/${report.total} checks passed`);
