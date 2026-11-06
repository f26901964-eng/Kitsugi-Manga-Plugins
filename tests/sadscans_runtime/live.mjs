import fs from 'node:fs';
import { chromium } from 'playwright';
const source = fs.readFileSync('keiyoushi-extensions-tr/src/tr/sadscans/src/eu/kanade/tachiyomi/extension/tr/sadscans/SadscansBrowser.kt', 'utf8');
const template = source.split('return """')[1].split('""".trimIndent()')[0];
const report = {passed: false, scope: 'Chromium browser transport; not Android WebView', steps: []};
let browser;
try {
  browser = await chromium.launch({headless: true});
  const page = await browser.newPage();
  await page.goto('https://sadscans.net/seriler', {waitUntil: 'domcontentloaded', timeout: 45000});
  async function call(proc, input) {
    const path = '/api/trpc/' + proc + '?' + new URLSearchParams({batch: '1', input: JSON.stringify({'0': {json: input}})});
    const script = template.replace('$target', JSON.stringify(path));
    const step = {proc, input}; report.steps.push(step);
    const deadline = Date.now() + 35000;
    while (Date.now() < deadline) {
      const raw = await page.evaluate(script);
      if (raw) {
        const result = JSON.parse(raw);
        step.status = result.status; step.bodyPreview = result.body.slice(0, 1500);
        if (result.status !== 200) throw new Error(`Browser API HTTP ${result.status}`);
        const data = JSON.parse(result.body)[0].result.data.json;
        if (Array.isArray(data)) step.count = data.length;
        return data;
      }
      await page.waitForTimeout(1000);
    }
    step.pageTitle = await page.title();
    step.pagePreview = (await page.locator('body').innerText()).slice(0, 1500);
    throw new Error('Browser verification timeout');
  }
  const list = await call('_s4d7g2k9m0.getSeries', {page: 1, limit: 15, sortBy: '-views'});
  const item = list.find(x => x.href && x.name);
  if (!item) throw new Error('Empty list');
  const found = await call('_s4d7g2k9m0.getSeries', {page: 1, limit: 15, sortBy: '-views', search: item.name});
  if (!found.some(x => x.href === item.href)) throw new Error('Search missing selected series');
  const detail = await call('_n3p6q1v8x5.getSeriesData', {sef: item.href.split('/').pop(), page: 1, limit: 10});
  if (!detail.series || !detail.chapters?.length) throw new Error('No details/chapters');
  report.passed = true;
} catch (error) {
  report.error = String(error);
} finally {
  await browser?.close();
  fs.mkdirSync('reports', {recursive: true});
  fs.writeFileSync('reports/sadscans-browser-live.json', JSON.stringify(report, null, 2));
  console.log(JSON.stringify(report, null, 2));
}
process.exitCode = report.passed ? 0 : 1;
