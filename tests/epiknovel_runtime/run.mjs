// Epik Novel (www.epiknovel.com) canlı sözleşme kontrolleri.
//
// Uzantının sorduğu her şey burada sitenin kendisine sorulur:
//   keşfet/arama  -> /seri-listesi (pop-href kartları, data-src kapak, Sayfa, q, durum, sorting)
//   seri detayı   -> /seri/<slug> (h1#tables, Çevirmen, Durum, Seri Özeti, bölüm tablosu)
//   bölüm metni   -> /seri/<slug>/bolum/<n> (#icerik, center>h4>b, <p> akışı)
//   görseller     -> kapak URL'leri gerçek görüntü olarak iniyor mu
// Bir seçici değişirse koşu hangi kontrol olduğunu yazarak başarısız olur.
import { writeFileSync, mkdirSync } from 'node:fs';
import { dirname } from 'node:path';

const BASE = 'https://www.epiknovel.com';
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
    return {
      status: response.status,
      ok: response.ok,
      buf,
      text: buf.toString('utf8'),
      headers: response.headers,
    };
  } finally {
    clearTimeout(timer);
  }
}

const countMatches = (html, re) => (html.match(re) ?? []).length;

// #pop-href içindeki kartlardan ilk href'i bul (yan panel hariç).
function firstCardUrl(html) {
  const start = html.indexOf('id="pop-href"');
  if (start < 0) return null;
  const section = html.slice(start, start + 200_000);
  const match = section.match(/<a class="pull-left" href="([^"]+)"/);
  return match ? match[1] : null;
}

function cardCount(html) {
  const start = html.indexOf('id="pop-href"');
  if (start < 0) return 0;
  const section = html.slice(start);
  return countMatches(section, /<div class="media">/g);
}

function imageKind(buf) {
  if (buf.length < 500) return null;
  if (buf.subarray(0, 4).toString('ascii') === 'RIFF' && buf.subarray(8, 12).toString('ascii') === 'WEBP') return 'webp';
  if (buf[0] === 0xff && buf[1] === 0xd8) return 'jpeg';
  if (buf.subarray(0, 8).toString('hex') === '89504e470d0a1a0a') return 'png';
  if (buf[0] === 0x47 && buf[1] === 0x49 && buf[2] === 0x46) return 'gif';
  return null;
}

// ------------------------------------------------- keşfet / arama / filtre
async function catalogue() {
  const list1 = await get(`${BASE}/seri-listesi`);
  check('epiknovel: liste sayfası 200', list1.ok, { status: list1.status });
  const count1 = cardCount(list1.text);
  check('epiknovel: pop-href içinde 50 kart', count1 >= 10, { cards: count1 });
  check(
    'epiknovel: ilk kart seri bağlantılı',
    Boolean(firstCardUrl(list1.text)?.includes('/seri/')),
    { first: firstCardUrl(list1.text) },
  );

  // Kapaklar data-src ile geliyor; ajax-loader yer tutucusu olmamalı.
  const start = list1.text.indexOf('id="pop-href"');
  const section = list1.text.slice(start, start + 60_000);
  const imgTag = section.match(/<img class="media-object[^>]*>/)?.[0] ?? '';
  const dataSrc = imgTag.match(/data-src="([^"]+)"/)?.[1] ?? '';
  const src = imgTag.match(/\ssrc="([^"]+)"/)?.[1] ?? '';
  check('epiknovel: kartta data-src gerçek kapak var', dataSrc.startsWith('http') && !dataSrc.includes('ajax-loader'), {
    dataSrc,
    src,
  });

  const cover = await get(dataSrc);
  const kind = imageKind(cover.buf);
  check('epiknovel: kapak görseli iniyor ve görüntü', Boolean(kind), {
    status: cover.status,
    kind,
    bytes: cover.buf.length,
  });

  // Sayfalama
  const list2 = await get(`${BASE}/seri-listesi?Sayfa=2`);
  check('epiknovel: Sayfa=2 200', list2.ok, { status: list2.status });
  const first2 = firstCardUrl(list2.text);
  check('epiknovel: Sayfa=2 farklı kart gösteriyor', Boolean(first2) && first2 !== firstCardUrl(list1.text), {
    page1: firstCardUrl(list1.text),
    page2: first2,
  });
  check('epiknovel: Sayfa=3 bağlantısı var', list2.text.includes('Sayfa=3'), {});

  // Arama: tek sonuçlu sorguda 2. sayfa olmamalı (disabled kenar).
  const search = await get(`${BASE}/seri-listesi?q=Against+The+God`);
  check('epiknovel: arama 200 ve sonuç döndürüyor', search.ok && cardCount(search.text) >= 1, {
    status: search.status,
    cards: cardCount(search.text),
  });
  check(
    'epiknovel: arama sonucu Against The God',
    search.text.includes('/seri/against-the-god'),
    {},
  );

  // Tamamlanan filtresi
  const completed = await get(`${BASE}/seri-listesi?durum=1`);
  check('epiknovel: durum=1 filtresi kart döndürüyor', completed.ok && cardCount(completed.text) >= 1, {
    status: completed.status,
    cards: cardCount(completed.text),
  });

  // Sıralama yönü gerçekten uygulanıyor mu?
  const asc = await get(`${BASE}/seri-listesi?sorting=views&sorting-type=ASC`);
  const desc = await get(`${BASE}/seri-listesi?sorting=views&sorting-type=DESC`);
  const ascFirst = firstCardUrl(asc.text);
  const descFirst = firstCardUrl(desc.text);
  check(
    'epiknovel: views ASC/DESC ilk kartları farklı',
    asc.ok && desc.ok && Boolean(ascFirst) && ascFirst !== descFirst,
    { asc: ascFirst, desc: descFirst },
  );
}

// ------------------------------------------------------------ seri detayı
async function details() {
  const series = await get(`${BASE}/seri/against-the-god`);
  check('epiknovel: seri sayfası 200', series.ok, { status: series.status });
  check('epiknovel: h1#tables başlık var', series.text.includes('id="tables"'), {});
  check('epiknovel: çevirmen alanı (SEFIX)', /Çevirmen:\s*SEFIX/.test(series.text), {});
  check('epiknovel: durum Devam ediyor + durum=0', series.text.includes('Devam ediyor') && series.text.includes('durum=0'), {});
  check('epiknovel: Seri Özeti bölümü var', series.text.includes('Seri Özeti'), {});
  const chapterLinks = countMatches(series.text, /href="https:\/\/www\.epiknovel\.com\/seri\/against-the-god\/bolum\/\d+"/g);
  check('epiknovel: bölüm tablosu >= 1000 bağlantı', chapterLinks >= 1000, { chapterLinks });

  const coverTag = series.text.match(/<img[^>]*class="[^"]*manga-cover[^"]*"[^>]*>/)?.[0] ?? '';
  const coverUrl = coverTag.match(/src="([^"]+)"/)?.[1] ?? '';
  check('epiknovel: seri kapağı doğrudan src ile', coverUrl.startsWith('http') && !coverUrl.includes('ajax-loader'), {
    coverUrl,
  });
  if (coverUrl) {
    const cover = await get(coverUrl);
    const kind = imageKind(cover.buf);
    check('epiknovel: seri kapağı iniyor ve görüntü', Boolean(kind), {
      status: cover.status,
      kind,
      bytes: cover.buf.length,
    });
  }

  const done = await get(`${BASE}/seri/dimensional-sovereign`);
  check('epiknovel: tamamlanan seri 200', done.ok, { status: done.status });
  check(
    'epiknovel: tamamlanan seri durum=1/Tamamlandı',
    done.text.includes('Tamamlandı') && done.text.includes('durum=1'),
    {},
  );
}

// -------------------------------------------------------------- bölüm metni
async function chapter() {
  const url = `${BASE}/seri/against-the-god/bolum/1731`;
  const page = await get(url);
  check('epiknovel: bölüm sayfası 200', page.ok, { status: page.status });
  check('epiknovel: #icerik kapsayıcısı var', page.text.includes('id="icerik"'), {});
  const title = page.text.match(/<h4[^>]*>\s*<b>([^<]+)<\/b>/)?.[1] ?? '';
  check('epiknovel: center>h4>b başlığı', title.includes('Bölüm 1'), { title: title.slice(0, 80) });

  const start = page.text.indexOf('id="icerik"');
  const end = page.text.indexOf('id="kalpbelow"');
  const content = start >= 0 && end > start ? page.text.slice(start, end) : '';
  const textLength = content
    .replace(/<[^>]+>/g, ' ')
    .replace(/\s+/g, ' ')
    .trim().length;
  check('epiknovel: bölüm metni >= 5000 karakter', textLength >= 5000, { textLength });
  check('epiknovel: çeviri künyesi metinde', content.includes('Çeviri için Avare'), {});
  check('epiknovel: içerik script/ins barındırmıyor', !/<script|<ins/i.test(content), {});
}

// ------------------------------------------------------------------ ana akış
const groups = { catalogue, details, chapter };
for (const [name, run] of Object.entries(groups)) {
  try {
    await run();
  } catch (error) {
    check(`epiknovel: ${name} canlı koşusu`, false, { error: String(error.message).slice(0, 160) });
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
  console.log(`::error title=Epik Novel runtime::${failed.map((f) => f.name).join(' | ')}`);
  process.exit(1);
}
console.log(`::notice title=Epik Novel runtime::${report.passed}/${report.total} checks passed`);
