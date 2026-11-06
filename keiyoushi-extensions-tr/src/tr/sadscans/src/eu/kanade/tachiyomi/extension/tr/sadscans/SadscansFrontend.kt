package eu.kanade.tachiyomi.extension.tr.sadscans

import kotlinx.serialization.json.JsonPrimitive

/**
 * sadscans.net üzerinde çalışan JavaScript köprüleri.
 *
 * Site Next.js + tRPC kullanıyor ve `/api/trpc/*` yolu Cloudflare tarafından korunuyor. Bu yüzden
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

    fun listScript(query: String, sort: String, type: String?, status: String?, page: Int): String {
        require(page > 0)
        val options = buildString {
            append("{query:").append(JsonPrimitive(query))
            append(",sort:").append(JsonPrimitive(sort))
            append(",type:").append(type?.let { JsonPrimitive(it) } ?: "null")
            append(",status:").append(status?.let { JsonPrimitive(it) } ?: "null")
            append(",page:").append(page)
            append('}')
        }
        return """
            (function() {
              var options = $options;
              var out = function(o) { return JSON.stringify(o); };
              var now = Date.now();
              var text = document.body ? (document.body.innerText || document.body.textContent || '') : '';
              if (/Sorry, you have been blocked|Attention Required/i.test(text)) {
                return out({error: 'SadScans bu bağlantıyı engelledi (Cloudflare). Siteyi tarayıcıda bir kez açıp tekrar deneyin.'});
              }
              if (location.pathname !== '/seriler') return null;
              var input = document.querySelector('input[placeholder="Aramak istediğiniz seriyi yazın"]');
              if (!input) return null;

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

              function trpcUrl(search, pageNumber) {
                var payload = {sortBy: options.sort, page: pageNumber, limit: 15};
                if (search) payload.search = search;
                if (options.type) payload.type = options.type;
                if (options.status) payload.status = options.status;
                return '/api/trpc/_s4d7g2k9m0.getSeries?batch=1&input=' +
                  encodeURIComponent(JSON.stringify({0: {json: payload}}));
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

              function request(search, pageNumber) {
                var box = {state: 'pending', items: null};
                if (typeof fetch !== 'function') {
                  box.state = 'bad';
                  box.items = [];
                  return box;
                }
                try {
                  fetch(trpcUrl(search, pageNumber), {credentials: 'same-origin', headers: {accept: 'application/json'}})
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
                  var title = anchor.querySelector('h3');
                  if (!title) continue;
                  var path;
                  try { path = new URL(anchor.href, location.href).pathname; } catch (e) { continue; }
                  if (path.indexOf('/seriler/') !== 0) continue;
                  if (seen[path]) continue;
                  seen[path] = 1;
                  var image = anchor.querySelector('img');
                  result.push({
                    href: path,
                    name: title.textContent.trim(),
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
                  api: request(search, options.page),
                  raw: null,
                  rawAt: 0,
                  dom: null
                };
                return null;
              }

              if (state.phase === 'api') {
                var api = state.api;
                if (api.state === 'pending') {
                  if (now - state.t0 < 12000) return null;
                  api.state = 'bad';
                }
                if (api.state === 'ok' && api.items.length) {
                  return out({items: api.items, hasMore: api.items.length >= 15, page: options.page, source: 'api'});
                }
                if (options.query && state.raw === null) {
                  state.raw = request(options.query, options.page);
                  state.rawAt = now;
                  return null;
                }
                if (state.raw) {
                  if (state.raw.state === 'pending') {
                    if (now - state.rawAt < 12000) return null;
                    state.raw.state = 'bad';
                  }
                  if (state.raw.state === 'ok' && state.raw.items.length) {
                    return out({items: state.raw.items, hasMore: state.raw.items.length >= 15, page: options.page, source: 'api'});
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
                var hydrated = false;
                for (var key in input) {
                  if (key.indexOf('__reactProps') === 0 || key.indexOf('__reactFiber') === 0) { hydrated = true; break; }
                }
                if (!hydrated && now - state.t0 < 15000) return null;

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
              if (items.length < options.page * 15 && sentinel && dom.scrolls < 60 && now - state.t0 < 30000) {
                if (now >= dom.changed + 900) {
                  window.scrollTo(0, document.body.scrollHeight);
                  dom.scrolls++;
                  dom.changed = now;
                }
                return null;
              }

              var empty = text.indexOf('sonuç bulunamadı') >= 0 || text.indexOf("Database'yi koymayı") >= 0;
              if (!items.length && !empty) {
                if (now - state.t0 < 20000) return null;
                return out({error: 'SadScans arama sonuçlarını yükleyemedi.'});
              }
              return out({items: items, hasMore: items.length > start + 15 || !!sentinel, page: options.page, source: 'dom'});
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
              if (/Sorry, you have been blocked|Attention Required/i.test(text)) {
                return out({error: 'SadScans detay sayfasını engelledi (Cloudflare).'});
              }
              if (location.pathname.indexOf('/seriler/') !== 0) return null;
              var parts = location.pathname.split('/').filter(Boolean);
              var slug = parts.length > 1 ? parts[1] : '';

              function request(pageNumber) {
                if (typeof fetch !== 'function') return Promise.resolve(null);
                var payload = {sef: slug, page: pageNumber, limit: 10};
                var url = '/api/trpc/_n3p6q1v8x5.getSeriesData?batch=1&input=' +
                  encodeURIComponent(JSON.stringify({0: {json: payload}}));
                try {
                  return fetch(url, {credentials: 'same-origin', headers: {accept: 'application/json'}})
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
                request(1).then(function(first) {
                  if (!first || !first.chapters || !first.chapters.length) {
                    box.state = 'bad';
                    return null;
                  }
                  var total = (first.pagination && first.pagination.totalPages) || 1;
                  var chapters = first.chapters.slice();
                  var next = 2;
                  function step() {
                    if (next > total || next > 60 || Date.now() - started > 20000) {
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
                  if (now - state.t0 < 25000) return null;
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
                if (!current.length && text.indexOf('Henüz bölüm') < 0 && now - state.t0 < 15000) return null;
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
              if (!current.length && text.indexOf('Henüz bölüm') < 0 && now - state.t0 < 18000) return null;
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
                source: 'dom'
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
              if (/Sorry, you have been blocked|Attention Required/i.test(text)) {
                return out({error: 'SadScans okuyucuyu engelledi (Cloudflare).'});
              }
              if (location.pathname.indexOf('/reader/') !== 0) return null;

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
                  if (url.host !== location.host) continue;
                  var path = url.pathname;
                  if (path.indexOf('/images/') < 0) continue;
                  if (path.indexOf('logo') >= 0) continue;
                  if (path.indexOf('/slider/') >= 0) continue;
                  var entry = {url: url.href, order: index};
                  if (path.indexOf('/' + id + '/') >= 0) {
                    scoped.push(entry);
                  } else if (path.indexOf('/api/uploads/assets/series/') === 0 && path.indexOf('/images/thumb_') < 0) {
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

              if (state.stable < 2 && state.scrolls < 150) {
                var y = window.scrollY || window.pageYOffset || 0;
                var stepSize = window.innerHeight || 900;
                var height = (document.documentElement && document.documentElement.scrollHeight) || 0;
                if (y + stepSize < height) {
                  window.scrollTo(0, y + stepSize);
                  state.scrolls++;
                  return null;
                }
              }

              if (!urls.length) {
                if (now - state.t0 > 22000) return out({error: 'SadScans okuyucusunda bölüm görselleri bulunamadı.'});
                return null;
              }
              if (state.stable < 2 && now - state.t0 < 30000) return null;
              return out({urls: urls});
            })();
        """.trimIndent()
    }
}
