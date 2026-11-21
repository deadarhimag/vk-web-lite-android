const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');
const vm = require('node:vm');
const base = path.resolve(__dirname, '../../app/src/main/assets');
const read = name => fs.readFileSync(path.join(base, name), 'utf8');
function fn(source, name) {
  const match = new RegExp('(^ *)function ' + name + '\\(', 'm').exec(source); assert(match, name);
  const end = source.indexOf('\n' + match[1] + '}', match.index); assert(end > match.index);
  return source.slice(match.index, end + match[1].length + 2);
}
function page() {
  const c = { window: {}, location: { pathname: '/friends', search: '' },
    num: value => Number(value) || 0, truthy: value => !!value, queryParam: () => '',
    mergeInto: (target, source) => Object.assign(target || {}, source),
    personBasics: u => ({ id: Number(u.id), name: u.first_name || u.last_name || '' }), firstUrl: () => '',
    MY_GROUPS_BLOCK: /all_groups|my_groups/, MAX_POSTS: 1000, FEED_ID: -999,
    rememberAll: (map, list) => { if (Array.isArray(list)) list.forEach(x => { map[x.id] = x; }); } };
  vm.createContext(c); vm.runInContext(read('shared/compat.js'), c);
  const people = read('vk_lite/60_people.js'), owner = read('vk_lite/70_owner.js');
  vm.runInContext(['toFriend', 'rememberFriend', 'onFriendsGet', 'rememberGroupOrder', 'onGroupsGet', 'onGroupsCatalog'].map(n => fn(people, n)).join('\n') + '\n' + ['isPost', 'onWall'].map(n => fn(owner, n)).join('\n'), c);
  return c;
}
function friends() { return { friends: { 7: { id: 7, name: 'saved' } }, friendOrder: ['7'], total: 1, hasMore: false, listSeen: true }; }
function groups() { return { groups: { 7: { id: 7, name: 'saved' } }, groupOrder: ['7'], counters: {}, total: 1, hasMore: false, listSeen: true }; }
function wall(ownerId = 7) { return { ownerId, users: {}, groups: {}, posts: { '7_1': { id: 1, owner_id: 7, text: 'saved' } }, order: ['7_1'], wallSeen: true, hasMore: false }; }
function unchanged(state, action) { const before = JSON.stringify(state); assert.equal(action(), false); assert.equal(JSON.stringify(state), before); }
let passed = 0;
function test(name, run) { run(); passed++; console.log('PASS', name); }
test('unknown friend fields preserve the entire last good list', () => {
  const c = page(), s = friends(); unchanged(s, () => c.onFriendsGet(s, {}, { items: [{ user_id: 8, name: 'changed' }], count: 1 }));
});
test('mixed friend schema cannot partially overwrite a good snapshot', () => {
  const c = page(), s = friends(); unchanged(s, () => c.onFriendsGet(s, {}, { items: [{ id: 8, first_name: 'valid' }, { id: 9 }], count: 2 }));
});
test('confirmed empty friends are accepted and clear the old JavaScript snapshot', () => {
  const c = page(), s = friends(); assert.equal(c.onFriendsGet(s, {}, { items: [], count: 0 }), true);
  assert.equal(s.friendOrder.length, 0); assert.equal(s.total, 0); assert.equal(s.hasMore, false);
});
test('positive count with empty initial friend page does not become an empty result', () => {
  const c = page(), s = friends(); unchanged(s, () => c.onFriendsGet(s, {}, { items: [], count: 50 }));
});
test('fresh partial friend snapshot remains explicitly incomplete for native merge', () => {
  const c = page(), s = friends(); assert.equal(c.onFriendsGet(s, {}, { items: [{ id: 8, first_name: 'new' }], count: 2 }), true);
  assert.equal(s.friendOrder.join(','), '8'); assert.equal(s.hasMore, true); assert.equal(s.total, 2);
});
test('unknown group entities and invalid totals preserve saved groups', () => {
  const c = page();
  for (const response of [{ items: [{ group_id: 8, name: 'changed' }], count: 1 }, { items: [{ id: 8, name: 'valid' }], count: 'unknown' }]) {
    const s = groups(); unchanged(s, () => c.onGroupsGet(s, { extended: 1 }, response));
  }
});
test('confirmed empty groups remain distinguishable from parser failure', () => {
  const c = page(), s = groups(); assert.equal(c.onGroupsGet(s, { extended: 1 }, { items: [], count: 0 }), true);
  assert.equal(s.groupOrder.length, 0); assert.equal(s.total, 0);
});
test('unrecognized catalog block cannot commit even its auxiliary group entities', () => {
  const c = page(), s = groups(); unchanged(s, () => c.onGroupsCatalog(s, { groups: [{ id: 8, name: 'new' }], block: { data_type: 'my_groups', changed_items: [8] } }));
});
test('invalid post schema preserves header and last good wall together', () => {
  const c = page(), s = wall(); unchanged(s, () => c.onWall(s, { owner_id: 7 }, { items: [{ post_id: 2, text: 'changed' }], profiles: [{ id: 8 }], count: 1 }));
});
test('mixed valid and malformed wall objects do not commit a partial snapshot', () => {
  const c = page(), s = wall(); unchanged(s, () => c.onWall(s, { owner_id: 7 }, { items: [{ id: 2, owner_id: 7, text: 'new' }, { id: 3 }], count: 2 }));
});
test('posts from another owner cannot erase the current owner snapshot', () => {
  const c = page(), s = wall(); unchanged(s, () => c.onWall(s, { owner_id: 7 }, { items: [{ id: 2, owner_id: 8, text: 'other' }], count: 1 }));
});
test('confirmed empty wall remains a valid empty result', () => {
  const c = page(), s = wall(); assert.equal(c.onWall(s, { owner_id: 7 }, { items: [], count: 0 }), true);
  assert.equal(s.order.length, 0); assert.equal(s.hasMore, false);
});
test('known feed post aliases still work alongside ignored feed entries', () => {
  const c = page(), s = wall(-999);
  assert.equal(c.onWall(s, {}, { items: [{ type: 'friend' }, { type: 'post', post_id: 2, source_id: 7, text: 'new' }], next_from: 'cursor' }), true);
  assert.equal(s.order.join(','), '7_2'); assert.equal(s.hasMore, true);
});
test('feed without any supported posts cannot declare saved posts empty', () => {
  const c = page(), s = wall(-999); unchanged(s, () => c.onWall(s, {}, { items: [{ type: 'video', id: 2, owner_id: 7, text: 'other' }], next_from: '' }));
});
console.log(`PASS ${passed} last-good snapshot checks`);
