// SadScans canlı tanı sondası.
//
// GitHub Actions runner'ından (gerçek internet) siteyi açar; HTML'i, tRPC yanıtını ve
// GÖRSEL URL'lerini toplar. Amaç: "resimler neden gelmiyor" sorusunu tahminle değil,
// sitenin kendi HTML'iyle yanıtlamak.
//
// Çıktı: JSON (docs/sadscans/live-probe.json olarak commit edilir).

const UA =
  'Mozilla/5.0 (Linux; Android 14; Pixel 7) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0.0.0 Mobile Safari/537.36';

const BASES = ['https://sadscans.net', 'https://sadscans.org'];

const report = {generatedAt: new Date().toISOString(), bases: []};

function headers(extra = {}) {
  return {
    'user-agent': UA,
    'accept-language': 'tr-TR,tr;q=0.9,en-US;q=0.8,en;q=0.7',
    accept: 'text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8',
    ...extra,
  };
}

async function get(url, extra = {}, followRedirect = true) {
  const started = Date.now();
  try {
    const res = await fetch(url, {headers: headers(extra), redirect: followRedirect ? 'follow' : 'manual'});
    const buf = Buffer.from(await res.arrayBuffer());
    const text = buf.toString('utf8');
    return {
      url,
      status: res.status,
      finalUrl: res.url,
      ms: Date.now() - started,
      headers: {
        server: res.headers.get('server'),
        'content-type': res.headers.get('content-type'),
        'cf-mitigated': res.headers.get('cf-mitigated'),
        'cf-ray': res.headers.get('cf-ray'),
        'cf-cache-status': res.headers.get('cf-cache-status'),
        'cache-control': res.headers.get('cache-control'),
        'access-control-allow-origin': res.headers.get('access-control-allow-origin'),
      },
      bytes: buf.length,
      bodyHead: text.slice(0, 1200),
      body: text,
    };
  } catch (e) {
    return {url, error: String(e && e.message ? e.message : e), ms: Date.now() - started};
  }
}

function classify(html) {
  if (!html) return 'empty';
  if (/Sorry, you have been blocked/i.test(html)) return 'waf-block';
  if (/Just a moment|Checking your browser|Verifying you are human/i.test(html)) return 'cf-challenge';
  if (/YAPIM AŞAMASINDA|YAKINDA GELİYOR/i.test(html)) return 'coming-soon';
  if (/__next_f|self\.__next_f|_next\/static/i.test(html)) return 'next-app-shell';
  return 'html';
}

function extractImages(html, base) {
  const out = new Set();
  const push = raw => {
    if (!raw) return;
    let value = String(raw).trim().replace(/^["']|["']$/g, '');
    if (!value || value.startsWith('data:')) return;
    // Next.js <img>/srcset ve JSON içindeki kaçışlı yollar
    value = value.replace(/\\u0026/g, '&').replace(/\\\//g, '/');
    try {
      out.add(new URL(value, base).href);
    } catch {
      /* yoksay */
    }
  };

  // og:image, twitter:image
  for (const m of html.matchAll(/<meta[^>]+(?:property|name)=["'](?:og:image|twitter:image)["'][^>]+content=["']([^"']+)["']/gi)) push(m[1]);
  // <img src|srcset|data-src>
  for (const m of html.matchAll(/<img\b[^>]*>/gi)) {
    const tag = m[0];
    for (const attr of ['src', 'data-src', 'data-lazy-src', 'srcset']) {
      const mm = tag.match(new RegExp(`${attr}=["']([^"']+)["']`, 'i'));
      if (mm) push(attr === 'srcset' ? mm[1].split(',')[0].split(' ')[0] : mm[1]);
    }
  }
  // JSON içindeki thumb/cover alanları (Next.js RSC/ flight verisi)
  for (const m of html.matchAll(/"(?:thumb|cover|image|thumbnail|poster)"\s*:\s*"([^"]+)"/gi)) push(m[1]);
  // _next/image?url=... sarmalayıcısı
  for (const m of html.matchAll(/\/_next\/image\?url=([^&"']+)/gi)) {
    try {
      push(decodeURIComponent(m[1]));
    } catch {
      /* yoksay */
    }
  }
  return [...out].slice(0, 40);
}

function imageHosts(urls) {
  const hosts = new Set();
  for (const u of urls) {
    try {
      hosts.add(new URL(u).host);
    } catch {
      /* yoksay */
    }
  }
  return [...hosts];
}

async function probeBase(base) {
  const entry = {base, pages: [], images: [], imageChecks: [], trpc: null, sitemap: null};

  for (const path of ['/', '/seriler']) {
    const res = await get(base + path);
    entry.pages.push({
      path,
      status: res.status,
      finalUrl: res.finalUrl,
      ms: res.ms,
      kind: classify(res.body),
      bytes: res.bytes,
      headers: res.headers,
      bodyHead: res.bodyHead,
    });
    if (res.body) entry.images.push(...extractImages(res.body, base));
  }

  // Sitemap'ten gerçek bir seri adresi bul (varsa)
  const sitemap = await get(base + '/sitemap.xml');
  entry.sitemap = {
    status: sitemap.status,
    kind: classify(sitemap.body),
    bytes: sitemap.bytes,
    bodyHead: (sitemap.bodyHead || '').slice(0, 600),
  };
  const slugs = [...(sitemap.body || '').matchAll(/\/seriler\/([a-z0-9-]+)/gi)].map(m => m[1]);
  entry.sitemapFirstSlugs = [...new Set(slugs)].slice(0, 5);

  if (entry.sitemapFirstSlugs.length) {
    const slug = entry.sitemapFirstSlugs[0];
    const series = await get(`${base}/seriler/${slug}`);
    entry.pages.push({
      path: `/seriler/${slug}`,
      status: series.status,
      finalUrl: series.finalUrl,
      ms: series.ms,
      kind: classify(series.body),
      bytes: series.bytes,
      headers: series.headers,
      bodyHead: series.bodyHead,
    });
    if (series.body) entry.images.push(...extractImages(series.body, base));
  }

  // tRPC: POST + JSON gövde (1.6.16'nın kullandığı biçim)
  const trpcUrl = `${base}/api/trpc/_s4d7g2k9m0.getSeries?batch=1`;
  const trpcRes = await fetch(trpcUrl, {
    method: 'POST',
    headers: headers({'content-type': 'application/json', accept: 'application/json', referer: `${base}/seriler`}),
    body: JSON.stringify({0: {json: {sortBy: 'views', page: 1, limit: 5}}}),
  }).catch(e => ({error: String(e.message), status: 0}));
  if (trpcRes && trpcRes.status !== undefined) {
    const text = await trpcRes.text().catch(() => '');
    entry.trpc = {
      status: trpcRes.status,
      kind: classify(text),
      bytes: text.length,
      bodyHead: text.slice(0, 1500),
    };
    // API'nin döndürdüğü ilk satırın alan adları (görsel alanı ne?)
    try {
      const parsed = JSON.parse(text);
      const json = parsed?.[0]?.result?.data?.json;
      if (Array.isArray(json) && json.length) {
        entry.trpc.keys = Object.keys(json[0]);
        entry.trpc.sample = json[0];
        for (const key of ['thumb', 'cover', 'image', 'thumbnail']) {
          if (typeof json[0][key] === 'string') entry.images.push(new URL(json[0][key], base).href);
        }
      }
    } catch {
      /* JSON değil */
    }
  } else if (trpcRes?.error) {
    entry.trpc = {error: trpcRes.error};
  }

  entry.images = [...new Set(entry.images)];
  entry.imageHosts = imageHosts(entry.images);

  // Görsellerin gerçekten indirilebilir olup olmadığı: Referer'li / Referer'siz
  for (const imageUrl of entry.images.slice(0, 6)) {
    const plain = await get(imageUrl, {'accept': 'image/avif,image/webp,image/*,*/*;q=0.8'});
    const withRef = await get(imageUrl, {
      'accept': 'image/avif,image/webp,image/*,*/*;q=0.8',
      referer: `${base}/seriler`,
    });
    entry.imageChecks.push({
      url: imageUrl,
      withoutReferer: {status: plain.status, type: plain.headers?.['content-type'], bytes: plain.bytes, kind: classify(plain.body)},
      withReferer: {status: withRef.status, type: withRef.headers?.['content-type'], bytes: withRef.bytes, kind: classify(withRef.body)},
    });
  }

  return entry;
}

for (const base of BASES) {
  try {
    report.bases.push(await probeBase(base));
  } catch (e) {
    report.bases.push({base, error: String(e && e.message ? e.message : e)});
  }
}

// Raporu hafiflet: tam gövdeyi taşımıyoruz.
for (const entry of report.bases) {
  for (const page of entry.pages || []) delete page.body;
}

process.stdout.write(JSON.stringify(report, null, 2) + '\n');
