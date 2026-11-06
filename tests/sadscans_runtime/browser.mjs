import fs from 'node:fs';
import vm from 'node:vm';
import assert from 'node:assert/strict';
const kotlin = fs.readFileSync('keiyoushi-extensions-tr/src/tr/sadscans/src/eu/kanade/tachiyomi/extension/tr/sadscans/SadscansBrowser.kt', 'utf8');
const template = kotlin.split('return """')[1].split('""".trimIndent()')[0];
const path = '/api/trpc/_s4d7g2k9m0.getSeries?batch=1&input=%7B%7D';
const script = template.replace('$target', JSON.stringify(path));
let calls = 0;
const context = vm.createContext({
  location: {pathname: '/seriler'}, document: {querySelector: () => ({})}, window: {},
  fetch: async (url, options) => {
    calls++;
    assert.equal(url, path);
    assert.equal(options.credentials, 'same-origin');
    return {status: 200, text: async () => '[{"result":{"data":{"json":[{"href":"/seriler/test","name":"Test"}]}}}]'};
  },
});
assert.equal(vm.runInContext(script, context), null);
assert.equal(vm.runInContext(script, context), null);
await new Promise(resolve => setImmediate(resolve));
const result = JSON.parse(vm.runInContext(script, context));
assert.equal(result.status, 200);
assert.equal(JSON.parse(result.body)[0].result.data.json[0].name, 'Test');
assert.equal(calls, 1, 'polling must not issue duplicate requests');
context.location.pathname = '/cdn-cgi/challenge';
assert.equal(vm.runInContext(script, context), null);
assert.equal(calls, 1, 'do not fetch during browser verification');
context.location.pathname = '/seriler';
context.window = {};
context.fetch = async () => ({status: 403, text: async () => 'blocked'});
vm.runInContext(script, context);
await new Promise(resolve => setImmediate(resolve));
assert.equal(JSON.parse(vm.runInContext(script, context)).status, 403);
context.window = {};
context.fetch = async () => { throw new Error('offline'); };
vm.runInContext(script, context);
await new Promise(resolve => setImmediate(resolve));
assert.equal(JSON.parse(vm.runInContext(script, context)).status, 0);
console.log('PASS: exact browser script: same-origin fetch, single request, delayed result, challenge guard, HTTP and network failure');
