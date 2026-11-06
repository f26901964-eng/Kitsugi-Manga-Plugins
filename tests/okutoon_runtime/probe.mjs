// Diagnostic: prints the markup around the hooks OkuToon.kt depends on.
const UA = 'Mozilla/5.0 (Linux; Android 13; SM-S918B) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Mobile Safari/537.36';
async function get(u) {
  const r = await fetch(u, { headers: { 'User-Agent': UA, 'Accept-Language': 'tr-TR,tr;q=0.9' } });
  return { status: r.status, text: await r.text() };
}
const show = (label, text, needle, before = 400, after = 1100) => {
  const i = text.indexOf(needle);
  console.log(`\n===== ${label} (${needle}) index=${i} =====`);
  if (i < 0) return;
  console.log(text.slice(Math.max(0, i - before), i + after));
};

const series = await get('https://okutoon.com/solo-leveling');
console.log('series status', series.status, 'bytes', series.text.length);
show('chapter item', series.text, 'chapter-item');
show('chapter-date', series.text, 'chapter-date', 200, 300);

const search = await get('https://okutoon.com/tur?q=solo+leveling');
console.log('\nsearch status', search.status, 'bytes', search.text.length);
show('series card', search.text, 'series-card', 200, 900);
show('pagination', search.text, 'pagination', 200, 700);
show('sayfa', search.text, 'sayfa=2', 300, 300);
// touch 2026-11-06T11:02:46Z
// touch 1793963051
