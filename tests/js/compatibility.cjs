const assert = require('node:assert/strict');
const vm = require('node:vm');
const fs = require('node:fs');
const path = require('node:path');
const base = path.join(__dirname, '../../app/src/main/assets');
const read = file => fs.readFileSync(path.join(base, file), 'utf8');
function page() {
  let now = 1700000000000;
  const c = { window: {}, location: { pathname: '/audio', search: '?secret=PRIVATE' }, URLSearchParams, URL,
    Date: { now: () => now }, document: {}, console };
  vm.createContext(c); vm.runInContext(read('shared/compat.js'), c);
  return { c, api: c.window.__vkCompat, advance: ms => { now += ms; } };
}
let passed = 0;
function test(name, fn) { fn(); passed++; console.log('PASS', name); }
test('JSON parsing accepts BOM and whitespace but rejects malformed or excessive input', () => {
  const p = page(); assert.equal(p.api.parse('\uFEFF \n{"response":7}').response, 7);
  assert.equal(p.api.parse('<html>'), null); assert.equal(p.api.parse(' '.repeat(8 * 1024 * 1024 + 1)), null);
});
test('known standard and alternative API envelopes normalize to the same model', () => {
  const p = page();
  for (const value of [{ response: { items: [1] } }, { error: false, response: { items: [1] } }, { error: 0, response: { items: [1] } }, { result: { items: [1] } }, { data: { response: { items: [1] } } }, { result: { data: { response: { items: [1] } } } }]) {
    assert.equal(p.api.api(value).response.items[0], 1);
  }
  assert.equal(p.api.api({ result: [] }).response.length, 0);
});
test('API errors and unknown schemas never become empty successful lists', () => {
  const p = page();
  for (const value of [{ error: { error_code: 5 }, response: { items: [] } }, { success: false, result: { items: [] } },
    { data: { error: { error_code: 10 } } }, { status: 'error', response: { items: [] } }, { arbitrary: { response: { items: [] } } }]) assert.equal(p.api.api(value), null);
});
test('batch responses are bound by request ID even when reordered and JSON encoded', () => {
  const p = page(); const body = JSON.stringify({ requests: [{ id: 'a', method: 'friends.get', params: { user_id: 7 } }, { id: 'b', method: 'groups.get', params: {} }] });
  const calls = p.api.batch(body, { result: { response: { responses: [{ request_id: 'b', body: '{"data":{"items":[2]}}' }, { id: 'a', response: { items: [1] } }] } } });
  assert.equal(calls.length, 2); assert.equal(calls.find(x => x.method === 'friends.get').params.user_id, 7);
  assert.equal(calls.find(x => x.method === 'groups.get').body.response.items[0], 2);
});
test('duplicate IDs, unmatched responses and malformed request parameters are rejected', () => {
  const p = page(); const q = { id: 'x', method: 'friends.get', params: {} };
  assert.equal(p.api.batch(JSON.stringify({ requests: [q, q] }), { responses: [{ id: 'x', body: { response: [] } }] }).length, 0);
  assert.equal(p.api.batch(JSON.stringify({ requests: [q] }), { responses: [{ id: 'x', body: { response: [] } }, { id: 'x', body: { response: [] } }] }).length, 0);
  assert.equal(p.api.batch(JSON.stringify({ requests: [{ ...q, params: 'user_id=7' }] }), { responses: [{ id: 'x', response: [] }] }).length, 0);
});
test('form-encoded batches and prototype-looking request IDs remain safe', () => {
  const p = page(); const requests = [{ id: '__proto__', method: 'friends.get', params: {} }];
  const calls = p.api.batch(new URLSearchParams({ requests: JSON.stringify(requests) }).toString(), { responses: [{ id: '__proto__', response: { items: [1] } }] });
  assert.equal(calls.length, 1); assert.equal({}.items, undefined);
});
test('read strategy failures cool down without affecting another section or invoking actions', () => {
  const p = page(); let broken = 0, fallback = 0;
  const strategies = [{ id: 'old', read() { broken++; throw Error(); } }, { id: 'new', read() { fallback++; return 7; } }];
  for (let i = 0; i < 8; i++) assert.equal(p.api.choose('music.row', strategies, x => x === 7), 7);
  assert.equal(broken, 3); assert.equal(fallback, 8);
  assert.equal(p.api.choose('friends.list', [{ id: 'old', read: () => 8 }], x => x === 8), 8);
  p.advance(30001); p.api.choose('music.row', strategies, x => x === 7); assert.equal(broken, 4);
});
test('a temporary missing row does not blacklist its read strategy', () => {
  const p = page(); let value = null; const strategies = [{ id: 'lazy', read: () => value }];
  for (let i = 0; i < 10; i++) assert.equal(p.api.choose('music.row', strategies, x => x === 9), null);
  value = 9; assert.equal(p.api.choose('music.row', strategies, x => x === 9), 9);
});
test('SPA navigation resets strategy failures and reports a new generation without private paths', () => {
  const p = page(); const initial = p.api.describe('lite', { read() {} });
  p.api.choose('music.row', [{ id: 'bad', read() { throw Error(); } }], Boolean);
  p.c.location.pathname = '/mail/private'; const next = p.api.describe('lite', { read() {} });
  assert.equal(next.generation, initial.generation + 1); assert.equal(next.documentId, initial.documentId);
  assert.equal(next.strategies.length, 0); assert(!JSON.stringify(next).includes('PRIVATE')); assert(!JSON.stringify(next).includes('/mail'));
});
test('actual ResponseTap delivers alternate and batched schemas but ignores errors', () => {
  const p = page(); let listener, changed = 0; const calls = [];
  p.c.NetTap = { listen: spec => { listener = spec; } }; p.c.Diag = { debug() {}, warn() {}, errorText() {} };
  const source = read('vk_lite/40_screens.js'); const start = source.indexOf('  var ResponseTap =');
  const end = source.indexOf('\n  })();', start) + '\n  })();'.length;
  vm.runInContext(source.slice(start, end), p.c);
  p.c.ResponseTap.listenApi(() => true, /^friends\.get$/, (method, params, body) => { calls.push(body.response); return true; }, () => changed++);
  listener.onText('https://web.api.vk.ru/method/friends.get', 'user_id=7', '\uFEFF  {"data":{"items":[1]}}');
  listener.onText('https://web.api.vk.ru/method/friends.get', '', '{"error":{"error_code":5}}');
  listener.onText('https://web.api.vk.ru/method/batch.call', JSON.stringify({ requests: [{ id: 1, method: 'friends.get' }] }), '{"responses":[{"id":1,"body":{"result":{"items":[2]}}}]}');
  assert.equal(calls.length, 2); assert.equal(calls[0].items[0], 1); assert.equal(calls[1].items[0], 2); assert.equal(changed, 2);
});

const music = read('vk_lite/50_music.js');
const tupleSource = music.slice(music.indexOf('  function isTrackTuple('), music.indexOf('\n  }', music.indexOf('  function isTrackTuple(')) + 4);
function row(attrs, children = []) { return { attrs, children, getAttribute: key => attrs[key] || '', contains: el => children.includes(el) }; }
function tuple(id = 2, owner = 1) { return JSON.stringify([id, owner, '', 'title', 'artist', 10, 0, 0, 0, 0, 0, 'my_audios', 0, 0, '']); }
function rowsPage(list) {
  const p = page(); let queries = 0;
  p.c.document.querySelectorAll = selector => {
    queries++;
    if (selector === '[data-audio]') return list.filter(el => el.attrs['data-audio']);
    if (selector.includes('aria-current')) return list.filter(el => el.attrs['aria-current'] === 'true' || el.attrs['data-state'] === 'playing');
    if (selector.startsWith('.audio_item')) return list.filter(el => /\baudio_item\b/.test(el.attrs.class || ''));
    return list;
  };
  vm.runInContext(tupleSource + '\n' + read('vk_lite/44_music_rows.js'), p.c);
  return { ...p, rows: p.c.MusicRows, queries: () => queries };
}
test('renamed music classes are read by validated track tuples', () => {
  const el = row({ class: 'NewRow', 'data-audio': tuple() }); const p = rowsPage([el]);
  assert.equal(p.rows.find('1_2'), el); assert.equal(p.rows.last(), 'tuple');
});
test('dedicated audio identity supports another DOM layout without a tuple', () => {
  const el = row({ class: 'SomethingElse', 'data-audio-id': '1_2' }); const p = rowsPage([el]); assert.equal(p.rows.find('1_2'), el);
});
test('unrelated post identifiers and conflicting audio identities never become click candidates', () => {
  const post = row({ class: 'post', 'data-id': '1_2' }); assert.equal(rowsPage([post]).rows.find('1_2'), null);
  const conflict = row({ class: 'audio_item', 'data-id': '1_2', 'data-audio': tuple(3) }); assert.equal(rowsPage([conflict]).rows.find('1_2'), null);
});
test('ambiguous distinct rows are rejected, nested duplicates resolve to their row', () => {
  const a = row({ class: 'audio_item', 'data-id': '1_2', 'data-audio': tuple() });
  const b = row({ class: 'audio_item', 'data-id': '1_2', 'data-audio': tuple() });
  assert.equal(rowsPage([a, b]).rows.find('1_2'), null);
  a.children.push(b); assert.equal(rowsPage([a, b]).rows.find('1_2'), a);
});
test('invalid identities cannot inject selectors and detached new row roots can be read', () => {
  const p = rowsPage([]); assert.equal(p.rows.find('1_2"] button'), null); assert.equal(p.queries(), 0);
  const root = row({ class: 'Renamed', 'data-audio': tuple() }); root.querySelectorAll = () => [];
  assert.equal(p.rows.rows(root)[0], root);
});
test('semantic current markers confirm one track and reject two active identities', () => {
  const a = row({ 'data-audio-id': '1_2', 'aria-current': 'true' });
  const b = row({ 'data-audio-id': '1_3', 'aria-current': 'true' });
  assert.equal(rowsPage([a]).rows.current(), '1_2'); assert.equal(rowsPage([a, b]).rows.current(), '');
});

function streams() {
  const p = page(); p.c.streamById = {}; p.c.streamIds = []; p.c.STREAM_MEMORY = 2; p.c.musicTracks = { '1_2': { id: '1_2', url: 'stale' } };
  p.c.NetTap = { onHost: value => /^https:\/\/cs1\.vkuseraudio\.ru\//.test(value) };
  const start = music.indexOf('  function streamDeadline('), end = music.indexOf('\n  function rememberApiAudio(', start);
  vm.runInContext(music.slice(start, end), p.c); return p;
}
test('signed stream URLs expire independently of the track list', () => {
  const p = streams(); p.c.rememberStreams('1_2', { mp3: 'https://cs1.vkuseraudio.ru/a' });
  assert(p.c.musicStreamFor('1_2')); p.advance(300001); assert.equal(p.c.musicStreamFor('1_2'), '');
  assert.equal(p.c.publicMusicTrack('1_2').url, ''); assert.equal(p.c.musicTracks['1_2'].url, 'stale');
});
test('rereading stale DOM does not renew a URL while a fresh network observation can', () => {
  const p = streams(), url = 'https://cs1.vkuseraudio.ru/a'; p.c.rememberStreams('1_2', { dash: url }); p.advance(300001);
  p.c.rememberStreams('1_2', { dash: url }); assert.equal(p.c.musicStreamFor('1_2'), '');
  p.c.rememberStreams('1_2', { dash: url }, true); assert.equal(p.c.musicStreamFor('1_2'), url);
});
test('explicit server expiry is respected and an expired format can fall back to a fresh one', () => {
  const p = streams(); p.c.rememberStreams('1_2', { mp3: 'https://cs1.vkuseraudio.ru/a?expires=1600000000', hls: 'https://cs1.vkuseraudio.ru/list' }, true);
  assert.equal(p.c.musicStreamFor('1_2'), 'https://cs1.vkuseraudio.ru/list');
});
test('stream memory stays bounded and untrusted URLs are never selected', () => {
  const p = streams(); for (let i = 1; i <= 3; i++) p.c.rememberStreams('1_' + i, { dash: 'https://cs1.vkuseraudio.ru/' + i });
  assert.equal(p.c.musicStreamFor('1_1'), ''); p.c.rememberStreams('1_4', { mp3: 'https://evil.example/x' }); assert.equal(p.c.musicStreamFor('1_4'), '');
});
test('a fresh MP3 observation cannot extend the lifetime of an old DASH URL', () => {
  const p = streams(); p.c.rememberStreams('1_2', { dash: 'https://cs1.vkuseraudio.ru/old' }); p.advance(300001);
  p.c.rememberStreams('1_2', { mp3: 'https://cs1.vkuseraudio.ru/new' }, true);
  assert(p.c.streamById['1_2'].until.dash < p.c.streamById['1_2'].until.mp3);
});
test('declared DASH API variants stay distinct from direct MP3 URLs', () => {
  const p = streams(); const start = music.indexOf('  function apiStreams('), end = music.indexOf('\n  /** Запомнить потоки', start);
  vm.runInContext(music.slice(start, end), p.c);
  const value = p.c.apiStreams({ audioStreams: [{ format: 'application/dash+xml', src: 'https://cs1.vkuseraudio.ru/manifest' }] });
  assert.equal(value.dash, 'https://cs1.vkuseraudio.ru/manifest'); assert.equal(value.mp3, '');
  assert.equal(p.c.apiStreams({ url: 'https://cs1.vkuseraudio.ru/audio.mpd' }).mp3, '');
});
function readiness(relative) {
  const native = fs.readFileSync(path.join(base, '../java/com/example/vkweb', relative), 'utf8');
  const match = /private const val PRESENT_CHECK =\s*("(?:\\.|[^"\\])*")/.exec(native);
  assert(match); return JSON.parse(match[1]);
}
test('native main readiness check rejects partial and incompatible script objects', () => {
  const code = readiness('web/PageScripts.kt');
  for (const value of [undefined, {}, { protocol: 0, contract() {}, ensureCss() {} }, { protocol: 1, contract() {} },
    { protocol: 1, contract() { throw Error(); }, ensureCss() {} }, { protocol: 1, contract() { return {}; }, ensureCss() {} }]) {
    assert.equal(vm.runInNewContext(code, { window: { __vkLite: value } }), false);
  }
  let restored = 0;
  assert.equal(vm.runInNewContext(code, { window: { __vkLite: { protocol: 1, contract() { return { protocol: 1, engine: 1, kind: 'lite', documentId: 'doc' }; }, ensureCss() { restored++; } } } }), true);
  assert.equal(restored, 1);
});
test('native reader readiness requires the read method and matching protocol', () => {
  const code = readiness('messages/ReaderScript.kt');
  for (const value of [undefined, {}, { protocol: 1, contract() {} }, { protocol: 2, contract() {}, scrape() {} }]) {
    assert.equal(vm.runInNewContext(code, { window: { __vkReader: value } }), false);
  }
  assert.equal(vm.runInNewContext(code, { window: { __vkReader: { protocol: 1, contract() { return { protocol: 1, engine: 1, kind: 'reader', documentId: 'doc' }; }, scrape() {} } } }), true);
});
test('mutation corpus preserves track identity across twelve renamed/optional DOM variants', () => {
  for (let i = 0; i < 12; i++) {
    const attrs = { class: i % 2 ? 'UnknownClass_' + i : '', 'data-audio': tuple() };
    if (i % 3 === 0) attrs['data-id'] = '1_2';
    if (i % 4 === 0) attrs['data-testid'] = 'NewAudioRow';
    const el = row(attrs), p = rowsPage([el]); assert.equal(p.rows.find('1_2'), el);
    assert.equal(el.attrs.class, attrs.class);
  }
});
test('mutation corpus preserves structured data across wrapper and field-order variants', () => {
  const p = page();
  const wrappers = [x => ({ response: x }), x => ({ data: x }), x => ({ result: x }),
    x => ({ data: { response: x } }), x => ({ result: { data: { response: x } } })];
  for (let i = 0; i < 10; i++) {
    const item = i % 2 ? { id: 7, first_name: 'name', optional: true } : { optional: false, first_name: 'name', id: 7 };
    const data = p.api.parse('\uFEFF \n' + JSON.stringify(wrappers[i % wrappers.length]({ items: [item] })));
    assert.equal(p.api.api(data).response.items[0].id, 7);
  }
});
test('mutation corpus rejects missing identity fields instead of choosing an unrelated row', () => {
  for (const index of [0, 1, 3, 4, 5]) {
    const changed = JSON.parse(tuple()); changed[index] = null;
    const el = row({ class: 'audio_item', 'data-id': '1_2', 'data-audio': JSON.stringify(changed) });
    assert.equal(rowsPage([el]).rows.find('1_2'), null);
  }
});
console.log(`PASS ${passed} compatibility checks`);
