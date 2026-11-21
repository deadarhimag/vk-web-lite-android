const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');
const vm = require('node:vm');
const code = fs.readFileSync(path.join(__dirname, '../../app/src/main/assets/shared/net_tap.js'), 'utf8');
const url = 'https://cs1.vkuseraudio.ru/source?token=PRIVATE';
const playlist = '#EXTM3U\n#EXTINF:10,\nsegment.ts\n#EXT-X-ENDLIST\n';
function page(response, compatibility = false) {
  const selected = [], normal = [], metadata = [];
  const promise = Promise.resolve(response);
  class Xhr {
    constructor() { this.events = {}; this.responseType = ''; this.status = 200; this.mime = 'application/octet-stream'; }
    open() {} send() { return 'send-result'; }
    addEventListener(name, fn) { this.events[name] = fn; }
    getResponseHeader(name) { return name === 'content-type' ? this.mime : ''; }
    finish(type, value) { this.responseType = type; this.response = value; if (type === 'text') this.responseText = value; this.events.load.call(this); }
  }
  const c = { window: { fetch: () => promise, addEventListener() {} }, XMLHttpRequest: Xhr,
    location: { href: 'https://m.vk.ru/audio', pathname: '/audio', search: '' }, URL, URLSearchParams, TextDecoder,
    Uint8Array, setTimeout, clearTimeout, console };
  c.window.history = { pushState(state, title, target) {
    const next = new URL(target, c.location.href); c.location.href = next.href;
    c.location.pathname = next.pathname; c.location.search = next.search; return 17;
  } };
  vm.createContext(c);
  if (compatibility) vm.runInContext(fs.readFileSync(path.join(__dirname, '../../app/src/main/assets/shared/compat.js'), 'utf8'), c);
  vm.runInContext(code, c);
  const tap = c.window.__vkNetTap;
  tap.listen({ playlist: true, wants: candidate => candidate === url, onText: (u, b, t) => selected.push(t) });
  tap.listen({ wants: candidate => candidate === url, onText: (u, b, t) => normal.push(t) });
  tap.onResponse((u, source, info) => metadata.push(info));
  return { c, tap, selected, normal, metadata, promise, xhr: () => { const x = new Xhr(); x.open('GET', url); assert.equal(x.send(), 'send-result'); return x; } };
}
async function flush() { for (let i = 0; i < 25; i++) await new Promise(resolve => setImmediate(resolve)); }
let passed = 0;
async function test(name, run) { await run(); passed++; console.log('PASS', name); }
(async () => {
  await test('binary MIME HLS is observed without changing fetch promise or page response', async () => {
    const response = new Response(playlist, { headers: { 'content-type': 'application/octet-stream' } });
    const p = page(response); assert.equal(p.c.window.fetch(url), p.promise);
    await p.promise;
    assert.equal(await response.text(), playlist); await flush();
    assert.deepEqual(p.selected, [playlist], JSON.stringify(p.metadata)); assert.equal(p.normal.length, 0);
    assert(p.metadata.some(x => x.mime === 'application/octet-stream')); assert(p.metadata.some(x => x.payload === 'hls'));
  });
  await test('normal text HLS still reaches existing text subscribers', async () => {
    const p = page(new Response(playlist, { headers: { 'content-type': 'application/vnd.apple.mpegurl' } }));
    await p.c.window.fetch(url); await flush(); assert.deepEqual(p.selected, [playlist]); assert.deepEqual(p.normal, [playlist]);
  });
  await test('binary media inspection stops after prefix rather than reading a whole song', async () => {
    let reads = 0, cancelled = 0;
    const bytes = new Uint8Array(128); bytes.set(Buffer.from('ftyp'), 4);
    const p = page({ ok: true, status: 200, headers: new Headers({ 'content-type': 'audio/mp4' }),
      clone: () => ({ body: { getReader: () => ({ read: async () => { reads++; return { value: bytes, done: false }; }, cancel: async () => { cancelled++; } }) } }) });
    await p.c.window.fetch(url); await flush();
    assert.equal(reads, 1); assert.equal(cancelled, 1); assert.equal(p.selected.length, 0);
    assert(p.metadata.some(x => x.payload === 'mp4'));
  });
  await test('declared large binary body is never cloned', async () => {
    let clones = 0;
    const p = page({ ok: true, status: 200, headers: new Headers({ 'content-type': 'application/octet-stream', 'content-length': '300000' }), clone: () => { clones++; } });
    await p.c.window.fetch(url); await flush(); assert.equal(clones, 0); assert(p.metadata.some(x => x.payload === 'limit'));
  });
  await test('split BOM and single-byte stream chunks are decoded as HLS', async () => {
    const bytes = Buffer.from('\uFEFF' + playlist);
    const stream = new ReadableStream({ start(controller) { for (const byte of bytes) controller.enqueue(Uint8Array.of(byte)); controller.close(); } });
    const response = new Response(stream, { headers: { 'content-type': 'application/octet-stream' } });
    const p = page(response); await p.c.window.fetch(url); await response.arrayBuffer(); await flush();
    assert.deepEqual(p.selected, [playlist]);
  });
  await test('XHR arraybuffer HLS reaches only explicit playlist subscribers', () => {
    const p = page(); const x = p.xhr(); x.finish('arraybuffer', new TextEncoder().encode(playlist).buffer);
    assert.deepEqual(p.selected, [playlist]); assert.equal(p.normal.length, 0); assert(p.metadata.some(x => x.mode === 'arraybuffer'));
  });
  await test('XHR blob HLS is decoded within the size bound', async () => {
    const p = page(); p.xhr().finish('blob', new Blob([playlist])); await flush(); assert.deepEqual(p.selected, [playlist]);
  });
  await test('non-playlist binary XHR cannot become a saved playlist', () => {
    const p = page(); const bytes = new Uint8Array(64); bytes.set(Buffer.from('ftyp'), 4);
    p.xhr().finish('arraybuffer', bytes.buffer); assert.equal(p.selected.length, 0); assert(p.metadata.some(x => x.payload === 'mp4'));
  });
  await test('HTTP failures retain status without parsing their body as a playlist', async () => {
    const p = page(new Response(playlist, { status: 403, headers: { 'content-type': 'application/octet-stream' } }));
    await p.c.window.fetch(url); await flush(); assert.equal(p.selected.length, 0); assert(p.metadata.some(x => x.status === 403));
  });
  await test('DASH manifest reaches the explicit manifest subscriber with its original XML', async () => {
    const xml = '<?xml version="1.0"?><MPD></MPD>';
    const p = page(new Response(xml, { headers: { 'content-type': 'application/dash+xml' } }));
    await p.c.window.fetch(url); await flush(); assert.deepEqual(p.selected, [xml]); assert.equal(p.normal.length, 0);
    assert(p.metadata.some(x => x.payload === 'dash'));
  });
  await test('binary body without declared size cannot exceed the playlist byte limit', async () => {
    const bytes = Buffer.alloc(300000, 32); Buffer.from(playlist).copy(bytes);
    const response = new Response(bytes, { headers: { 'content-type': 'application/octet-stream' } });
    const p = page(response); await p.c.window.fetch(url); await response.arrayBuffer(); await flush();
    assert.equal(p.selected.length, 0); assert(p.metadata.some(x => x.payload === 'limit'));
  });
  await test('binary replies outside the opted-in URL are not cloned', async () => {
    let clones = 0;
    const p = page({ ok: true, status: 200, headers: new Headers({ 'content-type': 'application/octet-stream' }), clone: () => { clones++; } });
    await p.c.window.fetch('https://example.org/unrelated'); await flush(); assert.equal(clones, 0);
  });
  await test('XHR JSON replies retain the existing text delivery path', () => {
    const p = page(); p.xhr().finish('json', { response: [1, 2] });
    assert.deepEqual(p.normal, ['{"response":[1,2]}']); assert.deepEqual(p.selected, p.normal);
  });
  await test('stalled binary inspection is cancelled on timeout without changing fetch result', async () => {
    let pending, cancelled = 0;
    const response = { ok: true, status: 200, headers: new Headers({ 'content-type': 'application/octet-stream' }),
      clone: () => ({ body: { getReader: () => ({ read: () => new Promise(resolve => { pending = resolve; }),
        cancel: async () => { cancelled++; if (pending) pending({ done: true }); } }) } }) };
    const p = page(response); p.c.setTimeout = fn => setImmediate(fn); p.c.clearTimeout = clearImmediate;
    assert.equal(p.c.window.fetch(url), p.promise); await p.promise; await flush();
    assert(cancelled > 0); assert(p.metadata.some(x => x.payload === 'timeout')); assert.equal(p.selected.length, 0);
  });
  await test('page-bound fetch replies cannot update another SPA route', async () => {
    const p = page(new Response('{"response":{"items":[1]}}', { headers: { 'content-type': 'application/json' } }));
    const bound = []; p.tap.listen({ samePage: true, onText: text => bound.push(text) });
    const result = p.c.window.fetch(url); p.c.location.pathname = '/friends'; await result; await flush();
    assert.equal(bound.length, 0); assert.equal(p.normal.length, 1);
  });
  await test('page-bound XHR replies cannot update another SPA route', () => {
    const p = page(), bound = []; p.tap.listen({ samePage: true, onText: text => bound.push(text) });
    const x = p.xhr(); p.c.location.pathname = '/groups'; x.finish('json', { response: { items: [1] } });
    assert.equal(bound.length, 0); assert.equal(p.normal.length, 1);
  });
  await test('API route guard preserves navigation document responses while rejecting stale method replies', async () => {
    const p = page(new Response('{"data":[]}', { headers: { 'content-type': 'application/json' } }));
    const bound = []; p.tap.listen({ samePageApi: true, onText: value => bound.push(value) });
    const result = p.c.window.fetch(url); p.c.location.pathname = '/friends'; await result; await flush();
    assert.equal(bound.length, 1);
    const q = page(new Response('{"response":[]}', { headers: { 'content-type': 'application/json' } }));
    const apiBound = []; q.tap.listen({ samePageApi: true, onText: value => apiBound.push(value) });
    const pending = q.c.window.fetch('https://web.api.vk.ru/method/friends.get'); q.c.location.pathname = '/groups';
    await pending; await flush(); assert.equal(apiBound.length, 0);
  });
  await test('returning to the same SPA URL cannot revive an old API response', async () => {
    const p = page(new Response('{"response":[]}', { headers: { 'content-type': 'application/json' } }), true);
    const received = []; p.tap.listen({ samePageApi: true, onText: value => received.push(value) });
    const pending = p.c.window.fetch('https://web.api.vk.ru/method/friends.get');
    assert.equal(p.c.window.history.pushState(null, '', '/groups'), 17);
    p.c.window.history.pushState(null, '', '/audio');
    await pending; await flush(); assert.equal(received.length, 0);
  });
  await test('XHR lookup generation is preserved across a round trip to another route', () => {
    const p = page(undefined, true), received = []; p.tap.listen({ samePage: true, onText: value => received.push(value) });
    const x = p.xhr(); p.c.window.history.pushState(null, '', '/groups'); p.c.window.history.pushState(null, '', '/audio');
    x.finish('json', { response: [] }); assert.equal(received.length, 0);
  });
  console.log(`PASS ${passed} playlist transport checks`);
})().catch(e => { console.error(e); process.exitCode = 1; });
