// SadScans gömülü JavaScript köprülerinin sözleşme testleri.
//
// Bu testler Kotlin içindeki metinlerin AYNISINI bir DOM ortamında çalıştırır; canlı siteye veya
// Android WebView'e istek yapmaz. Amaç: arama/sıralama/ayrıştırma davranışının sessizce
// bozulmadığını yakalamak.
import fs from 'node:fs';
import vm from 'node:vm';
import assert from 'node:assert/strict';
import {createRequire} from 'node:module';
const require = createRequire(import.meta.url);
const {parseHTML} = require(process.env.SADSCANS_LINKEDOM || 'linkedom');

const source = fs.readFileSync('keiyoushi-extensions-tr/src/tr/sadscans/src/eu/kanade/tachiyomi/extension/tr/sadscans/SadscansFrontend.kt', 'utf8');
const templates = [...source.matchAll(/return """([\s\S]*?)"""\.trimIndent\(\)/g)].map(x => x[1]);
assert.equal(templates.length, 3, 'list/detail/reader script şablonları bulunmalı');

let clock = 0;
const tick = () => new Promise(resolve => setImmediate(resolve));

function environment(html, {path = '/seriler', fetchImpl = null} = {}) {
  const {window} = parseHTML(html);
  window.__sadscansList = undefined;
  window.__sadscansDetails = undefined;
  window.__sadscansReader = undefined;
  window.scrollY = 0;
  window.innerHeight = 900;
  window.scrollTo = () => {};
  let scrolls = 0;
  window.scrollTo = () => { scrolls++; window.scrollY += 900; };
  const context = vm.createContext({
    window,
    document: window.document,
    location: {pathname: path, href: 'https://sadscans.net' + path, host: 'sadscans.net', origin: 'https://sadscans.net'},
    HTMLInputElement: window.HTMLInputElement,
    Event: window.Event,
    Date: {now: () => clock},
    URL,
    Map,
    Set,
    JSON,
    fetch: fetchImpl || undefined,
  });
  return {
    window,
    context,
    scrolls: () => scrolls,
    run: script => vm.runInContext(script, context),
  };
}

async function drive(env, script, maxSteps = 60, until = value => !!value) {
  for (let step = 0; step < maxSteps; step++) {
    clock += 2000;
    const value = env.run(script);
    if (until(value)) return value;
    await tick();
  }
  return null;
}

function trpc(rows) {
  return JSON.stringify([{result: {data: {json: rows}}}]);
}

function jsonResponse(body) {
  return Promise.resolve({ok: true, text: () => Promise.resolve(body)});
}

const listScript = (options) => templates[0].replace('$options', JSON.stringify(options));

// ---------------------------------------------------------------------------
// 1. Aynı kaynaklı API yolu
// ---------------------------------------------------------------------------
{
  let requested = null;
  const options = {query: 'Chainsaw Man', sort: 'views', type: null, status: null, page: 1};
  const env = environment('<html><body><input placeholder="Aramak istediğiniz seriyi yazın"><main></main></body></html>', {
    fetchImpl: url => {
      requested = url;
      return jsonResponse(trpc([
        {id: '1', href: '/seriler/chainsaw-man', name: 'Chainsaw Man', thumb: '/uploads/a.jpg'},
        {id: '2', href: '/seriler/jujutsu-kaisen', name: 'Jujutsu Kaisen', thumb: null},
      ]));
    },
  });
  const script = listScript(options);
  const raw = await drive(env, script);
  const result = JSON.parse(raw);
  assert.equal(result.source, 'api');
  assert.equal(result.items.length, 2);
  assert.equal(result.items[0].href, '/seriler/chainsaw-man');
  assert.equal(result.hasMore, false);
  assert.ok(requested.includes('/api/trpc/_s4d7g2k9m0.getSeries'), 'doğru tRPC prosedürü çağrılmalı');
  assert.ok(requested.includes('batch=1'), 'batch zarfı korunmalı');
  assert.ok(requested.includes('sortBy%22%3A%22views'), 'sıralama değeri sitedeki gibi iletilmeli');
  assert.ok(decodeURIComponent(requested).includes('"search":"chainsaw man"'), 'arama Türkçe normalizasyondan geçmeli');
  console.log('PASS: liste API yolu — prosedür, batch zarfı, sortBy ve normalize edilmiş arama');
}

// ---------------------------------------------------------------------------
// 2. API engellendiğinde sitenin kendi arayüzü sürülür
// ---------------------------------------------------------------------------
{
  const env = environment(`<html><body><main>
    <div class="dropdown-container"><button>Okunma: Hepsi</button></div>
    <input placeholder="Aramak istediğiniz seriyi yazın">
    <a href="https://sadscans.net/seriler/chainsaw-man"><h3>Chainsaw Man</h3><img src="https://sadscans.net/cover.jpg"></a>
    <a href="https://sadscans.net/seriler/not-a-card">navigation</a>
    <div aria-hidden="true" class="h-px w-full"></div>
  </main></body></html>`, {
    fetchImpl: () => Promise.reject(new Error('blocked')),
  });
  const input = env.window.document.querySelector('input');
  input.__reactPropsFixture = {onChange: () => {}};
  let typed = 0;
  input.addEventListener('input', () => typed++);

  const container = env.window.document.querySelector('.dropdown-container');
  const toggle = container.querySelector('button');
  toggle.addEventListener('click', () => {
    if (container.querySelectorAll('button').length > 1) return;
    const item = env.window.document.createElement('button');
    item.textContent = 'En Az';
    item.addEventListener('click', () => {
      toggle.textContent = 'Okunma: En Az';
      item.remove();
    });
    container.appendChild(item);
  });

  const script = listScript({query: 'Chainsaw "Man"', sort: '-views', type: null, status: null, page: 1});
  const raw = await drive(env, script, 80);
  const result = JSON.parse(raw);
  assert.equal(result.source, 'dom');
  assert.equal(result.items.length, 1);
  assert.equal(result.items[0].href, '/seriler/chainsaw-man');
  assert.equal(input.value, 'Chainsaw "Man"');
  assert.equal(typed, 1);
  assert.equal(toggle.textContent.trim(), 'Okunma: En Az', 'sıralama menüsü gerçekten uygulanmalı');
  console.log('PASS: liste DOM yedeği — menü tıklaması, arama kutusuna yazma, kart çıkarımı');
}

// ---------------------------------------------------------------------------
// 3. Menü değişse bile arama ölmez (ölümcül olmayan sıralama)
// ---------------------------------------------------------------------------
{
  const env = environment(`<html><body><input placeholder="Aramak istediğiniz seriyi yazın">
    <a href="https://sadscans.net/seriler/one-piece"><h3>One Piece</h3></a>
    </body></html>`, {fetchImpl: () => Promise.reject(new Error('blocked'))});
  env.window.document.querySelector('input').__reactPropsFixture = {onChange: () => {}};
  const script = listScript({query: 'one', sort: 'rating', type: 'Manga', status: 'devam-ediyor', page: 1});
  const raw = await drive(env, script, 80);
  const result = JSON.parse(raw);
  assert.equal(result.items.length, 1, 'menüler bulunamasa da arama sonucu dönmeli');
  console.log('PASS: menü değişikliği aramayı kilitlemiyor');
}

// ---------------------------------------------------------------------------
// 4. Boş sonuç, yükleme ve Cloudflare durumları
// ---------------------------------------------------------------------------
{
  const blocked = environment('<html><body>Sorry, you have been blocked</body></html>');
  const raw = await drive(blocked, listScript({query: 'a', sort: 'name', type: null, status: null, page: 1}), 3);
  assert.match(JSON.parse(raw).error, /engelledi/);

  const empty = environment(`<html><body><input placeholder="Aramak istediğiniz seriyi yazın">
    Seçilen filtreler için sonuç bulunamadı</body></html>`, {fetchImpl: () => Promise.reject(new Error('blocked'))});
  empty.window.document.querySelector('input').__reactPropsFixture = {onChange: () => {}};
  const emptyRaw = await drive(empty, listScript({query: 'zzzz', sort: 'name', type: null, status: null, page: 1}), 80);
  const emptyResult = JSON.parse(emptyRaw);
  assert.equal(emptyResult.items.length, 0);
  assert.equal(emptyResult.hasMore, false);

  const loading = environment('<html><body><input placeholder="Aramak istediğiniz seriyi yazın"><div class="animate-pulse"></div></body></html>', {
    fetchImpl: () => Promise.reject(new Error('blocked')),
  });
  loading.window.document.querySelector('input').__reactPropsFixture = {onChange: () => {}};
  const loadingRaw = await drive(loading, listScript({query: 'x', sort: 'name', type: null, status: null, page: 1}), 80);
  assert.match(JSON.parse(loadingRaw).error, /yükleyemedi/, 'yükleme durumu boş sonuç sayılmamalı');
  console.log('PASS: Cloudflare bloğu, gerçek boş sonuç ve yükleme durumu ayrı ele alınıyor');
}

// ---------------------------------------------------------------------------
// 5. Detay: API tüm bölüm sayfalarını birleştirir
// ---------------------------------------------------------------------------
{
  let calls = 0;
  const env = environment('<html><body></body></html>', {
    path: '/seriler/chainsaw-man',
    fetchImpl: url => {
      calls++;
      const page = decodeURIComponent(url).includes('"page":1') ? 1 : 2;
      const rows = page === 1
        ? [{chap_id: 'aaa', name: '1. Bölüm - Köpek ve Testere', no: 1, createdAt: '2026-11-01T10:00:00.000Z'}]
        : [{chap_id: 'bbb', name: '2. Bölüm - İkinci', no: 2, createdAt: '2026-11-02T10:00:00.000Z'}];
      return jsonResponse(trpc({series: {name: 'Chainsaw Man', slug: 'chainsaw-man'}, chapters: rows, pagination: {totalPages: 2}}));
    },
  });
  const raw = await drive(env, templates[1].replace('$page', '1'), 40);
  const result = JSON.parse(raw);
  assert.equal(result.merged, true);
  assert.equal(result.chapters.length, 2, 'tüm bölüm sayfaları birleştirilmeli');
  assert.equal(result.chapters[0].chap_id, 'aaa');
  assert.equal(result.chapters[1].chap_id, 'bbb');
  assert.ok(calls >= 2);
  assert.equal(result.series.name, 'Chainsaw Man');
  console.log('PASS: detay API yolu — sayfalar birleştiriliyor, chap_id ve seri bilgisi geliyor');
}

// ---------------------------------------------------------------------------
// 6. Detay: DOM yedeği ve sayfa numarası koruması
// ---------------------------------------------------------------------------
{
  const env = environment(`<html><head><meta property="og:image" content="https://sadscans.net/cover.png"></head><body>
    <h1>Chainsaw Man</h1>
    <a href="https://sadscans.net/reader/chainsaw-man/1-bolum/aaa"><h3>1. Bölüm - Köpek ve Testere</h3></a>
    <button aria-label="Sayfa 1"></button><button aria-label="Sayfa 2"></button>
  </body></html>`, {path: '/seriler/chainsaw-man'});
  const first = JSON.parse(await drive(env, templates[1].replace('$page', '1'), 40));
  assert.equal(first.series.name, 'Chainsaw Man');
  assert.equal(first.chapters.length, 1);
  assert.equal(first.chapters[0].chap_id, 'aaa');
  assert.equal(first.chapters[0].no, '1');
  assert.equal(first.chapters[0].href, '/reader/chainsaw-man/1-bolum/aaa');
  assert.equal(first.pagination.totalPages, 2);
  assert.equal(first.merged, false);

  const stale = environment(`<html><body><h1>Test</h1>
    <a href="https://sadscans.net/reader/s/c/aaa"><h3>1. Bölüm</h3></a>
    <button aria-label="Sayfa 2"></button>
  </body></html>`, {path: '/seriler/test'});
  const page2 = templates[1].replace('$page', '2');
  let value = null;
  for (let step = 0; step < 8; step++) {
    clock += 2000;
    value = stale.run(page2);
    await tick();
  }
  assert.equal(value, null, 'sayfa 1 satırları sayfa 2 diye etiketlenmemeli');
  console.log('PASS: detay DOM yedeği — başlık, kanonik okuyucu adresi, sayfalama koruması');
}

// ---------------------------------------------------------------------------
// 7. Okuyucu: bölüm kapsamı, lazy görseller, JSON-LD dışlama
// ---------------------------------------------------------------------------
{
  const env = environment(`<html><body><main>
    <script type="application/ld+json">{"image":"https://sadscans.net/api/uploads/assets/series/a/chapter123/images/cover.png"}</script>
    <img src="https://sadscans.net/api/uploads/assets/series/a/otherchapter/images/1.jpg">
    <img src="https://sadscans.net/api/uploads/assets/series/a/chapter123/images/1.jpg">
    <img data-src="https://sadscans.net/api/uploads/assets/series/a/chapter123/images/2.jpg">
    <img src="https://sadscans.net/api/uploads/assets/series/a/chapter123/images/1.jpg">
    <img src="/images/logo.png">
  </main></body></html>`, {path: '/reader/chainsaw-man/1-bolum/chapter123'});
  Object.defineProperty(env.window.document.documentElement, 'scrollHeight', {value: 6000, configurable: true});
  const raw = await drive(env, templates[2].replace('$id', JSON.stringify('chapter123')), 40);
  const result = JSON.parse(raw);
  assert.deepEqual(result.urls, [
    'https://sadscans.net/api/uploads/assets/series/a/chapter123/images/1.jpg',
    'https://sadscans.net/api/uploads/assets/series/a/chapter123/images/2.jpg',
  ]);
  assert.ok(env.scrolls() > 0, 'lazy görseller için kaydırma denenmeli');

  const schemaOnly = environment('<html><body><script type="application/ld+json">{"image":"https://sadscans.net/api/uploads/assets/series/a/chapter123/images/cover.jpg"}</script></body></html>', {
    path: '/reader/s/c/chapter123',
  });
  const schemaRaw = await drive(schemaOnly, templates[2].replace('$id', JSON.stringify('chapter123')), 60);
  assert.match(JSON.parse(schemaRaw).error, /görselleri bulunamadı/);
  console.log('PASS: okuyucu — bölüm kapsamı, lazy yükleme, JSON-LD/logo dışlama');
}


// ---------------------------------------------------------------------------
// 8. Sayfalama sınırı: hasMore yalnızca tam sayfa dolduysa true
// ---------------------------------------------------------------------------
{
  const cards = count => Array.from({length: count}, (_, index) =>
    `<a href="https://sadscans.net/seriler/seri-${index}"><h3>Seri ${index}</h3></a>`).join('');
  const page = (count) => `<html><body><input placeholder="Aramak istediğiniz seriyi yazın">
    ${cards(count)}<div aria-hidden="true" class="h-px w-full"></div></body></html>`;

  const full = environment(page(20), {fetchImpl: () => Promise.reject(new Error('blocked'))});
  full.window.document.querySelector('input').__reactPropsFixture = {onChange: () => {}};
  const fullResult = JSON.parse(await drive(full, listScript({query: '', sort: 'views', type: null, status: null, page: 1}), 80));
  assert.equal(fullResult.items.length, 20);
  assert.equal(fullResult.hasMore, true, 'tam sayfa + sentinel varken devam etmeli');

  const partial = environment(page(4), {fetchImpl: () => Promise.reject(new Error('blocked'))});
  partial.window.document.querySelector('input').__reactPropsFixture = {onChange: () => {}};
  const partialResult = JSON.parse(await drive(partial, listScript({query: '', sort: 'views', type: null, status: null, page: 1}), 80));
  assert.equal(partialResult.items.length, 4);
  assert.equal(partialResult.hasMore, false, 'eksik sayfada sonsuz sayfalama olmamalı');

  const second = environment(page(30), {fetchImpl: () => Promise.reject(new Error('blocked'))});
  second.window.document.querySelector('input').__reactPropsFixture = {onChange: () => {}};
  const secondResult = JSON.parse(await drive(second, listScript({query: '', sort: 'views', type: null, status: null, page: 2}), 120));
  assert.equal(secondResult.items.length, 30);
  assert.equal(secondResult.hasMore, true, 'ikinci sayfada da tam sayfa dolduğunda devam etmeli');
  console.log('PASS: sayfalama sınırı — eksik sayfada hasMore false');
}

console.log('Tüm SadScans frontend sözleşme testleri geçti.');
