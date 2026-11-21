// Dependency-free regression checks of actual page functions, using a controlled DOM/timer boundary.
const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');
const vm = require('node:vm');
const { File } = require('node:buffer');
const assets = path.resolve(__dirname, '../../app/src/main/assets');
const read = p => fs.readFileSync(path.join(assets, p), 'utf8');
const reader = read('vk_reader.js');
function between(start, end) {
  const a = reader.indexOf(start), b = reader.indexOf(end, a);
  assert(a >= 0 && b > a, start);
  return reader.slice(a, b);
}
const attachmentSource = between('  var staged = [];', '\n  // ------------------------------------------------------------ старые сообщения');
const sendSource = between('  function sendMessage(', '\n  /**');
const voiceSource = between('  function sendVoice(', '\n  /** Отправленное');
const historySource = between('  var history = {};', '\n  var hasApiData');
const sortedSource = between('  function sortedMessages(', '\n  var coverPending');
const tests = [];
const test = (name, run) => tests.push([name, run]);

function page(multiple = true) {
  let now = 0, nextTimer = 0;
  const timers = new Map(), posted = [], requests = [];
  const input = { multiple, files: [], getAttribute: () => '', dispatchEvent() {} };
  const editor = { focus() {}, getAttribute: () => '', dispatchEvent() {}, value: '' };
  let clicks = 0;
  const button = { disabled: false, getAttribute: () => null, click: () => clicks++ };
  const c = {
    peer: '5', currentPeer: () => c.peer, location: { pathname: '/mail/convo/5' },
    document: { querySelectorAll: q => q === 'input[type="file"]' ? [input] : [], querySelector: () => null },
    CONFIG: { compose: { input: 'editor', send: 'send' } },
    pick: (document, key) => key === 'editor' ? editor : button,
    setInputValue: (node, value) => { node.value = value; },
    post: value => posted.push(value), lastSent: null, addPendingOut() {},
    Event: class {}, InputEvent: class {}, File, atob, AbortController,
    DataTransfer: class { constructor() { this.files = []; this.items = { add: file => this.files.push(file) }; } },
    Date: { now: () => now },
    setTimeout: (callback, delay) => { const id = ++nextTimer; timers.set(id, { at: now + delay, callback }); return id; },
    clearTimeout: id => timers.delete(id),
    NetTap: { apiReady: () => true, onHost: () => true, api: (method, params, callback, retried, options) => requests.push({ method, params, callback, options }) },
    isNum: n => typeof n === 'number' && Number.isFinite(n),
    FormData: class { append() {} },
    fetch: async () => ({ json: async () => ({ file: 'uploaded' }) }),
  };
  vm.createContext(c);
  vm.runInContext(attachmentSource + '\n' + sendSource + '\n' + voiceSource, c);
  function advance(ms) {
    const target = now + ms;
    while (true) {
      const due = [...timers.entries()].filter(([, timer]) => timer.at <= target).sort((a, b) => a[1].at - b[1].at)[0];
      if (!due) break;
      timers.delete(due[0]); now = due[1].at; due[1].callback();
    }
    now = target;
  }
  function stage(count = 1, id = 'operation') {
    assert.equal(c.beginTransfer(id, '5'), true);
    for (let i = 0; i < count; i++) assert.equal(c.stageFile(i, 'file' + i, 'application/octet-stream', 1, 0, 'AAEC', id), true);
    return id;
  }
  return { c, posted, requests, input, editor, advance, stage, clicks: () => clicks, timers };
}

test('all assembled page programs parse', () => {
  const shared = read('shared/compat.js') + '\n' + read('shared/net_tap.js') + '\n' + read('shared/dump_core.js');
  const lite = fs.readdirSync(path.join(assets, 'vk_lite')).filter(f => f.endsWith('.js')).sort().map(f => read('vk_lite/' + f)).join('\n');
  for (const source of [read('page_visibility.js'), read('privacy.js'), shared + '\n' + reader, shared + '\n' + lite]) new vm.Script(source);
});
test('normal transfer sends once and preserves all selected files', () => {
  const p = page(); const op = p.stage(3);
  p.c.attachAndSend('caption', op);
  assert.equal(p.input.files.length, 3);
  p.advance(3000);
  assert.equal(p.clicks(), 1);
  assert.equal(p.editor.value, 'caption');
  const stages = p.posted.filter(x => x.type === 'attach').map(x => x.stage);
  assert(stages.indexOf('uploading') < stages.indexOf('sending'));
  assert(p.posted.findIndex(x => x.type === 'attach' && x.stage === 'sending') < p.posted.findIndex(x => x.type === 'sent'));
});
test('single-file input rejects multiple files without sending any', () => {
  const p = page(false); const op = p.stage(3);
  p.c.attachAndSend('caption', op); p.advance(3000);
  assert.equal(p.input.files.length, 0);
  assert.equal(p.clicks(), 0);
  assert(p.posted.some(x => x.type === 'attach' && x.stage === 'error'));
});
test('peer switch during upload prevents late click', () => {
  const p = page(); const op = p.stage();
  p.c.attachAndSend('for 5', op); p.c.peer = '6'; p.advance(3000);
  assert.equal(p.clicks(), 0);
});
test('peer switch between ready and delayed click is also guarded', () => {
  const p = page(); const op = p.stage();
  p.c.attachAndSend('for 5', op); p.advance(2000); p.c.peer = '6'; p.advance(200);
  assert.equal(p.clicks(), 0);
});
test('explicit cancellation stops attachment timers', () => {
  const p = page(); const op = p.stage();
  p.c.attachAndSend('cancelled', op); p.c.cancelTransfer(op); p.advance(3000);
  assert.equal(p.clicks(), 0);
  assert.equal(p.timers.size, 0);
});
test('cancellation in the final 120ms prevents click', () => {
  const p = page(); const op = p.stage();
  p.c.attachAndSend('cancelled', op); p.advance(2000); p.c.cancelTransfer(op); p.advance(200);
  assert.equal(p.clicks(), 0);
});
test('stale cancellation cannot cancel a newer operation', () => {
  const p = page(); p.stage(1, 'old'); const op = p.stage(1, 'new');
  p.c.cancelTransfer('old'); p.c.attachAndSend('new', op); p.advance(3000);
  assert.equal(p.clicks(), 1);
});
test('stale chunks and commands cannot join a new transfer', () => {
  const p = page(); p.stage(1, 'old'); p.stage(1, 'new');
  assert.equal(p.c.stageFile(1, 'old', '', 1, 0, 'AAEC', 'old'), false);
  p.c.attachAndSend('old', 'old'); p.advance(3000);
  assert.equal(p.clicks(), 0);
});
test('stage validates chunk order metadata and duplicates', () => {
  const p = page(); const op = p.stage();
  assert.equal(p.c.stageFile(0, 'duplicate', '', 1, 0, 'AAEC', op), false);
  assert.equal(p.c.stageFile(10, 'bad', '', 1, 0, 'AAEC', op), false);
  assert.equal(p.c.stageFile(1, 'bad', '', 65, 0, 'AAEC', op), false);
  assert.equal(p.c.stageFile(1, 'bad', '', 1, 0, '%%%%', op), false);
});
test('actual staged bytes cannot exceed 24 MiB total', () => {
  const p = page(); const op = 'large'; p.c.beginTransfer(op, '5');
  const data = Buffer.alloc(192 * 1024).toString('base64');
  for (let file = 0; file < 2; file++) for (let part = 0; part < 64; part++) {
    assert.equal(p.c.stageFile(file, 'large', '', 64, part, data, op), true);
  }
  assert.equal(p.c.stageFile(2, 'too-much', '', 1, 0, 'AAEC', op), false);
});
test('voice cancellation before upload response prevents later API steps', () => {
  const p = page(); const op = p.stage();
  p.c.sendVoice('5', 42, op);
  assert.equal(p.requests[0].method, 'docs.getMessagesUploadServer');
  p.c.cancelTransfer(op);
  p.requests[0].callback(null, { upload_url: 'https://pu.vk.com/upload' });
  assert.equal(p.requests.length, 1);
  assert.equal(p.posted.filter(x => x.type === 'voiceSent').length, 0);
});
test('normal voice upload keeps the original peer and random id', async () => {
  const p = page(); const op = p.stage();
  p.c.sendVoice('5', 42, op);
  p.requests[0].callback(null, { upload_url: 'https://pu.vk.com/upload' });
  await new Promise(resolve => setImmediate(resolve));
  assert.equal(p.requests[1].method, 'docs.save');
  p.requests[1].callback(null, { audio_message: { owner_id: 5, id: 7 } });
  assert.equal(p.requests[2].method, 'messages.send');
  assert.equal(p.requests[2].params.peer_id, '5');
  assert.equal(p.requests[2].params.random_id, 42);
  const stage = p.posted.find(x => x.type === 'voiceStage');
  assert.equal(stage.operation, op);
  assert.equal(stage.documentId, '5_7');
  p.requests[2].callback(null);
  assert(p.posted.some(x => x.type === 'voiceSent' && x.ok));
  assert.equal(p.posted.find(x => x.type === 'voiceSent').documentId, '5_7');
});
test('history LRU stays bounded and retains the open conversation', () => {
  const c = { currentPeer: () => '5', MAX_HISTORY: 2000 };
  vm.createContext(c); vm.runInContext(historySource + '\n' + sortedSource, c);
  c.historyBucket('5');
  for (let i = 10; i < 110; i++) c.historyBucket(i);
  assert.equal(Object.keys(c.history).length, 16);
  assert(c.history['5']);
});
test('stored message count is pruned, rather than only limiting output', () => {
  const c = { currentPeer: () => '5', MAX_HISTORY: 2000 };
  vm.createContext(c); vm.runInContext(historySource + '\n' + sortedSource, c);
  const bucket = c.historyBucket('5');
  for (let i = 0; i < 2100; i++) bucket['c' + i] = { key: 'c' + i, date: i, cmid: i, provisional: false };
  c.trimHistory('5');
  assert.equal(Object.keys(bucket).length, 2000);
  assert.equal(bucket.c0, undefined);
  assert(bucket.c2099);
});

test('cancelled composer cannot send leftover files with the next queued text', () => {
  const p = page(); const op = p.stage();
  p.c.attachAndSend('cancelled caption', op); p.advance(2000);
  p.c.cancelTransfer(op); p.advance(200);
  p.c.sendMessage('ordinary queued text'); p.advance(200);
  assert.equal(p.clicks(), 0);
  assert(p.posted.some(x => x.type === 'attach' && x.stage === 'reset'));
  assert.equal(p.c.beginTransfer('retry', '5'), false);
  const fresh = page(); fresh.c.sendMessage('ordinary queued text'); fresh.advance(200);
  assert.equal(fresh.clicks(), 1);
});
test('completed attachment click does not dirty the next transfer', () => {
  const p = page(); const op = p.stage();
  p.c.attachAndSend('caption', op); p.advance(3000);
  assert.equal(p.clicks(), 1);
  p.c.cancelTransfer(op);
  assert.equal(p.c.beginTransfer('new', '5'), true);
  assert(!p.posted.some(x => x.type === 'attach' && x.stage === 'reset'));
});
test('voice API timeout terminates the operation and aborts pending requests', () => {
  const p = page(); const op = p.stage();
  p.c.sendVoice('5', 42, op); p.advance(120000);
  const result = p.posted.filter(x => x.type === 'voiceSent');
  assert.equal(result.length, 1);
  assert.equal(result[0].uncertain, false);
  assert.equal(result[0].operation, op);
  assert.equal(p.requests[0].options.signal.aborted, true);
  assert.equal(p.c.transferCurrent(op), false);
  p.requests[0].callback(null, { upload_url: 'https://pu.vk.com/upload' });
  assert.equal(p.requests.length, 1);
});
test('hanging voice upload is aborted without continuing to docs.save', () => {
  const p = page(); const op = p.stage(); let uploadOptions;
  p.c.fetch = (url, options) => { uploadOptions = options; return new Promise(() => {}); };
  p.c.sendVoice('5', 42, op);
  p.requests[0].callback(null, { upload_url: 'https://pu.vk.com/upload' });
  p.advance(120000);
  assert.equal(uploadOptions.signal.aborted, true);
  assert.equal(p.requests.length, 1);
  assert.equal(p.posted.filter(x => x.type === 'voiceSent').length, 1);
});
test('voice timeout while saving the uploaded document prevents messages.send', async () => {
  const p = page(); const op = p.stage();
  p.c.sendVoice('5', 42, op);
  p.requests[0].callback(null, { upload_url: 'https://pu.vk.com/upload' });
  await new Promise(resolve => setImmediate(resolve));
  p.advance(120000);
  p.requests[1].callback(null, { audio_message: { owner_id: 5, id: 7 } });
  assert.equal(p.requests.length, 2);
  assert.equal(p.posted.find(x => x.type === 'voiceSent').uncertain, false);
});
test('cancelled voice API cannot retry after waiting for renewed authentication', async () => {
  const tap = read('shared/net_tap.js');
  const api = tap.slice(tap.indexOf('  function api('), tap.indexOf('  function waitAuth('));
  const controller = new AbortController();
  let calls = 0, renew;
  const c = { URLSearchParams, auth: { token: 'old', base: 'https://api.vk.com/method/', v: '5.0' },
    authValid: () => true, dropAuth: () => { c.auth = null; }, authEvent() {},
    waitAuth: callback => { renew = callback; },
    window: { fetch: (url, options) => {
      calls++; assert.equal(options.signal, controller.signal);
      return Promise.resolve({ json: async () => ({ error: { error_code: 5 } }) });
    } },
  };
  vm.createContext(c); vm.runInContext(api, c);
  let done = 0;
  c.api('docs.save', {}, () => done++, false, { signal: controller.signal });
  await new Promise(resolve => setImmediate(resolve));
  assert.equal(typeof renew, 'function');
  controller.abort(); c.auth = { token: 'new' }; renew(true);
  assert.equal(calls, 1);
  assert.equal(done, 1);
});
test('voice timeout during messages.send reports uncertain delivery exactly once', async () => {
  const p = page(); const op = p.stage();
  p.c.sendVoice('5', 42, op);
  p.requests[0].callback(null, { upload_url: 'https://pu.vk.com/upload' });
  await new Promise(resolve => setImmediate(resolve));
  p.requests[1].callback(null, { audio_message: { owner_id: 5, id: 7 } });
  p.advance(120000);
  assert.equal(p.posted.filter(x => x.type === 'voiceSent').length, 1);
  assert.equal(p.posted.find(x => x.type === 'voiceSent').uncertain, true);
  p.requests[2].callback(null);
  assert.equal(p.posted.filter(x => x.type === 'voiceSent').length, 1);
});

(async () => {
  for (const [name, run] of tests) { await run(); console.log('PASS', name); }
  console.log(`PASS ${tests.length} regression checks`);
})().catch(error => { console.error(error); process.exitCode = 1; });
