// Anonymous public-reader acceptance test. No cookies, CAPTCHA bypass or paid access.
import { createHash } from 'node:crypto';
import { mkdir, writeFile } from 'node:fs/promises';
import { resolve, join } from 'node:path';
import { gzipSync } from 'node:zlib';
import sharp from 'sharp';
import { chromium } from 'playwright';

const SITE = 'https://mangadenizi.net';
const out = resolve(process.env.AUDIT_OUTPUT || '.audit/mangadenizi');
const headers = { 'User-Agent': 'Mozilla/5.0 (Linux; Android 10) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Mobile Safari/537.36', Referer: `${SITE}/`, Origin: SITE };
const hash = (bytes) => createHash('sha256').update(bytes).digest('hex');
const report = { observedAt: new Date().toISOString(), status: 'running', androidVerified: false, checks: [], series: [], images: [], browser: [] };
function check(name, condition, detail) {
    report.checks.push({ name, passed: Boolean(condition), detail });
    if (!condition) throw Error(`${name}: ${detail}`);
}
async function fetchBytes(url, referer = `${SITE}/`) {
    const res = await fetch(url, { headers: { ...headers, Referer: referer }, signal: AbortSignal.timeout(30_000) });
    if (!res.ok) throw Error(`HTTP ${res.status}: ${new URL(url).hostname}${new URL(url).pathname}`);
    const parts = []; let count = 0;
    for await (const part of res.body) {
        count += part.length;
        if (count > 32 * 1024 * 1024) throw Error('Response too large');
        parts.push(part);
    }
    return Buffer.concat(parts);
}
async function json(path, name) {
    const bytes = await fetchBytes(`${SITE}${path}`);
    const value = JSON.parse(bytes);
    await writeFile(join(out, `${name}.json`), JSON.stringify(value, null, 2));
    return value;
}
function shuffle(size, seed) {
    let state = seed >>> 0 || 2463534242;
    const result = Array.from({ length: size }, (_, index) => index);
    for (let n = size - 1; n > 0; n--) {
        state = (state ^ (state << 13)) >>> 0;
        state = (state ^ (state >>> 17)) >>> 0;
        state = (state ^ (state << 5)) >>> 0;
        const i = state % (n + 1);
        [result[n], result[i]] = [result[i], result[n]];
    }
    return result;
}
function restorePixels(data, width, height, grid, seed) {
    const count = Math.max(1, Math.min(grid, width, height));
    const slices = (total) => Array.from({ length: count }, (_, i) => ({ offset: Math.floor(i * total / count), length: Math.floor((i + 1) * total / count) - Math.floor(i * total / count) }));
    const xs = slices(width), ys = slices(height);
    const columns = shuffle(count, (seed ^ 2246822507) >>> 0), rows = shuffle(count, (seed ^ 2654435769) >>> 0);
    const result = Buffer.alloc(data.length);
    let sy = 0;
    for (const row of rows) {
        let sx = 0;
        for (const column of columns) {
            for (let dy = 0; dy < ys[row].length; dy++) {
                const start = ((sy + dy) * width + sx) * 4;
                data.copy(result, ((ys[row].offset + dy) * width + xs[column].offset) * 4, start, start + xs[column].length * 4);
            }
            sx += xs[column].length;
        }
        sy += ys[row].length;
    }
    return result;
}

let browser;
try {
    await mkdir(out, { recursive: true });
    const first = (await json('/api/v1/web/manga?sort=popular&page=1', 'popular-1')).data;
    const second = (await json('/api/v1/web/manga?sort=popular&page=2', 'popular-2')).data;
    report.catalogueTotal = first.totalCount ?? first.manga.total;
    check('Catalogue and next page are real', first.manga.data.length > 0 && second.manga.data.some((m) => !first.manga.data.some((n) => n.slug === m.slug)), `${report.catalogueTotal} titles`);
    const references = [];
    for (const [slug, chapter] of [['black-clover', '392'], ['shadow-slave', '07']]) {
        const details = (await json(`/api/v1/web/manga/${slug}`, `${slug}-details`)).data.manga;
        const search = (await json(`/api/v1/web/manga?q=${encodeURIComponent(details.title)}&page=1`, `${slug}-search`)).data.manga;
        check(`${slug}: search and details agree`, details.slug === slug && search.data.some((m) => m.slug === slug), details.title);
        check(`${slug}: full chapters`, details.chapters?.length > 0 && new Set(details.chapters.map((c) => c.id)).size === details.chapters.length, `${details.chapters?.length} chapters`);
        const reader = await json(`/api/v1/reader/${slug}/${chapter}`, `${slug}-reader`);
        check(`${slug}: correct chapter, complete page numbering`, reader.manga.slug === slug && reader.chapter.slug === chapter && reader.chapter.pages_count === reader.pages.length && reader.pages.every((p, i) => p.page_number === i + 1), `${reader.pages.length} pages`);
        const cover = await fetchBytes(details.cover_url);
        const coverMeta = await sharp(cover).metadata(); await sharp(cover).stats();
        const summary = { slug, title: details.title, chapter, chapterCount: details.chapters.length, pageCount: reader.pages.length, decoded: 0, cover: { width: coverMeta.width, height: coverMeta.height, sha256: hash(cover) } };
        report.series.push(summary);
        for (const [index, page] of reader.pages.entries()) {
            const bytes = await fetchBytes(page.image_url, `${SITE}/read/${slug}/${chapter}`);
            const { data, info } = await sharp(bytes).ensureAlpha().raw().toBuffer({ resolveWithObject: true });
            const { method, grid, seed } = page.scramble || {};
            if (method && method !== 'tiled-v1') throw Error(`Unsupported image layout: ${method}`);
            const pixels = method === 'tiled-v1' ? restorePixels(data, info.width, info.height, grid, seed) : data;
            const restored = await sharp(pixels, { raw: { width: info.width, height: info.height, channels: 4 } }).png().toBuffer();
            const stats = await sharp(restored).stats();
            check(`${slug}: page ${index + 1} decodes`, info.width > 0 && info.height > 0 && stats.channels.some((channel) => channel.min !== channel.max), `${info.width}x${info.height}`);
            report.images.push({ slug, index, pageNumber: page.page_number, width: info.width, height: info.height, layout: method || 'plain', downloadedBytes: bytes.length, restoredSha256: hash(restored), pixelSha256: hash(pixels) });
            summary.decoded++;
            // Private CI scratch only, never included in Git or public evidence artifacts.
            if ([0, Math.floor(reader.pages.length / 2), reader.pages.length - 1].includes(index)) {
                await writeFile(join(out, `${slug}-${index}.png`), restored);
                references.push({ slug, chapter, index, width: info.width, height: info.height });
            }
        }
    }
    browser = await chromium.launch({ headless: true });
    for (const slug of ['black-clover', 'shadow-slave']) {
        const summary = report.series.find((s) => s.slug === slug);
        const page = await browser.newPage({ viewport: { width: 1100, height: 1800 } });
        await page.goto(`${SITE}/read/${slug}/${summary.chapter}`, { waitUntil: 'domcontentloaded', timeout: 45_000 });
        await page.waitForTimeout(4000);
        const body = await page.locator('body').innerText();
        check(`${slug}: public reader is not an authentication/challenge page`, !/Güvenlik Doğrulaması|Checking your browser|Just a moment/i.test(body) && body.includes(summary.title), (await page.title()).slice(0, 120));
        const canvases = await page.locator('canvas').count();
        const images = await page.locator('img').count();
        report.browser.push({ slug, canvasCount: canvases, imageCount: images, title: await page.title() });
        await writeFile(join(out, `${slug}-reader.html`), await page.content());
        await page.close();
    }
    report.status = 'all_sample_images_decoded';
} catch (error) {
    report.status = 'failed'; report.error = String(error.message);
} finally {
    if (browser) await browser.close();
    await mkdir(out, { recursive: true });
    await writeFile(join(out, 'report.json'), JSON.stringify(report, null, 2));
    console.log(JSON.stringify(report, null, 2));
    if (process.env.GITHUB_ACTIONS === 'true') {
        const data = gzipSync(Buffer.from(JSON.stringify(report)), { level: 9 });
        if (data.length > 20_000) throw Error('Evidence too large');
        for (let start = 0; start < data.length; start += 2000) {
            console.log('::notice title=MangaDeniziEvidence::' + JSON.stringify({ part: start / 2000, totalBytes: data.length, base64: data.subarray(start, start + 2000).toString('base64') }));
        }
    }
    if (report.status !== 'all_sample_images_decoded') process.exitCode = 2;
}
