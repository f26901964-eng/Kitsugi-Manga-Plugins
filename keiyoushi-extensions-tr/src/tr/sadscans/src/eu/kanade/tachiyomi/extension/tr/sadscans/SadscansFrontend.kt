package eu.kanade.tachiyomi.extension.tr.sadscans

import kotlinx.serialization.json.JsonPrimitive

/** Uses the site's own React controls/transport, never a fabricated tRPC request. */
internal object SadscansFrontend {
    fun listScript(query: String, sort: String, page: Int): String {
        require(page > 0)
        val options = "{query:${JsonPrimitive(query)},sort:${JsonPrimitive(sort)},page:$page}"
        return """
            (function() {
                const options = $options;
                const text = document.body ? document.body.innerText : '';
                const fail = message => JSON.stringify({error: message});
                if (/Sorry, you have been blocked/i.test(text)) return fail('SadScans bu tarayıcı bağlantısını engelledi.');
                if (location.pathname !== '/seriler') return null;
                const input = document.querySelector('input[placeholder="Aramak istediğiniz seriyi yazın"]');
                if (!input) return null;
                let state = window.__sadscansFrontend;
                if (!state) {
                    state = {started: Date.now(), phase: 0, fingerprint: '', changed: Date.now()};
                    window.__sadscansFrontend = state;
                }
                if (state.phase === 0) {
                    const label = options.sort === '-views' ? 'Okunma:' : 'Sırala:';
                    const group = Array.from(document.querySelectorAll('.dropdown-container')).find(el => el.textContent.trim().startsWith(label));
                    if (!group) return fail('SadScans sıralama kontrolü değişmiş.');
                    group.querySelector('button').click();
                    state.phase = 1;
                    return null;
                }
                if (state.phase === 1) {
                    const label = options.sort === '-views' ? 'Okunma:' : 'Sırala:';
                    const value = options.sort === '-views' ? 'En Az' : options.sort === '-name' ? 'Z-A' : 'A-Z';
                    const group = Array.from(document.querySelectorAll('.dropdown-container')).find(el => el.textContent.trim().startsWith(label));
                    const button = group && Array.from(group.querySelectorAll('button')).find(el => el.textContent.trim() === value);
                    if (!button) return null;
                    button.click();
                    const setter = Object.getOwnPropertyDescriptor(HTMLInputElement.prototype, 'value').set;
                    setter.call(input, options.query);
                    input.dispatchEvent(new Event('input', {bubbles: true}));
                    input.dispatchEvent(new Event('change', {bubbles: true}));
                    state.phase = 2;
                    state.changed = Date.now();
                    return null;
                }
                if (Date.now() - state.changed < 1500) return null;
                if (text.includes('Seriler yüklenemedi.') || text.includes('Daha fazla yüklenemedi')) return fail('SadScans kendi arayüzünde seri verisini yükleyemedi.');
                const cards = Array.from(document.querySelectorAll('a[href*="/seriler/"]')).filter(el => el.querySelector('h3'));
                const items = Array.from(new Map(cards.map(el => {
                    const image = el.querySelector('img');
                    return [el.href, {href: new URL(el.href).pathname, name: el.querySelector('h3').textContent.trim(), thumb: image ? image.currentSrc || image.src : null}];
                })).values());
                const fingerprint = JSON.stringify(items.map(el => el.href));
                if (state.fingerprint !== fingerprint) {
                    state.fingerprint = fingerprint;
                    state.changed = Date.now();
                    return null;
                }
                const more = !!document.querySelector('div[aria-hidden="true"].h-px.w-full');
                if (items.length < options.page * 15 && more) {
                    window.scrollTo(0, document.body.scrollHeight);
                    return null;
                }
                if (!items.length && !text.includes('sonuç bulunamadı') && !text.includes("Database'yi koymayı")) return null;
                const start = (options.page - 1) * 15;
                return JSON.stringify({items: items.slice(start, start + 15), hasMore: items.length > start + 15 || more});
            })();
        """.trimIndent()
    }

    fun detailsScript(page: Int): String {
        require(page > 0)
        return """
            (function() {
                const target = $page;
                const text = document.body ? document.body.innerText : '';
                if (/Sorry, you have been blocked/i.test(text)) return JSON.stringify({error:'SadScans detay bağlantısını engelledi.'});
                if (!location.pathname.startsWith('/seriler/')) return null;
                const rows = Array.from(document.querySelectorAll('a[href*="/reader/"]')).filter(el => el.querySelector('h3'));
                let state = window.__sadscansDetails;
                if (!state) {
                    if (!rows.length && !text.includes('Henüz bölüm eklenmemiş.')) return null;
                    const before = rows.map(el => el.href).join('|');
                    if (target > 1) {
                        const button = document.querySelector('button[aria-label="Sayfa ' + target + '"]');
                        if (!button) return JSON.stringify({error:'SadScans bölüm sayfası bulunamadı.'});
                        button.click();
                    }
                    state = {changed:Date.now(), fingerprint:'', before:before};
                    window.__sadscansDetails = state;
                    return null;
                }
                if (Date.now() - state.changed < 1500) return null;
                if (!rows.length && !text.includes('Henüz bölüm eklenmemiş.')) return null;
                if (target > 1 && rows.map(el => el.href).join('|') === state.before) return null;
                const chapters = rows.map(el => {
                    const url = new URL(el.href);
                    const parts = url.pathname.split('/').filter(Boolean);
                    const label = el.querySelector('h3').textContent.trim();
                    const match = label.match(/^([0-9.,]+)\.\s*Bölüm(?:\s*-\s*(.*))?/);
                    return {chap_id:parts[parts.length-1], href:url.pathname, no:match ? match[1].replace(',','.') : null, name:match ? match[2] || null : label};
                });
                const fingerprint = JSON.stringify(chapters);
                if (state.fingerprint !== fingerprint) {state.fingerprint=fingerprint;state.changed=Date.now();return null;}
                const h1 = document.querySelector('h1');
                const cover = document.querySelector('meta[property="og:image"]');
                const description = document.querySelector('meta[name="description"]');
                const buttons = Array.from(document.querySelectorAll('button[aria-label^="Sayfa "]'));
                const total = buttons.reduce((max, el) => Math.max(max, Number(el.getAttribute('aria-label').slice(6)) || 0), 1);
                return JSON.stringify({series:{name:h1 ? h1.textContent.trim() : document.title.split(' - SADSCANS')[0], cover:cover ? cover.content : null, description:description ? description.content : null}, chapters:chapters, pagination:{page:target,totalPages:total}});
            })();
        """.trimIndent()
    }

    fun readerScript(chapterId: String): String {
        val id = JsonPrimitive(chapterId).toString()
        return """
            (function() {
                const id = $id;
                const text = document.body ? document.body.innerText : '';
                if (/Sorry, you have been blocked/i.test(text)) return JSON.stringify({error:'SadScans okuyucu bağlantısını engelledi.'});
                if (!location.pathname.startsWith('/reader/')) return null;
                const images = Array.from(document.querySelectorAll('img')).map(img => img.currentSrc || img.src || img.getAttribute('data-src')).filter(Boolean).filter(src => {
                    try { const path = new URL(src, location.href).pathname; return path.includes('/' + id + '/images/'); } catch (_) { return false; }
                });
                const urls = Array.from(new Set(images));
                const fingerprint = JSON.stringify(urls);
                let state = window.__sadscansReader;
                if (!state || state.fingerprint !== fingerprint) {
                    window.__sadscansReader = {fingerprint: fingerprint, changed: Date.now()};
                    window.scrollTo(0, document.body.scrollHeight);
                    return null;
                }
                if (!urls.length || Date.now() - state.changed < 3000) return null;
                return JSON.stringify({urls: urls});
            })();
        """.trimIndent()
    }
}
