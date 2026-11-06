import fs from 'node:fs';
import vm from 'node:vm';
import assert from 'node:assert/strict';
import {createRequire} from 'node:module';
const require = createRequire(import.meta.url);
const {parseHTML} = require(process.env.SADSCANS_LINKEDOM || 'linkedom');
const source = fs.readFileSync('keiyoushi-extensions-tr/src/tr/sadscans/src/eu/kanade/tachiyomi/extension/tr/sadscans/SadscansFrontend.kt', 'utf8');
const templates = [...source.matchAll(/return """([\s\S]*?)"""\.trimIndent\(\)/g)].map(x => x[1]);
const list = templates[0].replace('$options', JSON.stringify({query:'Chainsaw "Man"',sort:'-views',page:1}));
let clock = 0;
function environment(html, path='/seriler') {
  const {window} = parseHTML(html);
  // linkedom's window proxy falls back to the Node global; reset per fixture.
  window.__sadscansFrontend = undefined;
  window.__sadscansDetails = undefined;
  window.__sadscansReader = undefined;
  let scrolls=0;
  const context = vm.createContext({window, document:window.document, location:{pathname:path,href:'https://sadscans.net'+path}, HTMLInputElement:window.HTMLInputElement,Event:window.Event,Date:{now:()=>clock},URL,Map,Set,JSON});
  window.scrollTo=()=>scrolls++;
  return {window,context,scrolls:()=>scrolls,run:script=>{clock+=2000;return vm.runInContext(script,context)}};
}
const page = environment(`<html><body><main>
<div class="dropdown-container"><button>Okunma: Hepsi</button><button>En Az</button></div>
<input placeholder="Aramak istediğiniz seriyi yazın">
<a href="https://sadscans.net/seriler/chainsaw-man"><h3>Chainsaw Man</h3><img src="https://sadscans.net/cover.jpg"></a>
<a href="https://sadscans.net/seriler/not-a-card">navigation</a>
</main></body></html>`);
let clicks=0,events=0;
page.window.document.querySelectorAll('button').forEach(b=>b.addEventListener('click',()=>clicks++));
page.window.document.querySelector('input').addEventListener('input',()=>events++);
assert.equal(page.run(list),null);
assert.equal(page.run(list),null);
assert.equal(page.window.document.querySelector('input').value,'Chainsaw "Man"');
assert.equal(events,1);
assert.equal(clicks,2);
assert.equal(page.run(list),null);
const result=JSON.parse(page.run(list));
assert.equal(result.items.length,1);
assert.equal(result.items[0].href,'/seriler/chainsaw-man');
assert.equal(result.hasMore,false);
assert(!list.includes('fetch('),'must use site controls, not unsigned fetch');
const blocked=environment('<html><body>Sorry, you have been blocked</body></html>');
assert.match(JSON.parse(blocked.run(list)).error,/engelledi/);
const reader=templates[2].replace('$id',JSON.stringify('chapter123'));
const chapter=environment(`<html><body><main>
<script type="application/ld+json">{"image":"/api/uploads/assets/series/a/chapter123/images/cover.png"}</script>
<img src="https://sadscans.net/api/uploads/assets/series/a/otherchapter/images/1.jpg">
<img src="https://sadscans.net/api/uploads/assets/series/a/chapter123/images/1.jpg">
<img src="https://sadscans.net/api/uploads/assets/series/a/chapter123/images/2.jpg">
</main></body></html>`,'/reader/series/chapter/chapter123');
assert.equal(chapter.run(reader),null);
assert.equal(chapter.run(reader),null);
const pages=JSON.parse(chapter.run(reader));
assert.equal(pages.urls.length,2);
assert(pages.urls.every(x=>!x.endsWith('cover.png')));
assert.equal(chapter.scrolls(),1);
console.log('PASS: exact frontend script: React control events, title/path extraction, hard-block detection, reader chapter scope and JSON-LD exclusion');

const detail = templates[1].replace('$page','1');
const detailPage=environment(`<html><head><meta property="og:image" content="cover.png"></head><body><h1>Chainsaw Man</h1>
<a href="https://sadscans.net/reader/chainsaw-man/1-bolum-kopek-ve-testere/hash1"><h3>1. Bölüm - Köpek ve Testere</h3></a>
<button aria-label="Sayfa 1"></button><button aria-label="Sayfa 2"></button>
</body></html>`,'/seriler/chainsaw-man');
assert.equal(detailPage.run(detail),null);
assert.equal(detailPage.run(detail),null);
const details=JSON.parse(detailPage.run(detail));
assert.equal(details.series.name,'Chainsaw Man');
assert.equal(details.chapters[0].chap_id,'hash1');
assert.equal(details.chapters[0].no,'1');
assert.equal(details.chapters[0].href,'/reader/chainsaw-man/1-bolum-kopek-ve-testere/hash1');
assert.equal(details.pagination.totalPages,2);
console.log('PASS: detail title, canonical reader URL, chapter number and pagination');
const loading=environment('<html><body><input placeholder="Aramak istediğiniz seriyi yazın"><div class="animate-pulse"></div></body></html>');
loading.window.__sadscansFrontend={phase:2,changed:0,fingerprint:''};
assert.equal(loading.run(list),null,'loading must not become an empty success');
const empty=environment('<html><body><input placeholder="Aramak istediğiniz seriyi yazın">Seçilen filtreler için sonuç bulunamadı</body></html>');
empty.window.__sadscansFrontend={phase:2,changed:0,fingerprint:'[]'};
assert.equal(JSON.parse(empty.run(list)).items.length,0);
const schemaOnly=environment('<html><body><script type="application/ld+json">{"image":"https://sadscans.net/api/uploads/assets/series/a/chapter123/images/cover.jpg"}</script></body></html>','/reader/s/c/chapter123');
assert.equal(schemaOnly.run(reader),null);
assert.equal(schemaOnly.run(reader),null,'schema-only HTML must not become reader success');
const detail2=templates[1].replace('$page','2');
const stale=environment(`<html><body><h1>Test</h1><a href="https://sadscans.net/reader/s/c/hash1"><h3>1. Bölüm</h3></a><button aria-label="Sayfa 2"></button></body></html>`,'/seriler/test');
assert.equal(stale.run(detail2),null);
assert.equal(stale.run(detail2),null,'must not relabel page 1 chapters as page 2');
stale.window.document.querySelector('a').setAttribute('href','https://sadscans.net/reader/s/c/hash2');
stale.window.document.querySelector('h3').textContent='2. Bölüm';
assert.equal(stale.run(detail2),null);
assert.equal(JSON.parse(stale.run(detail2)).chapters[0].chap_id,'hash2');
console.log('PASS: loading vs empty, schema-only rejection, stale chapter-pagination rejection');
