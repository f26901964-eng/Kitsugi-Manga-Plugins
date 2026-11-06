// Diagnostic round 3: can we reach the reader markup at all?
const UA = 'Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Safari/537.36';
const show = (label, text, needle, before = 300, after = 1200) => {
  const i = text.indexOf(needle);
  console.log(`\n===== ${label} (${needle}) index=${i} =====`);
  if (i < 0) return;
  console.log(text.slice(Math.max(0, i - before), i + after));
};

async function probe(label, url, headers = {}) {
  const r = await fetch(url, { headers: { 'User-Agent': UA, 'Accept-Language': 'tr-TR,tr;q=0.9', ...headers } });
  const text = await r.text();
  console.log(`${label}: ${r.status} bytes=${text.length} server=${r.headers.get('server')} cf-mitigated=${r.headers.get('cf-mitigated')}`);
  return { status: r.status, text };
}

// 1) Is the image CDN open?
const img = await fetch('https://om-cdn.okutoon.com/chapters/389/39635/yalniz-yukselis_bolum-200_001.webp', {
  headers: { 'User-Agent': UA, Referer: 'https://okutoon.com/' },
});
const buf = Buffer.from(await img.arrayBuffer());
console.log('cdn image:', img.status, buf.length, 'bytes', buf.subarray(0, 4).toString('ascii'), img.headers.get('content-type'));

// 2) Which reader-ish paths are reachable?
const paths = [
  'https://okutoon.com/solo-leveling/bolum-200',
  'https://okutoon.com/solo-leveling/bolum-1',
  'https://okutoon.com/solo-leveling',
];
for (const url of paths) {
  const r = await probe(url, url, { Referer: 'https://okutoon.com/' });
  if (r.status === 200) {
    show(`reader ${url}`, r.text, 'readerPages');
    show(`reader ${url} reader-page`, r.text, 'reader-page', 100, 600);
  }
}

// 3) Any JSON/API endpoint the reader could use?
for (const candidate of [
  'https://okutoon.com/api/chapter/39635',
  'https://okutoon.com/api/chapters/39635',
  'https://okutoon.com/solo-leveling/bolum-200?format=json',
]) {
  const r = await probe('api', candidate, { Accept: 'application/json', Referer: 'https://okutoon.com/solo-leveling/bolum-200' });
  if (r.status === 200) console.log(r.text.slice(0, 400));
}
