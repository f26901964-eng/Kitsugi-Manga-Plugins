/* Manga-TR Tek-Tık Toplayıcı - PC Chrome Console için
 * Kullanım: manga-tr.com'da F12 -> Console -> bu dosyanın tamamını yapıştır -> Enter
 * Sonuç: manga-tr-dump.json otomatik inecek, buraya at
 * Sandbox'tan siteye erişim kapalı olduğu için bunu senin PC topluyor, ben analiz ediyorum
 */
(async () => {
  const log = (m) => console.log("%c[MangaTR] " + m, "color:#ff4500;font-weight:bold");
  const origin = location.origin;
  const endpoints = [
    { name: "homepage", url: origin + "/" },
    { name: "popular_p1", url: origin + "/manga-list-sayfala.html?sort=views&sort_type=DESC&page=1&listType=pagination" },
    { name: "popular_p2", url: origin + "/manga-list-sayfala.html?sort=views&sort_type=DESC&page=2&listType=pagination" },
    { name: "arama_chainsaw_p1", url: origin + "/arama.html?view=sayfalama&icerik=Chainsaw%20Man&page=1" },
    { name: "arama_chainsaw_p2", url: origin + "/arama.html?view=sayfalama&icerik=Chainsaw%20Man&page=2" },
    { name: "arama_ladyliar", url: origin + "/arama.html?view=sayfalama&icerik=Lady%20Liar&page=1" },
    { name: "arama_bos", url: origin + "/arama.html?view=sayfalama&icerik=xyzabc123notfound&page=1" },
    { name: "detail_chainsaw", url: origin + "/manga-chainsaw-man.html" },
    { name: "detail_ladyliar", url: origin + "/manga-lady-liar.html" },
  ];

  const dump = { collectedAt: new Date().toISOString(), origin, url: location.href, htmlSnapshot: document.documentElement.outerHTML.slice(0, 500000), endpoints: {}, discovered: {}, posts: {} };

  async function fetchOne(name, url, opts = {}) {
    log(`GET ${name} -> ${url}`);
    try {
      const r = await fetch(url, { credentials: "include", headers: { "Accept": "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8" }, ...opts });
      const text = await r.text();
      const headers = {}; r.headers.forEach((v, k) => headers[k] = v);
      dump.endpoints[name] = { url, status: r.status, statusText: r.statusText, headers, bodyLen: text.length, body: text.slice(0, 800000) };
      log(`OK ${name}: ${r.status} ${text.length}b`);
      return text;
    } catch (e) {
      dump.endpoints[name] = { url, error: String(e) };
      log(`FAIL ${name}: ${e}`);
      return null;
    }
  }

  // 1) Basit GET'ler
  for (const ep of endpoints) {
    await fetchOne(ep.name, ep.url);
    await new Promise(r => setTimeout(r, 400));
  }

  // 2) Detail sayfasından listKey / data-manga / script src çıkar ve dış JS'i çek
  const html = dump.endpoints["detail_chainsaw"]?.body || dump.endpoints["detail_ladyliar"]?.body || "";
  const keys = [];
  const regexes = [
    /listKey:\s*'([^']+)'/g,
    /initialChapterListKey\s*=\s*['"`]([^'"`]+)['"`]/g,
    /window\.mtrChapterKeys\.listKey\s*=\s*['"]([^'"]+)['"]/g,
    /chapter_list_key\s*[:=]\s*['"]([^'"]+)['"]/g,
    /data-list-key\s*=\s*['"]([^'"]+)['"]/g,
  ];
  for (const re of regexes) {
    let m; while (m = re.exec(html)) keys.push(m[1]);
  }
  // DOM'dan da bak
  try {
    const doc = new DOMParser().parseFromString(html, "text/html");
    const d1 = doc.querySelector("#results")?.getAttribute("data-manga");
    const d2 = doc.querySelector("#results")?.getAttribute("data-list-key");
    const d3 = doc.querySelector("[data-list-key]")?.getAttribute("data-list-key");
    const scripts = [...doc.querySelectorAll("script[src]")].map(s => s.getAttribute("src"));
    dump.discovered = { keys, dataManga: d1, dataListKey: d2 || d3, scripts, hasRdm: html.includes("rdm-"), fpx: (html.match(/_fpx\s*=\s*"([^"]+)"/) || [])[1] || null, hasTurnstile: html.includes("turnstile") || html.includes("cf-turnstile"), hasDdos: html.includes("check.ddos-guard.net") };
    // dış JS
    const jsSrc = scripts.find(s => s && s.includes("manga-tr")) || null;
    if (jsSrc) {
      const abs = jsSrc.startsWith("http") ? jsSrc : origin + (jsSrc.startsWith("/") ? jsSrc : "/" + jsSrc);
      await fetchOne("external_js", abs);
      const jsBody = dump.endpoints["external_js"]?.body || "";
      for (const re of regexes) { let m; while (m = re.exec(jsBody)) keys.push(m[1]); }
      dump.discovered.keysFromJs = keys;
    }
  } catch (e) { dump.discovered.error = String(e); }

  // 3) POST denemeleri - chapter_list_key varsa
  const uniqKeys = [...new Set(keys.filter(k => k && k.length > 10))];
  for (const k of uniqKeys.slice(0, 2)) {
    for (const off of [0, 20, 100]) {
      const name = `post_fetch_pages_key_${k.slice(0, 6)}_off${off}`;
      log(`POST ${name}`);
      try {
        const fd = new URLSearchParams(); fd.set("chapter_list_key", k); if (off) fd.set("offset", String(off));
        const r = await fetch(origin + "/cek/fetch_pages_manga.php", { method: "POST", credentials: "include", headers: { "Content-Type": "application/x-www-form-urlencoded", "X-Requested-With": "XMLHttpRequest", "Accept": "*/*" }, body: fd.toString() });
        const t = await r.text();
        const h = {}; r.headers.forEach((v, k) => h[k] = v);
        dump.posts[name] = { status: r.status, headers: h, body: t.slice(0, 500000), bodyLen: t.length };
      } catch (e) { dump.posts[name] = { error: String(e) }; }
      await new Promise(r => setTimeout(r, 500));
    }
  }
  // fallback manga_cek
  for (const slug of ["chainsaw-man", "lady-liar"]) {
    const name = `post_manga_cek_${slug}`;
    try {
      const r = await fetch(origin + "/cek/fetch_pages_manga.php?manga_cek=" + slug, { credentials: "include", headers: { "X-Requested-With": "XMLHttpRequest" } });
      const t = await r.text(); const h = {}; r.headers.forEach((v, k) => h[k] = v);
      dump.posts[name] = { status: r.status, headers: h, body: t.slice(0, 500000), bodyLen: t.length };
    } catch (e) { dump.posts[name] = { error: String(e) }; }
    await new Promise(r => setTimeout(r, 400));
  }

  // 4) Gate ve reader testi: rastgele bir bölüm linki bulup rdm testi
  try {
    const detailBody = dump.endpoints["detail_chainsaw"]?.body || "";
    const m = detailBody.match(/href="(\/[^"]*\/id-[^"]+)"|href="(https:\/\/manga-tr\.com\/[^"]*read[^"]+)"/);
    const chapUrl = m ? (m[1] || m[2]) : null;
    dump.discovered.sampleChapterUrl = chapUrl;
    if (chapUrl) {
      const absChap = chapUrl.startsWith("http") ? chapUrl : origin + chapUrl;
      await fetchOne("sample_reader", absChap);
      const rb = dump.endpoints["sample_reader"]?.body || "";
      dump.discovered.reader = { hasRdm: rb.includes("rdm-"), fpx: (rb.match(/_fpx\s*=\s*"([^"]+)"/) || [])[1] || null, hasSlider: rb.includes("sliderCanvas"), hasTurnstile: rb.includes("turnstile"), imgPartCount: (rb.match(/img_part\.php/g) || []).length };
    }
  } catch (e) {}

  // İndir
  const blob = new Blob([JSON.stringify(dump, null, 2)], { type: "application/json" });
  const a = document.createElement("a"); a.href = URL.createObjectURL(blob); a.download = "manga-tr-dump-" + new Date().toISOString().slice(0, 10) + ".json"; a.click();
  setTimeout(() => URL.revokeObjectURL(a.href), 2000);
  log("BİTTİ! manga-tr-dump.json indi. Buraya at, gerisini ben hallederim.");
  console.log(dump);
})();