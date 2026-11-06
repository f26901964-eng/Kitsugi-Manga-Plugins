// Diagnostic: reader access + pagination markup.
const UA = 'Mozilla/5.0 (Linux; Android 13; SM-S918B) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Mobile Safari/537.36';
const BASE_HEADERS = { 'User-Agent': UA, 'Accept-Language': 'tr-TR,tr;q=0.9' };
async function get(u, headers = {}) {
  const r = await fetch(u, { headers: { ...BASE_HEADERS, ...headers }, redirect: 'follow' });
  return { status: r.status, text: await r.text(), headers: Object.fromEntries(r.headers) };
}
const show = (label, text, needle, before = 300, after = 1200) => {
  const i = text.indexOf(needle);
  console.log(`\n===== ${label} (${needle}) index=${i} =====`);
  if (i < 0) return;
  console.log(text.slice(Math.max(0, i - before), i + after));
};

// 1) Listing pagination markup
const list = await get('https://okutoon.com/tur');
show('pagination block', list.text, 'pagination', 200, 1500);

// 2) Reader: plain request, then with browser-ish headers
const url = 'https://okutoon.com/solo-leveling/bolum-200';
const plain = await get(url);
console.log('\n### reader plain:', plain.status, 'bytes', plain.text.length);
console.log('server:', plain.headers['server'], 'cf-ray:', plain.headers['cf-ray'], 'content-type:', plain.headers['content-type']);
console.log('--- first 700 chars ---');
console.log(plain.text.slice(0, 700));

const browserish = await get(url, {
  Accept: 'text/html,application/xhtml+xml,application/xml;q=0.9,image/avif,image/webp,*/*;q=0.8',
  Referer: 'https://okutoon.com/solo-leveling',
  'Upgrade-Insecure-Requests': '1',
  'sec-ch-ua': '"Chromium";v="124", "Google Chrome";v="124"',
  'sec-ch-ua-mobile': '?1',
  'sec-ch-ua-platform': '"Android"',
});
console.log('\n### reader browser-ish:', browserish.status, 'bytes', browserish.text.length);
if (browserish.status === 200) {
  show('readerPages', browserish.text, 'readerPages');
  show('reader-page', browserish.text, 'reader-page');
} else {
  console.log(browserish.text.slice(0, 500));
}

// 3) Older chapter, in case the newest is restricted
const old = await get('https://okutoon.com/solo-leveling/bolum-1', { Referer: 'https://okutoon.com/solo-leveling' });
console.log('\n### chapter 1:', old.status, 'bytes', old.text.length);
if (old.status === 200) show('readerPages (bolum-1)', old.text, 'readerPages');
