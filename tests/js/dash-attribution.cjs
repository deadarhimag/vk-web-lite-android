const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');
const vm = require('node:vm');
const source = fs.readFileSync(path.join(__dirname, '../../app/src/main/assets/vk_lite/50_music.js'), 'utf8');
function between(start, end) {
  const a = source.indexOf(start), b = source.indexOf(end, a); assert(a >= 0 && b > a); return source.slice(a, b);
}
const identity = between('  function currentTrackId()', '\n  /** Строка списка');
const memory = between('  function streamDeadline(', '\n  function rememberApiAudio(');
const attribution = between('  var hlsRequests =', '\n  // Плеер страницы получает адрес');
function page() {
  const timers = [], stages = []; let onRequest, listener;
  const c = { playbackGeneration: 0, musicTracks: { '1_2': { title: 'one', artist: 'artist' } }, musicHint: { id: '1_2' },
    streamById: {}, streamIds: [], STREAM_MEMORY: 20, pendingMusicDownload: null,
    document: { querySelector: () => null }, navigator: { mediaSession: { metadata: null } },
    norm: s => String(s).toLowerCase().trim(), Date: { now: () => 100000 }, URL,
    setTimeout: fn => timers.push(fn), report() {},
    Diag: { debug() {} }, MusicDiagnostics: { capture: x => stages.push(x), network() {} },
    NetTap: { onHost: u => u.startsWith('https://cs1.vkuseraudio.ru/'), onRequest: fn => { onRequest = fn; },
      listen: spec => { listener = spec; }, onResponse() {} } };
  vm.createContext(c); vm.runInContext(identity + '\n' + memory + '\n' + attribution, c);
  function response(url, type = 'dash') {
    onRequest(url); assert(listener.wants(url)); listener.onText(url, '', type === 'dash' ? '<MPD></MPD>' : '#EXTM3U\n');
  }
  return { c, stages, response, begin: url => { onRequest(url); return () => listener.onText(url, '', '<MPD></MPD>'); },
    settle: () => { for (let i = 0; i < 20 && timers.length; i++) timers.shift()(); } };
}
let passed = 0;
function test(name, fn) { fn(); passed++; console.log('PASS', name); }
test('DASH address is retained under the track observed in page markup', () => {
  const p = page(); p.c.document.querySelector = () => ({ getAttribute: () => '1_2' });
  p.response('https://cs1.vkuseraudio.ru/manifest?key=one'); p.settle();
  assert.equal(p.c.musicStreamFor('1_2'), 'https://cs1.vkuseraudio.ru/manifest?key=one');
  assert.equal(p.c.streamById['1_2'].dash, p.c.musicStreamFor('1_2')); assert(p.stages.includes('dash-attributed'));
});
test('requested ID alone cannot attribute a DASH manifest', () => {
  const p = page(); p.response('https://cs1.vkuseraudio.ru/manifest'); p.settle();
  assert.equal(p.c.musicStreamFor('1_2'), ''); assert(p.stages.includes('dash-unattributed'));
});
test('unique Media Session title and artist can confirm the track', () => {
  const p = page(); p.c.navigator.mediaSession.metadata = { title: 'ONE', artist: 'Artist' };
  p.response('https://cs1.vkuseraudio.ru/manifest'); p.settle(); assert.equal(p.c.streamById['1_2'].dash, 'https://cs1.vkuseraudio.ru/manifest');
});
test('ambiguous metadata cannot attribute a DASH manifest to the requested duplicate', () => {
  const p = page(); p.c.musicTracks['1_3'] = { title: 'one', artist: 'artist' };
  p.c.navigator.mediaSession.metadata = { title: 'one', artist: 'artist' };
  p.response('https://cs1.vkuseraudio.ru/manifest'); p.settle(); assert.equal(p.c.musicStreamFor('1_2'), '');
});
test('HLS retains its existing attribution path', () => {
  const p = page(); p.response('https://cs1.vkuseraudio.ru/index.m3u8', 'hls'); p.settle();
  assert.equal(p.c.streamById['1_2'].hls, 'https://cs1.vkuseraudio.ru/index.m3u8');
});
test('a superseded manifest cannot overwrite the latest load', () => {
  const p = page(); p.c.document.querySelector = () => ({ getAttribute: () => '1_2' });
  p.response('https://cs1.vkuseraudio.ru/old'); p.response('https://cs1.vkuseraudio.ru/new'); p.settle();
  assert.equal(p.c.musicStreamFor('1_2'), 'https://cs1.vkuseraudio.ru/new');
});
test('manifest response from a previous playback generation is ignored', () => {
  const p = page(); p.c.document.querySelector = () => ({ getAttribute: () => '1_2' });
  const finish = p.begin('https://cs1.vkuseraudio.ru/old'); p.c.playbackGeneration++; finish(); p.settle();
  assert.equal(p.c.musicStreamFor('1_2'), '');
});
test('manifest attribution waiting on a timer cannot bind after the track changes', () => {
  const p = page(); p.c.document.querySelector = () => ({ getAttribute: () => '1_2' });
  p.response('https://cs1.vkuseraudio.ru/old'); p.c.playbackGeneration++; p.settle();
  assert.equal(p.c.musicStreamFor('1_2'), '');
});
console.log(`PASS ${passed} DASH attribution checks`);
