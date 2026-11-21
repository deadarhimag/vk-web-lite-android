// Standalone regression checks: no browser or npm dependencies required.
const assert = require('node:assert/strict');
const vm = require('node:vm');
const fs = require('node:fs');
const path = require('node:path');
const source = fs.readFileSync(path.join(__dirname, '../../app/src/main/assets/vk_lite/45_music_diagnostics.js'), 'utf8');
function page(enabled = true, worker = false) {
  const records = [], handlers = {};
  let calls = 0, now = 100000, promise = Promise.resolve(), thrown;
  const attrs = { class: 'audio_item custom_play', 'data-id': '42_123', 'data-audio': 'PRIVATE_TOKEN',
    onclick: 'play("PRIVATE_TOKEN")', role: 'button' };
  const row = { tagName: 'DIV', getAttribute: n => attrs[n] || '', hasAttribute: n => n in attrs,
    textContent: 'PRIVATE_TEXT' };
  function Media() {}
  Media.prototype.play = function () { calls++; if (thrown) throw thrown; return promise; };
  const media = new Media();
  Object.assign(media, { tagName: 'AUDIO', paused: true, muted: false, volume: 1, readyState: 4,
    networkState: 1, currentTime: 0, src: 'https://audio.example/PRIVATE_PATH?token=PRIVATE_TOKEN' });
  const player = Object.assign(Object.create(null), { playTrack() { throw Error('must not execute'); } });
  Object.defineProperty(player, 'danger', { get() { throw Error('must not execute getter'); } });
  const c = { CONFIG: { collectDump: enabled, cacheWorker: worker, bridge: 'bridge' },
    window: { bridge: { postMessage: s => records.push(JSON.parse(s)) }, audioplayer: player },
    navigator: { userActivation: { isActive: false } }, HTMLMediaElement: Media,
    location: { pathname: '/audio/123', search: '?token=PRIVATE_TOKEN' }, URL,
    Date: class extends Date { constructor(value) { super(value === undefined ? now : value); } static now() { return now; } },
    document: { visibilityState: 'hidden', querySelectorAll: q => q === 'audio,video' ? [media] : [row],
      querySelector: () => row, addEventListener: (name, handler) => { handlers[name] = handler; } } };
  vm.createContext(c); vm.runInContext(source, c);
  return { c, diag: c.MusicDiagnostics, records, row, media, calls: () => calls,
    promise: p => { promise = p; }, fail: e => { thrown = e; }, advance: () => { now += 60000; }, handlers };
}
let passed = 0;
async function test(name, run) { await run(); passed++; console.log('PASS', name); }
(async () => {
  await test('disabled diagnostics emits nothing and calls original play once', async () => {
    const p = page(false); const expected = Promise.resolve('ok'); p.promise(expected);
    assert.equal(p.media.play(), expected); await expected;
    p.diag.begin('42_123'); p.diag.capture('test', p.row);
    assert.equal(p.records.length, 0); assert.equal(p.calls(), 1);
  });
  await test('snapshot retains DOM/player shape but excludes text tokens and stream paths', () => {
    const p = page(true, true); p.diag.begin('42_123'); p.diag.capture('resolve-start', p.row);
    const r = p.records[0]; assert.equal(r.worker, true); assert.equal(r.expectedId, '42_123');
    assert.equal(r.sample[0].onclick, true); assert.equal(r.sample[0].audioData, true);
    assert.deepEqual(r.playerMethods, ['playTrack']); assert.equal(r.media[0].source, 'https://audio.example');
    assert.equal(r.page, '/audio'); assert(!JSON.stringify(r).includes('PRIVATE_'));
    assert(JSON.stringify(r).length <= 18000);
  });
  await test('rejected play keeps promise identity and records only error name', async () => {
    const p = page(); const rejected = Promise.reject(Object.assign(Error('PRIVATE_TOKEN'), { name: 'NotAllowedError' }));
    p.promise(rejected); const actual = p.media.play(); assert.equal(actual, rejected);
    await assert.rejects(actual); await Promise.resolve();
    const r = p.records.find(x => x.stage === 'play-rejected'); assert(r);
    assert.equal(r.events.at(-1).error, 'NotAllowedError'); assert(!JSON.stringify(r).includes('PRIVATE_'));
    assert.equal(p.calls(), 1);
  });
  await test('synchronous play failure is rethrown unchanged', () => {
    const p = page(); const failure = Error('same');
    p.fail(failure);
    assert.throws(() => p.media.play(), e => e === failure);
    assert.equal(p.records.at(-1).stage, 'play-threw');
  });
  await test('a nonstandard thenable cannot break the caller', () => {
    const p = page(); const failure = Error('same');
    p.promise({ then() { throw failure; } });
    const result = p.media.play(); assert.equal(typeof result.then, 'function'); assert.equal(p.calls(), 1);
  });
  await test('records are bounded and collection can be disabled and reenabled at runtime', () => {
    const p = page();
    for (let i = 0; i < 500; i++) p.diag.capture('poll');
    assert.equal(p.records.length, 120); p.diag.set(false); p.advance(); p.diag.capture('off');
    assert.equal(p.records.length, 120); p.diag.set(true); p.diag.capture('on');
    assert.equal(p.records.length, 121); assert.equal(p.records.at(-1).dropped, 380);
  });
  await test('media events and network counters survive until timeout snapshot', () => {
    const p = page(); p.diag.network('audioRequests'); p.diag.network('hlsResponses');
    p.diag.network('unknown'); p.handlers.pause({ target: p.media });
    p.diag.capture('resolve-timeout'); const r = p.records[0];
    assert.equal(r.events.at(-1).event, 'pause'); assert.equal(r.network.audioRequests, 1);
    assert.equal(r.network.hlsResponses, 1); assert.equal(r.network.unknown, undefined);
  });
  await test('detached active audio is included even when DOM contains no media', () => {
    const p = page(); p.c.currentMedia = () => p.media;
    p.c.document.querySelectorAll = () => [];
    p.diag.capture('verify'); const r = p.records[0];
    assert.equal(r.media.length, 0); assert.equal(r.activeMedia.tag, 'AUDIO');
    assert.equal(r.activeMedia.source, 'https://audio.example');
  });
  await test('repeated identical failures cannot flood snapshots within a second', () => {
    const p = page(); p.fail(new RangeError('stack'));
    for (let i = 0; i < 500; i++) assert.throws(() => p.media.play());
    assert.equal(p.records.length, 1); p.diag.capture('timeout');
    assert.equal(p.records.at(-1).suppressedFailures, 499);
  });
  await test('response diagnostics preserve status MIME and payload kind without URLs or tokens', () => {
    const p = page(); const u = 'https://audio.example/PRIVATE_PATH?token=PRIVATE_TOKEN';
    p.diag.response(u, 'fetch', { status: 200, mime: 'application/octet-stream; PRIVATE_TOKEN', size: 400, mode: 'binary' });
    p.diag.response(u, 'fetch', { payload: 'dash' }); p.diag.capture('timeout');
    const r = p.records[0]; assert.equal(r.responses[0].mime, 'application/octet-stream');
    assert.equal(r.responses[0].status, 200); assert.equal(r.payloads.dash, 1);
    assert(!JSON.stringify(r).includes('PRIVATE_'));
  });
  await test('response metadata remains bounded and is not collected while disabled', () => {
    const p = page(); for (let i = 0; i < 100; i++) p.diag.response('https://audio.example/', 'xhr', { status: i });
    p.diag.set(false); p.diag.response('https://PRIVATE.example/', 'xhr', { status: 999 });
    p.diag.set(true); p.diag.capture('timeout'); const r = p.records[0];
    assert.equal(r.responses.length, 16); assert.equal(r.responses.at(-1).status, 99);
  });
  console.log(`PASS ${passed} music diagnostic checks`);
})().catch(e => { console.error(e); process.exitCode = 1; });
