const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');
const vm = require('node:vm');
const source = fs.readFileSync(path.join(__dirname, '../../app/src/main/assets/vk_lite/50_music.js'), 'utf8');
const start = source.indexOf('  var RESOLVE_STEP_MS ='), end = source.indexOf('  function pollMusicDownload(', start);
const resolver = source.slice(start, end);
const verify = source.slice(source.indexOf('  function verifyPlay('), source.indexOf('\n  /** Что есть в вёрстке', source.indexOf('  function verifyPlay(')));
function page() {
  let now = 100000, serial = 0;
  const timers = new Map(), posted = [], clicks = [], events = new Set();
  const media = { paused: true, muted: true, currentTime: 0, currentSrc: 'blob:source', pause() { this.paused = true; } };
  const c = { playbackGeneration: 0, streamById: {}, streamIds: [], lastHlsLoad: null, pendingMusicDownload: null,
    cacheWorker: true, RESOLVE_WAIT_MS: 15000, Date: { now: () => now },
    setTimeout: (fn, delay) => { const id = ++serial; timers.set(id, { fn, at: now + delay }); return id; },
    currentMedia: () => media, currentTrackId: () => '', isPlaylistPage: () => false, isSearchPage: () => false,
    musicStreamFor: id => c.streamById[id] || '',
    trackElement: id => ({ id }), playElement: (row, id) => { c.playbackGeneration++; clicks.push(id); },
    MusicDiagnostics: { begin() {}, capture() {} }, traceMusic() {}, postToApp: value => posted.push(value),
    NetTap: { onHost: () => true }, document: { addEventListener: (name, fn) => events.add(name), removeEventListener: name => events.delete(name) },
    mediaBrief: () => '', retryClickIfSilent: () => { c.retries++; }, retries: 0 };
  vm.createContext(c); vm.runInContext(resolver + '\n' + verify, c);
  function advance(ms) {
    const target = now + ms;
    for (let steps = 0; steps < 10000; steps++) {
      const entry = [...timers].filter(([, x]) => x.at <= target).sort((a, b) => a[1].at - b[1].at)[0];
      if (!entry) break;
      timers.delete(entry[0]); now = entry[1].at; entry[1].fn();
    }
    now = target;
  }
  return { c, posted, clicks, events, advance, resolve: (operation, force = []) => c.resolveByPlaying('1_2', JSON.stringify({ operation, force })) };
}
let passed = 0;
function test(name, fn) { fn(); passed++; console.log('PASS', name); }
test('old polling callback cannot resolve a new request for the same track', () => {
  const p = page(); p.resolve('old'); p.advance(80); p.c.stopResolveByPlaying(); p.resolve('new');
  p.c.streamById['1_2'] = 'https://cs1.vkuseraudio.ru/new'; p.advance(300);
  assert.equal(p.posted.length, 1); assert.equal(p.posted[0].operation, 'new');
});
test('queued step of a superseded operation cannot click the next operation again', () => {
  const p = page(); p.c.streamById['1_2'] = 'old-address'; p.resolve('old');
  p.resolve('new', ['1_2']); assert.equal(p.clicks.length, 1); p.advance(1); assert.equal(p.clicks.length, 1);
});
test('forced recovery bypasses the known URL and reports the fresh URL with its operation', () => {
  const p = page(); p.c.streamById['1_2'] = 'old-address'; p.c.streamIds = ['1_2'];
  p.resolve('refresh', ['1_2']); assert.equal(p.c.streamById['1_2'], undefined); assert.equal(p.c.streamIds.length, 0);
  p.c.streamById['1_2'] = 'fresh-address'; p.advance(200);
  assert.equal(p.posted[0].items[0].url, 'fresh-address'); assert.equal(p.posted[0].operation, 'refresh');
});
test('lookup timeout produces one terminal missing result, without a replay loop', () => {
  const p = page(); p.resolve('timeout'); p.advance(30000);
  assert.equal(p.posted.length, 1); assert.equal(p.posted[0].missing[0], '1_2'); assert.equal(p.posted[0].operation, 'timeout');
});
test('cancellation invalidates callbacks before a late stream becomes available', () => {
  const p = page(); p.resolve('cancel'); p.c.stopResolveByPlaying(); p.c.streamById['1_2'] = 'late'; p.advance(30000);
  assert.equal(p.posted.length, 0);
});
test('invalid operation tokens cannot start a lookup', () => {
  const p = page(); assert.equal(p.resolve('bad";operation'), false); assert.equal(p.clicks.length, 0);
});
test('verification timer of a previous playback cannot retry-click a newer track', () => {
  const p = page(); p.c.verifyPlay(1); p.c.playbackGeneration++; p.advance(5000);
  assert.equal(p.c.retries, 0); assert.equal(p.events.size, 0);
});
console.log(`PASS ${passed} music operation checks`);
