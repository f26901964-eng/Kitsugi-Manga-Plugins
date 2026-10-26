import assert from 'node:assert/strict';
import { readFileSync } from 'node:fs';
import test from 'node:test';
import vm from 'node:vm';

const assets = new URL('../keiyoushi-extensions-tr/src/tr/golgebahcesi/assets/', import.meta.url);
const readerScript = readFileSync(new URL('reader_capture.js', assets), 'utf8');
const workerScript = readFileSync(new URL('worker_hook.js', assets), 'utf8');

function reader({ previous = null, readError = false, nested = false } = {}) {
    const messages = [];
    const channels = [];
    const storage = new Map(previous === null ? [] : [['rdr_diag_ovr', previous]]);
    const visibility = [];
    let now = 1000;
    let tick = () => {};
    let cleared = false;
    let pages = [];
    class Blob {
        constructor(parts = [], options = {}) { this.parts = parts; this.type = options.type; }
    }
    class IntersectionObserver {
        constructor(callback) { this.callback = callback; }
        observe() {}
    }
    class BroadcastChannel {
        constructor() { channels.push(this); }
        close() { this.closed = true; }
    }
    class FileReader {
        readAsDataURL() {
            if (readError) { this.onerror(); return; }
            this.result = 'data:image/webp;base64,QUJD';
            this.onload();
        }
    }
    const sandbox = {
        Blob, IntersectionObserver, BroadcastChannel, FileReader,
        Date: { now: () => now },
        localStorage: {
            getItem: (key) => storage.get(key) ?? null,
            setItem: (key, value) => storage.set(key, value),
            removeItem: (key) => storage.delete(key),
        },
        document: { querySelectorAll: () => pages },
        setInterval: (callback) => { tick = callback; return 42; },
        clearInterval: (id) => { assert.equal(id, 42); cleared = true; },
    };
    sandbox.window = sandbox;
    sandbox.top = nested ? {} : sandbox;
    const context = vm.createContext(sandbox);
    vm.runInContext(`(${readerScript})`, context)({ postMessage: (m) => messages.push(m) }, 'channel', '// hook');
    const emit = (data) => channels[0].onmessage({ data });
    return {
        sandbox, messages, channels, storage, visibility, emit,
        page: (index) => emit({ index, image: new Blob() }),
        tick: (advance = 0) => { now += advance; tick(); },
        cleared: () => cleared,
        mount: (count) => {
            pages = Array.from({ length: count }, (_, index) => ({ index, contains: (element) => element.index === index }));
            const observer = new sandbox.IntersectionObserver((entries) => {
                entries.forEach((entry) => visibility.push([entry.target.index, entry.isIntersecting]));
            });
            pages.forEach((page) => observer.observe(page));
        },
    };
}

function worker() {
    const messages = [];
    let onMessage;
    class Blob { constructor(size) { this.size = size; } }
    class Response {
        constructor(url, length) { this.url = url; this.length = length; }
        arrayBuffer() { return Promise.resolve(new ArrayBuffer(this.length)); }
    }
    const sandbox = {
        Blob, Response,
        BroadcastChannel: class { postMessage(data) { messages.push(data); } },
        addEventListener: (_, callback) => { onMessage = callback; },
        createImageBitmap: () => Promise.resolve({}),
    };
    sandbox.self = sandbox;
    vm.runInNewContext(`(${workerScript})`, sandbox)('channel');
    return {
        messages,
        store: (urls) => onMessage({ data: { type: 'store', urls } }),
        fetch: async (url, length) => { await new sandbox.Response(url, length).arrayBuffer(); },
        decode: (size) => sandbox.createImageBitmap(new Blob(size)),
    };
}

test('reader ignores subframes without changing storage or creating a bridge channel', () => {
    const r = reader({ nested: true });
    assert.equal(r.channels.length, 0);
    assert.equal(r.storage.size, 0);
});

test('count may arrive before React mounts the page elements', () => {
    const r = reader();
    r.emit({ count: 2 });
    r.tick();
    assert.deepEqual(r.messages, ['count:2']);
    r.mount(2);
    r.tick();
    assert.deepEqual(r.visibility, [[0, true]]);
});

test('reader decodes only one page at a time and throttles repeated visibility notifications', () => {
    const r = reader();
    r.mount(3);
    r.emit({ count: 3 });
    r.tick();
    r.tick(250);
    assert.deepEqual(r.visibility, [[0, true]]);
    r.page(0);
    r.tick(250);
    assert.deepEqual(r.visibility, [[0, true], [0, false], [1, true]]);
});

test('prefetch stays disabled until all images are captured, then previous settings are restored', () => {
    const previous = '{"other":"setting"}';
    const r = reader({ previous });
    r.emit({ count: 2 });
    r.page(0);
    assert.equal(r.storage.get('rdr_diag_ovr'), '{"prefetch":false}');
    r.page(1);
    assert.equal(r.storage.get('rdr_diag_ovr'), previous);
    assert.equal(r.cleared(), true);
    assert.equal(r.channels[0].closed, true);
});

test('reader keeps original page indices despite out-of-order delivery, ignoring duplicates', () => {
    const r = reader();
    r.emit({ count: 3 });
    r.page(2);
    r.page(2);
    r.page(0);
    r.page(1);
    assert.deepEqual(r.messages.map((m) => m.split(',')[0]), ['count:3', 'page:2', 'page:0', 'page:1']);
    assert.equal(r.storage.has('rdr_diag_ovr'), false);
});

for (const count of [0, -1, 1.5, 2001, '2']) {
    test(`reader rejects invalid count ${JSON.stringify(count)}`, () => {
        const r = reader();
        r.emit({ count });
        assert.match(r.messages[0], /^error:/);
        assert.equal(r.cleared(), true);
    });
}

test('reader rejects changing counts and invalid image indices', () => {
    const r = reader();
    r.emit({ count: 2 });
    r.emit({ count: 3 });
    assert.match(r.messages.at(-1), /^error:Reader page count changed/);
    const second = reader();
    second.emit({ count: 2 });
    second.page(2);
    assert.match(second.messages.at(-1), /^error:Invalid reader page/);
});

test('FileReader failure produces one error and cleans up', () => {
    const r = reader({ readError: true });
    r.emit({ count: 1 });
    r.page(0);
    r.page(0);
    assert.equal(r.messages.filter((m) => m.startsWith('error:')).length, 1);
    assert.equal(r.storage.has('rdr_diag_ovr'), false);
});

test('a missing page times out once rather than failing immediately or waiting forever', () => {
    const r = reader();
    r.emit({ count: 1 });
    r.tick();
    r.tick(30001);
    r.tick(30001);
    assert.deepEqual(r.messages, ['count:1', 'error:Page 1 did not load']);
});

test('worker captures AES-GCM decoded bytes and preserves the fetch index', async () => {
    const w = worker();
    w.store(['https://site/0', 'https://site/1']);
    await w.fetch('https://site/1', 228);
    await w.decode(200);
    await w.fetch('https://site/0', 128);
    await w.decode(100);
    assert.deepEqual(w.messages.filter((m) => 'index' in m).map((m) => m.index), [1, 0]);
});

test('worker can see a decoded Blob before its arrayBuffer callback completes', async () => {
    const w = worker();
    w.store(['https://site/0']);
    await w.decode(100);
    await w.fetch('https://site/0', 128);
    assert.equal(w.messages.at(-1).index, 0);
});

test('worker does not silently swap equal-sized concurrent images', async () => {
    const w = worker();
    w.store(['https://site/0', 'https://site/1']);
    await w.fetch('https://site/0', 128);
    await w.fetch('https://site/1', 128);
    await w.decode(100);
    assert.equal(w.messages.filter((m) => 'index' in m).length, 0);
});

test('worker accepts unencrypted images and suppresses completed fetch duplicates', async () => {
    const w = worker();
    w.store(['https://site/0']);
    await w.fetch('https://site/0', 100);
    await w.decode(100);
    await w.fetch('https://site/0', 100);
    await w.decode(100);
    assert.deepEqual(w.messages.filter((m) => 'index' in m).map((m) => m.index), [0]);
});

test('worker reports excessive unmatched images instead of dropping data invisibly', async () => {
    const w = worker();
    w.store(['https://site/0']);
    for (let i = 0; i < 17; i++) await w.decode(100);
    assert.match(w.messages.at(-1).error, /matched safely/);
});
