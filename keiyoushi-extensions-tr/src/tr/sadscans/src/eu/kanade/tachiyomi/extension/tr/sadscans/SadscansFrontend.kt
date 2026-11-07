package eu.kanade.tachiyomi.extension.tr.sadscans

import kotlinx.serialization.json.JsonPrimitive

/**
 * sadscans.net üzerinde çalışan JavaScript köprüleri.
 *
 * Site Next.js + tRPC kullanıyor ve `/api/trpc` yolu Cloudflare tarafından korunuyor. Bu yüzden
 * her sorgu iki aşamalı yürütülür:
 *
 *  1. **Aynı kaynaklı API denemesi** — sayfanın içinde `fetch` ile. Tarayıcı kimliği, TLS parmak
 *     izi ve `cf_clearance` çerezi sitenin kendi isteğiyle aynıdır; koruma geçilirse tam ve hızlı
 *     veri döner.
 *  2. **Arayüz (DOM) yedeği** — API engellenirse sitenin kendi React kontrolleri sürülür: menüler
 *     tıklanır, arama kutusuna yazılır, sonsuz kaydırma ile sayfa doldurulur, kartlar okunur.
 *     Hiçbir adım ölümcül değildir; menü bulunamazsa arama yine yapılır.
 *
 * Seçiciler canlı JS parçalarından alındı: liste `1vy0fm3wxax8_.js`, detay `22yc01h34_ye1.js`.
 */
internal object SadscansFrontend {

    /**
     * Cloudflare önsözü: üç script de aynı davranışı kullanır.
     * Geçici doğrulama ekranı ("Just a moment") 12 sn'ye kadar beklenir; JS challenge
     * gerçek bir WebView'de genelde 2-5 sn içinde kendiliğinden geçer. Sert WAF bloğu
     * ("Sorry, you have been blocked") ayrı hata olarak raporlanır.
     */
    private val CF_PRELUDE = """
              // ── Cloudflare: geçici doğrulama ekranı genelde 2-5 sn içinde kendiliğinden geçer,
              // bu yüzden hemen hata vermek yerine beklenir (12 sn). Sert WAF bloğu ayrı ele alınır.
              // Site yeni alan adına taşınıyor: "SİTE YAPIM AŞAMASINDA / YAKINDA GELİYOR" sayfası.
              if (/YAPIM AŞAMASINDA|YAKINDA GELİYOR|yeni adresimiz/i.test(text)) {
                return out({error: 'SadScans yeni adresine taşınıyor (sadscans.org); site henüz yayında değil. Yakında tekrar deneyin.', migrating: true});
              }
              var hardBlock = /Sorry, you have been blocked|Attention Required|cf-error-details/i.test(text);
              var challenge = !hardBlock && (
                /Just a moment|Checking your browser|Verifying you are human|Verify you are human|Doğrulanıyor|Bir dakika lütfen/i.test(text) ||
                !!document.querySelector('#challenge-form, #cf-challenge-running, #challenge-stage, .cf-turnstile')
              );
              if (challenge) {
                var cfStart = window.__sadscansCfStart || (window.__sadscansCfStart = Date.now());
                if (Date.now() - cfStart < 12000) return null;
                return out({error: 'SadScans Cloudflare doğrulaması geçilemedi. Kaynağı uygulamada WebView ile bir kez açıp doğrulayın.', challenge: true});
              }
              window.__sadscansCfStart = 0;
              if (hardBlock) {
                return out({error: 'SadScans bağlantıyı engelledi (Cloudflare WAF). Kaynağı uygulamada WebView ile bir kez açıp doğrulayın.', blocked: true});
              }
              // cf_clearance çerezi bu UA'ya bağlıdır; API çağrıları aynı UA ile yapılmalı.
              var UA = (typeof navigator !== 'undefined' && navigator.userAgent) || '';
"""

    fun listScript(query: String, sort: String, type: String?, status: String?, page: Int, router: String? = null): String {
        require(page > 0)
        val options = buildString {
            append("{query:").append(JsonPrimitive(query))
            append(",sort:").append(JsonPrimitive(sort))
            append(",type:").append(type?.let { JsonPrimitive(it) } ?: "null")
            append(",status:").append(status?.let { JsonPrimitive(it) } ?: "null")
            append(",page:").append(page)
            append(",router:").append(router?.let { JsonPrimitive(it) } ?: "null")
            append('}')
        }
        return """
            (function() {
              var options = $options;
              var out = function(o) { return JSON.stringify(o); };
              var now = Date.now();
              var text = document.body ? (document.body.innerText || document.body.textContent || '') : '';
${CF_PRELUDE}              var route = location.pathname.replace(/\/+$/, '');
              if (route !== '/seriler' && route.indexOf('/seriler/') !== 0) return null;

              // Arama kutusu tRPC kademesi için gerekli değil: kutu adı değişse bile arama
              // çalışsın diye kutu yalnızca arayüz yedeğinde ve esnek biçimde aranır.
              function searchInput() {
                var exact = document.querySelector('input[placeholder="Aramak istediğiniz seriyi yazın"]');
                if (exact) return exact;
                var inputs = document.querySelectorAll('input');
                for (var i = 0; i < inputs.length; i++) {
                  var candidate = inputs[i];
                  var kind = (candidate.getAttribute('type') || 'text').toLowerCase();
                  if (kind !== 'text' && kind !== 'search') continue;
                  if (candidate.disabled || candidate.readOnly) continue;
                  return candidate;
                }
                return null;
              }

              function normalize(value) {
                return String(value)
                  .replace(/[İIı]/g, 'i')
                  .replace(/[Şş]/g, 's')
                  .replace(/[Ğğ]/g, 'g')
                  .replace(/[Üü]/g, 'u')
                  .replace(/[Öö]/g, 'o')
                  .replace(/[Çç]/g, 'c')
                  .toLowerCase();
              }

              var MENUS = {
                name: ['Sırala:', 'A-Z'],
                '-name': ['Sırala:', 'Z-A'],
                views: ['Okunma:', 'En Çok'],
                '-views': ['Okunma:', 'En Az'],
                rating: ['Puan:', 'En yüksek'],
                '-rating': ['Puan:', 'En düşük']
              };
              var STATUS_LABELS = {'devam-ediyor': 'Devam Ediyor', 'tamamlandi': 'Tamamlandı', 'birakildi': 'Bırakıldı'};

              function wishlist() {
                var list = [];
                var sort = MENUS[options.sort];
                if (sort) list.push(sort);
                if (options.type) list.push(['Tür:', options.type]);
                if (options.status) list.push(['Durumu:', STATUS_LABELS[options.status] || options.status]);
                return list;
              }

              var DEFAULT_ROUTER = '_s4d7g2k9m0';

              // Site her derlemede tRPC yönlendirici adını (build hash) değiştirebiliyor. Adı
              // sabit varsaymak, site güncellendiği gün aramayı sessizce kırar. Bu yüzden ad,
              // sayfanın kendi JS parçalarındaki "getSeries" çağrısından öğrenilir.
              function discoverRouters() {
                var box = {state: 'pending', map: {}};
                if (options.router) {
                  // Önceki aramada öğrenilen ad: sayfayı yeniden taramaya gerek yok.
                  box.map.getSeries = options.router;
                  box.state = 'ok';
                  return box;
                }
                var scripts = document.querySelectorAll('script[src*="/_next/static/"]');
                var urls = [];
                for (var i = 0; i < scripts.length && urls.length < 6; i++) urls.push(scripts[i].src);
                if (!urls.length || typeof fetch !== 'function') {
                  box.state = 'ok';
                  return box;
                }
                var index = 0;
                function step() {
                  if (index >= urls.length) { box.state = 'ok'; return; }
                  var target = urls[index++];
                  try {
                    fetch(target, {credentials: 'same-origin'})
                      .then(function(response) { return response.ok ? response.text() : ''; })
                      .then(function(source) {
                        var pattern = /([A-Za-z0-9_]{4,60})\.(getSeriesData|getSeries)\b/g;
                        var match;
                        while ((match = pattern.exec(source)) !== null) box.map[match[2]] = match[1];
                        step();
                      })
                      .catch(step);
                  } catch (e) {
                    step();
                  }
                }
                step();
                return box;
              }

              function activeRouter() {
                return options.router || routerName(state.routers) || DEFAULT_ROUTER;
              }

              function routerName(box) {
                var discovered = box && box.map ? box.map.getSeries : null;
                return discovered || DEFAULT_ROUTER;
              }

              function trpcUrl(pageNumber, router) {
                // Cloudflare WAF, sorgu dizesinde JSON (%22, %7B) görünce "malformed data" kuralıyla
                // sert blok uyguluyor. Bu yüzden gövde POST ile gönderilir; URL temiz kalır.
                return '/api/trpc/' + (router || options.router || DEFAULT_ROUTER) + '.getSeries?batch=1';
              }

              function trpcBody(search, pageNumber) {
                var payload = {sortBy: options.sort, page: pageNumber, limit: 15};
                if (search) payload.search = search;
                if (options.type) payload.type = options.type;
                if (options.status) payload.status = options.status;
                return JSON.stringify({0: {json: payload}});
              }

              function parseBatch(body) {
                try {
                  var batch = JSON.parse(body);
                  var data = batch[0] && batch[0].result && batch[0].result.data;
                  var rows = data ? data.json : null;
                  if (!rows || !rows.length) return null;
                  var mapped = [];
                  for (var i = 0; i < rows.length; i++) {
                    var row = rows[i];
                    if (!row || !row.href || !row.name) continue;
                    mapped.push({href: row.href, name: row.name, thumb: row.thumb || null});
                  }
                  return mapped.length ? mapped : null;
                } catch (e) {
                  return null;
                }
              }

              function request(search, pageNumber, router) {
                var box = {state: 'pending', items: null};
                if (typeof fetch !== 'function') {
                  box.state = 'bad';
                  box.items = [];
                  return box;
                }
                try {
                  fetch(trpcUrl(pageNumber, router), {
                    method: 'POST',
                    credentials: 'same-origin',
                    headers: {'content-type': 'application/json', accept: 'application/json'},
                    body: trpcBody(search, pageNumber)
                  })
                    .then(function(response) { return response.ok ? response.text() : null; })
                    .then(function(body) {
                      var items = body ? parseBatch(body) : null;
                      box.state = items ? 'ok' : 'bad';
                      box.items = items || [];
                    })
                    .catch(function() { box.state = 'bad'; box.items = []; });
                } catch (e) {
                  box.state = 'bad';
                  box.items = [];
                }
                return box;
              }

              function cards() {
                var anchors = document.querySelectorAll('a[href]');
                var result = [];
                var seen = {};
                for (var i = 0; i < anchors.length; i++) {
                  var anchor = anchors[i];
                  var path;
                  try { path = new URL(anchor.href, location.href).pathname; } catch (e) { continue; }
                  if (path.indexOf('/seriler/') !== 0) continue;
                  if (path.split('/').filter(Boolean).length < 2) continue;
                  if (seen[path]) continue;
                  // Başlık her zaman h3 olmayabilir; sırayla denenir.
                  var label = anchor.querySelector('h3, h2, h4, [class*="title"], [class*="Title"]');
                  var name = label ? label.textContent.trim() : '';
                  if (!name) name = (anchor.getAttribute('title') || anchor.getAttribute('aria-label') || '').trim();
                  var image = anchor.querySelector('img');
                  if (!name && image) name = (image.getAttribute('alt') || '').trim();
                  if (!name) continue;
                  seen[path] = 1;
                  result.push({
                    href: path,
                    name: name,
                    thumb: image ? (image.currentSrc || image.src || null) : null
                  });
                }
                return result;
              }

              function group(prefix) {
                var groups = document.querySelectorAll('.dropdown-container');
                for (var i = 0; i < groups.length; i++) {
                  var button = groups[i].querySelector('button');
                  if (button && button.textContent.trim().indexOf(prefix) === 0) return groups[i];
                }
                return null;
              }

              function groupValue(element) {
                var button = element.querySelector('button');
                var label = button ? button.textContent.trim() : '';
                var colon = label.indexOf(':');
                return colon >= 0 ? label.slice(colon + 1).trim() : '';
              }

              function plan(wanted) {
                var container = group(wanted[0]);
                if (!container) return {done: true};
                if (groupValue(container) === wanted[1]) return {done: true};
                var buttons = container.querySelectorAll('button');
                var toggle = buttons[0];
                if (!toggle) return {done: true};
                var item = null;
                for (var i = 1; i < buttons.length; i++) {
                  if (buttons[i].textContent.trim() === wanted[1]) { item = buttons[i]; break; }
                }
                return {done: false, toggle: toggle, item: item};
              }

              var state = window.__sadscansList;
              if (!state || state.query !== options.query || state.sort !== options.sort ||
                  state.type !== options.type || state.status !== options.status) {
                var search = normalize(options.query);
                state = window.__sadscansList = {
                  query: options.query,
                  sort: options.sort,
                  type: options.type,
                  status: options.status,
                  t0: now,
                  phase: 'api',
                  routers: discoverRouters(),
                  api: request(search, options.page, null),
                  retried: false,
                  raw: null,
                  rawAt: 0,
                  dom: null
                };
                return null;
              }

              if (state.phase === 'api') {
                var api = state.api;
                if (api.state === 'pending') {
                  if (now - state.t0 < 6000) return null;
                  api.state = 'bad';
                }
                if (api.state === 'ok' && api.items.length) {
                  return out({items: api.items, hasMore: api.items.length >= 15, page: options.page, source: 'api', ua: UA, router: activeRouter()});
                }
                // Sabit yönlendirici adı işe yaramadıysa sayfadan öğrenilen adla bir kez dene.
                if (!state.retried) {
                  if (state.routers && state.routers.state === 'pending' && now - state.t0 < 8000) return null;
                  var learned = routerName(state.routers);
                  state.retried = true;
                  if (learned !== DEFAULT_ROUTER) {
                    state.api = request(normalize(options.query), options.page, learned);
                    return null;
                  }
                }
                if (options.query && state.raw === null) {
                  state.raw = request(options.query, options.page);
                  state.rawAt = now;
                  return null;
                }
                if (state.raw) {
                  if (state.raw.state === 'pending') {
                    if (now - state.rawAt < 5000) return null;
                    state.raw.state = 'bad';
                  }
                  if (state.raw.state === 'ok' && state.raw.items.length) {
                    return out({items: state.raw.items, hasMore: state.raw.items.length >= 15, page: options.page, source: 'api', ua: UA, router: activeRouter()});
                  }
                }
                state.phase = 'dom';
                state.dom = {step: 'prepare', attempts: 0, opened: false, changed: 0, pause: 0, fingerprint: null, scrolls: 0};
                return null;
              }

              var dom = state.dom;
              if (!dom) return null;
              if (dom.pause && now < dom.pause) return null;

              if (dom.step === 'prepare') {
                var input = searchInput();
                if (!input) {
                  if (now - state.t0 < 10000) return null;
                  return out({error: 'SadScans arama kutusu bulunamadı; sayfa beklenen hâle gelmedi. Siteyi uygulama içi tarayıcıda açıp tekrar deneyin.'});
                }
                var hydrated = false;
                for (var key in input) {
                  if (key.indexOf('__reactProps') === 0 || key.indexOf('__reactFiber') === 0) { hydrated = true; break; }
                }
                if (!hydrated && now - state.t0 < 7000) return null;

                var wanted = wishlist();
                for (var index = 0; index < wanted.length; index++) {
                  var step = plan(wanted[index]);
                  if (step.done) {
                    dom.opened = false;
                    continue;
                  }
                  if (dom.attempts >= 12) break;
                  dom.attempts++;
                  if (!dom.opened) {
                    step.toggle.click();
                    dom.opened = true;
                    dom.pause = now + 400;
                    return null;
                  }
                  if (!step.item) {
                    // Menü açık ama istenen öğe yok (site menüyü değiştirmiş): ölümcül değil.
                    dom.opened = false;
                    continue;
                  }
                  step.item.click();
                  dom.opened = false;
                  dom.pause = now + 800;
                  return null;
                }

                var descriptor = Object.getOwnPropertyDescriptor(HTMLInputElement.prototype, 'value');
                if (descriptor && descriptor.set) {
                  descriptor.set.call(input, options.query);
                } else {
                  input.value = options.query;
                }
                input.dispatchEvent(new Event('input', {bubbles: true}));
                input.dispatchEvent(new Event('change', {bubbles: true}));
                dom.step = 'settle';
                dom.changed = now;
                dom.pause = 0;
                return null;
              }

              if (dom.step === 'settle') {
                if (now < dom.changed + 1500) return null;
                dom.step = 'collect';
                dom.changed = now;
                return null;
              }

              if (text.indexOf('Seriler yüklenemedi.') >= 0) {
                return out({error: 'SadScans kendi arayüzünde seri listesini yükleyemedi.'});
              }

              var items = cards();
              var fingerprint = JSON.stringify(items.map(function(item) { return item.href + '|' + item.name; }));
              if (fingerprint !== dom.fingerprint) {
                dom.fingerprint = fingerprint;
                dom.changed = now;
                return null;
              }

              var start = (options.page - 1) * 15;
              var sentinel = document.querySelector('div[aria-hidden="true"].h-px.w-full');
              if (items.length < options.page * 15 && sentinel && dom.scrolls < 20 && now - state.t0 < 15000) {
                if (now >= dom.changed + 900) {
                  window.scrollTo(0, document.body.scrollHeight);
                  dom.scrolls++;
                  dom.changed = now;
                }
                return null;
              }

              var empty = text.indexOf('sonuç bulunamadı') >= 0 || text.indexOf("Database'yi koymayı") >= 0;
              if (!items.length && !empty) {
                if (now - state.t0 < 12000) return null;
                return out({error: 'SadScans arama sonuçlarını yükleyemedi.'});
              }
              // hasMore yalnızca tam bir sayfa dolduysa true olabilir; aksi hâlde uygulama
              // sonsuz sayfalama isteğine girer (boş sayfa → tekrar istek).
              var full = items.length >= options.page * 15;
              return out({items: items, hasMore: full && (items.length > start + 15 || !!sentinel), page: options.page, source: 'dom', ua: UA, router: activeRouter()});
            })();
        """.trimIndent()
    }

    fun detailsScript(page: Int): String {
        require(page > 0)
        return """
            (function() {
              var target = $page;
              var out = function(o) { return JSON.stringify(o); };
              var now = Date.now();
              var text = document.body ? (document.body.innerText || document.body.textContent || '') : '';
${CF_PRELUDE}              if (location.pathname.indexOf('/seriler/') !== 0) return null;
              var parts = location.pathname.split('/').filter(Boolean);
              var slug = parts.length > 1 ? parts[1] : '';

              var DEFAULT_DETAIL_ROUTER = '_n3p6q1v8x5';
              var routerBox = {state: 'pending', map: {}};

              function discoverRouters() {
                var scripts = document.querySelectorAll('script[src*="/_next/static/"]');
                var urls = [];
                for (var i = 0; i < scripts.length && urls.length < 6; i++) urls.push(scripts[i].src);
                if (!urls.length || typeof fetch !== 'function') {
                  routerBox.state = 'ok';
                  return;
                }
                var index = 0;
                function step() {
                  if (index >= urls.length) { routerBox.state = 'ok'; return; }
                  var target = urls[index++];
                  try {
                    fetch(target, {credentials: 'same-origin'})
                      .then(function(response) { return response.ok ? response.text() : ''; })
                      .then(function(source) {
                        var pattern = /([A-Za-z0-9_]{4,60})\.(getSeriesData|getSeries)\b/g;
                        var match;
                        while ((match = pattern.exec(source)) !== null) routerBox.map[match[2]] = match[1];
                        step();
                      })
                      .catch(step);
                  } catch (e) {
                    step();
                  }
                }
                step();
              }

              function detailRouter() {
                return (routerBox.map && routerBox.map.getSeriesData) || DEFAULT_DETAIL_ROUTER;
              }

              function request(pageNumber, router) {
                if (typeof fetch !== 'function') return Promise.resolve(null);
                var payload = {sef: slug, page: pageNumber, limit: 10};
                var url = '/api/trpc/' + (router || DEFAULT_DETAIL_ROUTER) + '.getSeriesData?batch=1';
                try {
                  return fetch(url, {
                    method: 'POST',
                    credentials: 'same-origin',
                    headers: {'content-type': 'application/json', accept: 'application/json'},
                    body: JSON.stringify({0: {json: payload}})
                  })
                    .then(function(response) { return response.ok ? response.text() : null; })
                    .then(function(body) {
                      if (!body) return null;
                      try {
                        var batch = JSON.parse(body);
                        var data = batch[0] && batch[0].result && batch[0].result.data;
                        return data ? data.json : null;
                      } catch (e) { return null; }
                    })
                    .catch(function() { return null; });
                } catch (e) {
                  return Promise.resolve(null);
                }
              }

              function loadAll() {
                var box = {state: 'pending', data: null};
                var started = Date.now();
                discoverRouters();
                function start(router) {
                request(1, router).then(function(first) {
                  if (!first || !first.chapters || !first.chapters.length) {
                    var learned = detailRouter();
                    if (learned !== (router || DEFAULT_DETAIL_ROUTER)) {
                      start(learned);
                      return null;
                    }
                    box.state = 'bad';
                    return null;
                  }
                  var total = (first.pagination && first.pagination.totalPages) || 1;
                  var chapters = first.chapters.slice();
                  var next = 2;
                  function step() {
                    if (next > total || next > 60 || Date.now() - started > 12000) {
                      box.state = 'ok';
                      box.data = {
                        series: first.series || null,
                        chapters: chapters,
                        pagination: {totalPages: total},
                        merged: true,
                        source: 'api'
                      };
                      return null;
                    }
                    var current = next++;
                    return request(current).then(function(payload) {
                      if (payload && payload.chapters) chapters = chapters.concat(payload.chapters);
                      if (payload && payload.pagination && payload.pagination.totalPages) {
                        total = payload.pagination.totalPages;
                      }
                      return step();
                    }).catch(function() { return step(); });
                  }
                  return step();
                }).catch(function() { box.state = 'bad'; });
                }
                start(null);
                return box;
              }

              function rows() {
                var anchors = document.querySelectorAll('a[href*="/reader/"]');
                var result = [];
                var seen = {};
                for (var i = 0; i < anchors.length; i++) {
                  var anchor = anchors[i];
                  var title = anchor.querySelector('h3');
                  var label = title ? title.textContent.trim() : anchor.textContent.trim();
                  if (!label) continue;
                  var url;
                  try { url = new URL(anchor.href, location.href); } catch (e) { continue; }
                  if (url.pathname.indexOf('/reader/') < 0) continue;
                  if (seen[url.pathname]) continue;
                  seen[url.pathname] = 1;
                  var segments = url.pathname.split('/').filter(Boolean);
                  var match = label.match(/^([0-9]+(?:[.,][0-9]+)?)\.?\s*Bölüm(?:\s*[-–:]\s*(.*))?/i);
                  result.push({
                    chap_id: segments[segments.length - 1],
                    href: url.pathname,
                    no: match ? match[1].replace(',', '.') : null,
                    name: match ? (match[2] || null) : label
                  });
                }
                return result;
              }

              var state = window.__sadscansDetails;
              if (!state || state.slug !== slug) {
                state = window.__sadscansDetails = {slug: slug, t0: now, phase: 'api', api: loadAll(), dom: null};
                return null;
              }

              if (state.phase === 'api') {
                var api = state.api;
                if (api.state === 'pending') {
                  if (now - state.t0 < 12000) return null;
                  api.state = 'bad';
                }
                if (api.state === 'ok' && api.data) return out(api.data);
                state.phase = 'dom';
                state.dom = {step: 'boot', changed: 0, fingerprint: null, before: null};
                return null;
              }

              var dom = state.dom;
              var current = rows();
              if (dom.step === 'boot') {
                if (!current.length && text.indexOf('Henüz bölüm') < 0 && now - state.t0 < 8000) return null;
                dom.before = current.map(function(row) { return row.href; }).join('|');
                if (target > 1) {
                  var button = document.querySelector('button[aria-label="Sayfa ' + target + '"]');
                  if (!button) return out({error: 'SadScans bölüm sayfası bulunamadı (' + target + ').'});
                  button.click();
                }
                dom.step = 'collect';
                dom.changed = now;
                return null;
              }

              if (now < dom.changed + 1200) return null;
              if (!current.length && text.indexOf('Henüz bölüm') < 0 && now - state.t0 < 12000) return null;
              var fingerprint = JSON.stringify(current);
              if (fingerprint !== dom.fingerprint) {
                dom.fingerprint = fingerprint;
                dom.changed = now;
                return null;
              }
              if (target > 1 && current.map(function(row) { return row.href; }).join('|') === dom.before) return null;

              var heading = document.querySelector('h1');
              var cover = document.querySelector('meta[property="og:image"]');
              var description = document.querySelector('meta[name="description"]');
              var buttons = document.querySelectorAll('button[aria-label^="Sayfa "]');
              var total = 1;
              for (var i = 0; i < buttons.length; i++) {
                var value = Number((buttons[i].getAttribute('aria-label') || '').slice(6));
                if (value > total) total = value;
              }
              var tags = [];
              var links = document.querySelectorAll('a[href*="/tur"], a[href*="/genre"]');
              for (var j = 0; j < links.length; j++) {
                var name = links[j].textContent.trim();
                if (name && tags.indexOf(name) < 0) tags.push(name);
              }
              return out({
                series: {
                  name: heading ? heading.textContent.trim() : (document.title || '').split(' - SADSCANS')[0],
                  cover: cover ? cover.getAttribute('content') : null,
                  description: description ? description.getAttribute('content') : null,
                  genres: tags
                },
                chapters: current,
                pagination: {page: target, totalPages: total},
                merged: false,
                source: 'dom',
                ua: UA
              });
            })();
        """.trimIndent()
    }

    fun readerScript(chapterId: String): String {
        val id = JsonPrimitive(chapterId).toString()
        return """
            (function() {
              var id = $id;
              var out = function(o) { return JSON.stringify(o); };
              var now = Date.now();
              var text = document.body ? (document.body.innerText || document.body.textContent || '') : '';
${CF_PRELUDE}              if (location.pathname.indexOf('/reader/') !== 0) return null;

              function collect() {
                var images = document.querySelectorAll('img');
                var scoped = [];
                var loose = [];
                for (var index = 0; index < images.length; index++) {
                  var image = images[index];
                  var raw = image.currentSrc || image.src ||
                    image.getAttribute('data-src') || image.getAttribute('data-lazy-src');
                  if (!raw) {
                    var srcset = image.getAttribute('srcset');
                    if (srcset) raw = srcset.split(',')[0].trim().split(' ')[0];
                  }
                  if (!raw) continue;
                  var url;
                  try { url = new URL(raw, location.href); } catch (e) { continue; }
                  var path = url.pathname;
                  var lower = path.toLowerCase();
                  // Site arayüz görselleri (logo, banner, slider, küçük resim) bölüm sayılmaz.
                  if (lower.indexOf('logo') >= 0 || lower.indexOf('/slider/') >= 0) continue;
                  if (lower.indexOf('favicon') >= 0 || lower.indexOf('/icons/') >= 0) continue;
                  if (lower.indexOf('thumb_') >= 0 || lower.indexOf('/avatar') >= 0 || lower.indexOf('/banner') >= 0) continue;
                  // Bölüm görselleri CDN'e taşınmış olabilir: alan adına değil, yola bakılır.
                  var looksLikeImage = /\.(jpe?g|png|webp|gif|avif)$/.test(lower) || lower.indexOf('/images/') >= 0;
                  if (!looksLikeImage) continue;
                  var entry = {url: url.href, order: index};
                  if (path.indexOf('/' + id + '/') >= 0) {
                    scoped.push(entry);
                  } else if (lower.indexOf('/images/') >= 0 || lower.indexOf('/api/uploads/') >= 0) {
                    loose.push(entry);
                  }
                }
                var chosen = scoped.length ? scoped : loose;
                chosen.sort(function(a, b) { return a.order - b.order; });
                var seen = {};
                var result = [];
                for (var i = 0; i < chosen.length; i++) {
                  if (seen[chosen[i].url]) continue;
                  seen[chosen[i].url] = 1;
                  result.push(chosen[i].url);
                }
                return result;
              }

              var images = document.querySelectorAll('img');
              for (var i = 0; i < images.length; i++) {
                if (images[i].loading === 'lazy') images[i].loading = 'eager';
                var lazy = images[i].getAttribute('data-src') || images[i].getAttribute('data-lazy-src');
                if (lazy && !images[i].src) images[i].src = lazy;
              }

              var state = window.__sadscansReader;
              if (!state || state.id !== id) {
                state = window.__sadscansReader = {id: id, t0: now, last: '', stable: 0, scrolls: 0};
              }

              var urls = collect();
              var key = urls.join('|');
              if (key !== state.last) {
                state.last = key;
                state.stable = 0;
              } else {
                state.stable++;
              }

              if (state.stable < 2 && state.scrolls < 60) {
                var y = window.scrollY || window.pageYOffset || 0;
                var stepSize = window.innerHeight || 900;
                var height = (document.documentElement && document.documentElement.scrollHeight) || 0;
                if (y + stepSize < height) {
                  window.scrollTo(0, y + stepSize * 2);
                  state.scrolls++;
                  return null;
                }
              }

              if (!urls.length) {
                if (now - state.t0 > 15000) return out({error: 'SadScans okuyucusunda bölüm görselleri bulunamadı.'});
                return null;
              }
              if (state.stable < 2 && now - state.t0 < 20000) return null;
              return out({urls: urls, ua: UA});
            })();
        """.trimIndent()
    }
}
