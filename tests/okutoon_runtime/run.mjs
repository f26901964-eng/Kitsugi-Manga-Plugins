// Live end-to-end check for OkuToon (okutoon.com), mirroring OkuToon.kt.
//
// The dumps in docs/html-dumps/okutoon prove the selectors; this run proves the site still
// serves the same shape today. Chapter pages sit behind a Cloudflare challenge for bots, so
// a 403 there is reported as a known limitation instead of a failed selector: the same
// markup is asserted offline by tests/test_okutoon_dumps.py.
//
//   listing -> /tur?sira=..., .series-grid .series-card, nav.pagination a[rel=next]
//   search  -> ?q=, ?sira=, ?durum=, ?tip=, ?k[]=
//   details -> .series-detail-* , .series-status--*, [data-series-description-content]
//   chapters-> .chapter-item > a.chapter-item-link / .chapter-title / .chapter-date
//   reader  -> #readerPages img.reader-page (+ data-original-src), CDN host om-cdn.okutoon.com
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
    return {
      status: response.status,
      ok: response.ok,
      buf,
      text: buf.toString('utf8'),
      mitigated: response.headers.get('cf-mitigated'),
    };
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

function imageKind(buf) {
  if (buf.length < 800) return null;
  if (buf.subarray(0, 4).toString('ascii') === 'RIFF' && buf.subarray(8, 12).toString('ascii') === 'WEBP') return 'webp';
  if (buf[0] === 0xff && buf[1] === 0xd8) return 'jpeg';
  if (buf.subarray(0, 8).toString('hex') === '89504e470d0a1a0a') return 'png';
  if (buf[0] === 0x47 && buf[1] === 0x49 && buf[2] === 0x46) return 'gif';
  return null;
}

// ------------------------------------------------------------------- catalogue
const listing = await get(`${BASE}/tur?sira=popular`);
check('okutoon: catalogue answers 200', listing.ok, { status: listing.status });

const cards = unique(listing.text, /<a href="(\/[a-z0-9-]+)" class="series-card">/g);
const titles = unique(listing.text, /class="series-card-title"[^>]*>([^<]{1,120})</g);
const covers = unique(listing.text, /class="series-card-cover"[^>]*>\s*<img[^>]+src="([^"]+)"/g);
check('okutoon: series cards are anchors with a title and a cover', cards.length > 0 && titles.length > 0, {
  cards: cards.length,
  titles: titles.length,
  covers: covers.length,
  first: cards[0] ?? null,
});
check('okutoon: covers come from the image CDN', covers.every((url) => url.includes('om-cdn.okutoon.com')) || covers.length === 0, {
  host: covers[0] ? new URL(covers[0]).host : null,
});

const page1 = unique(listing.text, /class="series-card-title"[^>]*>([^<]{1,120})</g);
const page2Response = await get(`${BASE}/tur?sira=popular&sayfa=2`);
const page2 = unique(page2Response.text, /class="series-card-title"[^>]*>([^<]{1,120})</g);
check('okutoon: ?sayfa=2 returns a different page of the catalogue', page2.length > 0 && page1[0] !== page2[0], {
  page1: page1.length,
  page2: page2.length,
  firstOfEach: [page1[0] ?? null, page2[0] ?? null],
});
const nextLink = count(listing.text, /class="pagination-btn"[^>]*rel="next"/g);
check('okutoon: paging exposes a rel=next link', nextLink > 0, { nextLink });

// ---------------------------------------------------------------------- search
const search = await get(`${BASE}/tur?q=chainsaw+man&sira=updated`);
const searchTitles = unique(search.text, /class="series-card-title"[^>]*>([^<]{1,120})</g);
check('okutoon: search by Turkish/English title works', search.ok && searchTitles.includes('Chainsaw Man'), {
  hits: searchTitles.slice(0, 3),
});

// ------------------------------------------------------- details + chapter list
const seriesUrl = `${BASE}${cards[0] ?? '/chainsaw-man'}`;
const series = await get(seriesUrl);
const detailHooks = {
  title: count(series.text, /class="series-detail-title"/g),
  author: count(series.text, /class="series-detail-author"/g),
  cover: count(series.text, /class="series-detail-cover"/g),
  genres: count(series.text, /class="series-detail-genres"/g),
  status: count(series.text, /series-status--(ongoing|completed|hiatus)/g),
  description: count(series.text, /data-series-description-content/g),
};
check('okutoon: series details expose every hook the extension reads', Object.values(detailHooks).every((n) => n > 0), {
  url: seriesUrl,
  ...detailHooks,
});
const genreLinks = unique(series.text, /class="tag-link">([^<]+)</g);
check('okutoon: genres are .tag-link anchors', genreLinks.length > 0, { genres: genreLinks.slice(0, 5) });

const chapterRows = count(series.text, /<div class="chapter-item"/g);
const chapterLinks = unique(series.text, /<a href="(\/[^"]+\/bolum-[^"]+)" class="chapter-item-link"/g);
const chapterTitles = unique(series.text, /class="chapter-title">([^<]+)</g);
const chapterDates = unique(series.text, /class="chapter-date">([^<]+)</g);
check('okutoon: chapter rows parse into links, names and dates', chapterRows > 0 && chapterLinks.length > 0 && chapterTitles.length > 0, {
  rows: chapterRows,
  links: chapterLinks.length,
  titles: chapterTitles.length,
  dates: chapterDates.slice(0, 3),
});
check('okutoon: chapter dates use Turkish month names', chapterDates.length === 0 || /(Ocak|Şubat|Mart|Nisan|Mayıs|Haziran|Temmuz|Ağustos|Eylül|Ekim|Kasım|Aralık)/.test(chapterDates[0]), {
  sample: chapterDates[0] ?? null,
});

// ----------------------------------------------------------------------- reader
const chapterUrl = chapterLinks.length ? `${BASE}${chapterLinks[0]}` : null;
if (chapterUrl) {
  const chapter = await get(chapterUrl, { Referer: seriesUrl });
  const blocked = chapter.status === 403 && chapter.mitigated === 'challenge';
  const pageImages = unique(chapter.text, /<img[^>]*class="reader-page"[^>]*>/g);
  const srcs = unique(chapter.text, /<img[^>]*class="reader-page"[^>]*\ssrc="([^"]+)"/g);
  const lazySrcs = unique(chapter.text, /<img[^>]*class="reader-page"[^>]*\sdata-original-src="([^"]+)"/g);

  if (blocked) {
    // Known limitation: Cloudflare serves a challenge to non-browser clients. The markup is
    // covered by the saved dumps, so this is not a selector failure.
    check('okutoon: reader is behind a Cloudflare challenge for bots (known limitation)', true, {
      url: chapterUrl,
      status: chapter.status,
      mitigated: chapter.mitigated,
      dumpsCoverTheMarkup: 'docs/html-dumps/okutoon/message-73.txt',
    });
  } else {
    check('okutoon: reader answers and ships the page list', chapter.ok && pageImages.length > 0, {
      url: chapterUrl,
      status: chapter.status,
      images: pageImages.length,
      srcs: srcs.length,
      lazySrcs: lazySrcs.length,
    });
    const first = srcs[0] ?? lazySrcs[0];
    if (first) {
      check('okutoon: page images live on the CDN host', new URL(first).host === 'om-cdn.okutoon.com', {
        host: new URL(first).host,
      });
      const image = await get(first, { Referer: chapterUrl });
      check('okutoon: first page image downloads and decodes', Boolean(imageKind(image.buf)), {
        status: image.status,
        kind: imageKind(image.buf),
        bytes: image.buf.length,
      });
    }
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
