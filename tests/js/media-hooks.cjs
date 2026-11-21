// Exercise the actual hooks together in the shared scope used by PageScripts.
const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');
const vm = require('node:vm');
const base = path.resolve(__dirname, '../../app/src/main/assets/vk_lite');
const read = name => fs.readFileSync(path.join(base, name), 'utf8');
function section(source, start, end) {
  const a = source.indexOf(start), b = source.indexOf(end, a);
  assert(a >= 0 && b > a); return source.slice(a, b);
}
const core = section(read('00_core.js'), '  // Плееры часто создают new Audio()', '  // Элементы в DOM');
const worker = section(read('50_music.js'), '  var cacheWorker =', '  var RESOLVE_STEP_MS');
const diagnostics = read('45_music_diagnostics.js');
let passed = 0;
for (const hidden of [false, true]) for (const mode of ['absent', 'off', 'on']) {
  let calls = 0, tracked = 0, fail;
  const result = Promise.resolve('native result');
  function Media() {}
  Media.prototype.play = function (...args) {
    calls++; assert.equal(this, media); assert.deepEqual(args, ['argument']);
    assert.equal(this.muted, hidden); assert.equal(this.volume, hidden ? 0 : 1);
    if (fail) throw fail; return result;
  };
  const media = Object.assign(new Media(), { muted: false, volume: 1, tagName: 'AUDIO' });
  const c = { CONFIG: { cacheWorker: hidden, collectDump: mode === 'on' }, HTMLMediaElement: Media,
    track: () => tracked++, current: null, endedSeen: true, document: { addEventListener() {} },
    window: {}, navigator: {} };
  vm.createContext(c);
  vm.runInContext(core + '\n' + (mode === 'absent' ? '' : diagnostics) + '\n' + worker, c);
  // Before the fix this call throws RangeError in both hidden-window cases.
  assert.equal(media.play('argument'), result);
  assert.equal(calls, 1); assert.equal(tracked, 1); assert.equal(c.current, media); assert.equal(c.endedSeen, false);
  fail = Error('native failure'); assert.throws(() => media.play('argument'), e => e === fail);
  assert.equal(calls, 2); assert.equal(tracked, 2);
  passed++; console.log(`PASS combined media hooks worker=${hidden} diagnostics=${mode}`);
}
console.log(`PASS ${passed} combined media hook checks`);
