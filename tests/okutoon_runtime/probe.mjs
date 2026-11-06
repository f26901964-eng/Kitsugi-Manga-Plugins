// Diagnostic: prints the markup around the hooks OkuToon.kt depends on.
const UA = 'Mozilla/5.0 (Linux; Android 13; SM-S918B) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Mobile Safari/537.36';
async function get(u) {
  const r = await fetch(u, { headers: { 'User-Agent': UA, 'Accept-Language': 'tr-TR,tr;q=0.9' } });
  return { status: r.status, text: await r.text() };
}
const show = (label, text, needle, before = 300, after = 1400) => {
  const i = text.indexOf(needle);
  console.log(`\n===== ${label} (${needle}) index=${i} =====`);
  if (i < 0) return;
  console.log(text.slice(Math.max(0, i - before), i + after));
};

// Reader: the one selector we have not seen yet.
const chapter = await get('https://okutoon.com/solo-leveling/bolum-200');
console.log('\n### chapter status', chapter.status, 'bytes', chapter.text.length);
show('readerPages', chapter.text, 'readerPages', 400, 1600);
show('reader-page', chapter.text, 'reader-page', 200, 1200);
show('pages payload', chapter.text, 'pages', 200, 800);

// Listing pagination: does the server answer page 2 with different cards?
const p1 = await get('https://okutoon.com/tur');
const p2 = await get('https://okutoon.com/tur?sayfa=2');
const titles = (t) => [...t.matchAll(/class="series-card-title">([^<]+)</g)].map((m) => m[1]);
const t1 = titles(p1.text);
const t2 = titles(p2.text);
console.log('\n### listing page1 cards', t1.length, 'page2 cards', t2.length, 'same?', JSON.stringify(t1) === JSON.stringify(t2));
console.log('page1:', t1.slice(0, 4).join(' | '));
console.log('page2:', t2.slice(0, 4).join(' | '));
const more = ['Sonraki', 'pagination', 'load-more', 'Daha fazla', 'data-page', 'data-has-more'];
for (const needle of more) {
  const i = p1.text.indexOf(needle);
  console.log(`listing contains "${needle}": index=${i}`);
}
if (p1.text.includes('data-page')) show('listing data-page', p1.text, 'data-page', 200, 400);
