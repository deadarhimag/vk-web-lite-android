// Проверки скриптов страницы в jsdom. Запуск: cd tests/js && npm install && npm test
// jsdom не рисует и не имеет IntersectionObserver / настоящей скрытой страницы, поэтому «скрытое окно» задаётся
// заглушками, как у Chromium: наблюдатель молчит, кадров нет, document.hidden == true.
const test = require('node:test');
const assert = require('node:assert');
const fs = require('fs');
const path = require('path');
const vm = require('vm');
const { JSDOM } = require('jsdom');

const ASSETS = path.join(__dirname, '..', '..', 'app', 'src', 'main', 'assets');
const read = (p) => fs.readFileSync(path.join(ASSETS, p), 'utf8');
// Как собирает приложение (web/PageScripts.kt): общий перехват, затем части по порядку имён.
const netTapSrc = () => read('shared/compat.js') + '\n' + read('shared/net_tap.js') + '\n' + read('shared/dump_core.js');
const liteParts = () => netTapSrc() + '\n' + fs.readdirSync(path.join(ASSETS, 'vk_lite')).filter((f) => f.endsWith('.js')).sort()
  .map((f) => read('vk_lite/' + f)).join('\n');

const LITE_CONFIG = {
  css: '.x{}', bridge: 'vkLiteBridge', promoClicks: ['остаться в браузере'], promoHides: ['откройте приложение'],
  promoIgnoreSelector: '.msg', blockAdData: true, blockClips: true, collectDump: false, nextSelector: '', prevSelector: '',
};

function page(url, html = '<!doctype html><html><head></head><body></body></html>') {
  const dom = new JSDOM(html, { url, runScripts: 'outside-only', pretendToBeVisual: false });
  const w = dom.window;
  w.scrollTo = () => {};
  w.sent = [];
  w.vkLiteBridge = { postMessage: (m) => w.sent.push(m) };
  return w;
}

/** Заглушки скрытой страницы Chromium: наблюдатель пересечений и кадры молчат. */
function hideLikeChromium(w) {
  w.eval(`window.IntersectionObserver = function (cb, o) { this.root = (o && o.root) || null; this.rootMargin = (o && o.rootMargin) || '0px'; this.thresholds = [0]; };
    IntersectionObserver.prototype.observe = function () {}; IntersectionObserver.prototype.unobserve = function () {}; IntersectionObserver.prototype.disconnect = function () {};
    window.requestAnimationFrame = function () { return 1; }; window.cancelAnimationFrame = function () {};`);
}

const wait = (ms) => new Promise((r) => setTimeout(r, ms));

test('все скрипты разбираются без синтаксических ошибок', () => {
  for (const f of ['page_visibility.js', 'privacy.js', 'shared/net_tap.js']) new vm.Script(read(f), { filename: f });
  new vm.Script(netTapSrc() + '\n' + read('vk_reader.js'), { filename: 'vk_reader.js' });
  new vm.Script(liteParts().replace('/*CONFIG*/null', '{"css":""}'), { filename: 'vk_lite/*' });
});

test('страница не видит блокировку экрана, свои скрипты — видят', () => {
  const w = page('https://m.vk.com/audio');
  w.eval(read('page_visibility.js'));
  let seenByPage = 0;
  w.document.addEventListener('visibilitychange', () => seenByPage++);
  w.addEventListener('blur', () => seenByPage++);
  const own = [];
  w.__vkWebVisibility.onChange((h) => own.push(h));
  w.document.dispatchEvent(new w.Event('visibilitychange', { bubbles: true }));
  w.dispatchEvent(new w.Event('blur'));
  assert.strictEqual(w.document.hidden, false);
  assert.strictEqual(w.document.visibilityState, 'visible');
  assert.strictEqual(w.document.hasFocus(), true);
  assert.strictEqual(seenByPage, 0, 'события смены видимости не доходят до страницы');
  assert.strictEqual(own.length, 1, 'свои скрипты получили уведомление');
  assert.strictEqual(w.__vkWebVisibility.hidden(), true, 'настоящая видимость доступна');
});

test('скрытое окно: наблюдатель пересечений работает по расчёту, прокрутка догружает ленту', async () => {
  const w = page('https://m.vk.com/audio', '<!doctype html><body><div id="end"></div></body>');
  hideLikeChromium(w);
  w.eval(read('page_visibility.js'));
  let top = 2000;
  const end = w.document.getElementById('end');
  end.getBoundingClientRect = () => ({ top, bottom: top + 10, left: 0, right: 100, width: 100, height: 10 });
  const log = [];
  const io = new w.IntersectionObserver((es) => es.forEach((e) => log.push(e.isIntersecting)), { rootMargin: '0px 0px 300px 0px' });
  assert.ok(io instanceof w.IntersectionObserver);
  io.observe(end);
  await wait(50);
  assert.deepStrictEqual(log, [false], 'о новом элементе сообщают сразу');
  top = 900;
  w.__vkWebVisibility.afterScroll(w);
  await wait(30);
  assert.deepStrictEqual(log, [false, true], 'метка въехала в зону с отступом');
  w.__vkWebVisibility.afterScroll(w);
  await wait(30);
  assert.strictEqual(log.length, 3, 'после прокрутки до конца метка сообщается заново');
  io.disconnect();
  w.__vkWebVisibility.afterScroll(w);
  await wait(30);
  assert.strictEqual(log.length, 3, 'после disconnect — тишина');
});

test('скрытое окно: кадры идут (редким таймером), отмена работает', async () => {
  const w = page('https://m.vk.com/audio');
  hideLikeChromium(w);
  w.eval(read('page_visibility.js'));
  let ran = 0;
  w.requestAnimationFrame(() => ran++);
  const cancelled = w.requestAnimationFrame(() => ran += 100);
  w.cancelAnimationFrame(cancelled);
  await wait(250);
  assert.strictEqual(ran, 1);
});

test('скрытое окно: данные своего экрана уходят приложению без секундной задержки', async () => {
  const track = '[1,2,0,"T","A",100,0,0,0,0,0,"my_audios",0,0,""]';
  const w = page('https://m.vk.com/audio', `<!doctype html><body><div class="audio_item" data-audio='${track}' data-id="2_1"></div></body>`);
  w.eval(read('page_visibility.js'));
  w.eval(liteParts().replace('/*CONFIG*/null', JSON.stringify(LITE_CONFIG)));
  const t0 = Date.now();
  w.__vkLite.music('sync');
  while (!w.sent.length && Date.now() - t0 < 900) await wait(2);
  assert.ok(w.sent.length, 'сообщение пришло');
  assert.ok(Date.now() - t0 < 400, 'быстрее таймера, который в скрытом окне ждёт секунду');
  assert.strictEqual(JSON.parse(w.sent[0]).type, 'music');
});

test('окно «Откройте приложение» скрывается, обычная смена текста — нет', async () => {
  const w = page('https://m.vk.com/feed', '<!doctype html><body><div id="t">0:01</div></body>');
  w.eval(read('page_visibility.js'));
  w.eval(liteParts().replace('/*CONFIG*/null', JSON.stringify(LITE_CONFIG)));
  const popup = w.document.createElement('div');
  popup.textContent = 'Откройте приложение VK';
  w.document.body.appendChild(popup);
  w.document.getElementById('t').firstChild.nodeValue = '0:02';
  await wait(40);
  assert.strictEqual(popup.style.display, 'none');
  assert.strictEqual(w.document.getElementById('t').style.display, '');
});

test('скрипт идемпотентен и объявляет API один раз', () => {
  const w = page('https://m.vk.com/feed');
  const src = liteParts().replace('/*CONFIG*/null', JSON.stringify(LITE_CONFIG));
  w.eval(read('page_visibility.js'));
  w.eval(src);
  const api = w.__vkLite;
  w.eval(src);
  assert.strictEqual(w.__vkLite, api);
});

test('читатель сообщений встаёт и отвечает на проверку «скрипт уже на странице»', () => {
  const cfg = { bridge: 'vkReaderBridge', loginMarker: '#login', dialogs: { row: '.d', link: 'a', title: '.t', snippet: '.s', time: '.tm', unread: '.u', online: '.o' },
    history: { row: '.m', text: '.tx', outMarker: '.out', time: '.tm', title: '.h' }, compose: { input: 'textarea', send: 'button' } };
  const w = page('https://m.vk.com/mail/convo/5');
  w.vkReaderBridge = { postMessage: () => {} };
  w.eval(read('page_visibility.js'));
  w.eval(netTapSrc() + '\n' + read('vk_reader.js').replace('/*CONFIG*/null', JSON.stringify(cfg)));
  assert.strictEqual(w.eval('!!window.__vkReader'), true);
});

test('окно чтения: пересланные сообщения — автор, текст, вложения и вложенные с уровнем, без подписи в скобках', async () => {
  const w = readerPage();
  w.nextResponse = JSON.stringify({ response: {
    items: [{ id: 1, conversation_message_id: 301, date: 1700000000, from_id: 5, peer_id: 5, out: 0, text: 'Глянь',
      fwd_messages: [{ from_id: 8, date: 1690000000, text: 'Исходное', attachments: [{ type: 'audio', audio: { artist: 'A', title: 'B', id: 1, owner_id: 2 } }],
        fwd_messages: [{ from_id: -9, date: 1680000000, text: 'Ещё глубже' }] }] }],
    profiles: [{ id: 5, first_name: 'Иван', last_name: 'Петров' }, { id: 8, first_name: 'Анна', last_name: 'К' }],
    groups: [{ id: 9, name: 'Клуб', screen_name: 'club9' }] } });
  await w.fetch('https://web.api.vk.ru/method/messages.getHistory?peer_id=5');
  await wait(30);
  w.__vkReader.scrape('history');
  await wait(60);
  const m = w.sentToApp.filter((x) => x.type === 'history').pop().items[0];
  assert.strictEqual(m.text, 'Глянь', 'без «[Пересланные сообщения]»');
  assert.deepStrictEqual(m.forwards.map((f) => [f.sender, f.depth, f.text]), [['Анна К', 1, 'Исходное'], ['Клуб', 2, 'Ещё глубже']]);
  assert.strictEqual(m.forwards[0].cards[0].kind, 'audio', 'вложение пересланного — в нём самом');
  assert.ok(m.forwards[0].time, 'время пересланного');
});

test('окно чтения: музыка из сообщения играет в переписке — нажатие на вложение, состояние приложению, пауза и стоп', async () => {
  const tuple = JSON.stringify([77, -5, '', 'Песня', 'Группа', 185, 0, 0, '', 0, 0, '', '[]', '', '']).replace(/"/g, '&quot;');
  const w = readerPage(`<div class="audio_item" data-id="-5_77" data-audio="${tuple}" id="track"></div>`);
  // Плеер страницы: по нажатию создаёт звук вне документа и вызывает play() — как настоящий.
  w.eval(`window.HTMLMediaElement.prototype.load = function () {};
    Object.defineProperty(window.HTMLMediaElement.prototype, 'paused', { configurable: true, get() { return !this.__on; } });
    document.getElementById('track').addEventListener('click', function () {
      window.__a = new Audio(); window.__a.play();
    });`);
  // Настоящий play в jsdom не реализован: подменяем до перехвата нельзя (перехват уже стоит) — играем «поверх».
  w.eval(`(function () { var hooked = HTMLMediaElement.prototype.play; HTMLMediaElement.prototype.play = function () {
      try { hooked.apply(this, arguments); } catch (e) {} this.__on = true; this.dispatchEvent(new Event('playing')); return Promise.resolve(); };
    HTMLMediaElement.prototype.pause = function () { this.__on = false; this.dispatchEvent(new Event('pause')); }; })();`);
  assert.strictEqual(w.__vkReader.playAudio('-5_77', 'Группа — Песня'), true);
  await wait(50);
  let last = w.sentToApp.filter((m) => m.type === 'audio').pop();
  assert.deepStrictEqual([last.state, last.title], ['playing', 'Группа — Песня']);
  w.__vkReader.audio('toggle');
  last = w.sentToApp.filter((m) => m.type === 'audio').pop();
  assert.strictEqual(last.state, 'paused');
  w.__vkReader.audio('stop');
  last = w.sentToApp.filter((m) => m.type === 'audio').pop();
  assert.strictEqual(last.state, 'stopped');
  // Трека нет в вёрстке — «notfound» с описанием вёрстки (приложение включит его в разделе музыки).
  w.__vkReader.playAudio('1_2', 'X');
  last = w.sentToApp.filter((m) => m.type === 'audio').pop();
  assert.strictEqual(last.state, 'notfound');
  assert.match(last.detail, /audio_item=1/);
});

test('окно чтения: вложение-трек новой вёрстки (AttachAudio, без номера) находится по названию и нажимается кнопкой', async () => {
  const w = readerPage(`<div class="AttachAudio" id="a1"><div class="AttachAudio__album" id="play1"></div><span>Другая — Песня</span></div>
    <div class="AttachAudio" id="a2"><div class="AttachAudio__album" id="play2"></div><span>Группа</span> <span>Ночная песня</span></div>`);
  w.eval(`Object.defineProperty(window.HTMLMediaElement.prototype, 'paused', { configurable: true, get() { return !this.__on; } });
    document.getElementById('play2').addEventListener('click', function () { window.__clicked = 'play2'; window.__a = new Audio(); window.__a.play(); });
    document.getElementById('play1').addEventListener('click', function () { window.__clicked = 'play1'; });
    (function () { var hooked = HTMLMediaElement.prototype.play; HTMLMediaElement.prototype.play = function () {
      try { hooked.apply(this, arguments); } catch (e) {} this.__on = true; this.dispatchEvent(new Event('playing')); return Promise.resolve(); }; })();`);
  assert.strictEqual(w.__vkReader.playAudio('-2001_154', 'Группа — Ночная песня'), true);
  await wait(50);
  assert.strictEqual(w.eval('window.__clicked'), 'play2', 'нажата обложка нужного вложения');
  assert.strictEqual(w.sentToApp.filter((m) => m.type === 'audio').pop().state, 'playing');
});

test('окно чтения: музыку в переписке поставили на паузу в первые секунды — это не «не заиграла»', async () => {
  const tuple = JSON.stringify([77, -5, '', 'Песня', 'Группа', 185, 0, 0, '', 0, 0, '', '[]', '', '']).replace(/"/g, '&quot;');
  const w = readerPage(`<div class="audio_item" data-id="-5_77" data-audio="${tuple}" id="track"></div>`);
  w.eval(`Object.defineProperty(window.HTMLMediaElement.prototype, 'paused', { configurable: true, get() { return !this.__on; } });
    document.getElementById('track').addEventListener('click', function () { window.__a = new Audio(); window.__a.play(); });
    (function () { var hooked = HTMLMediaElement.prototype.play; HTMLMediaElement.prototype.play = function () {
      try { hooked.apply(this, arguments); } catch (e) {} this.__on = true; this.dispatchEvent(new Event('playing')); return Promise.resolve(); };
    HTMLMediaElement.prototype.pause = function () { this.__on = false; this.dispatchEvent(new Event('pause')); }; })();`);
  w.__vkReader.playAudio('-5_77', 'Группа — Песня');
  await wait(50);
  w.__vkReader.audio('toggle'); // пауза человеком
  await wait(4000);
  assert.ok(!w.sentToApp.some((m) => m.type === 'audio' && m.state === 'failed'), 'нет «не заиграла»');
});

test('переписка: строка вёрстки с вложением (файлом) черновиком не становится', async () => {
  const cfg = { bridge: 'vkReaderBridge', loginMarker: '#login', dialogs: { row: '.d', link: 'a', title: '.t', snippet: '.s', time: '.tm', unread: '.u', online: '.o' },
    history: { row: '.m', text: '.tx', outMarker: '.out', time: '.tm', title: '.h' }, compose: { input: 'textarea', send: 'button' } };
  const rows = '<div class="m"><span class="tx">bloknotik txt Документ 12 КБ Скачать</span><a href="/doc1_2">файл</a></div>' +
    '<div class="m"><img class="Avatar__img" src="https://x/a.jpg"><span class="tx">Совсем новое</span></div>';
  const w = page('https://m.vk.com/mail/convo/5', `<!doctype html><body><div id="h">${rows}</div></body>`);
  const sent = [];
  w.vkReaderBridge = { postMessage: (m) => sent.push(JSON.parse(m)) };
  const api = JSON.stringify({ response: { items: [{ id: 100, conversation_message_id: 10, date: 1700000000, from_id: 5, peer_id: 5, out: 0, text: '',
    attachments: [{ type: 'doc', doc: { id: 2, owner_id: 1, title: 'bloknotik.txt', ext: 'txt', size: 12000, url: 'https://vk.com/doc1_2' } }] }] } });
  w.fetch = async () => ({ ok: true, headers: { get: () => 'application/json' }, clone() { return { text: async () => api }; } });
  w.eval(read('page_visibility.js'));
  w.eval(netTapSrc() + '\n' + read('vk_reader.js').replace('/*CONFIG*/null', JSON.stringify(cfg)));
  await w.fetch('https://api.vk.com/method/messages.getHistory');
  await wait(20);
  w.document.getElementById('h').appendChild(w.document.createElement('i'));
  await wait(1700);
  w.__vkReader.scrape('history');
  await wait(50);
  const history = sent.filter((m) => m.type === 'history').pop();
  assert.deepStrictEqual(history.items.map((m) => m.cards.length ? 'файл' : m.text), ['файл', 'Совсем новое'],
    'строка с файлом не стала пузырём; строка с аватаром — обычный текст');
});

function readerApiPage() {
  const w = page('https://m.vk.com/mail/convo/5', '<!doctype html><body></body>');
  w.sentToApp = [];
  w.vkReaderBridge = { postMessage: (m) => w.sentToApp.push(JSON.parse(m)) };
  w.requests = [];
  w.reply = () => '{}';
  // fetch подменяется до перехвата; страница «уже обращалась к API» — данные входа есть.
  w.fetch = async (input, init) => {
    const url = String(input);
    const body = init && init.body != null ? String(init.body) : '';
    w.requests.push({ url, body });
    const text = w.reply(url, body);
    return { ok: true, status: 200, headers: { get: (h) => (h === 'content-type' ? 'application/json' : null) },
      clone() { return { text: async () => text }; }, json: async () => JSON.parse(text) };
  };
  w.eval(read('page_visibility.js'));
  w.eval(netTapSrc() + '\n' + read('vk_reader.js').replace('/*CONFIG*/null', JSON.stringify(READER_CFG)));
  return w;
}

test('окно чтения: ответ на сообщение — цитата с автором и номером, отправка ответа запросом страницы', async () => {
  const w = readerPage();
  w.nextResponse = JSON.stringify({ response: {
    items: [{ id: 2, conversation_message_id: 302, date: 1700000100, from_id: 5, peer_id: 5, out: 1, text: 'Согласен',
      reply_message: { conversation_message_id: 301, from_id: 8, date: 1700000000, text: 'Пойдём завтра в кино?\nВ семь' } }],
    profiles: [{ id: 8, first_name: 'Анна', last_name: 'К' }] } });
  await w.fetch('https://web.api.vk.ru/method/messages.getHistory?peer_id=5', { method: 'POST', body: 'peer_id=5&access_token=TKN&v=5.199' });
  await wait(30);
  w.__vkReader.scrape('history');
  await wait(60);
  const m = w.sentToApp.filter((x) => x.type === 'history').pop().items[0];
  assert.deepStrictEqual(m.reply, { cmid: 301, sender: 'Анна К', text: 'Пойдём завтра в кино? В семь' });
  assert.strictEqual(m.text, 'Согласен', 'без «[Ответ]»');
});

test('окно чтения: ответ отправляется messages.send с forward {is_reply}, итог — «replied»', async () => {
  const w = readerApiPage();
  w.reply = (url) => (url.includes('messages.send') ? '{"response":{"cmid":303,"message_id":3}}' : '{"response":{"items":[]}}');
  await w.fetch('https://web.api.vk.ru/method/messages.getHistory', { method: 'POST', body: 'peer_id=5&access_token=TKN&v=5.199' });
  await wait(30);
  w.__vkReader.reply('Да, давай', 301);
  await wait(80);
  const send = w.requests.find((r) => r.url.includes('/method/messages.send'));
  const p = Object.fromEntries(new URLSearchParams(send.body));
  assert.deepStrictEqual([p.peer_id, p.message, p.access_token], ['5', 'Да, давай', 'TKN']);
  assert.deepStrictEqual(JSON.parse(p.forward), { peer_id: 5, conversation_message_ids: [301], is_reply: true });
  assert.deepStrictEqual(w.sentToApp.filter((x) => x.type === 'replied').pop(), { type: 'replied', ok: true, reason: '' });
});

test('окно чтения: старые сообщения — запросом messages.getHistory со смещением; пусто — «история с начала»', async () => {
  const w = readerApiPage();
  const page = (from, n) => ({ response: { count: 150, items: Array.from({ length: n }, (_, i) => ({ id: from - i, conversation_message_id: from - i,
    date: 1700000000 + from - i, from_id: 5, peer_id: 5, out: 0, text: 'm' + (from - i) })) } });
  w.reply = (url, body) => {
    if (!url.includes('messages.getHistory')) return '{}';
    const offset = +new URLSearchParams(body).get('offset') || 0;
    return JSON.stringify(offset === 0 ? page(150, 50) : (offset < 150 ? page(150 - offset, 100) : { response: { count: 150, items: [] } }));
  };
  await w.fetch('https://web.api.vk.ru/method/messages.getHistory', { method: 'POST', body: 'peer_id=5&offset=0&access_token=TKN&v=5.199' });
  await wait(30);
  w.__vkReader.loadOlder();
  await wait(80);
  const older = w.requests.filter((r) => r.url.includes('messages.getHistory')).pop();
  const p = Object.fromEntries(new URLSearchParams(older.body));
  assert.deepStrictEqual([p.peer_id, p.offset, p.count, p.access_token], ['5', '50', '100', 'TKN']);
  w.__vkReader.scrape('history');
  await wait(80);
  assert.strictEqual(w.sentToApp.filter((x) => x.type === 'history').pop().items.length, 150, 'старые разобраны как обычный ответ');
  w.__vkReader.loadOlder();
  await wait(80);
  assert.ok(w.sentToApp.some((x) => x.type === 'older' && x.done), 'начало переписки — сообщение приложению');
});

test('переписка: сообщение только с вложением в беседе — имя автора из вёрстки не становится отдельным пузырём', async () => {
  const cfg = { bridge: 'vkReaderBridge', loginMarker: '#login', dialogs: { row: '.d', link: 'a', title: '.t', snippet: '.s', time: '.tm', unread: '.u', online: '.o' },
    history: { row: '.m', text: '.tx', outMarker: '.out', time: '.tm', title: '.h' }, compose: { input: 'textarea', send: 'button' } };
  const rows = '<div class="m"><span class="tx">Иван Петров Группа — Песня 3:05</span></div>' +
    '<div class="m"><span class="tx">Иван Петров Новое сообщение</span></div>';
  const w = page('https://m.vk.com/mail/convo/2000000001', `<!doctype html><body><div id="h">${rows}</div></body>`);
  const sent = [];
  w.vkReaderBridge = { postMessage: (m) => sent.push(JSON.parse(m)) };
  const api = JSON.stringify({ response: {
    items: [{ id: 100, conversation_message_id: 10, date: 1700000000, from_id: 5, peer_id: 2000000001, out: 0, text: '',
      attachments: [{ type: 'audio', audio: { artist: 'Группа', title: 'Песня', duration: 185, id: 1, owner_id: 2 } }] }],
    profiles: [{ id: 5, first_name: 'Иван', last_name: 'Петров' }] } });
  w.fetch = async () => ({ ok: true, headers: { get: () => 'application/json' }, clone() { return { text: async () => api }; } });
  w.eval(read('page_visibility.js'));
  w.eval(netTapSrc() + '\n' + read('vk_reader.js').replace('/*CONFIG*/null', JSON.stringify(cfg)));
  await w.fetch('https://api.vk.com/method/messages.getHistory');
  await wait(20);
  w.document.getElementById('h').appendChild(w.document.createElement('i'));
  await wait(1700);
  w.__vkReader.scrape('history');
  await wait(50);
  const history = sent.filter((m) => m.type === 'history').pop();
  assert.deepStrictEqual(history.items.map((m) => m.cards.length ? 'вложение' : m.text),
    ['вложение', 'Иван Петров Новое сообщение'], 'строка с вложением узнана, новое сообщение — нет');
});

test('музыка: первая порция из вёрстки без ответа сервера — не «весь список» (сохранённое не выбрасывается)', async () => {
  const t = (n) => `[${n},2,0,"T${n}","A",100,0,0,0,0,0,"my_audios",0,0,""]`;
  const w = page('https://m.vk.com/audio',
    `<!doctype html><body><div class="audio_item" data-audio='${t(1)}' data-id="2_1"></div><div class="audio_item" data-audio='${t(2)}' data-id="2_2"></div></body>`);
  // Ответ сервера «Моих треков» с явным «продолжения нет».
  const serverBody = JSON.stringify({ list: [JSON.parse(t(1)), JSON.parse(t(2)), JSON.parse(t(3))], hasMore: false });
  w.fetch = async () => ({ ok: true, headers: { get: (h) => (h === 'content-type' ? 'application/json' : null) },
    clone() { return { text: async () => serverBody }; } });
  w.eval(read('page_visibility.js'));
  w.eval(liteParts().replace('/*CONFIG*/null', JSON.stringify(LITE_CONFIG)));
  w.__vkLite.music('sync');
  await wait(30);
  const music = () => w.sent.map((m) => JSON.parse(m)).filter((m) => m.type === 'music').pop();
  const first = music();
  assert.strictEqual(first.items.length, 2);
  assert.strictEqual(first.complete, false, 'кнопки «Показать ещё» нет, но это не значит, что список весь');
  assert.strictEqual(first.hasMore, true, 'пока сервер не ответил, продолжение возможно');
  await w.fetch('https://m.vk.com/audio?act=load_section');
  await wait(30);
  const last = music();
  assert.strictEqual(last.items.length, 3);
  assert.strictEqual(last.complete, true, 'сервер явно сказал «продолжения нет»');
  assert.strictEqual(last.hasMore, false);
});

test('переписка: строки вёрстки с временем не становятся дублями серверных сообщений', async () => {
  const cfg = { bridge: 'vkReaderBridge', loginMarker: '#login', dialogs: { row: '.d', link: 'a', title: '.t', snippet: '.s', time: '.tm', unread: '.u', online: '.o' },
    history: { row: '.m', text: '.tx', outMarker: '.out', time: '.tm', title: '.h' }, compose: { input: 'textarea', send: 'button' } };
  const texts = ['Привет, как дела?', 'Нормально, а у тебя', 'Тоже хорошо', 'Завтра увидимся', 'Договорились!', 'До встречи'];
  const rows = texts.map((t) => `<div class="m"><span class="tx">${t} 12:3${t.length % 10}</span></div>`).join('');
  const w = page('https://m.vk.com/mail/convo/5', `<!doctype html><body><div id="h">${rows}</div></body>`);
  const sent = [];
  w.vkReaderBridge = { postMessage: (m) => sent.push(JSON.parse(m)) };
  const api = JSON.stringify({ response: { items: texts.map((t, i) => ({ id: 100 + i, conversation_message_id: 10 + i, date: 1700000000 + i,
    from_id: 5, peer_id: 5, out: 0, text: t })) } });
  w.fetch = async () => ({ ok: true, headers: { get: () => 'application/json' }, clone() { return { text: async () => api }; } });
  w.eval(read('page_visibility.js'));
  w.eval(netTapSrc() + '\n' + read('vk_reader.js').replace('/*CONFIG*/null', JSON.stringify(cfg)));
  await w.fetch('https://api.vk.com/method/messages.getHistory');
  await wait(20);
  // Вёрстка «изменилась» — сверка хвоста по вёрстке (не чаще раза в 1,5 с).
  w.document.getElementById('h').appendChild(w.document.createElement('i'));
  await wait(1700);
  w.__vkReader.scrape('history');
  await wait(50);
  const history = sent.filter((m) => m.type === 'history').pop();
  assert.ok(history, 'переписка пришла');
  assert.strictEqual(history.items.length, texts.length, 'без дублей из вёрстки');
  assert.ok(history.items.every((m) => /^c\d+$/.test(m.id)), 'только подтверждённые сервером');
});

const track = (n, ctx = 'my_audios') => `[${n},2,0,"Песня ${n}","Артист ${n}",100,0,0,0,0,0,"${ctx}",0,0,""]`;
const item = (n, ctx) => `<div class="audio_item" data-audio='${track(n, ctx)}' data-id="2_${n}"></div>`;

test('музыка: трек с главной включается через полный список «Моих треков» (там весь плейлист)', async () => {
  const html = `<!doctype html><body>
    <section id="recs"><div><div><div><a id="recs-all" href="/audio?act=block&block=RECS">Все</a></div></div></div>${item(90, 'recoms')}</section>
    <section id="my"><header><a id="my-all" href="/audio?act=block&block=MINE&back_url=%2Faudio">Показать все</a></header>${item(1)}${item(2)}</section>
  </body>`;
  const w = page('https://m.vk.com/audio', html);
  let clicked = null;
  w.document.addEventListener('click', (e) => { const a = e.target.closest && e.target.closest('a'); if (a) { clicked = a.id; e.preventDefault(); } }, true);
  w.eval(read('page_visibility.js'));
  w.eval(liteParts().replace('/*CONFIG*/null', JSON.stringify(LITE_CONFIG)));
  w.__vkLite.music('play', '2_7');
  assert.strictEqual(clicked, 'my-all', 'переход по ссылке именно «Моих треков», не рекомендаций');
  assert.strictEqual(w.sessionStorage.getItem('vkweb_my_audios_block'), 'MINE');
  assert.match(w.sessionStorage.getItem('vkweb_pending_play'), /^2_7\|\d+$/);
});

test('музыка: на странице полного списка нужный трек догружается и включается, список — тот же «Мои треки»', async () => {
  const w = page('https://m.vk.com/audio?act=block&block=MINE&back_url=%2Faudio',
    `<!doctype html><body><div id="list">${item(1)}${item(2)}${item(3)}</div><button class="show_more">Ещё</button></body>`);
  w.sessionStorage.setItem('vkweb_my_audios_block', 'MINE');
  w.sessionStorage.setItem('vkweb_pending_play', '2_7|' + Date.now());
  let played = null;
  w.document.addEventListener('click', (e) => {
    const el = e.target.closest && e.target.closest('.audio_item');
    if (el) played = el.getAttribute('data-id');
    if (e.target.classList && e.target.classList.contains('show_more')) {
      // VK догружает следующую порцию.
      w.document.getElementById('list').insertAdjacentHTML('beforeend', [4, 5, 6, 7, 8].map((n) => item(n)).join(''));
    }
  }, true);
  w.eval(read('page_visibility.js'));
  w.eval(liteParts().replace('/*CONFIG*/null', JSON.stringify(LITE_CONFIG)));
  w.__vkLite.music('sync');
  for (let i = 0; i < 40 && !played; i++) await wait(50);
  assert.strictEqual(played, '2_7', 'трек нашёлся после догрузки и включён');
  assert.strictEqual(w.sessionStorage.getItem('vkweb_pending_play'), null);
  await wait(30);
  const music = w.sent.map((m) => JSON.parse(m)).filter((m) => m.type === 'music').pop();
  assert.strictEqual(music.kind, 'my');
  assert.strictEqual(music.key, '/audio', 'тот же список, что главная: сохранённое сливается, а не заменяется');
});

test('музыка: название играющего трека берётся из списка, если страница его не сообщает', async () => {
  const w = page('https://m.vk.com/audio?act=block&block=MINE',
    `<!doctype html><body>${item(1)}<div class="audio_item audio_item_playing" data-audio='${track(5)}' data-id="2_5"></div><audio id="a"></audio></body>`);
  w.sessionStorage.setItem('vkweb_my_audios_block', 'MINE');
  w.eval(read('page_visibility.js'));
  w.eval(liteParts().replace('/*CONFIG*/null', JSON.stringify(LITE_CONFIG)));
  const audio = w.document.getElementById('a');
  Object.defineProperty(audio, 'paused', { get: () => false });
  audio.dispatchEvent(new w.Event('play'));
  await wait(400);
  const media = w.sent.map((m) => JSON.parse(m)).filter((m) => m.type === 'state').pop();
  assert.ok(media, 'состояние плеера отправлено');
  assert.strictEqual(media.playing, true);
  assert.strictEqual(media.title, 'Песня 5');
  assert.strictEqual(media.artist, 'Артист 5');
});

test('переписка: подгрузка старых — прокрутка области переписки вверх в два шага с событиями', async () => {
  const cfg = { bridge: 'vkReaderBridge', loginMarker: '#login', dialogs: { row: '.d', link: 'a', title: '.t', snippet: '.s', time: '.tm', unread: '.u', online: '.o' },
    history: { row: '.m', text: '.tx', outMarker: '.out', time: '.tm', title: '.h' }, compose: { input: 'textarea', send: 'button' } };
  const rows = Array.from({ length: 5 }, (_, i) => `<div class="m"><span class="tx">m${i}</span></div>`).join('');
  const w = page('https://m.vk.com/mail/convo/5', `<!doctype html><body><div id="wrap"><div id="sc" style="overflow-y:auto">${rows}</div></div></body>`);
  w.vkReaderBridge = { postMessage: () => {} };
  const sc = w.document.getElementById('sc');
  Object.defineProperty(sc, 'scrollHeight', { get: () => 2000 });
  Object.defineProperty(sc, 'clientHeight', { get: () => 600 });
  const positions = [];
  sc.addEventListener('scroll', () => positions.push(sc.scrollTop));
  w.eval(read('page_visibility.js'));
  w.eval(netTapSrc() + '\n' + read('vk_reader.js').replace('/*CONFIG*/null', JSON.stringify(cfg)));
  sc.scrollTop = 0; // уже наверху: одно scrollTop = 0 движением не было бы
  assert.strictEqual(w.__vkReader.loadOlder(), 'scroll');
  await wait(150);
  assert.deepStrictEqual(positions, [200, 0], 'сначала ниже начала, затем к началу — VK видит движение вверх');
});

test('музыка: устаревший запрос «включить трек» не срабатывает при следующем открытии списка', async () => {
  const w = page('https://m.vk.com/audio?act=block&block=MINE', `<!doctype html><body>${item(1)}${item(7)}</body>`);
  w.sessionStorage.setItem('vkweb_my_audios_block', 'MINE');
  w.sessionStorage.setItem('vkweb_pending_play', '2_7|' + (Date.now() - 10 * 60 * 1000)); // десять минут назад
  let played = null;
  w.document.addEventListener('click', (e) => { const el = e.target.closest && e.target.closest('.audio_item'); if (el) played = el.getAttribute('data-id'); }, true);
  w.eval(read('page_visibility.js'));
  w.eval(liteParts().replace('/*CONFIG*/null', JSON.stringify(LITE_CONFIG)));
  w.__vkLite.music('sync');
  await wait(100);
  assert.strictEqual(played, null, 'старый трек не заиграл сам');
  assert.strictEqual(w.sessionStorage.getItem('vkweb_pending_play'), null, 'устаревший запрос удалён');
});

test('переписка: предпросмотр из списка диалогов не заменяет полную версию сообщения', async () => {
  const cfg = { bridge: 'vkReaderBridge', loginMarker: '#login', dialogs: { row: '.d', link: 'a', title: '.t', snippet: '.s', time: '.tm', unread: '.u', online: '.o' },
    history: { row: '.m', text: '.tx', outMarker: '.out', time: '.tm', title: '.h' }, compose: { input: 'textarea', send: 'button' } };
  const w = page('https://m.vk.com/mail/convo/5', '<!doctype html><body></body>');
  const sent = [];
  w.vkReaderBridge = { postMessage: (m) => sent.push(JSON.parse(m)) };
  const photo = (url, width) => ({ type: 'photo', photo: { sizes: [{ url, width, height: 100 }] } });
  const full = { id: 115, conversation_message_id: 15, date: 1700000100, from_id: 5, peer_id: 5, out: 0, text: 'Смотри', attachments: [photo('https://x/big.jpg', 1280)] };
  const preview = { id: 115, conversation_message_id: 15, date: 1700000100, from_id: 5, out: 0, text: 'Смотри', attachments: [photo('https://x/small.jpg', 604)] };
  const responses = [
    { response: { items: [full] } },                                                        // история
    { response: { items: [{ conversation: { peer: { id: 5, type: 'user' }, last_conversation_message_id: 15 }, last_message: preview }] } } // список диалогов
  ];
  let n = 0;
  w.fetch = async () => { const body = JSON.stringify(responses[n++]); return { ok: true, headers: { get: () => 'application/json' }, clone() { return { text: async () => body }; } }; };
  w.eval(read('page_visibility.js'));
  w.eval(netTapSrc() + '\n' + read('vk_reader.js').replace('/*CONFIG*/null', JSON.stringify(cfg)));
  await w.fetch('https://api.vk.com/method/messages.getHistory');
  await w.fetch('https://api.vk.com/method/messages.getConversations');
  await wait(20);
  w.__vkReader.scrape('history');
  await wait(50);
  const msg = sent.filter((m) => m.type === 'history').pop().items.find((m) => m.id === 'c15');
  assert.strictEqual(msg.photos[0].url, 'https://x/big.jpg', 'осталась версия из истории');
  assert.strictEqual(msg.preview, false);
});

// ---------------------------------------------------------------- плейлисты

const lastOf = (w, type) => w.sent.map((m) => JSON.parse(m)).filter((m) => m.type === type).pop();

function musicPage(url, body = '') {
  const w = page(url, `<!doctype html><body>${body}</body>`);
  // fetch подменяется ДО скриптов: перехват ответов встаёт на него при старте.
  w.nextResponse = '{}';
  w.fetch = async () => ({ ok: true, headers: { get: () => 'application/json' }, clone() { return { text: async () => w.nextResponse }; } });
  // Переходы по ссылкам jsdom не выполняет: запоминаем нажатые ссылки.
  w.clicked = [];
  w.document.addEventListener('click', (e) => {
    const a = e.target.closest && e.target.closest('a');
    if (a) { w.clicked.push(a.getAttribute('href')); e.preventDefault(); }
  }, true);
  w.eval(read('page_visibility.js'));
  w.eval(liteParts().replace('/*CONFIG*/null', JSON.stringify(LITE_CONFIG)));
  return w;
}

const apiPlaylist = (id, extra = {}) => ({ id, owner_id: 7, title: `Плейлист ${id}`, count: 10 + id,
  photo: { photo_300: `https://sun9-1.vkuserphoto.ru/p${id}_300.jpg`, photo_600: `https://sun9-1.vkuserphoto.ru/p${id}_600.jpg` }, access_key: 'abc123', ...extra });

async function respond(w, body) {
  w.nextResponse = JSON.stringify(body);
  await w.fetch('https://m.vk.com/audio?act=anything');
  await wait(30);
}

test('плейлисты: из JSON-ответа (форма API) — название, число треков, обложка, адрес', async () => {
  const w = musicPage('https://m.vk.com/audio?act=audio_playlists7');
  await respond(w, { response: { items: [apiPlaylist(1), apiPlaylist(2)] } });
  const p = lastOf(w, 'playlists');
  assert.ok(p, 'сообщение пришло');
  assert.deepStrictEqual(p.items.map((i) => i.id), ['7_1', '7_2']);
  assert.strictEqual(p.items[0].title, 'Плейлист 1');
  assert.strictEqual(p.items[0].count, 11);
  assert.strictEqual(p.items[0].cover, 'https://sun9-1.vkuserphoto.ru/p1_600.jpg', 'самая крупная обложка');
  assert.strictEqual(p.items[0].url, '/audio?act=audio_playlist7_1&access_hash=abc123');
  assert.strictEqual(p.none, false);
});

test('плейлисты: форма веб-версии (camelCase) тоже распознаётся', async () => {
  const w = musicPage('https://m.vk.com/audio?act=audio_playlists7');
  await respond(w, { payload: [0, [{ id: 5, ownerId: 7, title: 'Рок', totalCount: 33, coverUrl: 'https://x/c.jpg', accessHash: 'h1' }]] });
  const p = lastOf(w, 'playlists');
  assert.strictEqual(p.items[0].id, '7_5');
  assert.strictEqual(p.items[0].count, 33);
  assert.strictEqual(p.items[0].url, '/audio?act=audio_playlist7_5&access_hash=h1');
});

test('плейлисты: треки, видео, фото и записи за плейлисты не принимаются', async () => {
  const w = musicPage('https://m.vk.com/audio?act=audio_playlists7');
  await respond(w, { response: { items: [
    { id: 1, owner_id: 7, title: 'Видео', duration: 100, views: 5, image: [] },
    { id: 2, owner_id: 7, title: 'Трек', artist: 'A', duration: 200 },
    { id: 3, owner_id: 7, title: 'Фото', sizes: [], date: 1 },
    { id: 4, owner_id: 7, title: 'Запись', text: 'x', date: 1 },
    { id: 5, owner_id: 7, title: 'Просто объект без признаков плейлиста' },
  ] } });
  assert.strictEqual(lastOf(w, 'playlists'), undefined, 'ничего не отправлено');
});

test('плейлисты: из вёрстки — ссылки на плейлисты с обложкой, названием и числом треков', async () => {
  const card = (o, id, title, n) => `<div class="pl"><a href="/audio?act=audio_playlist${o}_${id}&access_hash=k${id}"><img src="https://sun9-2.vkuserphoto.ru/c${id}.jpg"></a>
    <div><span>${title}</span><span>${n} треков</span></div></div>`;
  const w = musicPage('https://m.vk.com/audio?act=audio_playlists7', card(7, 1, 'Утро', 12) + card(-9, 2, 'Дорога', 1));
  w.__vkLite.music('playlists');
  await wait(60);
  const p = lastOf(w, 'playlists');
  assert.ok(p, 'сообщение пришло');
  assert.deepStrictEqual(p.items.map((i) => [i.id, i.title, i.count]), [['7_1', 'Утро', 12], ['-9_2', 'Дорога', 1]]);
  assert.strictEqual(p.items[0].cover, 'https://sun9-2.vkuserphoto.ru/c1.jpg');
  assert.strictEqual(p.items[0].url, '/audio?act=audio_playlist7_1&access_hash=k1', 'адрес из вёрстки с ключом доступа');
  assert.strictEqual(p.complete, true, 'на странице плейлистов без «Показать ещё» список полный');
});

test('плейлисты: ссылка «Плейлисты» на главной — переход по ней и сбор того, что появилось', async () => {
  const w = musicPage('https://m.vk.com/audio', `${item(1)}<a id="all" href="/audio?act=audio_playlists7">Плейлисты</a><div id="host"></div>`);
  // После перехода VK рисует страницу плейлистов (в SPA — тем же документом).
  w.document.addEventListener('click', (e) => {
    if (e.target.id !== 'all') return;
    // Плейлисты собираются только со своего списка: VK меняет адрес (pushState) и рисует его.
    w.history.pushState(null, '', '/audio?act=audio_playlists7');
    w.document.getElementById('host').innerHTML =
      '<div><a href="/audio?act=audio_playlist7_3"><img src="https://x/3.jpg"></a><b>Новый</b></div>';
  }, true);
  w.__vkLite.music('playlists');
  await wait(80);
  assert.deepStrictEqual(w.clicked, ['/audio?act=audio_playlists7'], 'нажата ссылка на страницу плейлистов');
  const p = lastOf(w, 'playlists');
  assert.ok(p && p.items.length === 1 && p.items[0].id === '7_3');
});

test('плейлисты: не нашлись — «нет» приложению и диагностика в журнал (без номеров)', async () => {
  const w = musicPage('https://m.vk.com/audio', `${item(1)}<a href="/audio?act=something-123_456&x=abcdefabcdefabcdef">Что-то</a>`);
  w.setTimeout = (fn) => setTimeout(fn, 40); // не ждать шесть секунд
  w.__vkLite.music('playlists');
  await wait(300);
  const p = lastOf(w, 'playlists');
  assert.ok(p, 'сообщение пришло');
  assert.strictEqual(p.none, true);
  const warn = w.sent.map((m) => JSON.parse(m)).find((m) => m.type === 'log' && /плейлисты не найдены/.test(JSON.stringify(m)));
  assert.ok(warn, 'диагностика в журнале');
  assert.ok(!/456/.test(JSON.stringify(warn)), 'номера из адресов убраны');
});

test('плейлисты: открыть — нажимается ссылка на странице, иначе переход по собранному адресу', async () => {
  const w = musicPage('https://m.vk.com/audio?act=audio_playlists7',
    '<div><a href="/audio?act=audio_playlist7_1&access_hash=k1"><img src="https://x/1.jpg"></a><i>Один</i></div>');
  w.__vkLite.music('playlists');
  await wait(50);
  w.__vkLite.music('playlist', '7_1');
  assert.deepStrictEqual(w.clicked.pop(), '/audio?act=audio_playlist7_1&access_hash=k1');
  // Ссылки на странице нет (другая страница): адрес строится по номеру.
  w.document.body.innerHTML = '';
  w.__vkLite.music('playlist', '7_9');
  assert.deepStrictEqual(w.clicked.pop(), '/audio?act=audio_playlist7_9');
  // Известный плейлист из JSON — с ключом доступа.
  await respond(w, { response: { items: [apiPlaylist(4)] } });
  w.__vkLite.music('playlist', '7_4');
  assert.deepStrictEqual(w.clicked.pop(), '/audio?act=audio_playlist7_4&access_hash=abc123');
});

test('плейлисты: кнопка «Поделиться» и чужие страницы — не ссылки на плейлист', async () => {
  const w = musicPage('https://m.vk.com/audio?act=audio_playlists7',
    '<div><a href="/like?act=publish_box&object=audio_playlist7_1">Поделиться</a>' +
    '<a href="/audios-42?section=playlists&z=audio_playlist7_1">Сообщество</a>' +
    '<a href="/audio?act=audio_playlist7_1&access_hash=k1"><img src="https://x/1.jpg"></a><i>Один</i></div>');
  w.__vkLite.music('playlists');
  await wait(50);
  const p = lastOf(w, 'playlists');
  assert.strictEqual(p.items[0].url, '/audio?act=audio_playlist7_1&access_hash=k1', 'адрес — сам плейлист');
  w.__vkLite.music('playlist', '7_1');
  assert.deepStrictEqual(w.clicked.pop(), '/audio?act=audio_playlist7_1&access_hash=k1', 'нажата ссылка плейлиста, не «Поделиться»');
});

test('плейлисты: на главной музыки и чужих страницах плейлисты в «мои» не собираются', async () => {
  const card = '<div><a href="/audio?act=audio_playlist-42_5"><img src="https://x/5.jpg"></a><i>Чужой</i></div>';
  const root = musicPage('https://m.vk.com/audio', `${item(1)}${card}`);
  await respond(root, { response: { items: [apiPlaylist(3, { owner_id: -42 })] } });
  root.__vkLite.music('sync'); await wait(40);
  assert.strictEqual(lastOf(root, 'playlists'), undefined, 'с главной — ничего');
  const other = musicPage('https://m.vk.com/audio?act=audio_playlists-42', card);
  other.eval('window.vk = { id: 7 }');
  other.__vkLite.music('playlists'); await wait(50);
  assert.deepStrictEqual(other.clicked.pop(), '/audio?act=audio_playlists7', 'чужой список — переход на свой');
});

test('музыка: при переходе между плейлистами треки прежнего в новый список не попадают', async () => {
  const w = musicPage('https://m.vk.com/audio?act=audio_playlist7_1', item(5, 'playlist7_1') + item(6, 'playlist7_1'));
  w.__vkLite.music('sync'); await wait(40);
  assert.deepStrictEqual(lastOf(w, 'music').items.map((t) => t.id), ['2_5', '2_6']);
  // Страница сменила адрес, а вёрстка прежнего плейлиста ещё на месте; затем появился трек нового.
  w.history.pushState(null, '', '/audio?act=audio_playlist7_2');
  w.__vkLite.music('sync'); await wait(40);
  w.document.body.insertAdjacentHTML('beforeend', item(9, 'playlist7_2'));
  await wait(40);
  w.__vkLite.music('sync'); await wait(40);
  assert.deepStrictEqual(lastOf(w, 'music').items.map((t) => t.id), ['2_9']);
});

test('поиск музыки (4.79): страница поиска — page=search, запрос из адреса, все треки (не только свои)', async () => {
  const w = musicPage('https://m.vk.com/audio?section=general&q=%D0%90%D1%80%D0%B8%D1%8F+live', item(1, 'search') + item(2, 'my_audios'));
  w.__vkLite.music('sync'); await wait(40);
  const m = lastOf(w, 'music');
  assert.strictEqual(m.page, 'search');
  assert.strictEqual(m.query, 'Ария live');
  assert.strictEqual(m.kind, 'list', 'не «Мои треки»: сохранённое не подменяется результатами');
  assert.deepStrictEqual(m.items.map((t) => t.id), ['2_1', '2_2']);
  const home = musicPage('https://m.vk.com/audio', item(1));
  home.__vkLite.music('sync'); await wait(40);
  assert.strictEqual(lastOf(home, 'music').page, 'my');
  assert.strictEqual(lastOf(home, 'music').query, '');
});

test('поиск музыки: без поля поиска — переход на страницу поиска с запросом; догрузка — на месте', async () => {
  const w = musicPage('https://m.vk.com/audio', item(1));
  assert.strictEqual(w.__vkLite.music('search', '', '  Кино  '), true);
  assert.strictEqual(w.clicked.pop(), '/audio?section=general&q=%D0%9A%D0%B8%D0%BD%D0%BE');
  assert.strictEqual(w.__vkLite.music('search', '', ' '), false, 'пустой запрос не уходит');
  const res = musicPage('https://m.vk.com/audio?section=general&q=%D0%9A%D0%B8%D0%BD%D0%BE', item(3, 'search') + '<a class="show_more" href="/audio?section=general&q=x&offset=50">ещё</a>');
  res.__vkLite.music('more', 'search');
  assert.ok(!res.clicked.includes('/audio'), 'не уводит на главную музыки');
  // Уже на этом поиске: заново только данные (через «sync»), без нового перехода.
  res.clicked.length = 0;
  res.sent.length = 0;
  res.__vkLite.music('search', '', 'кино');
  await wait(40);
  assert.deepStrictEqual(res.clicked, []);
  assert.strictEqual(lastOf(res, 'music').query, 'Кино');
});

test('поиск музыки: поле поиска есть — запрос введён; «Мои треки» снимают ожидание поиска', async () => {
  const w = musicPage('https://m.vk.com/audio?act=block&block=X', item(1) + '<form><input type="search" name="q" id="q"></form>');
  let typed = '';
  w.document.getElementById('q').addEventListener('input', (e) => { typed = e.target.value; });
  w.__vkLite.music('search', '', 'Сплин');
  assert.strictEqual(typed, 'Сплин');
  assert.strictEqual(w.sessionStorage.getItem('vkweb_chat_query'), 'Сплин');
  w.__vkLite.music('home');
  assert.strictEqual(w.sessionStorage.getItem('vkweb_chat_query'), null, 'прежний запрос больше не вводится');
  assert.strictEqual(w.sessionStorage.getItem('vkweb_chat_play'), null);
});

test('музыка: трек из переписки — есть на странице: нажать; нет: поиск страницы и нажатие в результатах', async () => {
  const w = musicPage('https://m.vk.com/audio', item(1) + '<form><input type="search" name="q" id="q"></form><div id="res"></div>');
  let played = [];
  w.document.addEventListener('click', (e) => { const el = e.target.closest && e.target.closest('.audio_item'); if (el) played.push(el.getAttribute('data-id')); }, true);
  assert.strictEqual(w.__vkLite.music('chat', '2_1', 'A — T1'), true);
  assert.deepStrictEqual(played, ['2_1'], 'трек был на странице');
  let typed = '';
  w.document.getElementById('q').addEventListener('input', (e) => { typed = e.target.value; });
  assert.strictEqual(w.__vkLite.music('chat', '2_77', 'Группа — Песня'), true);
  assert.strictEqual(typed, 'Группа — Песня', 'запрос введён в поле поиска');
  assert.match(w.sessionStorage.getItem('vkweb_chat_play'), /^2_77\|\d+$/);
  w.document.getElementById('res').innerHTML = item(77, 'search');
  await wait(700);
  assert.deepStrictEqual(played, ['2_1', '2_77'], 'найден в результатах и нажат');
  assert.strictEqual(w.sessionStorage.getItem('vkweb_chat_play'), null);
});

test('музыка: трек из переписки — на странице нет поля поиска: переход на поиск музыки, ввод запроса там, нажатие в результатах', async () => {
  const w = musicPage('https://m.vk.com/audio', item(1) + '<input type="checkbox" name="x"><div id="host"></div>');
  const played = [];
  w.document.addEventListener('click', (e) => { const el = e.target.closest && e.target.closest('.audio_item'); if (el) played.push(el.getAttribute('data-id')); }, true);
  assert.strictEqual(w.__vkLite.music('chat', '2_77', 'Группа — Песня'), true);
  assert.strictEqual(w.clicked.pop(), '/audio?section=general&q=' + encodeURIComponent('Группа — Песня'), 'переход на поиск музыки с запросом');
  // Страница поиска открылась (адрес сменился), поле появилось позже — запрос вводится один раз.
  w.history.pushState(null, '', '/audio?section=general');
  let typed = 0;
  w.document.getElementById('host').innerHTML = '<input type="search" name="q" id="q">';
  w.document.getElementById('q').addEventListener('input', () => { typed++; });
  await wait(700);
  assert.strictEqual(typed, 1, 'запрос введён в поле страницы поиска');
  w.document.getElementById('host').insertAdjacentHTML('beforeend', item(77, 'search'));
  await wait(700);
  assert.deepStrictEqual(played, ['2_77']);
  assert.strictEqual(w.sessionStorage.getItem('vkweb_chat_query'), null, 'ожидание закончено');
});

test('музыка: трек из переписки — в результатах поиска он под другим номером: находится по исполнителю и названию', async () => {
  const t = (id, title, artist) => `<div class="audio_item" data-id="2_${id}" data-audio='[${id},2,0,"${title}","${artist}",100,0,0,0,0,0,"search",0,0,""]'></div>`;
  const w = musicPage('https://m.vk.com/audio?section=general&q=x', t(1, 'Другая', 'Группа') + t(2, 'Ночная Песня', 'группа'));
  const played = [];
  w.document.addEventListener('click', (e) => { const el = e.target.closest && e.target.closest('.audio_item'); if (el) played.push(el.getAttribute('data-id')); }, true);
  assert.strictEqual(w.__vkLite.music('chat', '-2001251484_154251484', 'Группа — Ночная песня'), true);
  assert.deepStrictEqual(played, ['2_2']);
});

test('поиск музыки (4.88): разметка выделения в исполнителе и названии убирается — «Только исполнитель» их находит', async () => {
  const t = (id, title, artist) => `<div class="audio_item" data-id="2_${id}" data-audio='[${id},2,0,"${title}","${artist}",100,0,0,0,0,0,"search",0,0,""]'></div>`;
  const w = musicPage('https://m.vk.com/audio?section=general&q=%D0%9A%D0%BE%D1%80%D0%BE%D0%BB%D1%8C',
    t(1, 'Лесник', '&amp;lt;em class=&amp;quot;found&amp;quot;&amp;gt;Король&amp;lt;/em&amp;gt; и Шут') + t(2, '&lt;em&gt;Кукла&lt;/em&gt; колдуна', 'Король и Шут'));
  w.__vkLite.music('sync'); await wait(40);
  const sent = lastOf(w, 'music');
  assert.strictEqual(sent.page, 'search');
  assert.deepStrictEqual(sent.items.map((x) => x.artist), ['Король и Шут', 'Король и Шут']);
  assert.strictEqual(sent.items[1].title, 'Кукла колдуна');
});

// 4.88: найденное приходит ответом API catalog.getAudioSearch (отчёт 4.86: VK вернул 21 трек, экран показал 0).
const apiAudio = (id, artist, title, extra = {}) => ({ id, owner_id: 30, artist, title, duration: 200 + id, access_key: 'k' + id,
  url: `https://psv4.vkuseraudio.ru/s/v1/a${id}.mp3?x=1`, album: { thumb: { photo_300: `https://sun9-1.userapi.com/c${id}.jpg` } }, ...extra });

async function apiSearch(w, query, audios) {
  w.nextResponse = JSON.stringify({ response: { catalog: { sections: [] }, audios } });
  const body = (query ? 'query=' + encodeURIComponent(query) + '&' : '') + 'need_blocks=1&screen_ref=search_music_service&access_token=TKN&v=5.289';
  await w.fetch('https://web.api.vk.ru/method/catalog.getAudioSearch?v=5.289', { method: 'POST', body });
  await wait(40);
}

test('поиск музыки (4.88): найденное — из ответа API, подборки в вёрстке страницы поиска не подмешиваются', async () => {
  const w = musicPage('https://m.vk.com/audio?section=general&q=NeiroBarD', item(1, 'recoms') + item(2, 'recoms'));
  w.__vkLite.music('sync'); await wait(40);
  await apiSearch(w, 'NeiroBarD', [apiAudio(10, 'NeiroBarD', 'Девять смертей', { like: true }), apiAudio(11, 'NeiroBarD, Стальные когти', 'Вдвоём')]);
  const m = lastOf(w, 'music');
  assert.strictEqual(m.page, 'search');
  assert.deepStrictEqual(m.items.map((t) => t.id), ['30_10', '30_11'], 'только найденное API');
  assert.strictEqual(m.items[0].artist, 'NeiroBarD');
  assert.strictEqual(m.items[0].added, true, 'like — уже у себя');
  assert.strictEqual(m.items[1].added, false);
  assert.strictEqual(m.items[0].url, 'https://psv4.vkuseraudio.ru/s/v1/a10.mp3?x=1', 'адрес звука — приложению');
  assert.strictEqual(m.items[0].cover, 'https://sun9-1.userapi.com/c10.jpg');
  assert.strictEqual(m.complete, true, 'весь список: показанное сокращается до присланного');
  assert.strictEqual(m.hasMore, false);
  // Вёрстка страницы перерисовалась — её кортежи в список поиска не попадают.
  w.document.body.insertAdjacentHTML('beforeend', item(3, 'recoms'));
  w.__vkLite.music('sync'); await wait(40);
  assert.deepStrictEqual(lastOf(w, 'music').items.map((t) => t.id), ['30_10', '30_11']);
});

test('поиск музыки (4.88): ответ API пришёл раньше смены адреса — список поиска начинается с него', async () => {
  const w = musicPage('https://m.vk.com/audio', item(1, 'my_audios'));
  w.__vkLite.music('sync'); await wait(40);
  await apiSearch(w, 'Стальные когти', [apiAudio(20, 'Стальные когти', 'Марш')]);
  assert.strictEqual(lastOf(w, 'music').page, 'my', '«Мои треки» не тронуты');
  assert.deepStrictEqual(lastOf(w, 'music').items.map((t) => t.id), ['2_1']);
  w.history.pushState(null, '', '/audio?section=general&q=%D0%A1%D1%82%D0%B0%D0%BB%D1%8C%D0%BD%D1%8B%D0%B5%20%D0%BA%D0%BE%D0%B3%D1%82%D0%B8');
  w.__vkLite.music('sync'); await wait(40);
  const m = lastOf(w, 'music');
  assert.strictEqual(m.page, 'search');
  assert.deepStrictEqual(m.items.map((t) => t.id), ['30_20']);
});

test('поиск музыки (4.88): заготовка экрана поиска без запроса и ответ другого запроса — не результаты', async () => {
  const w = musicPage('https://m.vk.com/audio?section=general&q=Kino', item(1, 'search'));
  w.__vkLite.music('sync'); await wait(40);
  await apiSearch(w, '', [apiAudio(30, 'Подборка', 'Хит')]);
  await apiSearch(w, 'Другое', [apiAudio(31, 'Другое', 'Песня')]);
  assert.deepStrictEqual(lastOf(w, 'music').items.map((t) => t.id), ['2_1'], 'вёрстка поиска (старая страница) — как раньше');
});

test('музыка: повторно открытый плейлист — треки присылаются заново, а не пустой список', async () => {
  const w = musicPage('https://m.vk.com/audio?act=audio_playlist7_1', item(5, 'playlist7_1'));
  w.__vkLite.music('sync'); await wait(40);
  const before = w.sent.length;
  assert.strictEqual(w.__vkLite.music('playlist', '7_1'), true);
  await wait(40);
  const again = w.sent.slice(before).map((m) => JSON.parse(m)).filter((m) => m.type === 'music').pop();
  assert.ok(again, 'список отправлен ещё раз');
  assert.deepStrictEqual(again.items.map((t) => t.id), ['2_5']);
});

test('музыка: вёрстка прежнего плейлиста, перерисованная новыми элементами под новым адресом, в новый список не попадает', async () => {
  const w = musicPage('https://m.vk.com/audio?act=audio_playlist7_1', '<div id="list">' + item(5, 'playlist7_1') + item(6, 'playlist7_1') + '</div>');
  w.__vkLite.music('sync'); await wait(40);
  w.history.pushState(null, '', '/audio?act=audio_playlist7_2');
  // Страница перерисовала прежний список новыми элементами, затем пришёл ответ с новым (в нём есть и общий трек 2_6).
  w.document.getElementById('list').innerHTML = item(5, 'playlist7_1') + item(6, 'playlist7_1');
  await wait(40);
  await respond(w, { title: 'Плейлист', html: '<div class="audio_item" data-audio="' + track(6, '').replace(/"/g, '&quot;') + '"></div><div class="audio_item" data-audio="' + track(9, '').replace(/"/g, '&quot;') + '"></div>' });
  w.__vkLite.music('sync'); await wait(40);
  assert.deepStrictEqual(lastOf(w, 'music').items.map((t) => t.id).sort(), ['2_6', '2_9'], 'общий трек остался, чужой — нет');
});

test('музыка: плеер страницы не знает трек — ошибка не уходит наверх, команда отвечает false', async () => {
  const w = musicPage('https://m.vk.com/audio?act=audio_playlist7_1', item(5, 'playlist7_1'));
  w.eval("window.audioplayer = { playPause: function () { throw new Error('no audio'); } }");
  assert.strictEqual(w.__vkLite.music('play', '2_99', 'playlist'), false);
});

function ownerPage(url) {
  const w = page(url, '<!doctype html><body></body>');
  w.requests = [];
  w.reply = () => '{}';
  // fetch подменяется ДО скриптов: перехват встаёт поверх него. Ответ — по адресу и телу запроса.
  w.fetch = async (input, init) => {
    const url = String(input);
    const body = init && init.body != null ? String(init.body) : '';
    w.requests.push({ url, body });
    const text = await w.reply(url, body);
    return { ok: true, status: 200, headers: { get: (h) => (h === 'content-type' ? 'application/json' : null) },
      clone() { return { text: async () => text }; }, json: async () => JSON.parse(text), text: async () => text };
  };
  w.eval(read('page_visibility.js'));
  w.eval(liteParts().replace('/*CONFIG*/null', JSON.stringify(LITE_CONFIG)));
  return w;
}

const wallResponse = JSON.stringify({ response: { count: 1, items: [{ id: 7, owner_id: -42, from_id: -42, date: 1700000000, text: 'Пост',
  likes: { count: 10, user_likes: 0 }, attachments: [{ type: 'photo', photo: { id: 5, owner_id: -42, access_key: 'ab12',
    sizes: [{ url: 'https://sun9-1.userapi.com/p.jpg', width: 600, height: 400 }] } }] }],
  groups: [{ id: 42, name: 'Клуб', screen_name: 'club42' }] } });

const ownerApi = 'https://web.api.vk.ru/method/owners.getMainTab';
async function readOwner(w) {
  w.reply = () => wallResponse;
  await w.fetch(ownerApi, { method: 'POST', body: 'owner_id=-42&count=10&offset=0&start_from=initial&fields=photo_100&access_token=TKN' });
  await wait(25);
  w.__vkLite.owner('sync');
}

test('сообщество: снимок и успешное сетевое обновление без изменений различаются', async () => {
  const w = ownerPage('https://m.vk.ru/club42');
  await readOwner(w);
  assert.equal(lastOf(w, 'owner').snapshot, true);
  const before = w.requests.length;
  assert.equal(w.__vkLite.owner('refresh', '1', '/club42'), true);
  await wait(35);
  assert.equal(w.requests.length, before + 1);
  const args = new URLSearchParams(w.requests.at(-1).body);
  assert.deepEqual(['owner_id', 'count', 'offset', 'start_from', 'fields'].map(k => args.get(k)), ['-42', '10', '0', '', 'photo_100']);
  const result = lastOf(w, 'ownerRefresh');
  assert.deepEqual([result.operation, result.reason, result.payload.snapshot, result.payload.posts[0].key], ['1', '', false, '-42_7']);
  w.close();
});

test('сообщество: стена с курсором отмечена до получения начала со свежими записями', async () => {
  const w = ownerPage('https://m.vk.ru/club42');
  await readOwner(w);
  assert.equal(lastOf(w, 'owner').needsWallHead, true);
  const head = JSON.parse(wallResponse);
  head.response.items.unshift({ id: 8, owner_id: -42, from_id: -42, date: 1700300000, text: 'Новый пост' });
  head.response.count = 2;
  w.reply = (_url, body) => new URLSearchParams(body).get('start_from') === '' ? JSON.stringify(head) : wallResponse;
  assert.equal(w.__vkLite.owner('refresh', '1', '/club42'), true);
  await wait(35);
  const result = lastOf(w, 'ownerRefresh').payload;
  assert.equal(result.needsWallHead, false);
  assert.deepEqual(result.posts.map(post => post.key), ['-42_8', '-42_7']);
  w.__vkLite.owner('sync');
  assert.equal(lastOf(w, 'owner').needsWallHead, false);
  w.close();
});

test('сообщество: сетевой сбой и отказ VK оставляют прежний снимок', async () => {
  const w = ownerPage('https://m.vk.ru/club42');
  await readOwner(w);
  w.reply = () => Promise.reject(new Error('connection down'));
  w.__vkLite.owner('refresh', '1', '/club42');
  await wait(35);
  assert.deepEqual([lastOf(w, 'ownerRefresh').reason, lastOf(w, 'ownerRefresh').payload], ['request', null]);
  w.reply = () => JSON.stringify({ error: { error_code: 15, error_msg: 'Access denied' } });
  w.__vkLite.owner('refresh', '2', '/club42');
  await wait(35);
  assert.deepEqual([lastOf(w, 'ownerRefresh').reason, lastOf(w, 'ownerRefresh').payload], ['denied', null]);
  w.__vkLite.owner('sync');
  assert.equal(lastOf(w, 'owner').posts[0].key, '-42_7');
  w.close();
});

test('сообщество: повторное обновление и смена маршрута отбрасывают запоздалый ответ', async () => {
  const w = ownerPage('https://m.vk.ru/club42');
  await readOwner(w);
  const replies = [];
  w.reply = () => new Promise(resolve => replies.push(resolve));
  w.__vkLite.owner('refresh', '1', '/club42');
  w.__vkLite.owner('refresh', '2', '/club42');
  const fresh = JSON.parse(wallResponse);
  fresh.response.items[0].id = 8;
  replies[1](JSON.stringify(fresh));
  await wait(25);
  replies[0](wallResponse);
  await wait(35);
  assert.equal(lastOf(w, 'ownerRefresh').operation, '2');
  w.__vkLite.owner('sync');
  assert.equal(lastOf(w, 'owner').posts[0].key, '-42_8', 'поздний ответ не обработан и пассивным мостом');
  w.__vkLite.owner('refresh', '3', '/club42');
  w.history.pushState(null, '', '/club43');
  await wait(20);
  w.history.pushState(null, '', '/club42');
  replies[2](wallResponse);
  await wait(35);
  assert.equal(lastOf(w, 'ownerRefresh').operation, '2');
  w.close();
});

test('фото: диагностика сопоставляет цели и скрывает номера, ключи и значения параметров', async () => {
  const w = ownerPage('https://m.vk.ru/club42');
  await w.fetch('https://m.vk.ru/photo-42_987654?access_key=PRIVATE123&list=SECRET456');
  await w.fetch('https://m.vk.ru/club42?z=photo-42_987654%2FSECRET456');
  await w.fetch('https://m.vk.ru/photo-42_654321?access_key=PRIVATE123');
  await wait(35);
  const messages = w.sent.map(JSON.parse).filter(m => m.type === 'log' && m.message.startsWith('маршрут фото:'));
  assert.ok(messages.some(m => /цель=1, вид=photo.*ключ=true.*HTTP=200/.test(m.message)));
  assert.ok(messages.some(m => /цель=1, вид=z.*кодирование=true/.test(m.message)));
  assert.ok(messages.some(m => /цель=2, вид=photo/.test(m.message)));
  assert.ok(messages.every(m => m.page === '' && !/987654|654321|PRIVATE123|SECRET456/.test(m.message)));
  w.close();
});

test('друзья: страница не запросила friends.get (m.vk.ru отдаёт список в вёрстке) — список запросом от её имени', async () => {
  const w = ownerPage('https://m.vk.com/friends');
  const friend = (id) => ({ id, first_name: 'Имя' + id, last_name: 'Ф', photo_100: 'https://sun9-1.userapi.com/' + id + '.jpg', online: 0 });
  w.reply = (url, body) => {
    if (url.includes('friends.getRequests')) return JSON.stringify({ response: { count: 1, items: [{ user_id: 900, ...friend(900) }] } });
    if (url.includes('friends.get')) {
      const offset = +new URLSearchParams(body).get('offset');
      return JSON.stringify({ response: { count: 3, items: offset === 0 ? [friend(1), friend(2), friend(3)] : [] } });
    }
    return JSON.stringify({ response: [] });
  };
  // Страница сама делает только users.get (за фото): по нему берутся данные входа.
  await w.fetch('https://api.vk.ru/method/users.get', { method: 'POST', body: 'user_ids=1,2,3&fields=photo_50&access_token=TKN&v=5.199' });
  await wait(20);
  assert.strictEqual(w.__vkLite.people('sync'), true);
  await wait(400);
  const call = w.requests.find((r) => r.url.includes('/method/friends.get') && !r.url.includes('Requests'));
  assert.ok(call, 'friends.get запрошен');
  const p = Object.fromEntries(new URLSearchParams(call.body));
  assert.deepStrictEqual([p.order, p.offset, p.access_token], ['hints', '0', 'TKN']);
  const people = w.sent.map((m) => JSON.parse(m)).filter((m) => m.type === 'people').pop();
  assert.deepStrictEqual(people.items.map((f) => f.id), [1, 2, 3]);
  assert.strictEqual(people.listSeen, true);
});

test('друзья: «Возможно, вы знакомы» из friends.getRecommendations — без друзей и удалённых; у чужих друзей нет (3.83)', async () => {
  const friend = (id, extra) => ({ id, first_name: 'Имя' + id, last_name: 'Ф', photo_100: 'https://sun9-1.userapi.com/' + id + '.jpg', ...extra });
  const rec = JSON.stringify({ response: { count: 3, title: 'Возможно, вы знакомы', items: [
    friend(10, { mutual: { count: 2, users: [1, 2] } }), friend(1), friend(11, { deactivated: 'deleted' })] } });
  const w = ownerPage('https://m.vk.com/friends');
  w.reply = (url) => {
    if (url.includes('getRecommendations')) return rec;
    if (url.includes('friends.get')) return JSON.stringify({ response: { count: 1, items: [friend(1)] } });
    return JSON.stringify({ response: { count: 0, items: [] } });
  };
  await w.fetch('https://api.vk.ru/method/friends.get', { method: 'POST', body: 'count=50&access_token=TKN&v=5.199' });
  await w.fetch('https://api.vk.ru/method/friends.getRecommendations', { method: 'POST', body: 'count=25&access_token=TKN&v=5.199' });
  await wait(400);
  const people = w.sent.map((m) => JSON.parse(m)).filter((m) => m.type === 'people').pop();
  assert.deepStrictEqual(people.suggestions.map((f) => [f.id, f.mutual]), [[10, 2]]);
  const other = ownerPage('https://m.vk.com/friends?id=5');
  other.reply = (url) => (url.includes('getRecommendations') ? rec : JSON.stringify({ response: { count: 1, items: [friend(1)] } }));
  await other.fetch('https://api.vk.ru/method/friends.get', { method: 'POST', body: 'user_id=5&count=50&access_token=TKN&v=5.199' });
  await other.fetch('https://api.vk.ru/method/friends.getRecommendations', { method: 'POST', body: 'count=25&access_token=TKN&v=5.199' });
  await wait(400);
  const p2 = other.sent.map((m) => JSON.parse(m)).filter((m) => m.type === 'people').pop();
  assert.deepStrictEqual(p2.suggestions, []);
});

test('вызов API от имени страницы: данные входа берутся только из запросов к серверу VK', async () => {
  const w = ownerPage('https://m.vk.com/club42');
  await w.fetch('https://evil.example/method/wall.get', { method: 'POST', body: 'access_token=STOLEN&v=5.199' });
  await wait(20);
  assert.strictEqual(w.eval('window.__vkNetTap.apiReady()'), false, 'чужой сервер с /method/ не даёт данных входа');
  await w.fetch('https://api.vk.ru/method/wall.get', { method: 'POST', body: 'access_token=TKN&v=5.199' });
  await wait(20);
  assert.strictEqual(w.eval('window.__vkNetTap.apiReady()'), true);
});

test('музыка: переход «Мои треки» → плейлист — общие треки плейлиста не теряются, если вёрстка уже новая', async () => {
  const w = musicPage('https://m.vk.com/audio?act=audio_playlist7_1', '<div id="list">' + item(5, 'my_audios') + item(6, 'my_audios') + item(7, 'my_audios') + '</div>');
  w.__vkLite.music('sync'); await wait(40);
  w.history.pushState(null, '', '/audio?act=audio_playlist7_2');
  // Вёрстка уже нового плейлиста: в нём трек 6 из прежнего списка, но порядок другой — это не перерисовка прежнего.
  w.document.getElementById('list').innerHTML = item(9, 'x') + item(6, 'x');
  await wait(40);
  w.__vkLite.music('sync'); await wait(40);
  assert.deepStrictEqual(lastOf(w, 'music').items.map((t) => t.id), ['2_9', '2_6']);
});

test('страница сообщества: лайк записи — от имени страницы её данными входа, итог и новая стена приложению', async () => {
  const w = ownerPage('https://m.vk.com/club42');
  w.reply = (url) => (url.includes('likes.add') ? '{"response":{"likes":11}}' : wallResponse);
  await w.fetch('https://web.api.vk.ru/method/wall.get?v=5.199&client_id=6287487', { method: 'POST', body: 'owner_id=-42&access_token=TKN&v=5.199' });
  await wait(300);
  const before = w.sent.map((m) => JSON.parse(m)).filter((m) => m.type === 'owner').pop();
  assert.deepStrictEqual([before.posts[0].likes, before.posts[0].liked], [10, false]);
  assert.strictEqual(before.posts[0].photos[0].page, '/photo-42_5?access_key=ab12', 'страница фото с ключом');
  assert.strictEqual(w.__vkLite.owner('like', '-42_7', '1'), true);
  await wait(400);
  const call = w.requests.find((r) => r.url.includes('/method/likes.add'));
  assert.ok(call, 'вызван likes.add');
  assert.ok(call.url.startsWith('https://web.api.vk.ru/method/likes.add'), 'тот же сервер API, что у страницы');
  const params = Object.fromEntries(new URLSearchParams(call.body));
  assert.deepStrictEqual([params.type, params.owner_id, params.item_id, params.access_token, params.v], ['post', '-42', '7', 'TKN', '5.199']);
  const result = w.sent.map((m) => JSON.parse(m)).filter((m) => m.type === 'like').pop();
  assert.deepStrictEqual([result.key, result.ok, result.liked, result.likes], ['-42_7', true, true, 11]);
  const after = w.sent.map((m) => JSON.parse(m)).filter((m) => m.type === 'owner').pop();
  assert.deepStrictEqual([after.posts[0].likes, after.posts[0].liked], [11, true]);
  assert.ok(!w.sent.some((m) => m.includes('TKN')), 'данные входа приложению не уходят');
});

test('страница сообщества: лайк без данных входа или с ошибкой API — итог «не вышло» с причиной', async () => {
  const w = ownerPage('https://m.vk.com/club42');
  w.__vkLite.owner('like', '-42_7', '1');
  await wait(50);
  let result = w.sent.map((m) => JSON.parse(m)).filter((m) => m.type === 'like').pop();
  assert.strictEqual(result.ok, false);
  assert.match(result.reason, /данных входа нет/);
  w.reply = (url) => (url.includes('likes.add') ? '{"error":{"error_code":15,"error_msg":"Access denied"}}' : wallResponse);
  await w.fetch('https://web.api.vk.ru/method/wall.get', { method: 'POST', body: 'owner_id=-42&access_token=TKN&v=5.199' });
  await wait(100);
  w.__vkLite.owner('like', '-42_7', '1');
  await wait(100);
  result = w.sent.map((m) => JSON.parse(m)).filter((m) => m.type === 'like').pop();
  assert.deepStrictEqual([result.ok, /15/.test(result.reason)], [false, true]);
  assert.strictEqual(w.__vkLite.owner('like', 'drop table', '1'), false, 'чужой номер записи отклоняется');
});

test('музыка: тип страницы в сообщении — «my», «playlist», «other»', async () => {
  const mine = musicPage('https://m.vk.com/audio', item(1));
  mine.__vkLite.music('sync'); await wait(40);
  assert.strictEqual(lastOf(mine, 'music').page, 'my');
  const pl = musicPage('https://m.vk.com/audio?act=audio_playlist7_1', item(5, 'playlist7_1'));
  pl.__vkLite.music('sync'); await wait(40);
  const m = lastOf(pl, 'music');
  assert.strictEqual(m.page, 'playlist');
  assert.strictEqual(m.kind, 'list');
  const other = musicPage('https://m.vk.com/audio?act=search', item(6, 'search'));
  other.__vkLite.music('sync'); await wait(40);
  assert.strictEqual(lastOf(other, 'music').page, 'other');
});

test('музыка: «домой» — только если страница не на главной; трек «Моих» со страницы плейлиста — через главную', async () => {
  const w = musicPage('https://m.vk.com/audio?act=audio_playlist7_1', item(5, 'playlist7_1'));
  w.__vkLite.music('home');
  assert.deepStrictEqual(w.clicked.pop(), '/audio');
  const root = musicPage('https://m.vk.com/audio', item(1));
  root.__vkLite.music('home');
  assert.strictEqual(root.clicked.length, 0, 'уже на главной — никуда не идём');
  // Включить «мой» трек, которого на странице плейлиста нет: уходим на главную, запрос включения запомнен.
  w.__vkLite.music('play', '2_77', 'my');
  assert.deepStrictEqual(w.clicked.pop(), '/audio');
  assert.match(w.sessionStorage.getItem('vkweb_pending_play'), /^2_77\|\d+$/);
  // Трек самого плейлиста включается нажатием на него.
  let played = null;
  w.document.addEventListener('click', (e) => { const el = e.target.closest && e.target.closest('.audio_item'); if (el) played = el.getAttribute('data-id'); }, true);
  w.__vkLite.music('play', '2_5', 'playlist');
  assert.strictEqual(played, '2_5');
});

test('музыка: догрузка плейлиста по явному ответу сервера о продолжении', async () => {
  const w = musicPage('https://m.vk.com/audio?act=audio_playlist7_1', item(5, 'playlist7_1'));
  await respond(w, { list: [JSON.parse(track(5, 'playlist7_1'))], hasMore: false });
  w.__vkLite.music('sync'); await wait(40);
  const m = lastOf(w, 'music');
  assert.strictEqual(m.hasMore, false, 'сервер сказал: продолжения нет');
});

// ---------------------------------------------------------------- общий перехват (shared/net_tap.js)

function tapPage(url = 'https://m.vk.ru/audio') {
  const w = page(url);
  // Подставные XHR и WebSocket: jsdom сеть не ходит, а перехват встаёт на конструкторы при установке.
  w.eval(`
    window.XMLHttpRequest = function () { this.l = { load: [], error: [] }; this.status = 0; this.responseType = ''; this.responseText = ''; };
    XMLHttpRequest.prototype.open = function (m, u) { this.u = u; };
    XMLHttpRequest.prototype.addEventListener = function (t, f) { this.l[t].push(f); };
    XMLHttpRequest.prototype.getResponseHeader = function (name) {
      return name.toLowerCase() === 'content-type' ? 'application/json' : null;
    };
    XMLHttpRequest.prototype.send = function () { var self = this; setTimeout(function () { self.status = window.__xhrStatus || 200; self.responseText = window.__xhrBody || '{}'; self.l.load.forEach(function (f) { f.call(self); }); }, 0); };
    window.WebSocket = function (u) { this.u = u; this.l = { message: [], error: [] }; window.__socket = this; };
    WebSocket.prototype.addEventListener = function (t, f) { this.l[t].push(f); };
    WebSocket.CONNECTING = 0; WebSocket.OPEN = 1; WebSocket.CLOSING = 2; WebSocket.CLOSED = 3;
    window.fetch = async function () { return { ok: true, status: 200, headers: { get: function (h) { return h === 'content-type' ? 'application/json' : null; } }, clone: function () { return { text: async function () { return window.__fetchBody || '{}'; } }; } }; };
  `);
  w.eval(netTapSrc());
  return w;
}

test('общий перехват: XHR доставляется подписчику, страница получает ответ как есть', async () => {
  const w = tapPage();
  const got = [];
  w.__vkNetTap.listen({ onText: (url, body, text, source) => got.push([source, url, text]) });
  w.__xhrBody = '{"response":1}';
  const x = new w.XMLHttpRequest();
  x.open('GET', 'https://web.api.vk.ru/method/users.get?v=5');
  x.send('a=1');
  await wait(20);
  assert.deepStrictEqual(got, [['xhr', 'https://web.api.vk.ru/method/users.get?v=5', '{"response":1}']]);
  assert.strictEqual(x.responseText, '{"response":1}', 'ответ страницы не тронут');
});

test('общий перехват: фильтр wants и признак active', async () => {
  const w = tapPage();
  const api = [];
  const off = [];
  w.__vkNetTap.listen({ wants: (u) => w.__vkNetTap.isApiUrl(u), onText: (u, b, t) => api.push(u) });
  w.__vkNetTap.listen({ active: () => false, onText: (u, b, t) => off.push(u) });
  w.__fetchBody = '{"a":1}';
  await w.fetch('https://m.vk.ru/audio?act=foo');
  await w.fetch('https://web.api.vk.ru/method/audio.get');
  await wait(20);
  assert.deepStrictEqual(api, ['https://web.api.vk.ru/method/audio.get']);
  assert.deepStrictEqual(off, [], 'неактивный подписчик ничего не получает (и тело не копируется)');
});

test('общий перехват: WebSocket только по просьбе подписчика', async () => {
  const w = tapPage();
  const plain = new w.WebSocket('wss://queuev4.vk.ru/ws');
  assert.strictEqual(plain.l.message.length, 0, 'без подписки на сокеты хука нет');
  const got = [];
  w.__vkNetTap.listen({ sockets: true, onText: (u, b, t, s) => got.push([s, t]) });
  const sock = new w.WebSocket('wss://queuev4.vk.ru/ws');
  sock.l.message.forEach((f) => f({ data: '{"type":"message"}' }));
  sock.l.message.forEach((f) => f({ data: new ArrayBuffer(4) }));
  assert.deepStrictEqual(got, [['ws', '{"type":"message"}']], 'текст доставлен, двоичное — нет');
});

test('общий перехват: учёт по серверам — шаблон адреса без номеров и ключей, ошибки, сброс', async () => {
  const w = tapPage();
  w.__vkNetTap.listen({ onText: () => {} });
  w.__fetchBody = '[1,2,3]';
  await w.fetch('https://web.api.vk.ru/method/messages.getHistory?peer_id=2000000123&access_token=SECRET');
  await w.fetch('https://web.api.vk.ru/method/messages.getHistory?peer_id=2000000999&access_token=SECRET');
  await w.fetch('https://m.vk.ru/al_audio.php?act=reload_audio&x=1');
  w.__xhrStatus = 500;
  const x = new w.XMLHttpRequest(); x.open('POST', 'https://m.vk.ru/mail/convo/123456'); x.send(''); await wait(20);
  const sock = new w.WebSocket('wss://queuev4.vk.ru/connect/9999999');
  const stats = w.__vkNetTap.stats();
  const find = (h, e) => stats.find((s) => s.h === h && s.e === e);
  assert.strictEqual(find('web.api.vk.ru', 'method/messages.getHistory').n, 2, 'два запроса — одна строка');
  assert.ok(find('m.vk.ru', 'al_audio.php?act=reload_audio'), 'для al-страниц к адресу добавлено действие act');
  const failed = find('m.vk.ru', 'mail/convo/#');
  assert.ok(failed && failed.err === 1, 'ответ 500 — ошибка; номер беседы в адресе скрыт');
  assert.ok(!JSON.stringify(stats).match(/SECRET|2000000|123456|9999999/), 'ни ключей, ни номеров в учёте нет');
  assert.strictEqual(w.__vkNetTap.drain().length, stats.length);
  assert.strictEqual(w.__vkNetTap.stats().length, 0, 'после drain — новый период');
});

test('общий перехват: повторная установка не оборачивает fetch дважды, новый источник сообщается один раз', async () => {
  const w = tapPage();
  const seen = [];
  w.__vkNetTap.onNewSource((s) => seen.push(s.h + ' ' + s.e));
  let calls = 0;
  w.__vkNetTap.listen({ onText: () => calls++ });
  w.__vkNetTap.listen({ onText: () => {} });
  w.__vkNetTap.install();
  w.__fetchBody = '{}';
  await w.fetch('https://web.api.vk.ru/method/a.b');
  await w.fetch('https://web.api.vk.ru/method/a.b');
  await wait(20);
  assert.strictEqual(calls, 2, 'каждый ответ — ровно один раз');
  assert.deepStrictEqual(seen, ['web.api.vk.ru method/a.b']);
  // Повторная загрузка модуля (повторное внедрение скрипта) ничего не ломает.
  const tap = w.__vkNetTap;
  w.eval(netTapSrc());
  assert.strictEqual(w.__vkNetTap, tap);
});

test('трафик: сводку забирает приложение из общего модуля (без команд в скриптах), номера в неё не попадают', async () => {
  const w = musicPage('https://m.vk.ru/audio', item(1));
  await respond(w, { response: [] });
  await w.fetch('https://web.api.vk.ru/method/users.get?user_ids=123456789');
  await wait(40); // тело ответа читается асинхронно, учёт — после него
  // Так спрашивает web/TrafficCollector.kt через evaluateJavascript.
  const text = w.eval("(function(){var t=window.__vkNetTap&&window.__vkNetTap.trafficMessage();return t?JSON.stringify(t):''})()");
  const t = JSON.parse(text);
  assert.strictEqual(t.type, 'traffic');
  assert.ok(t.items.some((i) => i.e === 'method/users.get' && i.n === 1 && i.h === 'web.api.vk.ru'));
  assert.ok(!text.includes('123456789'), 'номер пользователя не попал в сводку');
  assert.strictEqual(w.eval("(function(){var t=window.__vkNetTap.trafficMessage();return t?'есть':''})()"), '', 'после выдачи период новый — пусто');
});

test('музыка: «назад» включает трек перед играющим (по выделенной строке списка)', async () => {
  const w = musicPage('https://m.vk.ru/audio?act=block&block=MINE', item(1) + item(2) + item(3));
  w.sessionStorage.setItem('vkweb_my_audios_block', 'MINE');
  w.document.querySelector('[data-id="2_2"]').classList.add('audio_item_playing');
  const played = [];
  w.document.addEventListener('click', (e) => { const el = e.target.closest && e.target.closest('.audio_item'); if (el) played.push(el.getAttribute('data-id')); }, true);
  w.__vkLite.command('prev');
  assert.deepStrictEqual(played, ['2_1']);
});

test('музыка: «назад» без выделенной строки — играющий определяется по Media Session', async () => {
  const w = page('https://m.vk.ru/audio?act=block&block=MINE', `<!doctype html><body>${item(1)}${item(2)}${item(3)}</body>`);
  Object.defineProperty(w.navigator, 'mediaSession', { value: { metadata: { title: 'Песня 3', artist: 'Артист 3' }, setActionHandler() {} } });
  w.sent = []; w.vkLiteBridge = { postMessage: (m) => w.sent.push(m) };
  w.sessionStorage.setItem('vkweb_my_audios_block', 'MINE');
  w.eval(read('page_visibility.js'));
  w.eval(liteParts().replace('/*CONFIG*/null', JSON.stringify(LITE_CONFIG)));
  w.__vkLite.music('sync'); await wait(40); // страница узнала треки списка
  const played = [];
  w.document.addEventListener('click', (e) => { const el = e.target.closest && e.target.closest('.audio_item'); if (el) played.push(el.getAttribute('data-id')); }, true);
  w.__vkLite.command('prev');
  assert.deepStrictEqual(played, ['2_2']);
});

test('музыка: «назад» на первом треке списка ничего не включает (запасной путь — начало трека)', async () => {
  const w = musicPage('https://m.vk.ru/audio?act=block&block=MINE', item(1) + item(2));
  w.sessionStorage.setItem('vkweb_my_audios_block', 'MINE');
  w.document.querySelector('[data-id="2_1"]').classList.add('audio_item_playing');
  const played = [];
  w.document.addEventListener('click', (e) => { const el = e.target.closest && e.target.closest('.audio_item'); if (el) played.push(el.getAttribute('data-id')); }, true);
  w.__vkLite.command('prev');
  assert.deepStrictEqual(played, [], 'предыдущего нет: другой трек не включается');
});

test('общий перехват: один объект XHR для двух запросов — у каждого свой адрес, тело и учёт', async () => {
  const w = tapPage();
  const got = [];
  w.__vkNetTap.listen({ onText: (url, body, text) => got.push([url, body, text]) });
  const x = new w.XMLHttpRequest();
  w.__xhrBody = '{"n":1}';
  x.open('POST', 'https://web.api.vk.ru/method/first.call'); x.send('a=1');
  await wait(20);
  w.__xhrBody = '{"n":2}';
  x.open('POST', 'https://web.api.vk.ru/method/second.call'); x.send('b=2');
  await wait(20);
  assert.deepStrictEqual(got, [
    ['https://web.api.vk.ru/method/first.call', 'a=1', '{"n":1}'],
    ['https://web.api.vk.ru/method/second.call', 'b=2', '{"n":2}'],
  ]);
  const names = Array.from(w.__vkNetTap.stats(), (s) => s.e).sort(); // Array.from: массив из окна jsdom — другой «мир»
  assert.deepStrictEqual(names, ['method/first.call', 'method/second.call']);
});

// ---------------------------------------------------------------- окно чтения: видео, номера фото, вложения

const READER_CFG = { bridge: 'vkReaderBridge', loginMarker: '#login', dialogs: { row: '.d', link: 'a', title: '.t', snippet: '.s', time: '.tm', unread: '.u', online: '.o' },
  history: { row: '.m', text: '.tx', outMarker: '.out', time: '.tm', title: '.h' }, compose: { input: 'textarea', send: 'button' } };

function readerPage(body = '', path = '/mail/convo/5') {
  const w = page('https://m.vk.com' + path, `<!doctype html><body>${body}</body>`);
  w.sentToApp = [];
  w.vkReaderBridge = { postMessage: (m) => w.sentToApp.push(JSON.parse(m)) };
  w.nextResponse = '{}';
  w.fetch = async () => ({ ok: true, headers: { get: () => 'application/json' }, clone() { return { text: async () => w.nextResponse }; } });
  w.eval(read('page_visibility.js'));
  w.eval(netTapSrc() + '\n' + read('vk_reader.js').replace('/*CONFIG*/null', JSON.stringify(READER_CFG)));
  return w;
}

test('окно чтения: номер фото и видео во вложениях приходят приложению', async () => {
  const w = readerPage();
  const photo = (id) => ({ type: 'photo', photo: { id, owner_id: 7, sizes: [{ url: `https://sun9-1.vkuserphoto.ru/p${id}.jpg?sign=x`, width: 800, height: 600 }] } });
  const video = { type: 'video', video: { id: 9, owner_id: -42, title: 'Ролик &amp; кот', duration: 75, access_key: 'abc123',
    image: [{ url: 'https://i.vkuserphoto.ru/v_130.jpg', width: 130, height: 97 }, { url: 'https://i.vkuserphoto.ru/v_800.jpg', width: 800, height: 450 }, { url: 'https://i.vkuserphoto.ru/v_1280.jpg', width: 1280, height: 720 }] } };
  w.nextResponse = JSON.stringify({ response: { items: [{ id: 1, conversation_message_id: 101, date: 1700000000, from_id: 5, peer_id: 5, out: 0, text: '', attachments: [photo(55), video] }] } });
  await w.fetch('https://web.api.vk.ru/method/messages.getHistory?peer_id=5');
  await wait(30);
  w.__vkReader.scrape('history');
  await wait(60);
  const m = w.sentToApp.filter((x) => x.type === 'history').pop().items[0];
  assert.strictEqual(m.photos[0].id, '7_55', 'номер фото передан');
  assert.deepStrictEqual(m.videos.map((v) => [v.id, v.title, v.duration, v.key]), [['-42_9', 'Ролик & кот', 75, 'abc123']]);
  assert.strictEqual(m.videos[0].cover, 'https://i.vkuserphoto.ru/v_800.jpg', 'обложка — наименьшая от 480 пикселей');
  assert.ok(!/Видео/.test(m.text), 'вместо подписи «[Видео]» — карточка');
});

function stagePng(w) {
  w.__vkReader.stage(0, 'cat.png', 'image/png', 2, 0, 'AAEC'); // 3 байта
  w.__vkReader.stage(0, 'cat.png', 'image/png', 2, 1, 'AwQF'); // ещё 3
}

test('вложения: файлы подставляются в поле выбора файла, после загрузки уходит сообщение', { timeout: 20000 }, async () => {
  const w = readerPage('<textarea></textarea><input type="file" accept="image/*,video/*" multiple><button>Отправить</button>');
  const input = w.document.querySelector('input[type="file"]');
  Object.defineProperty(input, 'files', { configurable: true, writable: true, value: null }); // jsdom не даёт присвоить files
  w.DataTransfer = class { constructor() { this.list = []; this.items = { add: (f) => this.list.push(f) }; } get files() { return this.list; } };
  const events = [];
  input.addEventListener('change', () => events.push('change'));
  let clicks = 0;
  w.document.querySelector('button').addEventListener('click', () => clicks++);
  stagePng(w);
  w.__vkReader.attachAndSend('подпись');
  await wait(300);
  assert.strictEqual(input.files.length, 1);
  assert.strictEqual(input.files[0].name, 'cat.png');
  assert.strictEqual(input.files[0].size, 6, 'куски склеены');
  assert.deepStrictEqual(events, ['change']);
  assert.ok(w.sentToApp.some((m) => m.type === 'attach' && m.stage === 'uploading'));
  assert.strictEqual(clicks, 0, 'пока идёт минимальная пауза загрузки, сообщение не отправлено');
  await wait(2800);
  assert.strictEqual(clicks, 1, 'загрузка закончилась — «Отправить» нажато');
  assert.ok(w.sentToApp.some((m) => m.type === 'sent' && m.ok === true));
});

test('вложения: поля выбора файла нет — приложению причина, в журнал — что есть в композере', async () => {
  const w = readerPage('<textarea></textarea><button>Отправить</button><button aria-label="Прикрепить файл">+</button>');
  stagePng(w);
  w.__vkReader.attachAndSend('');
  await wait(1100); // кнопка «Прикрепить» нажата, ждём 700 мс и ищем поле ещё раз
  const err = w.sentToApp.find((m) => m.type === 'attach' && m.stage === 'error');
  assert.ok(err && /не найдено/.test(err.detail), 'причина передана');
  const log = w.sentToApp.find((m) => m.type === 'log' && /вложения/.test(m.message));
  assert.ok(log && /полей выбора файла=0/.test(log.message) && /кнопки=\[Прикрепить файл\]/.test(log.message), 'в журнале состояние композера');
});

test('вложения: не все куски переданы — отказ без попытки отправки', async () => {
  const w = readerPage('<textarea></textarea><input type="file"><button>Отправить</button>');
  w.__vkReader.stage(0, 'a.bin', 'application/octet-stream', 3, 0, 'AAEC'); // куски 1 и 2 не пришли
  w.__vkReader.attachAndSend('');
  await wait(50);
  assert.ok(w.sentToApp.some((m) => m.type === 'attach' && m.stage === 'error' && /не полностью/.test(m.detail)));
  assert.ok(!w.sentToApp.some((m) => m.type === 'sent'));
});

// ---------------------------------------------------------------- ответ отправки (messages.send)

async function sendResponse(w, body, response) {
  w.nextResponse = JSON.stringify(response);
  await w.fetch('https://web.api.vk.ru/method/messages.send', { method: 'POST', body });
  await wait(30);
}

test('отправка: успешный ответ messages.send — «доставлено» приложению и настоящее сообщение в истории', async () => {
  const w = readerPage();
  await sendResponse(w, 'peer_id=5&message=%D0%BF%D1%80%D0%B8%D0%B2%D0%B5%D1%82&random_id=1', { response: { message_id: 900, conversation_message_id: 77 } });
  const d = w.sentToApp.find((m) => m.type === 'delivered');
  assert.ok(d, 'сообщение о доставке пришло');
  assert.deepStrictEqual([d.peerId, d.text], ['5', 'привет']);
  w.__vkReader.scrape('history');
  await wait(60);
  const items = w.sentToApp.filter((m) => m.type === 'history').pop().items;
  assert.deepStrictEqual(items.map((m) => [m.id, m.out, m.text]), [['c77', true, 'привет']], 'настоящее сообщение с номером');
});

test('отправка: ответ без номера в беседе или с телом JSON тоже подтверждает', async () => {
  const w = readerPage();
  await sendResponse(w, JSON.stringify({ peer_id: 7, message: 'ок' }), { response: 123456 });
  const d = w.sentToApp.find((m) => m.type === 'delivered');
  assert.deepStrictEqual([d.peerId, d.text], ['7', 'ок']);
  w.__vkReader.scrape('history');
  await wait(60);
  assert.ok(!w.sentToApp.some((m) => m.type === 'history' && m.items.length), 'без номера в беседе в историю ничего не подмешиваем');
});

test('отправка: ошибка в ответе — доставки нет; одинаковые сообщения подряд не склеиваются', async () => {
  const w = readerPage();
  await sendResponse(w, 'peer_id=5&message=x', { error: { error_code: 9, error_msg: 'Flood control' } });
  assert.ok(!w.sentToApp.some((m) => m.type === 'delivered'), 'ошибка VK — не доставлено');
  await sendResponse(w, 'peer_id=5&message=ok', { response: 1 });
  await sendResponse(w, 'peer_id=5&message=ok', { response: 2 });
  const ds = w.sentToApp.filter((m) => m.type === 'delivered');
  assert.strictEqual(ds.length, 2);
  assert.notStrictEqual(ds[0].n, ds[1].n, 'разные n: приложение не отбросит второе как повтор');
});

test('отправка: без текста в теле берётся последний отправленный через окно', async () => {
  const w = readerPage('<textarea></textarea><button>Отправить</button>');
  w.__vkReader.send('привет, мир');
  await wait(300);
  await sendResponse(w, '', { response: 5 });
  const d = w.sentToApp.find((m) => m.type === 'delivered');
  assert.strictEqual(d.text, 'привет, мир');
});

// ---------------------------------------------------------------- карточки вложений (ссылка, файл, запись, клип)

test('окно чтения: ссылка, файл, запись и клип приходят карточками, подпись в скобках не дублируется', async () => {
  const w = readerPage();
  const attachments = [
    { type: 'link', link: { url: 'https://example.org/page?a=1', title: 'Заголовок &amp; ссылка', caption: 'example.org' } },
    { type: 'doc', doc: { id: 1, owner_id: 7, title: 'Отчёт.pdf', ext: 'pdf', size: 1572864, url: 'https://vk.com/doc7_1?hash=abc&no_preview=1' } },
    { type: 'wall', wall: { id: 33, to_id: -5, from_id: -5, text: 'Первая строка записи\nвторая' } },
    { type: 'clip', clip: { id: 8, owner_id: -5, title: 'Мой клип' } },
    { type: 'audio', audio: { artist: 'A', title: 'B' } },
  ];
  w.nextResponse = JSON.stringify({ response: { items: [{ id: 1, conversation_message_id: 201, date: 1700000000, from_id: 5, peer_id: 5, out: 0, text: 'смотри', attachments }] } });
  await w.fetch('https://web.api.vk.ru/method/messages.getHistory?peer_id=5');
  await wait(30);
  w.__vkReader.scrape('history');
  await wait(60);
  const m = w.sentToApp.filter((x) => x.type === 'history').pop().items[0];
  const byKind = Object.fromEntries(m.cards.map((c) => [c.kind, c]));
  assert.deepStrictEqual(m.cards.map((c) => c.kind), ['link', 'doc', 'wall', 'audio']);
  // Клип показывается как видео (карточка с обложкой, открывается страницей клипа), а не простой карточкой.
  assert.deepStrictEqual(m.videos.map((v) => [v.id, v.clip]), [['-5_8', true]]);
  assert.deepStrictEqual([byKind.link.title, byKind.link.url], ['Заголовок & ссылка', 'https://example.org/page?a=1']);
  assert.deepStrictEqual([byKind.doc.title, byKind.doc.subtitle], ['Отчёт.pdf', 'PDF · 1.5 МБ']);
  assert.strictEqual(byKind.doc.url, 'https://vk.com/doc7_1?hash=abc&no_preview=1');
  assert.deepStrictEqual([byKind.wall.path, byKind.wall.title], ['/wall-5_33', 'Первая строка записи']);
  assert.strictEqual(m.text, 'смотри', 'всё показано карточками: подписей в скобках нет');
});

test('окно чтения: у музыки в карточке обложка, номер трека и ключ', async () => {
  const w = readerPage();
  const attachments = [
    { type: 'audio', audio: { artist: 'Группа', title: 'Песня', duration: 185, id: 77, owner_id: -5, access_key: 'ab12',
      album: { thumb: { photo_68: 'https://sun.userapi.com/s.jpg', photo_300: 'https://sun.userapi.com/m.jpg', photo_600: 'https://sun.userapi.com/l.jpg' } } } },
    { type: 'audio', audio: { artist: 'X', title: 'Y', id: 1, owner_id: 2, album: { thumb: { photo_300: 'http://insecure.example/m.jpg' } } } },
    { type: 'audio', audio: { artist: 'Без', title: 'номера' } },
  ];
  w.nextResponse = JSON.stringify({ response: { items: [{ id: 1, conversation_message_id: 301, date: 1700000000, from_id: 5, peer_id: 5, out: 0, text: '', attachments }] } });
  await w.fetch('https://web.api.vk.ru/method/messages.getHistory?peer_id=5');
  await wait(30);
  w.__vkReader.scrape('history');
  await wait(60);
  const cards = w.sentToApp.filter((x) => x.type === 'history').pop().items[0].cards;
  assert.deepStrictEqual([cards[0].title, cards[0].cover, cards[0].id, cards[0].key],
    ['Группа — Песня', 'https://sun.userapi.com/m.jpg', '-5_77', 'ab12']);
  assert.deepStrictEqual([cards[1].cover, cards[1].id], ['', '2_1'], 'обложка только по https');
  assert.deepStrictEqual([cards[2].cover, cards[2].id], ['', ''], 'нет номера — поиск по названию');
});

test('окно чтения: небезопасный адрес или вложение без номера карточки не получают', async () => {
  const w = readerPage();
  w.nextResponse = JSON.stringify({ response: { items: [{ id: 2, conversation_message_id: 202, date: 1700000100, from_id: 5, peer_id: 5, out: 0, text: 'x', attachments: [
    { type: 'link', link: { url: 'javascript:alert(1)', title: 'плохая' } },
    { type: 'link', link: { url: 'http://insecure.example/', title: 'без https' } },
    { type: 'doc', doc: { title: 'без адреса' } },
    { type: 'wall', wall: { text: 'без номера' } },
    { type: 'clip', clip: { id: 1 } },
  ] }] } });
  await w.fetch('https://web.api.vk.ru/method/messages.getHistory?peer_id=5');
  await wait(30);
  w.__vkReader.scrape('history');
  await wait(60);
  const m = w.sentToApp.filter((x) => x.type === 'history').pop().items[0];
  assert.deepStrictEqual(m.cards, []);
  assert.match(m.text, /\[Ссылка\]/, 'вместо карточки — прежняя подпись');
});

// ---------------------------------------------------------------- голосовые, музыка, видеосообщения

test('окно чтения: голосовое приходит проигрывателем, музыка и видеосообщение — карточками', async () => {
  const w = readerPage();
  const attachments = [
    { type: 'audio_message', audio_message: { id: 4, owner_id: 5, duration: 12, link_ogg: 'https://cs.example/a.ogg?sig=1', link_mp3: 'https://cs.example/a.mp3?sig=2' } },
    { type: 'audio', audio: { artist: 'Группа', title: 'Песня &amp; танец', duration: 185 } },
    { type: 'video_message', video_message: { id: 1, owner_id: 5, duration: 7, link_mp4: 'https://cs.example/v.mp4?sig=3' } },
  ];
  w.nextResponse = JSON.stringify({ response: { items: [{ id: 3, conversation_message_id: 301, date: 1700000200, from_id: 5, peer_id: 5, out: 0, text: '', attachments }] } });
  await w.fetch('https://web.api.vk.ru/method/messages.getHistory?peer_id=5');
  await wait(30);
  w.__vkReader.scrape('history');
  await wait(60);
  const m = w.sentToApp.filter((x) => x.type === 'history').pop().items[0];
  assert.deepStrictEqual(m.voices.map((v) => [v.id, v.duration, v.url]), [['5_4', 12, 'https://cs.example/a.mp3?sig=2']], 'mp3 предпочтительнее ogg');
  const byKind = Object.fromEntries(m.cards.map((c) => [c.kind, c]));
  assert.deepStrictEqual([byKind.audio.title, byKind.audio.subtitle], ['Группа — Песня & танец', 'Музыка · 3:05']);
  assert.ok(!byKind.audio.url && !byKind.audio.path, 'у музыки адреса нет: приложение откроет поиск');
  assert.deepStrictEqual([byKind.video_message.subtitle, byKind.video_message.url], ['0:07', 'https://cs.example/v.mp4?sig=3']);
  assert.strictEqual(m.text, '', 'подписей в скобках не осталось');
});

test('окно чтения: голосовое без https-записи остаётся подписью', async () => {
  const w = readerPage();
  w.nextResponse = JSON.stringify({ response: { items: [{ id: 4, conversation_message_id: 302, date: 1700000300, from_id: 5, peer_id: 5, out: 0, text: 'x', attachments: [
    { type: 'audio_message', audio_message: { id: 1, owner_id: 5, duration: 3, link_mp3: 'http://insecure.example/a.mp3' } },
  ] }] } });
  await w.fetch('https://web.api.vk.ru/method/messages.getHistory?peer_id=5');
  await wait(30);
  w.__vkReader.scrape('history');
  await wait(60);
  const m = w.sentToApp.filter((x) => x.type === 'history').pop().items[0];
  assert.deepStrictEqual(m.voices, []);
  assert.match(m.text, /Голосовое сообщение/);
});

// ---------------------------------------------------------------- рецензия: повторная отправка вложений

test('вложения: после ошибки файлы прежней попытки в новую отправку не попадают', { timeout: 20000 }, async () => {
  // Первая попытка: три файла, поля выбора нет — отказ.
  const w = readerPage('<textarea></textarea><button>Отправить</button>');
  for (let i = 0; i < 3; i++) w.__vkReader.stage(i, `old${i}.bin`, 'application/octet-stream', 1, 0, 'AAEC');
  w.__vkReader.attachAndSend('');
  await wait(100);
  assert.ok(w.sentToApp.some((m) => m.type === 'attach' && m.stage === 'error'), 'первая попытка не удалась');
  // Человек убрал два файла и повторил: теперь поле выбора файла есть, файл один.
  const input = w.document.createElement('input');
  input.type = 'file'; input.multiple = true;
  Object.defineProperty(input, 'files', { configurable: true, writable: true, value: null });
  w.document.body.appendChild(input);
  w.DataTransfer = class { constructor() { this.list = []; this.items = { add: (f) => this.list.push(f) }; } get files() { return this.list; } };
  w.__vkReader.stage(0, 'new.bin', 'application/octet-stream', 1, 0, 'AAEC');
  w.__vkReader.attachAndSend('');
  await wait(300);
  assert.deepStrictEqual(Array.from(input.files, (f) => f.name), ['new.bin'], 'уходит только выбранный сейчас файл');
});

// ---- 3.54: переслать, видео в переписке, звонки

test('окно чтения: «переслать» — messages.send в выбранную переписку с forward без is_reply, итог «forwarded»', async () => {
  const w = readerApiPage();
  w.reply = (url) => (url.includes('messages.send') ? '{"response":{"cmid":9,"message_id":9}}' : '{"response":{"items":[]}}');
  await w.fetch('https://web.api.vk.ru/method/messages.getHistory', { method: 'POST', body: 'peer_id=5&access_token=TKN&v=5.199' });
  await wait(30);
  w.__vkReader.forward('30804332', [301]);
  await wait(80);
  const send = w.requests.find((r) => r.url.includes('/method/messages.send'));
  const p = Object.fromEntries(new URLSearchParams(send.body));
  assert.strictEqual(p.peer_id, '30804332');
  assert.strictEqual(p.message, undefined, 'без текста');
  assert.deepStrictEqual(JSON.parse(p.forward), { peer_id: 5, conversation_message_ids: [301] });
  assert.deepStrictEqual(w.sentToApp.filter((x) => x.type === 'forwarded').pop(), { type: 'forwarded', ok: true, target: '30804332', reason: '' });
  w.__vkReader.forward('abc', [301]);
  await wait(20);
  assert.strictEqual(w.sentToApp.filter((x) => x.type === 'forwarded').pop().ok, false, 'неверный адресат — отказ без запроса');
});

test('окно чтения: файлы видео — video.get с ключом доступа, mp4 до 720p, чужие адреса отбрасываются', async () => {
  const w = readerApiPage();
  w.reply = (url) => (url.includes('video.get') ? JSON.stringify({ response: { items: [{ id: 77, owner_id: -5, title: 'Клип &amp; кот',
    files: { mp4_240: 'https://vkvd1.okcdn.ru/?q=240', mp4_720: 'https://vkvd1.okcdn.ru/?q=720', mp4_1080: 'https://vkvd1.okcdn.ru/?q=1080',
      hls: 'https://evil.example/v.m3u8', hls_fmp4: 'https://vkvd1.okcdn.ru/video.m3u8?x' },
    player: 'https://vkvideo.ru/video_ext.php?oid=-5&id=77&hash=h' }] } }) : '{"response":{"items":[]}}');
  await w.fetch('https://web.api.vk.ru/method/messages.getHistory', { method: 'POST', body: 'peer_id=5&access_token=TKN&v=5.199' });
  await wait(30);
  w.__vkReader.video('-5_77', 'k3y');
  await wait(80);
  const req = w.requests.find((r) => r.url.includes('/method/video.get'));
  assert.strictEqual(new URLSearchParams(req.body).get('videos'), '-5_77_k3y');
  const v = w.sentToApp.filter((x) => x.type === 'video').pop();
  assert.deepStrictEqual([v.id, v.ok, v.mp4, v.hls, v.player, v.title],
    ['-5_77', true, 'https://vkvd1.okcdn.ru/?q=720', 'https://vkvd1.okcdn.ru/video.m3u8?x', 'https://vkvideo.ru/video_ext.php?oid=-5&id=77&hash=h', 'Клип & кот']);
});

test('окно чтения: без файлов видео — второй способ, затем отказ (видео откроется страницей)', async () => {
  const w = readerApiPage();
  w.reply = () => '{"response":{"items":[{"id":77,"owner_id":-5,"title":"x"}]}}';
  await w.fetch('https://web.api.vk.ru/method/messages.getHistory', { method: 'POST', body: 'peer_id=5&access_token=TKN&v=5.199' });
  await wait(30);
  w.__vkReader.video('-5_77', '');
  await wait(120);
  assert.ok(w.requests.some((r) => r.url.includes('/method/video.getByIds')), 'спросили и вторым способом');
  assert.strictEqual(w.sentToApp.filter((x) => x.type === 'video').pop().ok, false);
});

test('окно чтения: звонки — направление, итог и длительность карточкой, строка диалога по звонку', async () => {
  const w = readerPage();
  w.vk = { id: 8 };
  const call = (c) => ({ type: 'call', call: c });
  w.nextResponse = JSON.stringify({ response: { items: [
    { id: 1, conversation_message_id: 101, date: 1700000000, from_id: 8, peer_id: 5, out: 1, text: '',
      attachments: [call({ initiator_id: 8, receiver_id: 5, state: 'reached', duration: 65, video: true, time: 1700000000 })] },
    { id: 2, conversation_message_id: 102, date: 1700000100, from_id: 5, peer_id: 5, out: 0, text: '',
      attachments: [call({ initiator_id: 5, receiver_id: 8, state: 'canceled_by_initiator', video: false })] },
    { id: 3, conversation_message_id: 103, date: 1700000200, from_id: 8, peer_id: 5, out: 1, text: '',
      attachments: [call({ initiator_id: 8, receiver_id: 5, state: 'canceled_by_receiver', video: false })] }] } });
  await w.fetch('https://web.api.vk.ru/method/messages.getHistory?peer_id=5');
  await wait(30);
  w.__vkReader.scrape('history');
  await wait(60);
  const items = w.sentToApp.filter((x) => x.type === 'history').pop().items;
  assert.deepStrictEqual(items.map((m) => [m.text, m.cards[0].kind, m.cards[0].title, m.cards[0].subtitle]), [
    ['', 'call', 'Исходящий видеозвонок', 'Длительность 1:05'],
    ['', 'call', 'Пропущенный звонок', 'Вы не ответили'],
    ['', 'call', 'Исходящий звонок', 'Собеседник отклонил'],
  ], 'вместо «[Звонок]» — сведения о звонке');
  w.__vkReader.scrape('dialogs');
  await wait(60);
  const d = (w.sentToApp.filter((x) => x.type === 'dialogs').pop() || { items: [] }).items.find((x) => x.peerId === '5');
  assert.ok(d, 'диалог есть в списке');
  assert.strictEqual(d.snippet, 'Исходящий звонок · Собеседник отклонил');
});

test('окно чтения: групповой звонок — участники и ссылка «присоединиться» только на VK', async () => {
  const w = readerPage();
  w.nextResponse = JSON.stringify({ response: { items: [
    { id: 1, conversation_message_id: 101, date: 1700000000, from_id: 5, peer_id: 5, out: 0, text: '',
      attachments: [{ type: 'group_call_in_progress', group_call_in_progress: { initiator_id: 5, join_link: 'https://vk.com/call/join/abc', participants: { count: 3, list: [5, 6, 7] } } }] },
    { id: 2, conversation_message_id: 102, date: 1700000100, from_id: 5, peer_id: 5, out: 0, text: '',
      attachments: [{ type: 'group_call_in_progress', group_call_in_progress: { initiator_id: 5, join_link: 'https://evil.example/call', participants: { count: 2 } } }] }],
    profiles: [{ id: 5, first_name: 'Анна', last_name: 'К' }] } });
  await w.fetch('https://web.api.vk.ru/method/messages.getHistory?peer_id=5');
  await wait(30);
  w.__vkReader.scrape('history');
  await wait(60);
  const [a, b] = w.sentToApp.filter((x) => x.type === 'history').pop().items.map((m) => m.cards[0]);
  assert.deepStrictEqual([a.title, a.subtitle, a.url], ['Групповой звонок идёт', 'начал(а) Анна К · участников: 3', 'https://vk.com/call/join/abc']);
  assert.strictEqual(b.url, undefined, 'чужая ссылка не передаётся');
});

// ---- 3.55: трек из переписки — адрес потока из ответа API

test('окно чтения: адрес звука трека — из ответа с сообщением, затем audio.getById, затем поиск по названию', async () => {
  const w = readerApiPage();
  const stream = 'https://cs9-3v4.vkuseraudio.ru/s/v1/ac/x/index.m3u8?siren=1';
  w.reply = (url, body) => {
    if (url.includes('audio.getById')) return '{"response":[{"id":2,"owner_id":1,"artist":"A","title":"B","url":"https://vk.com/mp3/audio_api_unavailable.mp3"}]}';
    if (url.includes('catalog.getAudioSearch')) {
      assert.strictEqual(new URLSearchParams(body).get('query'), 'SISENIN — Блуд');
      return JSON.stringify({ response: { catalog: {}, audios: [
        { id: 9, owner_id: 383978993, artist: 'SISENIN', title: 'Блуд (mp3bob.ru)', duration: 137, url: stream + '&b' },
        { id: 456973058, owner_id: 474499155, artist: 'SISENIN', title: 'Блуд', duration: 137, url: stream }] } });
    }
    if (url.includes('messages.getHistory')) return JSON.stringify({ response: { items: [{ id: 1, conversation_message_id: 101, date: 1700000000, from_id: 5, peer_id: 5, out: 0, text: '',
      attachments: [{ type: 'audio', audio: { id: 7, owner_id: 3, artist: 'X', title: 'Y', duration: 60, url: stream + '&msg' } }] }] } });
    return '{"response":{"items":[]}}';
  };
  await w.fetch('https://web.api.vk.ru/method/messages.getHistory', { method: 'POST', body: 'peer_id=5&access_token=TKN&v=5.199' });
  await wait(30);
  w.__vkReader.scrape('history');
  await wait(40);
  w.__vkReader.audioUrl('3_7', '', 'X — Y');
  await wait(40);
  let r = w.sentToApp.filter((x) => x.type === 'audiourl').pop();
  assert.deepStrictEqual([r.ok, r.url, r.how], [true, stream + '&msg', 'сообщение'], 'адрес из ответа с сообщением, без запроса');
  w.__vkReader.audioUrl('1_2', 'k', 'SISENIN — Блуд');
  await wait(120);
  r = w.sentToApp.filter((x) => x.type === 'audiourl').pop();
  assert.ok(w.requests.some((q) => q.url.includes('audio.getById') && new URLSearchParams(q.body).get('audios') === '1_2_k'));
  assert.deepStrictEqual([r.ok, r.url, r.how, r.title, r.duration], [true, stream, 'поиск', 'SISENIN — Блуд', 137], 'заглушка по номеру не годится — точное совпадение в поиске');
});

// ---- 3.57: перемотка клипов и в ленте «Клипы»

test('клипы: полоса перемотки есть в ленте /clips — у идущего и видного ролика, нажатие перематывает', async () => {
  const w = page('https://m.vk.ru/clips', '<!doctype html><html><head></head><body><video id="a"></video><video id="b"></video></body></html>');
  w.innerHeight = 800;
  w.innerWidth = 400;
  const setup = (id, top, paused, duration) => {
    const v = w.document.getElementById(id);
    Object.defineProperty(v, 'src', { value: 'blob:https://m.vk.ru/' + id, configurable: true });
    Object.defineProperty(v, 'currentSrc', { value: 'blob:https://m.vk.ru/' + id, configurable: true });
    Object.defineProperty(v, 'duration', { value: duration, configurable: true });
    Object.defineProperty(v, 'paused', { value: paused, configurable: true });
    v.getBoundingClientRect = () => ({ left: 0, right: 400, top, bottom: top + 800, width: 400, height: 800 });
    v.currentTime = 0;
    return v;
  };
  const shown = setup('a', 0, false, 40);
  const next = setup('b', 800, true, 30); // следующий в ленте — ниже экрана
  w.eval(liteParts().replace('/*CONFIG*/null', JSON.stringify(LITE_CONFIG)));
  await wait(700);
  const bar = w.document.querySelector('[data-vklite-seek]');
  assert.ok(bar, 'полоса создана в ленте клипов');
  assert.strictEqual(bar.style.display, 'block');
  bar.getBoundingClientRect = () => ({ left: 0, right: 400, top: 764, bottom: 800, width: 400, height: 36 });
  const down = new w.Event('pointerdown', { bubbles: true, cancelable: true });
  down.clientX = 200;
  down.pointerId = 1;
  bar.dispatchEvent(down);
  assert.strictEqual(Math.round(shown.currentTime), 20, 'середина полосы — середина идущего ролика');
  assert.strictEqual(next.currentTime, 0, 'следующий ролик ленты не тронут');
  w.close(); // таймер полосы держал бы проверку вечно
});

// ---- 3.58: прочитано, сообщение с файлами сразу, увиденные записи

test('окно чтения: «прочитано» — messages.markAsRead открытой переписки', async () => {
  const w = readerApiPage();
  w.reply = (url) => (url.includes('markAsRead') ? '{"response":1}' : '{"response":{"items":[]}}');
  await w.fetch('https://web.api.vk.ru/method/messages.getHistory', { method: 'POST', body: 'peer_id=5&access_token=TKN&v=5.199' });
  await wait(30);
  assert.strictEqual(w.__vkReader.markRead(), true);
  await wait(60);
  const req = w.requests.find((r) => r.url.includes('/method/messages.markAsRead'));
  const p = Object.fromEntries(new URLSearchParams(req.body));
  assert.deepStrictEqual([p.peer_id, p.mark_conversation_as_read], ['5', '1']);
});

test('окно чтения: после отправки с файлами сообщение запрашивается целиком и приходит в историю', async () => {
  const w = readerApiPage();
  w.reply = (url) => {
    if (url.includes('messages.send')) return '{"response":{"cmid":77,"message_id":7}}';
    if (url.includes('getByConversationMessageId')) return JSON.stringify({ response: { count: 1, items: [{ id: 7, conversation_message_id: 77, date: 1700000500, from_id: 8, peer_id: 5, out: 1, text: 'Смотри',
      attachments: [{ type: 'doc', doc: { id: 1, owner_id: 8, title: 'отчёт.pdf', ext: 'pdf', size: 2048, url: 'https://vk.com/doc8_1' } }] }] } });
    return '{"response":{"items":[]}}';
  };
  await w.fetch('https://web.api.vk.ru/method/messages.getHistory', { method: 'POST', body: 'peer_id=5&access_token=TKN&v=5.199' });
  await wait(30);
  // Так отправляет композер страницы после загрузки файлов: в запросе есть attachment.
  await w.fetch('https://web.api.vk.ru/method/messages.send', { method: 'POST', body: 'peer_id=5&message=%D0%A1%D0%BC%D0%BE%D1%82%D1%80%D0%B8&attachment=doc8_1&access_token=TKN&v=5.199' });
  await wait(30);
  w.__vkReader.scrape('history');
  await wait(60);
  assert.ok(w.requests.some((r) => r.url.includes('getByConversationMessageId') && new URLSearchParams(r.body).get('conversation_message_ids') === '77'),
    'сообщение запрошено по номеру из ответа отправки');
  const items = w.sentToApp.filter((x) => x.type === 'history').pop().items;
  const m = items.find((x) => x.id === 'c77');
  assert.ok(m, 'отправленное с файлом — в истории сразу');
  assert.strictEqual(m.cards[0].title, 'отчёт.pdf', 'вместе с файлом, а не одной подписью');
});

test('страница сообщества: увиденные на своём экране записи выводятся на экран страницы по очереди', async () => {
  const html = '<!doctype html><html><head></head><body><div class="post" data-post-id="-5_1">1</div><div class="post"><a href="/wall-5_2">2</a></div></body></html>';
  const w = page('https://m.vk.ru/club5', html);
  const scrolled = [];
  w.HTMLElement.prototype.scrollIntoView = function () { scrolled.push(this.getAttribute('data-post-id') || this.textContent); };
  // Страница закрыта своим экраном приложения (настоящее окно скрыто) — только тогда её можно прокручивать.
  let hidden = true;
  w.__vkWebVisibility = { hidden: () => hidden, soon: (fn) => setTimeout(fn, 0), afterScroll() {}, onChange() {} };
  w.eval(liteParts().replace('/*CONFIG*/null', JSON.stringify(LITE_CONFIG)));
  assert.strictEqual(w.__vkLite.owner('seen', '-5_1 -5_2 -5_9 bad'), true);
  await wait(50);
  assert.deepStrictEqual(scrolled, ['-5_1'], 'первая — сразу');
  await wait(1300);
  assert.deepStrictEqual(scrolled, ['-5_1', '2'], 'вторая — через паузу (по ссылке на запись)');
  w.__vkLite.owner('seen', '-5_1');
  await wait(1300);
  assert.strictEqual(scrolled.filter((x) => x === '-5_1').length, 1, 'уже увиденная — не повторно');
  // Человек переключился на саму страницу VK — очередь останавливается, страница не прыгает у него перед глазами.
  const before = scrolled.length;
  w.document.body.insertAdjacentHTML('beforeend', '<div class="post" data-post-id="-5_3">3</div><div class="post" data-post-id="-5_4">4</div>');
  w.__vkLite.owner('seen', '-5_3 -5_4');
  hidden = false;
  await wait(1300);
  assert.ok(!scrolled.slice(before).includes('-5_4'), 'после того как страницу стало видно — очередь не продолжается');
  assert.ok(scrolled.length <= before + 1);
  assert.strictEqual(w.__vkLite.owner('seen', '-5_4'), false, 'на видимой странице команда не прокручивает');
  w.close();
});

test('окно чтения: ответ пересылки не принимается за отправку текста из очереди (код ревью 3.59)', async () => {
  const w = readerApiPage();
  w.reply = (url) => {
    if (url.includes('messages.send')) return '{"response":{"cmid":900,"message_id":9}}';
    if (url.includes('getByConversationMessageId')) return '{"response":{"count":0,"items":[]}}';
    return '{"response":{"items":[]}}';
  };
  await w.fetch('https://web.api.vk.ru/method/messages.getHistory', { method: 'POST', body: 'peer_id=5&access_token=TKN&v=5.199' });
  await wait(30);
  w.__vkReader.send('секретный текст'); // поле ввода не найдено — «sent:false», но lastSent запомнен
  w.__vkReader.forward('5', [10]);
  await wait(80);
  const delivered = w.sentToApp.filter((x) => x.type === 'delivered');
  assert.strictEqual(delivered.length, 0, 'пересылка — не «доставлено»');
  w.__vkReader.scrape('history');
  await wait(60);
  const items = (w.sentToApp.filter((x) => x.type === 'history').pop() || { items: [] }).items;
  assert.ok(!items.some((m) => m.text === 'секретный текст'), 'чужой текст не встал в историю под номером пересланного');
  assert.ok(w.requests.some((r) => r.url.includes('getByConversationMessageId')), 'пересланное в эту же переписку запрошено целиком');
});

test('окно чтения: очередь музыки переписки — один audio.getById на все треки, нажатый без адреса — поиском', async () => {
  const w = readerApiPage();
  const url = (n) => 'https://cs9-3v4.vkuseraudio.ru/s/v1/ac/' + n + '/index.m3u8';
  w.reply = (u, body) => {
    if (u.includes('audio.getById')) {
      assert.strictEqual(new URLSearchParams(body).get('audios'), '1_1_k1,1_2,1_3');
      return JSON.stringify({ response: [
        { id: 1, owner_id: 1, artist: 'A', title: 'One', duration: 60, url: url(1) },
        { id: 3, owner_id: 1, artist: 'C', title: 'Three', duration: 90, url: 'https://vk.com/mp3/audio_api_unavailable.mp3' }] });
    }
    if (u.includes('catalog.getAudioSearch')) return JSON.stringify({ response: { audios: [{ id: 7, owner_id: 9, artist: 'B', title: 'Two', duration: 70, url: url(2) }] } });
    return '{"response":{"items":[]}}';
  };
  await w.fetch('https://web.api.vk.ru/method/messages.getHistory', { method: 'POST', body: 'peer_id=5&access_token=TKN&v=5.199' });
  await wait(30);
  w.__vkReader.audioQueue([{ id: '1_1', key: 'k1', title: 'A — One' }, { id: '1_2', key: '', title: 'B — Two' }, { id: '1_3', key: '', title: 'C — Three' }], '1_2', 'B — Two');
  await wait(120);
  const q = w.sentToApp.filter((x) => x.type === 'audioqueue').pop();
  assert.strictEqual(q.wanted, '1_2');
  assert.deepStrictEqual(q.items.map((i) => [i.id, i.url, i.title]), [['1_1', url(1), 'One'], ['1_2', url(2), 'Two']],
    'по порядку переписки; без адреса (заглушка) — не в очереди; нажатый найден поиском');
  assert.strictEqual(q.how, 'поиск');
});

test('окно чтения (4.88): звук трека — mp3 из audio_streams (okcdn), повтор — старым адресом url', async () => {
  const w = readerApiPage();
  const legacy = 'https://cs1-59v4.vkuseraudio.ru/s/v1/acmp/abc.mp3?siren=1';
  const mp3 = 'https://vd437.okcdn.ru/?expires=1&type=mp3';
  const track = { id: 1, owner_id: 1, artist: 'A', title: 'One', duration: 60, url: legacy, audio_streams: [
    { type: 'aac', url: 'https://vd437.okcdn.ru/?type=aac' }, { type: 'mp3', url: mp3, fallback_url: 'https://vd445.okcdn.ru/?type=mp3' },
    { type: 'hls_ts', url: 'https://vd437.okcdn.ru/expires/1/clientType/58/fn/track.m3u8' }] };
  w.reply = (u) => {
    if (u.includes('audio.getById')) return JSON.stringify({ response: [track, { id: 2, owner_id: 1, artist: 'B', title: 'Two', duration: 70,
      url: legacy + '&2', audio_streams: [{ type: 'mp3', url: 'https://evil.example.com/a.mp3' }] }] });
    return '{"response":{"items":[]}}';
  };
  await w.fetch('https://web.api.vk.ru/method/messages.getHistory', { method: 'POST', body: 'peer_id=5&access_token=TKN&v=5.199' });
  await wait(30);
  w.__vkReader.audioQueue([{ id: '1_1', key: '', title: 'A — One' }, { id: '1_2', key: '', title: 'B — Two' }], '1_1', 'A — One');
  await wait(120);
  const q = w.sentToApp.filter((x) => x.type === 'audioqueue').pop();
  assert.deepStrictEqual(q.items.map((i) => i.url), [mp3, legacy + '&2'], 'mp3 с okcdn; чужой сервер — не годится, тогда старый адрес');
  w.__vkReader.audioUrl('1_1', '', 'A — One', true);
  await wait(120);
  const r = w.sentToApp.filter((x) => x.type === 'audiourl').pop();
  assert.deepStrictEqual([r.ok, r.url], [true, legacy], 'свежий адрес после неудачи — другим путём');
});

test('окно чтения: стикер — картинкой, а не «[Стикер]»; долгий опрос с событием — свежие сообщения запросом', async () => {
  const w = readerApiPage();
  let history = 0;
  w.reply = (url) => {
    if (url.includes('messages.getHistory')) {
      history++;
      return JSON.stringify({ response: { items: [{ id: 1, conversation_message_id: 101, date: 1700000000, from_id: 5, peer_id: 5, out: 0, text: '',
        attachments: [{ type: 'sticker', sticker: { sticker_id: 9, images: [{ url: 'https://vk.com/sticker/1-9-64', width: 64, height: 64 }, { url: 'https://vk.com/sticker/1-9-256', width: 256, height: 256 }] } }] }] } });
    }
    if (url.includes('/im0332')) return '{"ts":"2","events":["x"]}';
    return '{"response":{"items":[]}}';
  };
  await w.fetch('https://web.api.vk.ru/method/messages.getHistory', { method: 'POST', body: 'peer_id=5&access_token=TKN&v=5.199' });
  await wait(30);
  w.__vkReader.scrape('history');
  await wait(60);
  const m = w.sentToApp.filter((x) => x.type === 'history').pop().items[0];
  assert.deepStrictEqual([m.text, m.photos[0].url, m.photos[0].sticker], ['', 'https://vk.com/sticker/1-9-256', true]);
  const before = history;
  await w.fetch('https://queuev4.vk.ru/im0332?act=a_check&ts=1', { method: 'GET' });
  await wait(600);
  assert.strictEqual(history, before + 1, 'событие опроса — один запрос истории');
});

// ---- 3.65: «прочитано» до первого запроса страницы, переход ссылкой самой страницы

test('окно чтения: «прочитано» до первого запроса страницы не теряется — уходит, когда появятся данные входа', async () => {
  const w = readerApiPage();
  w.reply = (url) => (url.includes('markAsRead') ? '{"response":1}' : '{"response":{"items":[]}}');
  assert.strictEqual(w.__vkReader.markRead(), true);
  await wait(60);
  assert.ok(!w.requests.some((r) => r.url.includes('markAsRead')), 'без данных входа запроса нет');
  assert.ok(!w.sentToApp.some((m) => m.type === 'log' && /не отмечена/.test(m.message)), 'и предупреждения нет');
  await w.fetch('https://web.api.vk.ru/method/messages.getHistory', { method: 'POST', body: 'peer_id=5&access_token=TKN&v=5.199' });
  await wait(700);
  const req = w.requests.find((r) => r.url.includes('/method/messages.markAsRead'));
  assert.ok(req, 'отметка ушла после первого запроса страницы');
  assert.strictEqual(new URLSearchParams(req.body).get('peer_id'), '5');
  w.close();
});

test('переход силами страницы: есть ссылка самой страницы на сообщество — нажимается она (и по короткому имени)', async () => {
  const w = page('https://m.vk.com/groups', '<!doctype html><body><a id="g" href="/rockclub">Рок</a><a id="o" href="/club77">Другое</a></body>');
  const resp = JSON.stringify({ response: { catalog: { sections: [] }, groups: [{ id: 39, name: 'Рок', screen_name: 'rockclub' }] } });
  w.fetch = async () => ({ ok: true, status: 200, headers: { get: (h) => (h === 'content-type' ? 'application/json' : null) },
    clone() { return { text: async () => resp }; }, json: async () => JSON.parse(resp) });
  w.eval(liteParts().replace('/*CONFIG*/null', JSON.stringify(LITE_CONFIG)));
  const clicks = [];
  ['g', 'o'].forEach((id) => w.document.getElementById(id).addEventListener('click', (e) => { clicks.push(id); e.preventDefault(); }));
  await w.fetch('https://api.vk.com/method/catalog.getGroups', { method: 'POST', body: 'owner_id=0&access_token=T&v=5.199' });
  await wait(50);
  w.__vkLite.command('navigate', '/club77');
  w.__vkLite.command('navigate', '/club39');
  assert.deepStrictEqual(clicks, ['o', 'g']);
  w.close();
});

test('переход силами страницы: сообщество в «Моих сообществах» — ссылка с trackcode, номер в data-id (отчёт 3.80)', async () => {
  const w = page('https://m.vk.com/groups', '<!doctype html><body><a id="p" data-id="29231851" href="/wall-29231851_5">Запись</a>' +
    '<a id="g" data-id="29231851" href="/lovelycosplay?trackcode=abc">Косплей</a></body>');
  w.eval(liteParts().replace('/*CONFIG*/null', JSON.stringify(LITE_CONFIG)));
  const clicks = [];
  ['p', 'g'].forEach((id) => w.document.getElementById(id).addEventListener('click', (e) => { clicks.push(id); e.preventDefault(); }));
  w.__vkLite.command('navigate', '/club29231851');
  assert.deepStrictEqual(clicks, ['g'], 'нажата ссылка сообщества, не записи');
  w.close();
});

// ---- 3.67: токен страницы сохраняется со сроком; окно чтения переходит и обновляется без перезагрузки

test('токен страницы: сохраняется со сроком из web_token, новое окно действует сразу, истёкший не берётся', async () => {
  const w1 = readerApiPage();
  w1.reply = (url) => (url.includes('web_token') ? JSON.stringify({ type: 'okay', data: { access_token: 'TKN', expires: Math.floor(Date.now() / 1000) + 3600 } }) : '{"response":{"items":[]}}');
  await w1.fetch('https://login.vk.ru/?act=web_token', { method: 'POST', body: 'version=1' });
  await w1.fetch('https://web.api.vk.ru/method/messages.getHistory', { method: 'POST', body: 'peer_id=5&access_token=TKN&v=5.199' });
  await wait(30);
  const saved = JSON.parse(w1.localStorage.getItem('__vkweb_auth'));
  assert.strictEqual(saved.token, 'TKN');
  assert.ok(saved.exp > Date.now() + 3000 * 1000, 'срок — из ответа web_token');
  assert.strictEqual(saved.base, 'https://web.api.vk.ru/method/');

  // Новое окно того же сайта: страница ещё ничего не запрашивала, а «прочитано» уходит сразу.
  const w2 = page('https://m.vk.com/mail/convo/5');
  w2.localStorage.setItem('__vkweb_auth', JSON.stringify(saved));
  w2.requests = [];
  w2.fetch = async (url, init) => { w2.requests.push({ url: String(url), body: String(init && init.body) }); return { ok: true, status: 200, headers: { get: () => 'application/json' }, clone() { return { text: async () => '{"response":1}' }; }, json: async () => ({ response: 1 }) }; };
  w2.vkReaderBridge = { postMessage: () => {} };
  w2.eval(read('page_visibility.js'));
  w2.eval(netTapSrc() + '\n' + read('vk_reader.js').replace('/*CONFIG*/null', JSON.stringify(READER_CFG)));
  assert.strictEqual(w2.__vkNetTap.apiReady(), true);
  w2.__vkReader.markRead();
  await wait(30);
  const req = w2.requests.find((r) => r.url.includes('messages.markAsRead'));
  assert.ok(req && new URLSearchParams(req.body).get('access_token') === 'TKN');

  // Истёкший токен не используется и удаляется.
  const w3 = page('https://m.vk.com/mail');
  w3.localStorage.setItem('__vkweb_auth', JSON.stringify(Object.assign({}, saved, { exp: Date.now() - 1000 })));
  w3.eval(netTapSrc());
  assert.strictEqual(w3.__vkNetTap.apiReady(), false);
  assert.strictEqual(w3.localStorage.getItem('__vkweb_auth'), null);
  [w1, w2, w3].forEach((w) => w.close());
});

test('окно чтения: переход в переписку — ссылкой мессенджера, без загрузки; затем свежая история запросом', async () => {
  const w = readerApiPage();
  w.reply = () => '{"response":{"items":[]}}';
  await w.fetch('https://web.api.vk.ru/method/messages.getConversations', { method: 'POST', body: 'access_token=TKN&v=5.199' });
  w.history.replaceState(null, '', '/mail');
  const a = w.document.createElement('a');
  a.href = '/mail/convo/7';
  // Как роутер мессенджера: нажатие меняет адрес без загрузки.
  a.addEventListener('click', (e) => { e.preventDefault(); w.history.pushState(null, '', '/mail/convo/7'); });
  w.document.body.appendChild(a);
  assert.strictEqual(w.__vkReader.go('/mail/convo/7'), true);
  await wait(300);
  assert.strictEqual(w.location.pathname, '/mail/convo/7');
  const req = w.requests.find((r) => r.url.includes('messages.getHistory'));
  assert.ok(req, 'свежая история запрошена');
  assert.strictEqual(new URLSearchParams(req.body).get('peer_id'), '7');
  assert.strictEqual(w.__vkReader.go('https://evil.example/mail'), false, 'чужой адрес — нет');
  w.close();
});

// ---- 3.68: дамп окна чтения, события токена в журнале

test('окно чтения: дамп разработчика — ответы API и сокета, обезличенные', async () => {
  const w = page('https://m.vk.com/mail/convo/5');
  w.sentToApp = [];
  w.vkReaderBridge = { postMessage: (m) => w.sentToApp.push(JSON.parse(m)) };
  const text = JSON.stringify({ response: { items: [{ id: 1, text: 'Секрет', from_id: 5 }], profiles: [{ id: 5, first_name: 'Анна' }] } });
  w.fetch = async () => ({ ok: true, status: 200, headers: { get: () => 'application/json' }, clone() { return { text: async () => text }; }, json: async () => JSON.parse(text) });
  w.eval(read('page_visibility.js'));
  w.eval(netTapSrc() + '\n' + read('vk_reader.js').replace('/*CONFIG*/null', JSON.stringify(Object.assign({}, READER_CFG, { collectDump: true }))));
  await w.fetch('https://web.api.vk.ru/method/messages.getHistory', { method: 'POST', body: 'peer_id=5&access_token=TKN&v=5.199' });
  await wait(50);
  const rec = w.sentToApp.find((m) => m.type === 'dump' && m.method === 'messages.getHistory');
  assert.ok(rec, 'запись дампа есть');
  assert.strictEqual(rec.params.access_token, 'XXX');
  assert.strictEqual(rec.response.response.items[0].text, '•••(6)');
  assert.strictEqual(rec.response.response.profiles[0].first_name, '•••(4)');
  assert.ok(!JSON.stringify(rec).includes('TKN') && !JSON.stringify(rec).includes('Секрет'));
  w.close();
});

test('токен: события в журнал окна — взят сохранённый, устарел (ошибка 5) и повтор', async () => {
  const w = page('https://m.vk.com/mail/convo/5');
  w.localStorage.setItem('__vkweb_auth', JSON.stringify({ base: 'https://web.api.vk.ru/method/', token: 'OLD', v: '5.199', clientId: '', exp: Date.now() + 3600e3, savedAt: Date.now() }));
  w.sentToApp = [];
  w.vkReaderBridge = { postMessage: (m) => w.sentToApp.push(JSON.parse(m)) };
  w.fetch = async (url, init) => {
    const body = String(init && init.body);
    const r = body.includes('access_token=OLD') ? { error: { error_code: 5, error_msg: 'User authorization failed' } } : { response: 1 };
    const t = JSON.stringify(r);
    return { ok: true, status: 200, headers: { get: () => 'application/json' }, clone() { return { text: async () => t }; }, json: async () => r };
  };
  w.eval(read('page_visibility.js'));
  w.eval(netTapSrc() + '\n' + read('vk_reader.js').replace('/*CONFIG*/null', JSON.stringify(READER_CFG)));
  const logs = () => w.sentToApp.filter((m) => m.type === 'log').map((m) => m.message).join('\n');
  assert.match(logs(), /взят сохранённый токен, срок — \d+ мин/);
  w.__vkReader.markRead();
  await wait(50);
  assert.match(logs(), /сохранённый устарел \(ошибка 5\) на messages\.markAsRead/);
  // Страница сама обращается к API со свежим токеном — повтор уходит.
  await w.fetch('https://web.api.vk.ru/method/messages.getHistory', { method: 'POST', body: 'peer_id=5&access_token=NEW&v=5.199' });
  await wait(700);
  assert.match(logs(), /свежий токен получен — повтор messages\.markAsRead/);
  assert.ok(!logs().includes('OLD') && !logs().includes('NEW'), 'значение токена в журнал не попадает');
  w.close();
});

// ---- 3.70: долгий опрос api.vk.com/ruim… (act=a_check в теле)

test('окно чтения: опрос api.vk.com/ruim — новое сообщение запрашивает историю, «печатает» — нет', async () => {
  const w = readerApiPage();
  let history = 0;
  let updates = '[[602,5,0]]';
  w.reply = (url) => {
    if (url.includes('messages.getHistory')) { history++; return '{"response":{"items":[]}}'; }
    if (url.includes('/ruim')) return '{"ts":2,"pts":3,"updates":' + updates + '}';
    return '{"response":{"items":[]}}';
  };
  await w.fetch('https://web.api.vk.ru/method/messages.getHistory', { method: 'POST', body: 'peer_id=5&access_token=TKN&v=5.199' });
  await wait(30);
  const before = history;
  await w.fetch('https://api.vk.com/ruim30804332', { method: 'POST', body: 'act=a_check&key=K&ts=1&wait=25' });
  await wait(600);
  assert.strictEqual(history, before, 'набор текста — без запроса');
  updates = '[[10004,5,101,0,1700000000],[80,1,0]]';
  await w.fetch('https://api.vk.com/ruim30804332', { method: 'POST', body: 'act=a_check&key=K&ts=2&wait=25' });
  await wait(600);
  assert.strictEqual(history, before + 1, 'новое сообщение — один запрос истории');
  w.close();
});

// ---- 3.72: плейлисты — свои и добавленные (медиатека), без недавно слушанных

test('плейлисты: медиатека от VK — свои и добавленные остаются, недавно слушанные убираются', async () => {
  const card = (o, id, title) => `<div class="pl"><a href="/audio?act=audio_playlist${o}_${id}"><img src="https://sun9-2.vkuserphoto.ru/c${id}.jpg"></a><span>${title}</span><span>3 трека</span></div>`;
  const w = page('https://m.vk.com/audio?act=audio_playlists7', '<!doctype html><body>' + card(7, 1, 'Свой') + card(-9, 2, 'Добавленный') + card(-5, 3, 'Недавний') + '</body>');
  const lib = { response: { count: 2, items: [
    { id: 1, owner_id: 7, title: 'Свой', count: 3, access_key: 'k1' },
    { id: 44, owner_id: 7, title: 'Добавленный', count: 3, access_key: 'k2', original: { owner_id: -9, playlist_id: 2, access_key: 'o2' } },
  ] } };
  w.fetch = async (url) => {
    const t = String(url).includes('audio.getPlaylists') ? JSON.stringify(lib) : '{"response":1}';
    return { ok: true, status: 200, headers: { get: () => 'application/json' }, clone() { return { text: async () => t }; }, json: async () => JSON.parse(t) };
  };
  w.eval(read('page_visibility.js'));
  w.eval(liteParts().replace('/*CONFIG*/null', JSON.stringify(LITE_CONFIG)));
  await w.fetch('https://web.api.vk.ru/method/account.getInfo', { method: 'POST', body: 'access_token=T&v=5.199' });
  w.__vkLite.music('playlists');
  await wait(200);
  const p = lastOf(w, 'playlists');
  assert.deepStrictEqual(p.items.map((i) => i.id), ['7_1', '-9_2'], 'недавний -5_3 убран, добавленный — одним пунктом (оригинал)');
  w.close();
});

test('плейлисты: добавленный чужой, которого нет на странице, — адрес оригинала с его ключом доступа (код ревью 3.81)', async () => {
  const w = page('https://m.vk.com/audio?act=audio_playlists7', '<!doctype html><body><div class="pl"><a href="/audio?act=audio_playlist7_1"><img src="https://sun9-2.vkuserphoto.ru/c1.jpg"></a><span>Свой</span></div></body>');
  const lib = { response: { count: 2, items: [
    { id: 1, owner_id: 7, title: 'Свой', count: 3, access_key: 'k1' },
    { id: 44, owner_id: 7, title: 'Добавленный', count: 3, access_key: 'copy', original: { owner_id: -9, playlist_id: 2, access_key: 'orig' } },
  ] } };
  w.fetch = async (url) => {
    const t = String(url).includes('audio.getPlaylists') ? JSON.stringify(lib) : '{"response":1}';
    return { ok: true, status: 200, headers: { get: () => 'application/json' }, clone() { return { text: async () => t }; }, json: async () => JSON.parse(t) };
  };
  w.eval(read('page_visibility.js'));
  w.eval(liteParts().replace('/*CONFIG*/null', JSON.stringify(LITE_CONFIG)));
  await w.fetch('https://web.api.vk.ru/method/account.getInfo', { method: 'POST', body: 'access_token=T&v=5.199' });
  w.__vkLite.music('playlists');
  await wait(200);
  const p = lastOf(w, 'playlists');
  const followed = p.items.find((i) => i.id === '-9_2');
  assert.ok(followed, 'добавленный — пунктом оригинала');
  assert.ok(/access_hash=orig/.test(followed.url) && !/copy/.test(followed.url), 'ключ оригинала, не копии: ' + followed.url);
  w.close();
});

// ---- 3.72: данные беседы из ответов API

test('окно чтения: прочитано собеседником, можно ли писать, когда был в сети, без уведомлений; видео — из вложения', async () => {
  const w = readerApiPage();
  const now = Math.floor(Date.now() / 1000);
  w.reply = (url) => {
    if (url.includes('messages.getHistory')) return JSON.stringify({ response: { count: 1, items: [
      { id: 2, conversation_message_id: 20, date: now, from_id: 9, peer_id: 5, out: 1, text: 'Привет',
        attachments: [{ type: 'video', video: { id: 7, owner_id: 5, title: 'Ролик', duration: 3, files: { mp4_480: 'https://vkvd1.okcdn.ru/v.mp4' } } }] }],
      profiles: [{ id: 5, first_name: 'Анна', last_name: 'К', online_info: { visible: true, is_online: false, last_seen: now - 300, is_mobile: true } }],
      conversations: [{ peer: { id: 5, type: 'user' }, last_conversation_message_id: 20, in_read_cmid: 20, out_read_cmid: 19,
        can_write: { allowed: false, reason: 915 }, push_settings: { disabled_forever: true } }] } });
    if (url.includes('video.get')) throw new Error('video.get не нужен');
    return '{"response":{"items":[]}}';
  };
  await w.fetch('https://web.api.vk.ru/method/messages.getHistory', { method: 'POST', body: 'peer_id=5&access_token=TKN&v=5.199' });
  await wait(30);
  w.__vkReader.scrape('history');
  await wait(60);
  const h = w.sentToApp.filter((m) => m.type === 'history').pop();
  assert.deepStrictEqual([h.outRead, h.canWrite, h.online, h.mobile, h.lastSeen], [19, false, false, true, now - 300]);
  w.__vkReader.video('5_7', '');
  await wait(30);
  const v = w.sentToApp.filter((m) => m.type === 'video').pop();
  assert.deepStrictEqual([v.ok, v.mp4, v.reason], [true, 'https://vkvd1.okcdn.ru/v.mp4', 'вложение']);
  assert.ok(!w.requests.some((r) => r.url.includes('video.get')), 'запроса видео не было');
  w.close();
});

// ---- 3.73: стикеры

test('окно чтения: недавние стикеры из ответа страницы и отправка стикера запросом', async () => {
  const w = readerApiPage();
  w.reply = (url) => {
    if (url.includes('getRecentStickers')) return JSON.stringify({ response: { count: 2, items: [
      { sticker_id: 9046, is_allowed: true, images: [{ url: 'https://vk.ru/sticker/1-9046-64', width: 64 }, { url: 'https://vk.ru/sticker/1-9046-128', width: 128 }, { url: 'https://vk.ru/sticker/1-9046-512', width: 512 }] },
      { sticker_id: 1, is_allowed: false, images: [{ url: 'https://vk.ru/sticker/1-1-128', width: 128 }] }] } });
    if (url.includes('messages.send')) return '{"response":{"cmid":5}}';
    return '{"response":{"items":[]}}';
  };
  await w.fetch('https://web.api.vk.ru/method/messages.getRecentStickers', { method: 'POST', body: 'access_token=TKN&v=5.199' });
  await wait(30);
  w.__vkReader.stickers();
  const st = w.sentToApp.filter((m) => m.type === 'stickers').pop();
  assert.deepStrictEqual(st.items, [{ id: 9046, url: 'https://vk.ru/sticker/1-9046-128' }]);
  w.__vkReader.sendSticker(9046);
  await wait(50);
  const send = w.requests.find((r) => r.url.includes('messages.send'));
  const p = new URLSearchParams(send.body);
  assert.deepStrictEqual([p.get('peer_id'), p.get('sticker_id')], ['5', '9046']);
  assert.strictEqual(w.sentToApp.filter((m) => m.type === 'stickersent').pop().ok, true);
  w.close();
});

test('окно чтения: подсказки стикеров по словарю страницы — свои недавние первыми, недоступный больше не предлагается (3.92)', async () => {
  const w = readerApiPage();
  w.reply = (url) => {
    if (url.includes('getStickersKeywords')) return JSON.stringify({ response: { count: 1, dictionary: [
      { words: ['привет', 'хай'], stickers: [{ pack_id: 1, sticker_id: 21 }, { pack_id: 5, sticker_id: 9046 }, { pack_id: 7, sticker_id: 77 }] }] } });
    if (url.includes('getRecentStickers')) return JSON.stringify({ response: { count: 1, items: [
      { sticker_id: 9046, is_allowed: true, images: [{ url: 'https://vk.ru/sticker/1-9046-128', width: 128 }] }] } });
    if (url.includes('messages.send')) return JSON.stringify({ error: { error_code: 100, error_msg: 'sticker not available' } });
    return '{"response":{"items":[]}}';
  };
  await w.fetch('https://web.api.vk.ru/method/messages.getRecentStickers', { method: 'POST', body: 'access_token=TKN&v=5.199' });
  await w.fetch('https://web.api.vk.ru/method/store.getStickersKeywords', { method: 'POST', body: 'chunk=0&access_token=TKN&v=5.199' });
  await wait(30);
  w.__vkReader.stickerSuggest('Привет!');
  let s = w.sentToApp.filter((m) => m.type === 'stickersuggest').pop();
  assert.deepStrictEqual(s.items.map((x) => x.id), [9046, 21, 77]);
  assert.ok(/\/sticker\/1-21-128$/.test(s.items[1].url));
  w.__vkReader.sendSticker(77);
  await wait(50);
  w.__vkReader.stickerSuggest('хай');
  s = w.sentToApp.filter((m) => m.type === 'stickersuggest').pop();
  assert.deepStrictEqual(s.items.map((x) => x.id), [9046, 21]);
  w.__vkReader.stickerSuggest('неизвестное');
  assert.deepStrictEqual(w.sentToApp.filter((m) => m.type === 'stickersuggest').pop().items, []);
  w.close();
});

// ---- 3.76: лента клипов для своего проигрывателя

test('клипы: лента из shortVideo.getRecom — ролики с файлами и автором, реклама пропущена; продолжение запросом', async () => {
  const recom = (ids, anchor) => JSON.stringify({ response: { feed: { page_anchor: anchor, items: ids.map((id) => id === 'ad'
    ? { type: 'short_video_mytarget_sdk_video', item: { id: 9, owner_id: -9 } }
    : { type: 'short_video_full_legacy', item: { id, owner_id: -5, description: 'Описание', duration: 20, likes: { count: 7, user_likes: 0 }, comments: 2,
      files: { mp4_720: 'https://vkvd1.okcdn.ru/' + id + '.mp4', hls: 'https://vkvd1.okcdn.ru/' + id + '.m3u8' } } }) },
    groups: [{ id: 5, name: 'Автор', photo_100: 'https://sun9-1.userapi.com/a.jpg' }], profiles: [] } });
  const w = page('https://m.vk.com/clips');
  let next = recom([1, 'ad', 2], 'A1');
  w.requests = [];
  w.fetch = async (url, init) => {
    w.requests.push({ url: String(url), body: String(init && init.body) });
    const t = next;
    return { ok: true, status: 200, headers: { get: () => 'application/json' }, clone() { return { text: async () => t }; }, json: async () => JSON.parse(t) };
  };
  w.eval(liteParts().replace('/*CONFIG*/null', JSON.stringify(LITE_CONFIG)));
  await w.fetch('https://web.api.vk.ru/method/shortVideo.getRecom', { method: 'POST', body: 'ref=clips&count=10&access_token=T&v=5.199' });
  await wait(50);
  let p = lastOf(w, 'clips');
  assert.deepStrictEqual(p.items.map((c) => c.id), ['-5_1', '-5_2']);
  assert.deepStrictEqual([p.items[0].author, p.items[0].mp4, p.reset, p.hasMore], ['Автор', 'https://vkvd1.okcdn.ru/1.mp4', true, true]);
  next = recom([3], '');
  assert.strictEqual(w.__vkLite.clips('more'), true);
  await wait(80);
  const more = w.requests.filter((r) => r.url.includes('getRecom')).pop();
  assert.strictEqual(new URLSearchParams(more.body).get('page_anchor'), 'A1');
  p = lastOf(w, 'clips');
  assert.deepStrictEqual([p.items.map((c) => c.id), p.reset, p.hasMore], [['-5_1', '-5_2', '-5_3'], false, false]);
  w.close();
});

test('клипы: «release» возвращает звук роликам, которые заглушили мы; продолжение без ленты не блокирует следующее (код ревью 3.81)', async () => {
  const recom = JSON.stringify({ response: { feed: { page_anchor: 'A1', items: [{ type: 'short_video_full', item: { id: 1, owner_id: -5,
    files: { mp4_720: 'https://vkvd1.okcdn.ru/1.mp4' } } }] }, groups: [], profiles: [] } });
  const w = page('https://m.vk.com/clips', '<!doctype html><body><video id="a"></video><video id="b"></video></body>');
  let next = recom;
  w.fetch = async () => { const t = next; return { ok: true, status: 200, headers: { get: () => 'application/json' }, clone() { return { text: async () => t }; }, json: async () => JSON.parse(t) }; };
  w.eval(liteParts().replace('/*CONFIG*/null', JSON.stringify(LITE_CONFIG)));
  const a = w.document.getElementById('a');
  const b = w.document.getElementById('b');
  b.muted = true; // заглушён самой страницей
  w.__vkLite.clips('silence');
  assert.deepStrictEqual([a.muted, b.muted], [true, true]);
  w.__vkLite.clips('release');
  assert.deepStrictEqual([a.muted, b.muted], [false, true], 'звук вернулся только тому, что заглушили мы');
  await w.fetch('https://web.api.vk.ru/method/shortVideo.getRecom', { method: 'POST', body: 'ref=clips&access_token=T&v=5.199' });
  await wait(50);
  next = '{"response":{}}';
  assert.strictEqual(w.__vkLite.clips('more'), true);
  await wait(80);
  assert.strictEqual(w.__vkLite.clips('more'), true, 'после ответа без ленты продолжение снова можно просить');
  // Обработать второй ответ fetch и изменения DOM, пока документ ещё доступен.
  w.document.body.replaceChildren();
  await new Promise((resolve) => setImmediate(resolve));
  w.close();
});

test('клипы: вкладки раздела со страницы приходят с лентой; выбор вкладки — нажатие самой вкладки (3.84)', async () => {
  const recom = (id) => JSON.stringify({ response: { feed: { page_anchor: 'A', items: [{ type: 'short_video_full', item: { id, owner_id: -5,
    files: { mp4_720: 'https://vkvd1.okcdn.ru/' + id + '.mp4' } } }] }, groups: [], profiles: [] } });
  const w = page('https://m.vk.com/clips', '<!doctype html><body><div role="tablist"><button role="tab" aria-selected="true" id="t0">Для вас</button>' +
    '<button role="tab" aria-selected="false" id="t1">Подписки</button></div></body>');
  let next = recom(1);
  w.fetch = async () => { const t = next; return { ok: true, status: 200, headers: { get: () => 'application/json' }, clone() { return { text: async () => t }; }, json: async () => JSON.parse(t) }; };
  w.eval(liteParts().replace('/*CONFIG*/null', JSON.stringify(LITE_CONFIG)));
  await w.fetch('https://web.api.vk.ru/method/shortVideo.getRecom', { method: 'POST', body: 'ref=clips&access_token=T&v=5.199' });
  await wait(50);
  let p = lastOf(w, 'clips');
  assert.deepStrictEqual(p.tabs, [{ label: 'Для вас', selected: true }, { label: 'Подписки', selected: false }]);
  const clicked = [];
  w.document.getElementById('t1').addEventListener('click', () => {
    clicked.push('t1');
    w.document.getElementById('t0').setAttribute('aria-selected', 'false');
    w.document.getElementById('t1').setAttribute('aria-selected', 'true');
  });
  assert.strictEqual(w.__vkLite.clips('tab', '1'), true);
  assert.deepStrictEqual(clicked, ['t1']);
  // Страница запрашивает ленту вкладки — она приходит заново (reset) с отмеченной вкладкой.
  next = recom(2);
  await w.fetch('https://web.api.vk.ru/method/shortVideo.getRecom', { method: 'POST', body: 'ref=clips&tab=subscriptions&access_token=T&v=5.199' });
  await wait(50);
  p = lastOf(w, 'clips');
  assert.deepStrictEqual([p.reset, p.items.map((c) => c.id), p.tabs[1].selected], [true, ['-5_2'], true]);
  assert.strictEqual(w.__vkLite.clips('tab', '7'), false, 'нет такой вкладки');
  w.close();
});

test('дамп: ключ-идентификатор («key»: «for_you») не скрывается, ключи доступа — скрываются (3.84)', async () => {
  const w = page('https://m.vk.com/clips');
  w.eval(netTapSrc());
  const sh = w.__vkDumpCore.shrink({ tabs: [{ key: 'for_you' }, { key: 'a1b2c3' }], access_key: 'abc' }, 0);
  assert.deepStrictEqual([sh.tabs[0].key, sh.tabs[1].key, sh.access_key], ['for_you', 'XXX', 'XXX']);
  w.close();
});

test('клипы: вкладки по подписям ключей getHeaderTabs, когда ряда вкладок в вёрстке не узнать (3.92)', async () => {
  const recom = JSON.stringify({ response: { feed: { page_anchor: 'A', items: [{ type: 'short_video_full_legacy', item: { id: 1, owner_id: -5,
    files: { mp4_720: 'https://vkvd1.okcdn.ru/1.mp4' } } }] }, groups: [], profiles: [] } });
  const tabs = JSON.stringify({ response: { tabs: [{ key: 'rec' }, { key: 'shops' }, { key: 'trends' }, { key: 'subscriptions' }] } });
  const w = page('https://m.vk.com/clips', '<!doctype html><body><header><div class="x"><span id="a">Для вас</span></div><div class="x"><span id="b">Подписки</span></div><div class="x"><span>Тренды</span></div></header></body>');
  w.fetch = async (url) => { const t = String(url).includes('getHeaderTabs') ? tabs : recom; return { ok: true, status: 200, headers: { get: () => 'application/json' }, clone() { return { text: async () => t }; }, json: async () => JSON.parse(t) }; };
  w.eval(liteParts().replace('/*CONFIG*/null', JSON.stringify(LITE_CONFIG)));
  await w.fetch('https://web.api.vk.ru/method/shortVideo.getHeaderTabs', { method: 'POST', body: 'access_token=T&v=5.199' });
  await w.fetch('https://web.api.vk.ru/method/shortVideo.getRecom', { method: 'POST', body: 'ref=clips&access_token=T&v=5.199' });
  await wait(60);
  const p = lastOf(w, 'clips');
  assert.deepStrictEqual(p.tabs.map((t) => t.label), ['Для вас', 'Тренды', 'Подписки']);
  const clicked = [];
  w.document.getElementById('b').parentElement.addEventListener('click', () => clicked.push('subs'));
  assert.strictEqual(w.__vkLite.clips('tab', '2'), true);
  assert.deepStrictEqual(clicked, ['subs'], 'нажатие подписи всплыло к обработчику');
  w.close();
});

// ---- 3.77: лента новостей своим экраном

test('лента новостей: записи newsfeed.getFeedExp — как стена (автор из profiles/groups), реклама и не-записи пропущены', async () => {
  const resp = JSON.stringify({ response: { next_from: 'N1', items: [
    { type: 'post', id: 10, owner_id: -5, source_id: -5, from_id: -5, date: 1700000000, text: 'Пост сообщества', likes: { count: 3, user_likes: 1 } },
    { type: 'post', id: 11, owner_id: 7, from_id: 7, date: 1700000100, text: 'Пост друга', marked_as_ads: 1 },
    { type: 'friends_recomm', id: 12, owner_id: 7, text: '' },
    { type: 'post', id: 13, owner_id: 7, from_id: 7, date: 1700000200, text: 'Ещё пост' }],
    profiles: [{ id: 7, first_name: 'Анна', last_name: 'К', photo_100: 'https://sun9-1.userapi.com/a.jpg' }],
    groups: [{ id: 5, name: 'Сообщество', photo_100: 'https://sun9-1.userapi.com/g.jpg' }] } });
  const w = page('https://m.vk.com/feed');
  w.fetch = async () => ({ ok: true, status: 200, headers: { get: () => 'application/json' }, clone() { return { text: async () => resp }; }, json: async () => JSON.parse(resp) });
  w.eval(liteParts().replace('/*CONFIG*/null', JSON.stringify(LITE_CONFIG)));
  await w.fetch('https://web.api.vk.ru/method/newsfeed.getFeedExp', { method: 'POST', body: 'section=news&access_token=T&v=5.199' });
  await wait(400);
  const p = lastOf(w, 'owner');
  assert.ok(p, 'лента пришла');
  assert.deepStrictEqual([p.key, p.ownerId, p.header, p.hasMore], ['/feed', 1000000000000000, null, true]);
  assert.deepStrictEqual(p.posts.map((x) => [x.key, x.fromName, x.liked]), [['-5_10', 'Сообщество', true], ['7_13', 'Анна К', false]]);
  w.close();
});

test('лента новостей: записи newsfeed.get (source_id/post_id) тоже показываются (код ревью 3.81)', async () => {
  const resp = JSON.stringify({ response: { next_from: '', items: [
    { type: 'post', source_id: -5, post_id: 20, date: 1700000000, text: 'Старый формат' }],
    profiles: [], groups: [{ id: 5, name: 'Сообщество', photo_100: 'https://sun9-1.userapi.com/g.jpg' }] } });
  const w = page('https://m.vk.com/feed');
  w.fetch = async () => ({ ok: true, status: 200, headers: { get: () => 'application/json' }, clone() { return { text: async () => resp }; }, json: async () => JSON.parse(resp) });
  w.eval(liteParts().replace('/*CONFIG*/null', JSON.stringify(LITE_CONFIG)));
  await w.fetch('https://web.api.vk.ru/method/newsfeed.get', { method: 'POST', body: 'access_token=T&v=5.199' });
  await wait(400);
  const p = lastOf(w, 'owner');
  assert.ok(p, 'лента пришла');
  assert.deepStrictEqual([p.posts.map((x) => x.key), p.hasMore], [['-5_20'], false]);
  w.close();
});

// ---- 3.78: каталог VK Видео своим экраном

test('VK Видео: каталог главной — ролики с файлами, превью и автором; клипы пропущены', async () => {
  const resp = JSON.stringify({ response: { catalog: { sections: [{ id: 's', blocks: [{ id: 'b', data_type: 'videos', next_from: 'NX' }] }] },
    videos: [
      { id: 1, owner_id: -5, title: 'Фильм', duration: 8402, views: 56827, files: { mp4_720: 'https://vkvd1.okcdn.ru/1.mp4' }, image: [{ url: 'https://iv.okcdn.ru/p1.jpg', width: 640, height: 360 }] },
      { id: 2, owner_id: -5, title: 'Клип', type: 'short_video', files: { mp4_720: 'https://vkvd1.okcdn.ru/2.mp4' } },
      // Настройки рекламы внутри обычного ролика (есть почти у всех) — ролик не рекламный (отчёт 3.80).
      { id: 3, owner_id: -5, title: 'С прероллом', ads: { slot_id: 6531, sections: ['preroll'] }, files: { mp4_720: 'https://vkvd1.okcdn.ru/3.mp4' } },
      { id: 4, owner_id: -5, title: 'Реклама', is_ads: 1, files: { mp4_720: 'https://vkvd1.okcdn.ru/4.mp4' } }],
    groups: [{ id: 5, name: 'Студия', photo_100: 'https://sun9-1.userapi.com/g.jpg' }] } });
  const w = page('https://m.vkvideo.ru/');
  w.fetch = async () => ({ ok: true, status: 200, headers: { get: () => 'application/json' }, clone() { return { text: async () => resp }; }, json: async () => JSON.parse(resp) });
  w.eval(liteParts().replace('/*CONFIG*/null', JSON.stringify(LITE_CONFIG)));
  await w.fetch('https://api.vkvideo.ru/method/catalog.getVideo', { method: 'POST', body: 'url=https%3A%2F%2Fm.vkvideo.ru%2F&access_token=T&v=5.199' });
  await wait(400);
  const p = lastOf(w, 'vkvideo');
  assert.ok(p, 'каталог пришёл');
  assert.deepStrictEqual([p.key, p.hasMore, p.items.map((i) => i.id)], ['/', true, ['-5_1', '-5_3']]);
  assert.deepStrictEqual([p.items[0].id, p.items[0].author, p.items[0].mp4, p.items[0].cover], ['-5_1', 'Студия', 'https://vkvd1.okcdn.ru/1.mp4', 'https://iv.okcdn.ru/p1.jpg']);
  w.close();
});

test('VK Видео: разделы каталога фильтрами — «Для вас» отмечен на главной, чужой поддомен пропущен, подразделы (3.85)', async () => {
  const resp = JSON.stringify({ response: { catalog: { default_section: 'S1', sections: [
    { id: 'S1', title: 'Для вас', url: 'https://m.vkvideo.ru/discover', blocks: [{ id: 'b', data_type: 'videos', next_from: 'N' }] },
    { id: 'S2', title: 'Тренды', url: 'https://m.vkvideo.ru/trends' },
    { id: 'S3', title: 'Спорт', url: 'https://vksport.m.vkvideo.ru/sport' },
    { id: 'S4', title: 'Политика', url: 'https://m.vkvideo.ru/politics', subsections: [
      { id: 'a', title: 'Все', url: 'https://m.vkvideo.ru/politics' }, { id: 'b', title: 'СМИ', url: 'https://m.vkvideo.ru/politics/media' }] }] },
    videos: [{ id: 1, owner_id: -5, title: 'Ролик', files: { mp4_720: 'https://vkvd1.okcdn.ru/1.mp4' } }], groups: [] } });
  const w = page('https://m.vkvideo.ru/');
  w.fetch = async () => ({ ok: true, status: 200, headers: { get: () => 'application/json' }, clone() { return { text: async () => resp }; }, json: async () => JSON.parse(resp) });
  w.eval(liteParts().replace('/*CONFIG*/null', JSON.stringify(LITE_CONFIG)));
  await w.fetch('https://api.vkvideo.ru/method/catalog.getVideo', { method: 'POST', body: 'url=https%3A%2F%2Fm.vkvideo.ru%2F&access_token=T&v=5.199' });
  await wait(400);
  const p = lastOf(w, 'vkvideo');
  assert.deepStrictEqual(p.sections.map((x) => [x.title, x.url, x.selected]), [
    ['Для вас', 'https://m.vkvideo.ru/discover', true], ['Тренды', 'https://m.vkvideo.ru/trends', false], ['Политика', 'https://m.vkvideo.ru/politics', false]]);
  assert.deepStrictEqual(p.sections[2].subs.map((x) => x.title), ['Все', 'СМИ']);
  w.close();
});

test('видео: все качества файла — в ответе окна чтения и по запросу каталога VK Видео (3.86)', async () => {
  const w = readerApiPage();
  w.reply = (url) => (url.includes('video.get') ? JSON.stringify({ response: { items: [{ id: 7, owner_id: 5, title: 'Ролик',
    files: { mp4_240: 'https://vkvd1.okcdn.ru/2.mp4', mp4_720: 'https://vkvd1.okcdn.ru/7.mp4', mp4_1080: 'https://evil.example/1.mp4', hls: 'https://vkvd1.okcdn.ru/v.m3u8' } }] } }) : '{"response":{"items":[]}}');
  await w.fetch('https://web.api.vk.ru/method/messages.getHistory', { method: 'POST', body: 'peer_id=5&access_token=TKN&v=5.199' });
  await wait(30);
  w.__vkReader.video('5_7', '');
  await wait(80);
  const v = w.sentToApp.filter((m) => m.type === 'video').pop();
  assert.deepStrictEqual(v.files, { 240: 'https://vkvd1.okcdn.ru/2.mp4', 720: 'https://vkvd1.okcdn.ru/7.mp4' });
  w.close();

  const resp = JSON.stringify({ response: { catalog: { sections: [] }, groups: [],
    videos: [{ id: 1, owner_id: -5, title: 'Фильм', files: { mp4_360: 'https://vkvd1.okcdn.ru/3.mp4', mp4_1080: 'https://vkvd1.okcdn.ru/10.mp4', hls: 'https://vkvd1.okcdn.ru/v.m3u8' } }] } });
  const c = page('https://m.vkvideo.ru/');
  c.fetch = async () => ({ ok: true, status: 200, headers: { get: () => 'application/json' }, clone() { return { text: async () => resp }; }, json: async () => JSON.parse(resp) });
  c.eval(liteParts().replace('/*CONFIG*/null', JSON.stringify(LITE_CONFIG)));
  await c.fetch('https://api.vkvideo.ru/method/catalog.getVideo', { method: 'POST', body: 'url=x&access_token=T&v=5.199' });
  await wait(400);
  assert.strictEqual(lastOf(c, 'vkvideo').items[0].files, undefined, 'в каталоге качеств нет — сообщение не растёт');
  assert.strictEqual(c.__vkLite.vkvideo('files', '-5_1'), true);
  const f = lastOf(c, 'videofiles');
  assert.deepStrictEqual([f.id, f.files, f.hls], ['-5_1', { 360: 'https://vkvd1.okcdn.ru/3.mp4', 1080: 'https://vkvd1.okcdn.ru/10.mp4' }, 'https://vkvd1.okcdn.ru/v.m3u8']);
  c.close();
});

// ---- 3.79: папки мессенджера

test('окно чтения: папки мессенджера из messages.getDiff и folder_ids бесед — в списке диалогов', async () => {
  const w = readerApiPage();
  w.history.replaceState(null, '', '/mail');
  w.reply = () => JSON.stringify({ responses: [
    { id: '0', body: { response: { folders: { count: 2, items: [
      { id: 1, name: 'Бизнес', type: 'business', included_peer_ids: [-220050914] },
      { id: 3, name: 'Непрочитанные', type: 'unread', included_peer_ids: [] }] } } } },
    { id: '1', body: { response: { conversations: { items: [
      { conversation: { peer: { id: 7, type: 'user' }, last_conversation_message_id: 5, in_read_cmid: 5, folder_ids: [4] },
        last_message: { id: 50, conversation_message_id: 5, date: 1700000000, from_id: 7, peer_id: 7, out: 0, text: 'Привет' } }] } } } }] });
  await w.fetch('https://web.api.vk.ru/method/batch.call', { method: 'POST', body: 'access_token=TKN&v=5.199' });
  await wait(50);
  w.__vkReader.scrape('dialogs');
  await wait(50);
  const d = w.sentToApp.filter((m) => m.type === 'dialogs').pop();
  assert.deepStrictEqual(d.folders.map((f) => [f.id, f.name, f.type, f.peers]), [[1, 'Бизнес', 'business', [-220050914]], [3, 'Непрочитанные', 'unread', []]]);
  assert.deepStrictEqual(d.items.find((i) => i.peerId === '7').folders, [4]);
  w.close();
});

// ---- 3.94: число непрочитанных и дата сообщения

test('окно чтения: число непрочитанных в списке диалогов, без числа — только признак', async () => {
  const w = readerApiPage();
  w.history.replaceState(null, '', '/mail');
  w.reply = () => JSON.stringify({ responses: [
    { id: '1', body: { response: { conversations: { items: [
      { conversation: { peer: { id: 7, type: 'user' }, last_conversation_message_id: 9, in_read_cmid: 6, unread_count: 3 },
        last_message: { id: 90, conversation_message_id: 9, date: 1700000000, from_id: 7, peer_id: 7, out: 0, text: 'Привет' } },
      { conversation: { peer: { id: 8, type: 'user' }, last_conversation_message_id: 4, in_read_cmid: 4, is_marked_unread: true },
        last_message: { id: 40, conversation_message_id: 4, date: 1700000001, from_id: 8, peer_id: 8, out: 0, text: 'Ой' } },
      { conversation: { peer: { id: 9, type: 'user' }, last_conversation_message_id: 2, in_read_cmid: 2 },
        last_message: { id: 20, conversation_message_id: 2, date: 1700000002, from_id: 9, peer_id: 9, out: 0, text: 'Ок' } }] } } } }] });
  await w.fetch('https://web.api.vk.ru/method/batch.call', { method: 'POST', body: 'access_token=TKN&v=5.199' });
  await wait(50);
  w.__vkReader.scrape('dialogs');
  await wait(50);
  const d = w.sentToApp.filter((m) => m.type === 'dialogs').pop();
  const byPeer = (id) => d.items.find((i) => i.peerId === id);
  assert.deepStrictEqual([byPeer('7').unread, byPeer('7').unreadCount], [true, 3]);
  assert.deepStrictEqual([byPeer('8').unread, byPeer('8').unreadCount], [true, 0], 'отмечено вручную — число неизвестно');
  assert.deepStrictEqual([byPeer('9').unread, byPeer('9').unreadCount], [false, 0]);
  w.close();
});

test('переписка: у сообщения из API есть дата числом (разделители дней в приложении)', async () => {
  const cfg = { bridge: 'vkReaderBridge', loginMarker: '#login', dialogs: { row: '.d', link: 'a', title: '.t', snippet: '.s', time: '.tm', unread: '.u', online: '.o' },
    history: { row: '.m', text: '.tx', outMarker: '.out', time: '.tm', title: '.h' }, compose: { input: 'textarea', send: 'button' } };
  const w = page('https://m.vk.com/mail/convo/5', '<!doctype html><body><div id="h"></div></body>');
  const sent = [];
  w.vkReaderBridge = { postMessage: (m) => sent.push(JSON.parse(m)) };
  const api = JSON.stringify({ response: { items: [{ id: 100, conversation_message_id: 10, date: 1700000000, from_id: 5, peer_id: 5, out: 0, text: 'Привет' }] } });
  w.fetch = async () => ({ ok: true, headers: { get: () => 'application/json' }, clone() { return { text: async () => api }; } });
  w.eval(read('page_visibility.js'));
  w.eval(netTapSrc() + '\n' + read('vk_reader.js').replace('/*CONFIG*/null', JSON.stringify(cfg)));
  await w.fetch('https://api.vk.com/method/messages.getHistory');
  await wait(20);
  w.__vkReader.scrape('history');
  await wait(50);
  const history = sent.filter((m) => m.type === 'history').pop();
  const m = history.items.find((i) => i.text === 'Привет');
  assert.strictEqual(m.date, 1700000000);
});

test('окно чтения: своя страница по команде self — один запрос users.get, имя и фото', async () => {
  const w = readerApiPage();
  await w.fetch('https://web.api.vk.ru/method/batch.call', { method: 'POST', body: 'access_token=TKN&v=5.199' });
  w.reply = (url) => (url.includes('users.get')
    ? JSON.stringify({ response: [{ id: 77, first_name: 'Иван', last_name: 'Петров', photo_100: 'https://x/a.jpg' }] })
    : '{}');
  w.__vkReader.self();
  w.__vkReader.self();
  await wait(80);
  const self = w.sentToApp.filter((m) => m.type === 'self');
  assert.deepStrictEqual(self.map((m) => [m.id, m.name, m.photo]), [[77, 'Иван Петров', 'https://x/a.jpg']]);
  assert.strictEqual(w.requests.filter((r) => r.url.includes('users.get')).length, 1, 'повторная команда не шлёт второй запрос');
  w.close();
});

test('окно чтения: список диалогов по команде dialogs, когда страница стоит на переписке (две колонки)', async () => {
  const w = readerApiPage();
  await w.fetch('https://web.api.vk.ru/method/batch.call', { method: 'POST', body: 'access_token=TKN&v=5.199' });
  w.reply = (url) => (url.includes('messages.getConversations')
    ? JSON.stringify({ response: { items: [
      { conversation: { peer: { id: 9, type: 'user' }, last_conversation_message_id: 3, in_read_cmid: 1, unread_count: 2 },
        last_message: { id: 30, conversation_message_id: 3, date: 1700000100, from_id: 9, peer_id: 9, out: 0, text: 'Новое' } }],
      profiles: [{ id: 9, first_name: 'Оля', last_name: 'К' }] } })
    : '{}');
  w.__vkReader.dialogs();
  await wait(200);
  const d = w.sentToApp.filter((m) => m.type === 'dialogs').pop();
  assert.ok(d, 'список прислан со страницы переписки');
  const row = d.items.find((i) => i.peerId === '9');
  assert.deepStrictEqual([row.snippet, row.unreadCount], ['Новое', 2]);
  w.close();
});

test('окно чтения: удалить и изменить сообщение — запросы API и своя память истории', async () => {
  const w = readerApiPage();
  w.reply = (url) => {
    if (url.includes('messages.getHistory')) return JSON.stringify({ response: { items: [
      { id: 100, conversation_message_id: 10, date: 1700000000, from_id: 1, peer_id: 5, out: 1, text: 'Первое' },
      { id: 101, conversation_message_id: 11, date: 1700000060, from_id: 1, peer_id: 5, out: 1, text: 'Втрое' }] } });
    return JSON.stringify({ response: 1 });
  };
  await w.fetch('https://api.vk.com/method/messages.getHistory', { method: 'POST', body: 'access_token=TKN&v=5.199' });
  await wait(50);
  w.__vkReader.deleteMessage(10, false);
  w.__vkReader.editMessage(11, 'Второе');
  await wait(80);
  const del = w.requests.find((r) => r.url.includes('messages.delete'));
  assert.ok(/cmids=10/.test(del.body) && /delete_for_all=0/.test(del.body) && /peer_id=5/.test(del.body));
  const edit = w.requests.find((r) => r.url.includes('messages.edit'));
  assert.ok(/conversation_message_id=11/.test(edit.body) && /keep_forward_messages=1/.test(edit.body));
  assert.deepStrictEqual(w.sentToApp.filter((m) => m.type === 'deleted').map((m) => [m.ok, m.cmid]), [[true, 10]]);
  assert.deepStrictEqual(w.sentToApp.filter((m) => m.type === 'edited').map((m) => [m.ok, m.cmid, m.text]), [[true, 11, 'Второе']]);
  w.__vkReader.scrape('history');
  await wait(50);
  const h = w.sentToApp.filter((m) => m.type === 'history').pop();
  assert.deepStrictEqual(h.items.map((m) => [m.id, m.text]), [['c11', 'Второе']], 'удалённое не вернулось, изменённое — с новым текстом');
});

test('окно чтения: отказ VK внутри ответа на удаление — не «удалено»; «у всех» нельзя — удаляется у себя', async () => {
  const w = readerApiPage();
  const deleteBodies = [];
  w.reply = (url, body) => {
    if (url.includes('messages.getHistory')) return JSON.stringify({ response: { items: [
      { id: 100, conversation_message_id: 10, date: 1700000000, from_id: 1, peer_id: 5, out: 1, text: 'Первое' }] } });
    if (url.includes('messages.delete')) {
      deleteBodies.push(String(body));
      return /delete_for_all=1/.test(String(body))
        ? JSON.stringify({ response: [{ peer_id: 5, conversation_message_id: 10, error: { code: 15, description: 'Access denied' } }] })
        : JSON.stringify({ response: [{ peer_id: 5, conversation_message_id: 10, response: 1 }] });
    }
    return JSON.stringify({ response: 1 });
  };
  await w.fetch('https://api.vk.com/method/messages.getHistory', { method: 'POST', body: 'access_token=TKN&v=5.199' });
  await wait(50);
  w.__vkReader.deleteMessage(10, true);
  await wait(120);
  assert.strictEqual(w.requests.filter((r) => r.url.includes('messages.delete')).length, 2, 'повтор — у себя');
  assert.deepStrictEqual(w.sentToApp.filter((m) => m.type === 'deleted').map((m) => [m.ok, m.cmid]), [[true, 10]]);
});

test('догрузка списка, перезагрузившая страницу, отключается: «есть ещё» больше не обещается', async () => {
  const track = '[1,2,0,"T","A",100,0,0,0,0,0,"my_audios",0,0,""]';
  const html = `<!doctype html><body><div class="audio_item" data-audio='${track}' data-id="2_1"></div><a class="show_more" href="#">Показать ещё</a></body>`;
  const w = page('https://m.vk.com/audio?act=audio_playlist1_2', html);
  // Страница загрузилась сразу после нажатия «Показать ещё» на этом же списке (метка в памяти вкладки).
  w.sessionStorage.setItem('vkweb_more_guard', JSON.stringify({ key: '/audio?act=audio_playlist1_2', at: Date.now() - 500 }));
  w.eval(read('page_visibility.js'));
  w.eval(liteParts().replace('/*CONFIG*/null', JSON.stringify(LITE_CONFIG)));
  let clicked = 0;
  w.document.querySelector('.show_more').addEventListener('click', (e) => { clicked++; e.preventDefault(); });
  w.__vkLite.music('more');
  w.__vkLite.music('sync');
  const t0 = Date.now();
  const msgs = () => w.sent.map((m) => JSON.parse(m));
  while (!msgs().some((m) => m.type === 'music') && Date.now() - t0 < 900) await wait(5);
  const music = msgs().filter((m) => m.type === 'music').pop();
  assert.strictEqual(clicked, 0, 'кнопку больше не нажимаем');
  assert.strictEqual(music.hasMore, false);
});

test('клипы: «нравится» из своего проигрывателя — likes.add от имени страницы, тип video, не принят — clip', async () => {
  const recom = JSON.stringify({ response: { feed: { page_anchor: 'A1', items: [{ type: 'short_video_full', item: { id: 1, owner_id: -5,
    likes: { count: 7, user_likes: 0 }, files: { mp4_720: 'https://vkvd1.okcdn.ru/1.mp4' } } }] }, groups: [], profiles: [] } });
  const w = page('https://m.vk.com/clips');
  w.requests = [];
  w.fetch = async (url, init) => {
    const u = String(url);
    const body = String(init && init.body);
    w.requests.push({ url: u, body });
    let t = recom;
    if (u.includes('likes.add')) t = /type=video/.test(body) ? '{"error":{"error_code":100,"error_msg":"bad type"}}' : '{"response":{"likes":8}}';
    return { ok: true, status: 200, headers: { get: () => 'application/json' }, clone() { return { text: async () => t }; }, json: async () => JSON.parse(t) };
  };
  w.eval(liteParts().replace('/*CONFIG*/null', JSON.stringify(LITE_CONFIG)));
  await w.fetch('https://web.api.vk.ru/method/shortVideo.getRecom', { method: 'POST', body: 'ref=clips&count=10&access_token=T&v=5.199' });
  await wait(50);
  assert.strictEqual(w.__vkLite.clips('like', '-5_1:1'), true);
  await wait(120);
  const tried = w.requests.filter((r) => r.url.includes('likes.add')).map((r) => new URLSearchParams(r.body).get('type'));
  assert.deepStrictEqual(tried, ['video', 'clip']);
  const res = lastOf(w, 'clipLike');
  assert.deepStrictEqual([res.id, res.ok, res.liked, res.likes], ['-5_1', true, true, 8]);
  assert.strictEqual(w.__vkLite.clips('like', 'мусор'), false);
  w.close();
});

test('окно чтения: действия с беседой — без звука и удаление переписки методами API, итог и свежий список', async () => {
  const w = readerApiPage();
  w.history.replaceState(null, '', '/mail');
  w.reply = (url) => {
    if (url.includes('batch.call') || url.includes('getConversations')) return JSON.stringify({ response: { items: [
      { conversation: { peer: { id: 7, type: 'user' }, last_conversation_message_id: 2, in_read_cmid: 2 },
        last_message: { id: 20, conversation_message_id: 2, date: 1700000000, from_id: 7, peer_id: 7, out: 0, text: 'Привет' } }] } });
    return JSON.stringify({ response: 1 });
  };
  await w.fetch('https://web.api.vk.ru/method/messages.getConversations', { method: 'POST', body: 'access_token=TKN&v=5.199' });
  await wait(50);
  w.__vkReader.convAction('mute', '7');
  await wait(80);
  const mute = w.requests.find((r) => r.url.includes('account.setSilenceMode'));
  assert.ok(/peer_id=7/.test(mute.body) && /time=-1/.test(mute.body));
  let d = w.sentToApp.filter((m) => m.type === 'dialogs').pop();
  assert.strictEqual(d.items.find((i) => i.peerId === '7').muted, true);
  w.__vkReader.convAction('remove', '7');
  await wait(80);
  assert.ok(w.requests.some((r) => r.url.includes('messages.deleteConversation')));
  assert.deepStrictEqual(w.sentToApp.filter((m) => m.type === 'convaction').map((m) => [m.action, m.ok]), [['mute', true], ['remove', true]]);
  w.__vkReader.convAction('взлом', '7');
  assert.strictEqual(w.sentToApp.filter((m) => m.type === 'convaction').pop().ok, false);
});

test('ошибки документа: своё — предупреждение в журнал, чужое — подробно, загрузка картинок — не ошибка (4.11)', () => {
  const w = page('https://m.vk.com/feed');
  w.eval(liteParts().replace('/*CONFIG*/null', JSON.stringify(LITE_CONFIG)));
  const logs = () => w.sent.map((m) => JSON.parse(m)).filter((m) => m.type === 'log');
  w.dispatchEvent(new w.ErrorEvent('error', { message: 'item.title is undefined in 123456', filename: '', lineno: 12, colno: 5 }));
  w.dispatchEvent(new w.ErrorEvent('error', { message: 'boom', filename: 'https://st.vk.com/dist/app.js', lineno: 1, colno: 1 }));
  w.dispatchEvent(new w.ErrorEvent('error', { message: 'Script error.', filename: '' }));
  const img = w.document.createElement('img');
  w.document.body.appendChild(img);
  img.dispatchEvent(new w.Event('error'));
  const got = logs();
  const own = got.find((m) => m.message.startsWith('ошибка скрипта приложения'));
  assert.ok(own, 'своя ошибка записана');
  assert.strictEqual(own.level, 'warn');
  assert.ok(!own.message.includes('123456'), 'длинные числа скрыты');
  assert.ok(own.message.includes('@12:5'), 'строка и столбец');
  const site = got.find((m) => m.message.startsWith('ошибка скрипта страницы'));
  assert.strictEqual(site.level, 'debug');
  assert.ok(site.message.endsWith('@st.vk.com'), 'от чужого — только сервер');
  assert.ok(got.some((m) => m.message.startsWith('ошибка чужого скрипта')), '«Script error.» — чужой скрипт');
  assert.strictEqual(got.filter((m) => /ошибка (скрипта|чужого)/.test(m.message)).length, 3, 'ошибка картинки — не исключение');
});

test('ошибки документа копятся до подписки и не больше предела (4.11)', () => {
  const w = page('https://m.vk.com/feed');
  w.eval(netTapSrc());
  for (let i = 0; i < 30; i++) w.dispatchEvent(new w.ErrorEvent('error', { message: 'e' + i, filename: '' }));
  const seen = [];
  w.__vkErrorTap.listen((item) => seen.push(item));
  assert.strictEqual(seen.length, 20);
  assert.ok(seen.every((item) => item.own));
  w.dispatchEvent(new w.ErrorEvent('error', { message: 'later', filename: '' }));
  assert.strictEqual(seen.length, 20, 'после предела — тишина');
});

// ---- 4.12: над клипами не должно быть меню сайта
const clipsRecom = JSON.stringify({ response: { feed: { page_anchor: 'A', items: [{ type: 'short_video_full', item: { id: 1, owner_id: -5,
  files: { mp4_720: 'https://vkvd1.okcdn.ru/1.mp4' } } }] }, groups: [], profiles: [] } });
const clipsFetch = (w, tabsJson) => {
  w.fetch = async (url) => {
    const t = String(url).includes('getHeaderTabs') ? tabsJson : clipsRecom;
    return { ok: true, status: 200, headers: { get: () => 'application/json' }, clone() { return { text: async () => t }; }, json: async () => JSON.parse(t) };
  };
};

test('клипы: меню сайта с role="tab" не становится фильтрами — берутся вкладки раздела (4.12)', async () => {
  const w = page('https://m.vk.com/clips', '<!doctype html><body>' +
    '<nav><div role="tablist"><a role="tab" href="/feed">Главная</a><a role="tab" href="/im">Мессенджер</a>' +
    '<a role="tab" href="/clips" aria-selected="true">Клипы</a><a role="tab" href="/services">Сервисы</a></div></nav>' +
    '<div role="tablist"><button role="tab" aria-selected="true">Для вас</button><button role="tab">Подписки</button></div></body>');
  clipsFetch(w, '{}');
  w.eval(liteParts().replace('/*CONFIG*/null', JSON.stringify(LITE_CONFIG)));
  await w.fetch('https://web.api.vk.ru/method/shortVideo.getRecom', { method: 'POST', body: 'ref=clips&access_token=T&v=5.199' });
  await wait(50);
  assert.deepStrictEqual(lastOf(w, 'clips').tabs, [{ label: 'Для вас', selected: true }, { label: 'Подписки', selected: false }]);
  w.close();
});

test('клипы: только меню сайта (классы с «tab») — фильтров нет вовсе (4.12)', async () => {
  const w = page('https://m.vk.com/clips', '<!doctype html><body><div class="TabBar">' +
    '<a class="TabBar__item" href="/feed">Главная</a><a class="TabBar__item" href="/im">Мессенджер</a>' +
    '<a class="TabBar__item TabBar__item--active" href="/clips">Клипы</a><a class="TabBar__item" href="/menu">Ещё</a></div></body>');
  clipsFetch(w, '{}');
  w.eval(liteParts().replace('/*CONFIG*/null', JSON.stringify(LITE_CONFIG)));
  await w.fetch('https://web.api.vk.ru/method/shortVideo.getRecom', { method: 'POST', body: 'ref=clips&access_token=T&v=5.199' });
  await wait(50);
  assert.deepStrictEqual(lastOf(w, 'clips').tabs, []);
  assert.strictEqual(w.__vkLite.clips('tab', '0'), false, 'нажимать меню сайта нечем');
  w.close();
});

test('клипы: меню сайта рядом — вкладки по подписям ключей getHeaderTabs (4.12)', async () => {
  const tabs = JSON.stringify({ response: { tabs: [{ key: 'rec' }, { key: 'subscriptions' }] } });
  const w = page('https://m.vk.com/clips', '<!doctype html><body><div class="TabBar">' +
    '<a class="TabBar__item" href="/feed">Главная</a><a class="TabBar__item" href="/clips">Клипы</a></div>' +
    '<div><span>Для вас</span></div><div><span>Подписки</span></div></body>');
  clipsFetch(w, tabs);
  w.eval(liteParts().replace('/*CONFIG*/null', JSON.stringify(LITE_CONFIG)));
  await w.fetch('https://web.api.vk.ru/method/shortVideo.getHeaderTabs', { method: 'POST', body: 'access_token=T&v=5.199' });
  await w.fetch('https://web.api.vk.ru/method/shortVideo.getRecom', { method: 'POST', body: 'ref=clips&access_token=T&v=5.199' });
  await wait(60);
  assert.deepStrictEqual(lastOf(w, 'clips').tabs.map((t) => t.label), ['Для вас', 'Подписки']);
  w.close();
});

test('окно чтения: непрочитанные заранее — история без отметки о прочтении, по одной, открытая не присылается (4.13)', async () => {
  const w = readerApiPage();
  w.history.replaceState(null, '', '/mail');
  w.reply = (url, body) => {
    if (url.includes('getHistory')) {
      const peer = +new URLSearchParams(body).get('peer_id');
      return JSON.stringify({ response: { count: 2, items: [
        { id: peer * 10 + 2, conversation_message_id: 2, date: 1700000100, from_id: peer, peer_id: peer, out: 0, text: 'Второе' },
        { id: peer * 10 + 1, conversation_message_id: 1, date: 1700000000, from_id: peer, peer_id: peer, out: 0, text: 'Первое' }] } });
    }
    return JSON.stringify({ response: { items: [] } });
  };
  await w.fetch('https://web.api.vk.ru/method/messages.getConversations', { method: 'POST', body: 'access_token=TKN&v=5.199' });
  await wait(50);
  assert.strictEqual(w.__vkReader.prefetch(['7', '8', 'мусор']), true);
  await wait(1700);
  const asked = w.requests.filter((r) => r.url.includes('getHistory')).map((r) => new URLSearchParams(r.body).get('peer_id'));
  assert.deepStrictEqual(asked, ['7', '8'], 'по одной, мусор отброшен');
  assert.ok(!w.requests.some((r) => r.url.includes('markAsRead')), 'прочитанными не отмечены');
  const hist = w.sentToApp.filter((m) => m.type === 'history');
  assert.deepStrictEqual(hist.map((m) => [m.peerId, m.items.length]), [['7', 2], ['8', 2]]);
});

test('страница сообщества: файлы ролика из записи — с ключом доступа, video.get, иначе video.getByIds; нет файлов — пусто (4.14)', async () => {
  const w = ownerPage('https://m.vk.com/club42');
  let videoGet = '{"response":{"items":[]}}';
  w.reply = (url) => {
    if (url.includes('video.getByIds')) return '{"response":{"items":[{"id":7,"owner_id":-42,"files":{"mp4_480":"https://vkvd1.okcdn.ru/480.mp4","mp4_720":"https://vkvd1.okcdn.ru/720.mp4","hls":"https://vkvd1.okcdn.ru/v.m3u8","external":"http://bad"}}]}}';
    if (url.includes('video.get')) return videoGet;
    return wallResponse;
  };
  await w.fetch('https://web.api.vk.ru/method/wall.get?v=5.199&client_id=6287487', { method: 'POST', body: 'owner_id=-42&access_token=TKN&v=5.199' });
  await wait(300);
  assert.strictEqual(w.__vkLite.owner('video', '-42_7', 'k9'), true);
  await wait(300);
  const get = w.requests.find((r) => r.url.includes('/method/video.get?') || r.url.endsWith('/method/video.get'));
  assert.strictEqual(new URLSearchParams(get.body).get('videos'), '-42_7_k9', 'с ключом доступа');
  let res = w.sent.map((m) => JSON.parse(m)).filter((m) => m.type === 'postvideo').pop();
  assert.deepStrictEqual([res.id, res.files, res.hls, res.reason], ['-42_7', { 480: 'https://vkvd1.okcdn.ru/480.mp4', 720: 'https://vkvd1.okcdn.ru/720.mp4' }, 'https://vkvd1.okcdn.ru/v.m3u8', 'video.getByIds']);
  w.reply = () => '{"error":{"error_code":15,"error_msg":"Access denied"}}';
  w.__vkLite.owner('video', '-42_8', '');
  await wait(300);
  res = w.sent.map((m) => JSON.parse(m)).filter((m) => m.type === 'postvideo').pop();
  assert.deepStrictEqual([res.id, res.files, res.hls], ['-42_8', {}, '']);
  assert.strictEqual(w.__vkLite.owner('video', 'drop', ''), false);
});

test('окно чтения: «непрочитана» после отложенного — messages.markAsUnreadConversation, список с отметкой (4.18)', async () => {
  const w = readerApiPage();
  w.history.replaceState(null, '', '/mail');
  w.reply = (url) => {
    if (url.includes('getConversations')) return JSON.stringify({ response: { items: [
      { conversation: { peer: { id: 7, type: 'user' }, last_conversation_message_id: 2, in_read_cmid: 2 },
        last_message: { id: 20, conversation_message_id: 2, date: 1700000000, from_id: 1, peer_id: 7, out: 1, text: 'Отложенное' } }] } });
    return JSON.stringify({ response: 1 });
  };
  await w.fetch('https://web.api.vk.ru/method/messages.getConversations', { method: 'POST', body: 'access_token=TKN&v=5.199' });
  await wait(50);
  w.__vkReader.convAction('unread', '7');
  await wait(80);
  const call = w.requests.find((r) => r.url.includes('messages.markAsUnreadConversation'));
  assert.ok(call && /peer_id=7/.test(call.body), 'запрос отметки');
  assert.deepStrictEqual(w.sentToApp.filter((m) => m.type === 'convaction').map((m) => [m.action, m.peer, m.ok]), [['unread', '7', true]]);
  const d = w.sentToApp.filter((m) => m.type === 'dialogs').pop();
  assert.strictEqual(d.items.find((i) => i.peerId === '7').unread, true);
});

test('окно чтения: фоновая отправка запросом — messages.send с постоянным random_id, итог «sent» и «delivered» (4.22)', async () => {
  const w = readerApiPage();
  w.history.replaceState(null, '', '/mail/convo/7');
  w.reply = (url) => (url.includes('messages.send') ? '{"response":{"message_id":55,"cmid":3}}' : '{"response":{"items":[]}}');
  await w.fetch('https://web.api.vk.ru/method/messages.getConversations', { method: 'POST', body: 'access_token=TKN&v=5.199' });
  await wait(50);
  w.__vkReader.sendApi('7', 'Отложенное', 123456);
  await wait(120);
  const call = w.requests.find((r) => r.url.includes('messages.send'));
  const body = new URLSearchParams(call.body);
  assert.deepStrictEqual([body.get('peer_id'), body.get('random_id'), body.get('message')], ['7', '123456', 'Отложенное']);
  const sent = w.sentToApp.filter((m) => m.type === 'sent').pop();
  assert.deepStrictEqual([sent.ok, sent.via], [true, 'api']);
  assert.ok(w.sentToApp.some((m) => m.type === 'delivered' && m.text === 'Отложенное'), 'перехват сообщает о доставке');
  w.__vkReader.sendApi('7', 'x', 0);
  await wait(20);
  assert.strictEqual(w.sentToApp.filter((m) => m.type === 'sent').pop().ok, false, 'без номера — не отправляется');
});

test('окно чтения: в беседе у входящих — номер автора (4.31: участники из шапки), у своих и в личной — 0', async () => {
  const w = readerPage('', '/mail/convo/2000000012');
  w.nextResponse = JSON.stringify({ response: {
    items: [
      { id: 1, conversation_message_id: 1, date: 1700000000, from_id: 8, peer_id: 2000000012, out: 0, text: 'Привет' },
      { id: 2, conversation_message_id: 2, date: 1700000060, from_id: -5, peer_id: 2000000012, out: 0, text: 'Бот' },
      { id: 3, conversation_message_id: 3, date: 1700000120, from_id: 1, peer_id: 2000000012, out: 1, text: 'Моё' }],
    profiles: [{ id: 8, first_name: 'Анна', last_name: 'К' }], groups: [{ id: 5, name: 'Сообщество' }] } });
  await w.fetch('https://web.api.vk.ru/method/messages.getHistory?peer_id=2000000012', { method: 'POST', body: 'peer_id=2000000012&access_token=TKN&v=5.199' });
  await wait(30);
  w.__vkReader.scrape('history');
  await wait(60);
  const items = w.sentToApp.filter((x) => x.type === 'history').pop().items;
  assert.deepStrictEqual(items.map((m) => [m.text, m.senderId, m.sender]), [['Привет', 8, 'Анна К'], ['Бот', -5, 'Сообщество'], ['Моё', 0, '']]);
});

test('окно чтения: в беседе у входящих — фото автора (4.53: аватар у реплики), у своих — пусто', async () => {
  const w = readerPage('', '/mail/convo/2000000012');
  w.nextResponse = JSON.stringify({ response: {
    items: [
      { id: 1, conversation_message_id: 1, date: 1700000000, from_id: 8, peer_id: 2000000012, out: 0, text: 'Привет' },
      { id: 2, conversation_message_id: 2, date: 1700000060, from_id: -5, peer_id: 2000000012, out: 0, text: 'Бот' },
      { id: 3, conversation_message_id: 3, date: 1700000120, from_id: 9, peer_id: 2000000012, out: 0, text: 'Без фото' },
      { id: 4, conversation_message_id: 4, date: 1700000180, from_id: 1, peer_id: 2000000012, out: 1, text: 'Моё' }],
    profiles: [
      { id: 8, first_name: 'Анна', last_name: 'К', photo_100: 'https://sun9-1.userapi.com/a.jpg' },
      { id: 9, first_name: 'Борис', last_name: 'Л' }],
    groups: [{ id: 5, name: 'Сообщество', photo_200: 'https://sun9-2.userapi.com/g.jpg' }] } });
  await w.fetch('https://web.api.vk.ru/method/messages.getHistory?peer_id=2000000012', { method: 'POST', body: 'peer_id=2000000012&access_token=TKN&v=5.199' });
  await wait(30);
  w.__vkReader.scrape('history');
  await wait(60);
  const items = w.sentToApp.filter((x) => x.type === 'history').pop().items;
  assert.deepStrictEqual(items.map((m) => m.senderAvatar), [
    'https://sun9-1.userapi.com/a.jpg', 'https://sun9-2.userapi.com/g.jpg', '', '']);
});

test('окно чтения: участники беседы — messages.getConversationMembers, люди и сообщества с именами (4.32)', async () => {
  const w = readerApiPage();
  w.reply = (url) => (url.includes('getConversationMembers')
    ? JSON.stringify({ response: { count: 3, items: [{ member_id: 8 }, { member_id: -5 }, { member_id: 77 }],
      profiles: [{ id: 8, first_name: 'Анна', last_name: 'К', photo_100: 'https://sun9-1.userapi.com/a.jpg' }], groups: [{ id: 5, name: 'Сообщество' }] } })
    : '{"response":{"items":[]}}');
  await w.fetch('https://web.api.vk.ru/method/messages.getHistory', { method: 'POST', body: 'peer_id=5&access_token=TKN&v=5.199' });
  await wait(30);
  w.__vkReader.members('2000000012');
  await wait(80);
  const req = w.requests.filter((r) => r.url.includes('getConversationMembers')).pop();
  assert.ok(req && /peer_id=2000000012/.test(req.body + req.url), 'запрос с номером беседы');
  // Отчёт 4.33: без extended=1 VK не присылает имён — список выходил пустым.
  assert.ok(/extended=1/.test(req.body + req.url), 'запрос с extended=1');
  const m = w.sentToApp.filter((x) => x.type === 'members').pop();
  assert.deepStrictEqual(m, { type: 'members', peerId: '2000000012', ok: true, items: [{ id: 8, name: 'Анна К', photo: 'https://sun9-1.userapi.com/a.jpg' }, { id: -5, name: 'Сообщество', photo: '' }] });
  // Не беседа — запроса нет.
  const before = w.requests.length;
  w.__vkReader.members('5');
  await wait(30);
  assert.strictEqual(w.requests.length, before);
});

test('окно чтения: в участниках беседы нет себя (4.37)', async () => {
  const w = readerApiPage();
  w.vk = { id: 8 };
  w.reply = (url) => (url.includes('getConversationMembers')
    ? JSON.stringify({ response: { count: 2, items: [{ member_id: 8 }, { member_id: 9 }],
      profiles: [{ id: 8, first_name: 'Я', last_name: 'Сам' }, { id: 9, first_name: 'Борис', last_name: 'Б' }] } })
    : '{"response":{"items":[]}}');
  await w.fetch('https://web.api.vk.ru/method/messages.getHistory', { method: 'POST', body: 'peer_id=5&access_token=TKN&v=5.199' });
  await wait(30);
  w.__vkReader.members('2000000012');
  await wait(80);
  const m = w.sentToApp.filter((x) => x.type === 'members').pop();
  assert.deepStrictEqual(m.items.map((p) => p.id), [9]);
});

test('клипы: вкладка «Подписки» — лента из getSubscriptionsShortVideos, а не рекомендаций (4.41)', async () => {
  const clip = (owner, id) => ({ type: 'short_video_full', item: { owner_id: owner, id, files: { mp4_720: 'https://vkvd1.okcdn.ru/' + id + '.mp4' } } });
  const recom = JSON.stringify({ response: { feed: { items: [clip(-1, 1), clip(-1, 2)], page_anchor: 'a' } } });
  const subsClip = clip(-7, 70);
  // Поля ответа «Подписок» (дамп 4.41): first_frames/covers, engagement, duration_seconds.
  Object.assign(subsClip.item, {
    first_frames: [{ url: 'https://iv.okcdn.ru/videoPreview?f=1', width: 720, height: 1280 }],
    covers: [{ url: 'https://iv.okcdn.ru/videoPreview?c=1', width: 720, height: 1034 }],
    engagement: { like_count: 24, comment_count: 3 }, duration_seconds: 9,
  });
  const subs = JSON.stringify({ response: { feed: { items: [subsClip] }, groups: [{ id: 7, name: 'Подписка' }] } });
  const reply = (url) => (url.includes('getSubscriptionsShortVideos') ? subs : recom);
  // На «Подписках» страница шлёт оба запроса: приложению — только подписки.
  const w = ownerPage('https://m.vk.ru/clips/subscriptions');
  w.reply = reply;
  await w.fetch('https://api.vk.ru/method/shortVideo.getRecom', { method: 'POST', body: 'count=10&access_token=TKN&v=5.199' });
  await w.fetch('https://api.vk.ru/method/shortVideo.getSubscriptionsShortVideos', { method: 'POST', body: 'count=10&access_token=TKN&v=5.199' });
  await wait(300);
  const feeds = w.sent.map((m) => JSON.parse(m)).filter((m) => m.type === 'clips');
  const subsFeed = feeds.pop();
  assert.deepStrictEqual(subsFeed.items.map((c) => c.id), ['-7_70']);
  // 4.42: превью, счётчики и длительность — и у роликов «Подписок».
  const c = subsFeed.items[0];
  assert.deepStrictEqual([c.cover, c.likes, c.comments, c.duration, c.author], ['https://iv.okcdn.ru/videoPreview?f=1', 24, 3, 9, 'Подписка']);
  assert.ok(feeds.every((f) => f.items.every((c) => c.id === '-7_70')), 'рекомендации на «Подписках» не уходят');
  // На «Для вас» — как раньше, getRecom; ответ подписок там не берётся.
  const r = ownerPage('https://m.vk.ru/clips');
  r.reply = reply;
  await r.fetch('https://api.vk.ru/method/shortVideo.getSubscriptionsShortVideos', { method: 'POST', body: 'count=10&access_token=TKN&v=5.199' });
  await r.fetch('https://api.vk.ru/method/shortVideo.getRecom', { method: 'POST', body: 'count=10&access_token=TKN&v=5.199' });
  await wait(300);
  const last = r.sent.map((m) => JSON.parse(m)).filter((m) => m.type === 'clips').pop();
  assert.deepStrictEqual(last.items.map((c) => c.id), ['-1_1', '-1_2']);
  // Окна страниц закрываются: их таймеры (проверка роликов страницы) иначе держат прогон тестов.
  w.close();
  r.close();
});

// ---- 4.45: продолжение музыки после конца загруженного списка, «добавить к себе», комментарии

/** Страница с поддельным API: ответ по имени метода (reply), запросы — в w.requests. */
function apiPage(url, body, reply) {
  const w = page(url, `<!doctype html><body>${body}</body>`);
  w.requests = [];
  w.fetch = async (u, init) => {
    const s = String(u);
    w.requests.push({ url: s, body: String(init && init.body) });
    const t = reply(s, String(init && init.body)) || '{"response":1}';
    return { ok: true, status: 200, headers: { get: () => 'application/json' }, clone() { return { text: async () => t }; }, json: async () => JSON.parse(t) };
  };
  w.eval(read('page_visibility.js'));
  w.eval(liteParts().replace('/*CONFIG*/null', JSON.stringify(LITE_CONFIG)));
  return w;
}

/** Страница «узнала» данные входа: её собственный запрос к API. */
const authorize = (w) => w.fetch('https://web.api.vk.ru/method/account.getInfo', { method: 'POST', body: 'access_token=T&v=5.199' });

function playingPage() {
  const w = page('https://m.vk.ru/audio?act=block&block=MINE',
    `<!doctype html><body><div id="list">${[1, 2, 3, 4, 5, 6].map((n) => item(n)).join('')}</div><button class="show_more">Ещё</button><audio id="a"></audio></body>`);
  w.sessionStorage.setItem('vkweb_my_audios_block', 'MINE');
  w.clickedRows = [];
  w.moreClicks = 0;
  w.document.addEventListener('click', (e) => {
    const row = e.target.closest && e.target.closest('.audio_item');
    if (row) w.clickedRows.push(row.getAttribute('data-id'));
    if (e.target.classList && e.target.classList.contains('show_more') && w.moreClicks++ === 0) {
      w.document.getElementById('list').insertAdjacentHTML('beforeend', [7, 8].map((n) => item(n)).join(''));
    }
  }, true);
  w.eval(read('page_visibility.js'));
  w.eval(liteParts().replace('/*CONFIG*/null', JSON.stringify(LITE_CONFIG)));
  const audio = w.document.getElementById('a');
  Object.defineProperty(audio, 'paused', { get: () => false });
  /** Плеер страницы заиграл трек [n]: выделенная строка и событие play. */
  w.nowPlaying = async (n) => {
    w.document.querySelectorAll('.audio_item').forEach((el) => el.classList.remove('audio_item_playing'));
    w.document.querySelector(`[data-id="2_${n}"]`).classList.add('audio_item_playing');
    audio.dispatchEvent(new w.Event('play'));
    await wait(350);
  };
  return w;
}

test('музыка: под конец загруженного списка догружается продолжение, круг плеера на первый трек — включается следующий', async () => {
  const w = playingPage();
  await w.nowPlaying(4);
  await w.nowPlaying(5);
  await w.nowPlaying(6);
  assert.ok(w.moreClicks >= 1, 'продолжение попрошено заранее');
  assert.ok(w.document.querySelector('[data-id="2_7"]'), 'догрузилось');
  await w.nowPlaying(1); // плеер VK сам вернулся к началу
  for (let i = 0; i < 20 && !w.clickedRows.length; i++) await wait(50);
  assert.deepStrictEqual(w.clickedRows, ['2_7'], 'включён трек после последнего сыгранного, а не круг');
  w.close();
});

test('музыка: первый трек, выбранный человеком, — не круг плеера, ничего не переключается', async () => {
  const w = playingPage();
  await w.nowPlaying(5);
  await w.nowPlaying(6);
  w.__vkLite.music('play', '2_1', 'my');
  await w.nowPlaying(1);
  await wait(200);
  assert.deepStrictEqual(w.clickedRows, ['2_1'], 'только нажатие человека');
  w.close();
});

test('музыка: «добавить к себе» — audio.add от имени страницы, итог приложению и отметка в списке', async () => {
  const w = apiPage('https://m.vk.ru/audio?act=audio_playlist-9_2', item(3, 'playlist'), (u) => (u.includes('audio.add') ? '{"response":555}' : ''));
  await authorize(w);
  w.__vkLite.music('sync');
  await wait(60);
  assert.strictEqual(lastOf(w, 'music').items[0].added, false, 'трек чужого плейлиста — ещё не у человека');
  assert.strictEqual(w.__vkLite.music('add', '2_3'), true);
  await wait(150);
  const call = w.requests.find((r) => r.url.includes('/method/audio.add'));
  assert.ok(call, 'вызван audio.add');
  const p = new URLSearchParams(call.body);
  assert.deepStrictEqual([p.get('owner_id'), p.get('audio_id')], ['2', '3']);
  const res = lastOf(w, 'musicAction');
  assert.deepStrictEqual([res.action, res.id, res.ok], ['add', '2_3', true]);
  await wait(300);
  assert.strictEqual(lastOf(w, 'music').items[0].added, true, 'в списке трек отмечен добавленным');
  assert.strictEqual(w.__vkLite.music('add', 'мусор'), false);
  w.close();
});

test('музыка: «добавить к себе» — API отказал, кнопки на странице нет: ошибка приложению', async () => {
  const w = apiPage('https://m.vk.ru/audio?act=audio_playlist-9_2', item(3, 'playlist'),
    (u) => (u.includes('audio.add') ? '{"error":{"error_code":15,"error_msg":"Access denied"}}' : ''));
  await authorize(w);
  w.__vkLite.music('add', '2_3');
  await wait(150);
  const res = lastOf(w, 'musicAction');
  assert.strictEqual(res.ok, false);
  assert.match(res.reason, /15/);
  w.close();
});

test('плейлисты: «добавить к себе» — audio.followPlaylist с ключом доступа из адреса, в сообщении — «добавлен»', async () => {
  const w = apiPage('https://m.vk.ru/audio?act=audio_playlist-9_2&access_hash=abc123', item(3, 'playlist'),
    (u) => (u.includes('audio.followPlaylist') ? '{"response":{"owner_id":7,"playlist_id":50}}' : ''));
  await authorize(w);
  w.__vkLite.music('sync');
  await wait(60);
  assert.deepStrictEqual(lastOf(w, 'music').playlist, { id: '-9_2', own: null, followed: false });
  w.__vkLite.music('follow', '-9_2', '');
  await wait(150);
  const call = w.requests.find((r) => r.url.includes('/method/audio.followPlaylist'));
  const p = new URLSearchParams(call.body);
  assert.deepStrictEqual([p.get('owner_id'), p.get('playlist_id'), p.get('access_key')], ['-9', '2', 'abc123']);
  assert.deepStrictEqual([lastOf(w, 'musicAction').action, lastOf(w, 'musicAction').ok], ['follow', true]);
  await wait(300);
  assert.strictEqual(lastOf(w, 'music').playlist.followed, true);
  w.close();
});

test('комментарии: список к клипу — video.getComments, авторы и упоминания; отправка — video.createComment', async () => {
  const list = { response: { count: 3, items: [
    { id: 11, from_id: 5, date: 1700000000, text: 'Привет, [id7|Анна]!', likes: { count: 2 }, thread: { count: 1 } },
    { id: 12, from_id: -9, date: 1700000100, text: '', attachments: [{ type: 'sticker', sticker: { images: [{ url: 'https://vk.ru/sticker/1-5-128', width: 128 }] } }] },
    { id: 13, from_id: 5, date: 1700000200, text: '', deleted: true },
  ], profiles: [{ id: 5, first_name: 'Иван', last_name: 'Петров', photo_100: 'https://sun9-1.userapi.com/a.jpg' }],
  groups: [{ id: 9, name: 'Клуб', photo_100: 'https://sun9-1.userapi.com/g.jpg' }] } };
  const w = apiPage('https://m.vk.ru/clips', '', (u) => {
    if (u.includes('video.getComments')) return JSON.stringify(list);
    if (u.includes('video.createComment')) return '{"response":14}';
    return '';
  });
  await authorize(w);
  assert.strictEqual(w.__vkLite.comments('get', '-5_1', '0'), true);
  await wait(150);
  const call = w.requests.find((r) => r.url.includes('video.getComments'));
  const p = new URLSearchParams(call.body);
  assert.deepStrictEqual([p.get('owner_id'), p.get('video_id'), p.get('offset'), p.get('extended')], ['-5', '1', '0', '1']);
  const page1 = lastOf(w, 'comments');
  assert.strictEqual(page1.ok, true);
  assert.strictEqual(page1.total, 3);
  assert.strictEqual(page1.next, 3);
  assert.strictEqual(page1.more, false);
  assert.deepStrictEqual(page1.items.map((c) => [c.id, c.author, c.text]), [[11, 'Иван Петров', 'Привет, Анна!'], [12, 'Клуб', '']], 'удалённый пропущен');
  assert.strictEqual(page1.items[1].sticker, 'https://vk.ru/sticker/1-5-128');
  assert.deepStrictEqual([page1.items[0].likes, page1.items[0].replies], [2, 1]);

  assert.strictEqual(w.__vkLite.comments('send', '-5_1', '  Классно  '), true);
  await wait(150);
  const sent = w.requests.find((r) => r.url.includes('video.createComment'));
  assert.strictEqual(new URLSearchParams(sent.body).get('message'), 'Классно');
  assert.strictEqual(lastOf(w, 'commentSent').ok, true);
  assert.strictEqual(w.__vkLite.comments('send', '-5_1', '   '), false, 'пустой не отправляется');
  assert.strictEqual(w.__vkLite.comments('get', 'мусор', '0'), false);
  w.close();
});

test('фото (5.00.12): альбомы — photos.getAlbums, альбом — photos.get, системный альбом и все фото, адреса для сохранения порциями', async () => {
  const sz = (base, list) => list.map(([type, width, height]) => ({ type, width, height, url: `https://sun9-1.vkuserphoto.ru/${base}.jpg?cs=${width}x0` }));
  const albums = { response: { count: 2, items: [
    { id: -15, owner_id: 7, title: 'Сохранённые фото', size: 155, sizes: sz('c', [['s', 75, 75], ['x', 604, 604]]) },
    { id: 33, owner_id: 7, title: 'Лето', size: 4, thumb_src: 'https://sun9-1.vkuserphoto.ru/t.jpg' },
    { id: 'плохой' },
  ] } };
  const photo = (id) => ({ id, owner_id: 7, album_id: -15, date: 1700000000 + id, sizes: sz('p' + id, [['s', 75, 50], ['x', 604, 400], ['y', 807, 538], ['z', 1280, 853]]),
    orig_photo: { url: `https://sun9-1.vkuserphoto.ru/p${id}.jpg?orig`, width: 1600, height: 1066 } });
  const w = apiPage('https://m.vk.ru/albums7', '', (u, body) => {
    const p = new URLSearchParams(body);
    if (u.includes('photos.getAlbums')) return JSON.stringify(albums);
    if (u.includes('photos.getAll')) return JSON.stringify({ response: { count: 1, items: [photo(9)] } });
    if (u.includes('photos.get')) {
      const offset = +p.get('offset');
      const count = +p.get('count');
      const items = [];
      for (let i = offset; i < Math.min(offset + count, 1500); i++) items.push(photo(i + 1));
      return JSON.stringify({ response: { count: 1500, items } });
    }
    return '';
  });
  await authorize(w);

  assert.strictEqual(w.__vkLite.photos('albums', '7', '', '0'), true);
  await wait(150);
  const a = lastOf(w, 'photos');
  assert.deepStrictEqual([a.kind, a.ok, a.total, a.more], ['albums', true, 2, false]);
  assert.deepStrictEqual(a.items.map((x) => [x.id, x.title, x.size]), [[-15, 'Сохранённые фото', 155], [33, 'Лето', 4]], 'испорченный альбом пропущен');
  assert.ok(a.items[0].cover.includes('cs=604'), 'обложка - размер не меньше 400');
  assert.strictEqual(a.items[1].cover, 'https://sun9-1.vkuserphoto.ru/t.jpg');
  const ga = new URLSearchParams(w.requests.find((r) => r.url.includes('photos.getAlbums')).body);
  assert.deepStrictEqual([ga.get('owner_id'), ga.get('need_system'), ga.get('need_covers')], ['7', '1', '1']);

  assert.strictEqual(w.__vkLite.photos('photos', '7', '-15', '60'), true);
  await wait(150);
  const b = lastOf(w, 'photos');
  assert.deepStrictEqual([b.kind, b.album, b.offset, b.next, b.more, b.items.length], ['album', '-15', 60, 120, true, 60]);
  assert.ok(b.items[0].thumb.includes('cs=604'), 'превью около 400 px');
  assert.ok(b.items[0].full.endsWith('?orig'), 'крупный - оригинал');
  assert.ok(b.items[0].fallback.length > 0 && !b.items[0].fallback.includes(b.items[0].full));
  const gp = new URLSearchParams(w.requests.filter((r) => r.url.includes('photos.get?') || r.url.endsWith('photos.get')).pop().body);
  assert.deepStrictEqual([gp.get('album_id'), gp.get('offset'), gp.get('rev')], ['-15', '60', '1']);

  assert.strictEqual(w.__vkLite.photos('photos', '7', '', '0'), true);
  await wait(150);
  assert.deepStrictEqual(lastOf(w, 'photos').items.map((x) => x.id), [9], 'все фото - photos.getAll');

  assert.strictEqual(w.__vkLite.photos('all', '7', '-15', 'job1'), true);
  await wait(400);
  const parts = w.sent.map((m) => JSON.parse(m)).filter((m) => m.type === 'photosAll' && m.job === 'job1');
  assert.deepStrictEqual(parts.map((m) => [m.items.length, m.done]), [[1000, false], [500, true]], 'порциями до конца списка');
  assert.ok(parts[0].items[0].full.endsWith('?orig'));

  assert.strictEqual(w.__vkLite.photos('albums', 'мусор', '', '0'), false);
  assert.strictEqual(w.__vkLite.photos('photos', '7', 'x', '0'), false);
  w.close();
});

test('комментарии (4.46): первые ответы приходят с комментарием, ветка — запросом comment_id, ответ — reply_to_comment', async () => {
  const top = { response: { count: 1, items: [
    { id: 11, from_id: 5, date: 1, text: 'Вопрос', thread: { count: 4, items: [{ id: 21, from_id: 5, date: 2, text: 'Первый ответ', reply_to_comment: 11, parents_stack: [11] }] } },
  ], profiles: [{ id: 5, first_name: 'Иван', last_name: 'П' }], groups: [] } };
  const thread = { response: { count: 4, items: [21, 22, 23, 24].map((id) => ({ id, from_id: 5, date: id, text: 'о' + id, parents_stack: [11] })), profiles: [], groups: [] } };
  const w = apiPage('https://m.vk.ru/clips', '', (u, body) => {
    if (u.includes('video.getComments')) return JSON.stringify(/comment_id=11/.test(body) ? thread : top);
    if (u.includes('video.createComment')) return '{"response":30}';
    return '';
  });
  await authorize(w);
  w.__vkLite.comments('get', '-5_1', '0', '0');
  await wait(150);
  const first = lastOf(w, 'comments');
  assert.strictEqual(new URLSearchParams(w.requests.find((r) => r.url.includes('getComments')).body).get('thread_items_count'), '3');
  assert.deepStrictEqual(first.items[0].thread.map((r) => r.text), ['Первый ответ']);
  assert.strictEqual(first.items[0].replies, 4);

  w.__vkLite.comments('get', '-5_1', '0', '11');
  await wait(150);
  const branch = lastOf(w, 'comments');
  assert.strictEqual(branch.parent, 11);
  assert.deepStrictEqual(branch.items.map((r) => r.id), [21, 22, 23, 24]);

  w.__vkLite.comments('send', '-5_1', 'Ответ', '21');
  await wait(150);
  const sent = w.requests.filter((r) => r.url.includes('createComment')).pop();
  assert.strictEqual(new URLSearchParams(sent.body).get('reply_to_comment'), '21');
  assert.strictEqual(lastOf(w, 'commentSent').replyTo, 21);
  w.close();
});

test('комментарии (4.46): VK не понял запрос ветки (вернул обычный список) — честный отказ, не чужие комментарии', async () => {
  const plain = { response: { count: 2, items: [{ id: 1, from_id: 5, text: 'другой' }, { id: 2, from_id: 5, text: 'ещё' }], profiles: [], groups: [] } };
  const w = apiPage('https://m.vk.ru/clips', '', (u) => (u.includes('video.getComments') ? JSON.stringify(plain) : ''));
  await authorize(w);
  w.__vkLite.comments('get', '-5_1', '0', '11');
  await wait(150);
  const res = lastOf(w, 'comments');
  assert.deepStrictEqual([res.ok, res.parent, res.items.length], [false, 11, 0]);
  w.close();
});

test('плеер (4.46): в состоянии — номер играющего трека и добавлен ли он; у видео номера нет', async () => {
  const w = page('https://m.vk.ru/audio?act=audio_playlist-9_2',
    `<!doctype html><body>${item(3, 'playlist')}<audio id="a"></audio><video id="v"></video></body>`);
  w.eval(read('page_visibility.js'));
  w.eval(liteParts().replace('/*CONFIG*/null', JSON.stringify(LITE_CONFIG)));
  w.__vkLite.music('sync');
  await wait(40);
  w.document.querySelector('[data-id="2_3"]').classList.add('audio_item_playing');
  const audio = w.document.getElementById('a');
  let audioPaused = false;
  Object.defineProperty(audio, 'paused', { get: () => audioPaused });
  audio.dispatchEvent(new w.Event('play'));
  await wait(350);
  const st = lastOf(w, 'state');
  assert.deepStrictEqual([st.trackId, st.trackAdded], ['2_3', false]);
  audioPaused = true;
  const video = w.document.getElementById('v');
  Object.defineProperty(video, 'paused', { get: () => false });
  video.dispatchEvent(new w.Event('play'));
  await wait(350);
  const vs = lastOf(w, 'state');
  assert.deepStrictEqual([vs.trackId, vs.trackAdded], ['', null]);
  w.close();
});

test('окно чтения: голосовое — сервер загрузки, загрузка, docs.save, messages.send с вложением (4.59)', async () => {
  const w = readerApiPage();
  w.reply = (url) => {
    if (url.includes('docs.getMessagesUploadServer')) return '{"response":{"upload_url":"https://pu.vk.com/c123/upload.php?act=add_doc"}}';
    if (url.startsWith('https://pu.vk.com/')) return '{"file":"FILE_TOKEN"}';
    if (url.includes('docs.save')) return '{"response":{"type":"audio_message","audio_message":{"id":77,"owner_id":5,"access_key":"k1"}}}';
    if (url.includes('messages.send')) return '{"response":{"cmid":10,"message_id":9}}';
    return '{"response":{"items":[]}}';
  };
  await w.fetch('https://web.api.vk.ru/method/messages.getHistory', { method: 'POST', body: 'peer_id=5&access_token=TKN&v=5.199' });
  await wait(30);
  w.__vkReader.stage(0, 'voice.ogg', 'audio/ogg', 1, 0, Buffer.from('OggS-voice').toString('base64'));
  w.__vkReader.sendVoice('5', 4242);
  await wait(120);
  const urls = w.requests.map((r) => r.url);
  assert.ok(urls.some((u) => u.includes('docs.getMessagesUploadServer')), 'сервер загрузки');
  assert.ok(urls.some((u) => u.startsWith('https://pu.vk.com/')), 'загрузка файла');
  const send = w.requests.find((r) => r.url.includes('messages.send'));
  const body = new URLSearchParams(send.body);
  assert.deepStrictEqual([body.get('peer_id'), body.get('random_id'), body.get('attachment')], ['5', '4242', 'doc5_77_k1']);
  assert.deepStrictEqual(w.sentToApp.filter((m) => m.type === 'voiceSent').pop(), {
    type: 'voiceSent',
    ok: true,
    reason: '',
    uncertain: false,
    operation: 'legacy-1',
    documentId: '5_77',
  });
});

test('окно чтения: голосовое — чужой адрес загрузки не используется (4.59)', async () => {
  const w = readerApiPage();
  w.reply = (url) => (url.includes('docs.getMessagesUploadServer') ? '{"response":{"upload_url":"https://evil.example/up"}}' : '{"response":{"items":[]}}');
  await w.fetch('https://web.api.vk.ru/method/messages.getHistory', { method: 'POST', body: 'peer_id=5&access_token=TKN&v=5.199' });
  await wait(30);
  w.__vkReader.stage(0, 'voice.ogg', 'audio/ogg', 1, 0, Buffer.from('x').toString('base64'));
  w.__vkReader.sendVoice('5', 1);
  await wait(80);
  assert.ok(!w.requests.some((r) => r.url.startsWith('https://evil.example')), 'файл не ушёл на чужой сервер');
  assert.strictEqual(w.sentToApp.filter((m) => m.type === 'voiceSent').pop().ok, false);
});

test('окно чтения: «печатает» из долгого опроса — новый и классический формат (4.60)', async () => {
  const w = readerApiPage();
  let updates = '[[602,5,0]]';
  w.reply = (url) => {
    if (url.includes('/ruim')) return '{"ts":2,"pts":3,"updates":' + updates + '}';
    return '{"response":{"items":[],"profiles":[{"id":8,"first_name":"Анна","last_name":"К"}]}}';
  };
  await w.fetch('https://web.api.vk.ru/method/messages.getHistory', { method: 'POST', body: 'peer_id=5&access_token=TKN&v=5.199' });
  await wait(30);
  await w.fetch('https://api.vk.com/ruim30804332', { method: 'POST', body: 'act=a_check&key=K&ts=1&wait=25' });
  await wait(30);
  let t = w.sentToApp.filter((m) => m.type === 'typing').pop();
  assert.deepStrictEqual([t.peer, t.users, t.voice], ['5', [5], false], 'личная: печатает собеседник');
  updates = '[[602,2000000012,0]]';
  await w.fetch('https://api.vk.com/ruim30804332', { method: 'POST', body: 'act=a_check&key=K&ts=2&wait=25' });
  await wait(30);
  t = w.sentToApp.filter((m) => m.type === 'typing').pop();
  assert.deepStrictEqual([t.peer, t.users], ['2000000012', []], 'беседа: кто — неизвестно');
  updates = '[[64,2000000012,[8],1,1700000000]]';
  await w.fetch('https://api.vk.com/ruim30804332', { method: 'POST', body: 'act=a_check&key=K&ts=3&wait=25' });
  await wait(30);
  t = w.sentToApp.filter((m) => m.type === 'typing').pop();
  assert.deepStrictEqual([t.peer, t.users, t.names, t.voice], ['2000000012', [8], ['Анна К'], true], 'записывает голосовое');
  w.close();
});

test('окно чтения: 602 сразу после нашей отметки «прочитано» — не «печатает» (отчёт 4.85)', async () => {
  const w = readerApiPage();
  w.reply = (url) => {
    if (url.includes('/ruim')) return '{"ts":2,"pts":3,"updates":[[602,5,0]]}';
    return '{"response":1}';
  };
  await w.fetch('https://web.api.vk.ru/method/messages.getHistory', { method: 'POST', body: 'peer_id=5&access_token=TKN&v=5.199' });
  await wait(30);
  assert.strictEqual(w.__vkReader.markRead(), true);
  await wait(30);
  assert.ok(w.requests.some((r) => r.url.includes('messages.markAsRead')), 'отметка ушла');
  await w.fetch('https://api.vk.com/ruim30804332', { method: 'POST', body: 'act=a_check&key=K&ts=1&wait=25' });
  await wait(30);
  assert.strictEqual(w.sentToApp.filter((m) => m.type === 'typing').length, 0, 'отклик на прочтение не показывается как набор');
  w.close();
});

test('окно чтения: реакции — в истории с числом и своей, картинки — из getReactionsAssets, поставить и снять (4.61)', async () => {
  const w = readerApiPage();
  let sent = [];
  w.reply = (url, body) => {
    if (url.includes('messages.getHistory') || url.includes('getByConversationMessageId')) return JSON.stringify({ response: { items: [
      { id: 1, conversation_message_id: 7, date: 1700000000, from_id: 5, peer_id: 5, out: 0, text: 'Привет',
        reaction_id: 2, reactions: [{ reaction_id: 1, count: 1, user_ids: [5] }, { reaction_id: 2, count: 3, user_ids: [1, 5, 9] }, { reaction_id: 9, count: 0 }] }] } });
    if (url.includes('getReactionsAssets')) return JSON.stringify({ response: { version: 3, assets: [
      { reaction_id: 1, links: { static: 'https://vk.com/images/reaction/1.png', big_animation: 'https://vk.com/a.json' } },
      { reaction_id: 2, links: { static: 'http://evil.example/2.png' } }] } });
    if (url.includes('sendReaction') || url.includes('deleteReaction')) { sent.push([url.split('/method/')[1].split('?')[0], new URLSearchParams(body).get('reaction_id'), new URLSearchParams(body).get('cmid')]); return '{"response":1}'; }
    return '{"response":{"items":[]}}';
  };
  await w.fetch('https://web.api.vk.ru/method/messages.getHistory', { method: 'POST', body: 'peer_id=5&access_token=TKN&v=5.199' });
  await wait(30);
  w.__vkReader.scrape('history');
  await wait(60);
  const m = w.sentToApp.filter((x) => x.type === 'history').pop().items[0];
  assert.deepStrictEqual(m.reactions, [{ id: 2, count: 3, mine: true }, { id: 1, count: 1, mine: false }], 'по убыванию, без нулевых, своя отмечена');
  w.__vkReader.reactionAssets();
  await wait(60);
  assert.deepStrictEqual(w.sentToApp.filter((x) => x.type === 'reactionAssets').pop().items, [{ id: 1, image: 'https://vk.com/images/reaction/1.png' }], 'только https');
  w.__vkReader.react(7, 1);
  await wait(60);
  w.__vkReader.react(7, 0);
  await wait(60);
  assert.deepStrictEqual(sent.map((x) => [x[0], x[1], x[2]]), [['messages.sendReaction', '1', '7'], ['messages.deleteReaction', null, '7']]);
  assert.strictEqual(w.sentToApp.filter((x) => x.type === 'reacted').pop().ok, true);
});

test('окно чтения: queuev4 — события-массивы дают «печатает», вид остальных в журнал без содержимого (4.63)', async () => {
  const w = readerApiPage();
  w.reply = (url) => {
    if (url.includes('/im0332')) return JSON.stringify({ ts: '2', events: [[63, 5, [5], 1, 1700000000], 'Привет мир 12345 секрет', { a: 1, text: 'тайна' }] });
    return '{"response":{"items":[]}}';
  };
  await w.fetch('https://web.api.vk.ru/method/messages.getHistory', { method: 'POST', body: 'peer_id=5&access_token=TKN&v=5.199' });
  await wait(30);
  await w.fetch('https://queuev4.vk.ru/im0332?act=a_check&ts=1', { method: 'GET' });
  await wait(30);
  const t = w.sentToApp.filter((m) => m.type === 'typing').pop();
  assert.deepStrictEqual([t.peer, t.users], ['5', [5]]);
  const logs = w.sentToApp.filter((m) => m.type === 'log' && /событие очереди/.test(m.message)).map((m) => m.message);
  assert.strictEqual(logs.length, 2);
  assert.ok(logs.every((l) => !/Привет|секрет|тайна|12345/.test(l)), 'содержимое не попадает в журнал: ' + logs.join(' | '));
  w.close();
});

test('серверы VK — один список для всех скриптов (NetTap.onHost, 4.65)', () => {
  const w = page('https://m.vk.com/');
  w.eval(netTapSrc());
  const on = (u, k) => w.__vkNetTap.onHost(u, k);
  // Клипы раньше не знали vkvideo.ru, а видео VK Видео — знало: теперь списки одни.
  assert.strictEqual(on('https://vd1.vkvideo.ru/x.mp4', 'video'), true);
  assert.strictEqual(on('https://vkvd12.okcdn.ru/v.mp4', 'video'), true);
  assert.strictEqual(on('https://cs1.vkuservideo.net/v.mp4', 'video'), true);
  assert.strictEqual(on('https://cs1.vkuservideo.net/v.mp4', 'image'), false, 'файлы видео — не картинки');
  assert.strictEqual(on('https://cs1-2.vkuseraudio.net/a.m3u8', 'audio'), true);
  assert.strictEqual(on('https://cs1-2.vkuseraudio.net/a.m3u8', 'image'), false);
  assert.strictEqual(on('https://sun9-1.userapi.com/s.png', 'image'), true);
  assert.strictEqual(on('https://pu.vk.com:443/c/upload.php', 'vk'), true, 'порт в адресе загрузки допустим');
  for (const bad of ['http://vk.com/a', 'https://evilvk.com/a', 'https://vk.com.evil.example/a', 'https://userapi.com.x/a', 'javascript:1', '', null, 5]) {
    assert.strictEqual(on(bad, 'image'), false, String(bad));
  }
  assert.strictEqual(on('https://vk.com/a', 'нет такого'), false);
});

test('https-адрес — одна проверка для всех скриптов (NetTap.https, 4.76)', () => {
  const w = page('https://m.vk.com/');
  w.eval(netTapSrc());
  const h = (v) => w.__vkNetTap.https(v);
  assert.strictEqual(h('https://sun9-1.userapi.com/a.jpg'), 'https://sun9-1.userapi.com/a.jpg');
  for (const bad of ['http://vk.com/a', 'javascript:alert(1)', '//vk.com/a', ' https://vk.com/a', '', null, undefined, 5, {}]) {
    assert.strictEqual(h(bad), '', String(bad));
  }
  // Годится как фильтр массива: лишние аргументы filter не мешают.
  assert.deepStrictEqual(['http://x', 'https://y', 7].filter(w.__vkNetTap.https), ['https://y']);
});
