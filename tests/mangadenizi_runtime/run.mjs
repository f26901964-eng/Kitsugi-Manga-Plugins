// Runs the real MangaDenizi request/parse/descramble logic against the live site.
//
// The Kotlin classes cannot run here (they need Android + the Tachiyomi core), so this
// harness keeps a 1:1 port of the two pieces that decide whether the source works:
// Dto.kt (payload shapes) and UnscramblerInterceptor.kt (tiled-v1 decoder). If a shape
// or the scrambling scheme changes on the site, this test fails loudly.
import { spawnSync } from 'node:child_process';
import { mkdirSync, readFileSync, writeFileSync } from 'node:fs';
import { dirname } from 'node:path';
import { encodePng, syntheticPage, blit, tileStats, pngToRgba } from './images.mjs';

const BASE = 'https://mangadenizi.net';
const HEADERS = {
  'User-Agent':
    'Mozilla/5.0 (Linux; Android 13; SM-S918B) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Mobile Safari/537.36',
  Accept: 'application/json',
  Referer: `${BASE}/manga`,
  'Accept-Language': 'tr-TR,tr;q=0.9',
};

const results = [];
function check(name, ok, detail = {}) {
  results.push({ name, ok });
  console.log(`${ok ? 'PASS' : 'FAIL'} ${name} ${JSON.stringify(detail).slice(0, 400)}`);
}

async function getJson(path) {
  const response = await fetch(`${BASE}${path}`, { headers: HEADERS, redirect: 'follow' });
  const text = await response.text();
  let json = null;
  try {
    json = JSON.parse(text);
  } catch {
    /* not json */
  }
  return { status: response.status, json, bytes: text.length };
}

// ---------------------------------------------------------------- Unscrambler port
const TA = 2463534242n;
const VO = 2654435769n;
const BO = 2246822507n;
const ft = (v) => BigInt.asUintN(32, v);

class XorShift {
  constructor(seed) {
    const masked = ft(seed);
    this.e = masked === 0n ? TA : masked;
  }
  next() {
    this.e = ft(this.e ^ ft(this.e << 13n));
    this.e = ft(this.e ^ (this.e >> 17n));
    this.e = ft(this.e ^ ft(this.e << 5n));
    return this.e;
  }
}

function shuffle(length, seed) {
  const t = Array.from({ length: Math.max(1, length) }, (_, i) => i);
  const rng = new XorShift(seed);
  for (let n = t.length - 1; n >= 1; n--) {
    const i = Number(rng.next() % BigInt(n + 1));
    const temp = t[n];
    t[n] = t[i];
    t[i] = temp;
  }
  return t;
}

function createSlices(total, pieces) {
  const t = Math.max(1, total);
  const r = Math.max(1, Math.min(pieces, t));
  return Array.from({ length: r }, (_, i) => {
    const offset = Math.floor((i * t) / r);
    const next = Math.floor(((i + 1) * t) / r);
    return { offset, length: Math.max(1, next - offset) };
  });
}

function mapSlices(slices, indices) {
  let t = 0;
  return indices.map((r) => {
    const n = r >= 0 && r < slices.length ? slices[r].length : 1;
    const slice = { offset: t, length: n };
    t += n;
    return slice;
  });
}

function scheme(width, height, grid, seed) {
  const l = Math.max(1, Math.min(grid, Math.min(width, height)));
  const columns = createSlices(width, l);
  const rows = createSlices(height, l);
  const colSeed = (ft(seed) ^ ft(BO)) === 0n ? TA : ft(seed) ^ ft(BO);
  const rowSeed = (ft(seed) ^ ft(VO)) === 0n ? TA : ft(seed) ^ ft(VO);
  const m = shuffle(l, colSeed);
  const f = shuffle(l, rowSeed);
  return { l, columns, rows, m, f, columnOrder: mapSlices(columns, m), rowOrder: mapSlices(rows, f) };
}

// Mirrors UnscramblerInterceptor.descramble (canvas.drawBitmap src -> dst per tile).
export function descramble(image, grid, seed) {
  const { l, columns, rows, m, f, columnOrder, rowOrder } = scheme(image.width, image.height, grid, seed);
  const dst = { width: image.width, height: image.height, data: Buffer.alloc(image.width * image.height * 4) };
  for (let hIdx = 0; hIdx < l; hIdx++) {
    const v = rows[f[hIdx]];
    const y = rowOrder[hIdx];
    for (let bIdx = 0; bIdx < l; bIdx++) {
      const k = columns[m[bIdx]];
      const t = columnOrder[bIdx];
      blit(
        image,
        dst,
        { x: t.offset, y: y.offset, w: t.length, h: y.length },
        { x: k.offset, y: v.offset, w: k.length, h: v.length },
      );
    }
  }
  return dst;
}

// The encoder the site applies: the exact inverse of the loop above.
export function scrambleImage(image, grid, seed) {
  const { l, columns, rows, m, f, columnOrder, rowOrder } = scheme(image.width, image.height, grid, seed);
  const dst = { width: image.width, height: image.height, data: Buffer.alloc(image.width * image.height * 4) };
  for (let hIdx = 0; hIdx < l; hIdx++) {
    const v = rows[f[hIdx]];
    const y = rowOrder[hIdx];
    for (let bIdx = 0; bIdx < l; bIdx++) {
      const k = columns[m[bIdx]];
      const t = columnOrder[bIdx];
      blit(
        image,
        dst,
        { x: k.offset, y: v.offset, w: k.length, h: v.length },
        { x: t.offset, y: y.offset, w: t.length, h: y.length },
      );
    }
  }
  return dst;
}

// ------------------------------------------------------------------- Image plumbing
function webpToPng(buffer) {
  // Pillow only decodes the WebP into raw pixels; every scramble decision stays in JS.
  const tmpIn = '/tmp/mangadenizi-page.webp';
  const tmpOut = '/tmp/mangadenizi-page.png';
  writeFileSync(tmpIn, Buffer.from(buffer));
  const run = spawnSync('python3', [
    '-c',
    'import sys;from PIL import Image;Image.open(sys.argv[1]).convert("RGBA").save(sys.argv[2])',
    tmpIn,
    tmpOut,
  ]);
  if (run.status !== 0) throw new Error('Pillow could not decode the page image');
  return readFileSync(tmpOut);
}

function pixelDifference(a, b) {
  let diff = 0;
  for (let i = 0; i < a.data.length; i += 4) {
    diff += Math.abs(a.data[i] - b.data[i]);
  }
  return diff / (a.data.length / 4);
}

// ------------------------------------------------------------------------ The checks
function selfTest() {
  const grid = 10;
  const width = 400;
  const height = 400;
  const seed = 3618263072n;
  const original = syntheticPage(width, height, grid);
  const scrambled = scrambleImage(original, grid, seed);
  const restored = descramble(scrambled, grid, seed);
  const restoredDiff = pixelDifference(original, restored);
  check('descrambler inverts the tiled-v1 scheme', restoredDiff < 1, { meanChannelDiff: restoredDiff.toFixed(3) });

  const wrong = descramble(scrambled, grid, seed + 1n);
  const wrongDiff = pixelDifference(original, wrong);
  check('a wrong seed stays scrambled', wrongDiff > 5, { meanChannelDiff: wrongDiff.toFixed(3) });

  const decoded = pngToRgba(encodePng(width, height, restored.data));
  check('restored page survives the pipeline', decoded.width === width && decoded.height === height);
}

async function liveFlow() {
  const popular = await getJson('/api/v1/web/manga?sort=popular&page=1');
  const list = popular.json?.data?.manga;
  check('popular listing parses like MangaListDataDto', Boolean(list?.data?.length && list.current_page), {
    status: popular.status,
    items: list?.data?.length ?? 0,
    lastPage: list?.last_page ?? null,
  });
  if (!list?.data?.length) return;

  const first = list.data[0];
  const detail = await getJson(`/api/v1/web/manga/${first.slug}`);
  const manga = detail.json?.data?.manga;
  const chapters = manga?.chapters ?? [];
  check('series detail parses like MangaDetailsData', Boolean(manga?.slug && chapters.length), {
    status: detail.status,
    slug: manga?.slug,
    title: manga?.title,
    chapters: chapters.length,
  });
  if (!chapters.length) return;

  // ChapterDto.toSChapter builds "/read/<mangaSlug>/<chapterSlug>"; getPageList then calls
  // /api/v1/reader/<mangaSlug>/<chapterSlug>.
  const chapter = chapters[0];
  const reader = await getJson(`/api/v1/reader/${manga.slug}/${chapter.slug}`);
  const pages = reader.json?.pages ?? [];
  const scrambled = pages.filter((p) => p.scramble?.method === 'tiled-v1');
  check('reader payload parses like ReaderDto', Boolean(pages.length), {
    status: reader.status,
    pages: pages.length,
    scrambled: scrambled.length,
    grid: scrambled[0]?.scramble?.grid ?? null,
    hasSeed: Boolean(scrambled[0]?.scramble?.seed),
  });
  if (!pages.length) return;

  const numbers = pages.map((p) => p.page_number);
  const ascending = numbers.every((n, i) => i === 0 || numbers[i - 1] < n);
  check('pages arrive in reading order', ascending, { first: numbers[0], last: numbers.at(-1) });

  const image = pages.find((p) => p.image_url);
  const response = await fetch(image.image_url, { headers: { ...HEADERS, Accept: 'image/webp,image/*' } });
  const bytes = Buffer.from(await response.arrayBuffer());
  check('page image downloads without a referer', response.ok && bytes.length > 5000, {
    status: response.status,
    bytes: bytes.length,
    type: response.headers.get('content-type'),
    host: new URL(image.image_url).host,
  });

  const decoded = pngToRgba(webpToPng(bytes));
  const grid = image.scramble?.grid ?? 10;
  const output = descramble(decoded, grid, BigInt(image.scramble?.seed ?? 0));

  // A reader page that renders must be non-blank and keep real (non-white) artwork once
  // the tiles are back in place.
  const lit = [];
  for (let i = 0; i < output.data.length; i += 4) {
    const r = output.data[i];
    const g = output.data[i + 1];
    const b = output.data[i + 2];
    if (r + g + b < 720) lit.push(1);
    else lit.push(0);
  }
  const inkRatio = lit.reduce((a, b) => a + b, 0) / lit.length;
  const before = (() => {
    let ink = 0;
    for (let i = 0; i < decoded.data.length; i += 4) {
      if (decoded.data[i] + decoded.data[i + 1] + decoded.data[i + 2] < 720) ink++;
    }
    return ink / (decoded.data.length / 4);
  })();
  check('descrambled page renders real artwork', inkRatio > 0.02, {
    width: output.width,
    height: output.height,
    inkRatioBefore: Number(before.toFixed(4)),
    inkRatioAfter: Number(inkRatio.toFixed(4)),
    tiles: tileStats(output, grid).length,
  });
}

selfTest();

if (process.env.SKIP_LIVE === '1') {
  console.log('live flow skipped (SKIP_LIVE=1)');
} else {
  try {
    await liveFlow();
  } catch (error) {
    check('live site reachable', false, { error: String(error.message).slice(0, 120) });
  }
}

const failed = results.filter((r) => !r.ok);
const reportPath = process.env.REPORT;
if (reportPath) {
  mkdirSync(dirname(reportPath), { recursive: true });
  writeFileSync(
    reportPath,
    JSON.stringify(
      {
        generatedAt: new Date().toISOString().replace(/\.\d+Z$/, 'Z'),
        baseUrl: BASE,
        passed: results.length - failed.length,
        total: results.length,
        checks: results,
      },
      null,
      2,
    ) + '\n',
  );
}
console.log(`\n${results.length - failed.length}/${results.length} checks passed`);
if (failed.length) {
  console.log('::error title=MangaDenizi runtime::' + failed.map((f) => f.name).join(' | '));
  process.exit(1);
}
console.log(`::notice title=MangaDenizi runtime::${results.length}/${results.length} checks passed`);
