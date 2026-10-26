// Anonymous public-site audit. Never logs cookies/tokens, unlocks chapters, or bypasses a challenge.
import { createHash, randomUUID } from 'node:crypto';
import { mkdir, readFile, writeFile } from 'node:fs/promises';
import { fileURLToPath } from 'node:url';
import { resolve, join } from 'node:path';
import { chromium } from 'playwright';
import sharp from 'sharp';

const SITE = 'https://golgebahcesi.com';
const API = 'https://api.golgebahcesi.com/api';
const out = resolve(process.env.AUDIT_OUTPUT || '.audit/golgebahcesi');
const USER_AGENT = 'Mozilla/5.0 (Linux; Android 10; K) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Mobile Safari/537.36';
const report = {
    observedAt: new Date().toISOString(),
    status: 'running',
    androidVerified: false,
    checks: [],
    assets: [],
    series: [],
    note: 'Anonymous network/Chromium audit, not an Android APK/device test. No challenge or access lock is bypassed.',
};
const sha256 = (bytes) => createHash('sha256').update(bytes).digest('hex');
const check = (name, passed, detail) => {
    report.checks.push({ name, passed, detail });
    if (!passed) throw new Error(`${name}: ${detail}`);
};

async function response(url, maximum = 8 * 1024 * 1024) {
    const result = await fetch(url, {
        headers: { 'User-Agent': USER_AGENT, Referer: `${SITE}/`, Origin: SITE },
        signal: AbortSignal.timeout(25_000),
    });
    if (!result.ok) throw new Error(`HTTP ${result.status} (${new URL(url).pathname})`);
    if (Number(result.headers.get('content-length')) > maximum) throw new Error('Oversized response');
    const chunks = [];
    let size = 0;
    for await (const chunk of result.body) {
        size += chunk.length;
        if (size > maximum) throw new Error('Oversized response');
        chunks.push(chunk);
    }
    return Buffer.concat(chunks);
}

async function json(url, name) {
    const bytes = await response(url);
    const value = JSON.parse(bytes.toString());
    // Only public metadata/URLs are saved, never session requests or authentication responses.
    await writeFile(join(out, `${name}.json`), JSON.stringify(value, null, 2));
    return value;
}

async function html(url, name) {
    const bytes = await response(url);
    await writeFile(join(out, `${name}.html`), bytes);
    return bytes.toString();
}

async function scriptsFrom(htmlSources) {
    const urls = new Set();
    for (const source of htmlSources) {
        for (const match of source.matchAll(/<script\b[^>]*\bsrc=["']([^"']+)["']/gi)) {
            const url = new URL(match[1].replaceAll('&amp;', '&'), SITE);
            if (url.origin === SITE && url.pathname.startsWith('/_next/static/') && url.pathname.endsWith('.js')) urls.add(url.href);
        }
    }
    check('Next.js script references exist in actual HTML', urls.size > 0, `${urls.size} first-party scripts`);
    let total = 0;
    for (const [index, url] of [...urls].slice(0, 40).entries()) {
        const bytes = await response(url, 4 * 1024 * 1024);
        total += bytes.length;
        if (total > 24 * 1024 * 1024) throw new Error('JavaScript capture limit exceeded');
        const file = `js/${String(index).padStart(2, '0')}-${new URL(url).pathname.split('/').at(-1)}`;
        await writeFile(join(out, file), bytes);
        const text = bytes.toString();
        const markers = ['deliverySystem', 'imageEnc', 'rdr_diag_ovr', 'data-reader-page', 'createImageBitmap', 'turnstile', '/chapters/', '/series/']
            .filter((marker) => text.includes(marker));
        report.assets.push({ url, file, bytes: bytes.length, sha256: sha256(bytes), markers });
    }
}

async function readerAudit(ref, expectedCount) {
    const browser = await chromium.launch({ headless: true });
    const context = await browser.newContext({ userAgent: USER_AGENT, viewport: { width: 1080, height: 1920 } });
    const page = await context.newPage();
    const bridge = `kitsugi${randomUUID().replaceAll('-', '')}`;
    const channel = `kitsugi${randomUUID().replaceAll('-', '')}`;
    const binding = `kitsugi${randomUUID().replaceAll('-', '')}`;
    const images = new Map();
    const errors = [];
    let count = 0;
    const consoleErrors = [];
    page.on('pageerror', (error) => consoleErrors.push(String(error.message).slice(0, 300)));
    await page.exposeFunction(binding, async (message) => {
        if (message.startsWith('count:')) {
            count = Number(message.slice(6));
            return;
        }
        if (message.startsWith('error:')) { errors.push(message.slice(6)); return; }
        if (!message.startsWith('page:')) { errors.push('Unknown bridge message'); return; }
        const comma = message.indexOf(',');
        const index = Number(message.slice(5, comma));
        if (!Number.isInteger(index) || index < 0 || index >= expectedCount || message.length > 32 * 1024 * 1024) {
            errors.push('Invalid page message'); return;
        }
        const data = message.slice(comma + 1);
        if (!/^data:[^,]*;base64,/.test(data)) { errors.push('Invalid image data URL'); return; }
        const bytes = Buffer.from(data.slice(data.indexOf(',') + 1), 'base64');
        // Decode ALL page pixels, not just an HTTP status or file signature.
        const metadata = await sharp(bytes).metadata();
        await sharp(bytes).stats();
        if (!metadata.width || !metadata.height) { errors.push('Image has no dimensions'); return; }
        images.set(index, { index, width: metadata.width, height: metadata.height, format: metadata.format, bytes: bytes.length, sha256: sha256(bytes) });
    });
    const assets = fileURLToPath(new URL('../../keiyoushi-extensions-tr/src/tr/golgebahcesi/assets/', import.meta.url));
    const worker = `(${await readFile(join(assets, 'worker_hook.js'), 'utf8')})(${JSON.stringify(channel)});\n`;
    const capture = await readFile(join(assets, 'reader_capture.js'), 'utf8');
    const bootstrap = `if (window === window.top && location.origin === ${JSON.stringify(SITE)}) {
        const bridge = { postMessage: (message) => window[${JSON.stringify(binding)}](message) };
        (${capture})(bridge, ${JSON.stringify(channel)}, ${JSON.stringify(worker)});
    }`;
    await page.addInitScript({ content: bootstrap });
    try {
        await page.goto(`${SITE}/manga/${encodeURIComponent(ref.seriesSlug)}/bolum/${encodeURIComponent(ref.slug)}`, { waitUntil: 'domcontentloaded', timeout: 45_000 });
        const deadline = Date.now() + 90_000;
        while (Date.now() < deadline && !errors.length && images.size !== expectedCount) await page.waitForTimeout(500);
        const text = (await page.locator('body').innerText()).slice(0, 30_000);
        const challenge = /Güvenlik Doğrulaması|İçeriği görmek için.*doğrulamayı|Checking your Browser/i.test(text);
        await writeFile(join(out, 'reader-rendered.html'), await page.content());
        const result = {
            chapterId: ref.id,
            expectedCount,
            bridgeCount: count,
            decodedCount: images.size,
            challengeVisible: challenge,
            errors,
            pageErrors: consoleErrors.slice(0, 10),
            images: [...images.values()].sort((a, b) => a.index - b.index),
            passed: count === expectedCount && images.size === expectedCount && errors.length === 0,
        };
        report.reader = result;
        // Only complete capture is green. A challenge, 18 encrypted URLs or partial images are NOT success.
        if (!result.passed) {
            report.status = challenge ? 'blocked_by_challenge' : 'reader_failed';
            throw new Error(challenge ? 'Official reader requires interactive site verification' : `Reader captured ${images.size}/${expectedCount} images: ${errors.join('; ')}`);
        }
        check('All official-reader images decode in correct page-index order', result.passed, `${images.size}/${expectedCount}`);
    } finally {
        await context.close();
        await browser.close();
    }
}

try {
    await mkdir(join(out, 'js'), { recursive: true });
    const home = await html(`${SITE}/`, 'home');
    const first = await json(`${API}/series?page=1&limit=24&sort=popular`, 'popular-page-1');
    const second = await json(`${API}/series?page=2&limit=24&sort=popular`, 'popular-page-2');
    check('Popular page 1 has real data and pagination', first.data?.length > 0 && first.pagination?.currentPage === 1, `${first.data?.length} results`);
    check('Popular page 2 is distinct', second.data?.length > 0 && second.pagination?.currentPage === 2 && second.data.some((item) => !first.data.some((other) => item.slug === other.slug)), `${second.data?.length} results`);
    const sources = [];
    const covers = [];
    for (const slug of ['mairimashita-iruma-kun', 'golge-kole']) {
        const details = await json(`${API}/series/${slug}`, `${slug}-details`);
        const search = await json(`${API}/series?${new URLSearchParams({ page: '1', limit: '24', search: details.title })}`, `${slug}-search`);
        const chapters = await json(`${API}/series/${slug}/chapters`, `${slug}-chapters`);
        check(`${slug}: exact series found by real title search`, details.slug === slug && search.data?.some((item) => item.slug === slug), details.title);
        check(`${slug}: nonempty chapters belong to this series`, Array.isArray(chapters) && chapters.length > 0 && chapters.every((item) => item.seriesSlug === slug), `${chapters.length} chapters`);
        const selected = chapters.filter((item) => !item.isLocked).sort((a, b) => b.number - a.number)[0];
        if (!selected) throw new Error(`${slug}: no public accessible chapter`);
        const content = await json(`${API}/chapters/${selected.id}`, `${slug}-chapter-content`);
        check(`${slug}: chapter identity and readable page metadata`, content.id === selected.id && content.slug === selected.slug && content.seriesSlug === slug && !content.isLocked && content.pages?.length > 0, `${content.pages?.length} pages, ${content.deliverySystem}, imageEnc=${content.imageEnc}`);
        const seriesHtml = await html(`${SITE}/manga/${slug}`, `${slug}-series`);
        const chapterHtml = await html(`${SITE}/manga/${slug}/bolum/${selected.slug}`, `${slug}-reader`);
        check(`${slug}: HTML references selected series and chapter`, seriesHtml.includes(slug) && chapterHtml.includes(selected.slug), selected.slug);
        const summary = { slug, title: details.title, type: details.type, chapterCount: chapters.length, selectedChapterId: selected.id, selectedChapterNumber: selected.number, pageCount: content.pages.length, deliverySystem: content.deliverySystem, imageEnc: content.imageEnc };
        report.series.push(summary);
        covers.push({ slug, url: details.coverImage || details.cover, summary });
        sources.push(seriesHtml, chapterHtml);
        if (slug === 'mairimashita-iruma-kun') report.selected = { ref: selected, count: content.pages.length };
    }
    await scriptsFrom([home, ...sources]);
    for (const { slug, url, summary } of covers) {
        const coverBytes = await response(url);
        const cover = await sharp(coverBytes).metadata();
        check(`${slug}: real cover decodes`, cover.width > 0 && cover.height > 0, `${cover.width}x${cover.height} ${cover.format}`);
        summary.cover = { width: cover.width, height: cover.height, format: cover.format, sha256: sha256(coverBytes) };
    }
    await readerAudit(report.selected.ref, report.selected.count);
    report.status = 'browser_chain_passed';
} catch (error) {
    if (report.status === 'running') report.status = 'failed';
    report.error = String(error.message);
} finally {
    delete report.selected;
    await mkdir(out, { recursive: true });
    await writeFile(join(out, 'report.json'), JSON.stringify(report, null, 2));
    console.log(JSON.stringify(report, null, 2));
    if (report.status !== 'browser_chain_passed') process.exitCode = 2;
}
