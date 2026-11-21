/*
 * Общий перехват сетевых ответов страницы VK: fetch, XMLHttpRequest и (по просьбе) WebSocket.
 *
 * Подключается и главным скриптом (assets/vk_lite/), и окном чтения сообщений (assets/vk_reader.js): раньше у каждого была
 * своя копия перехвата, и «что приходит от каких серверов» нигде не считалось. Теперь перехват один на документ
 * (window.__vkNetTap), а скрипты лишь подписываются на ответы.
 *
 * Хуки ставятся не при загрузке этого файла, а при первом install()/listen(): порядок обёрток fetch/XHR важен.
 * Скрипт рекламы (vk_lite/20_ads.js) подменяет ответы, вырезая рекламу, и перехват экранов должен встать поверх него —
 * тогда экраны получают уже очищенные данные.
 *
 * Учёт: для каждого запроса (даже если никто не подписан) считается сервер и «шаблон» адреса без номеров и ключей:
 * сколько раз, сколько байт, сколько ошибок. Сводку забирает приложение (web/TrafficCollector.kt) вызовом
 * trafficMessage() через evaluateJavascript — в журнале видно, какие серверы отвечают и что приходит. Тела ответов
 * копируются, только если подписчик их хочет.
 */
(function () {
  'use strict';
  if (window.__vkNetTap) return;

  var API_MARKER = '/method/';
  var ACT_PARAM = /[?&]act=([\w.]+)/;
  // mpegurl — плейлисты HLS (4.99.2: музыка страницы играет HLS, адрес плейлиста нужен кэшу музыки).
  var TEXT_TYPE = /json|text|javascript|mpegurl/i;
  var MAX_TEXT_BYTES = 8 * 1024 * 1024;
  var MAX_PLAYLIST_BYTES = 256 * 1024;
  var MAX_SOCKET_CHARS = 2000000;
  var MAX_KEYS = 150;

  var listeners = [];
  /** Подписчики на начало запроса (4.99.2): fn(url, source) — до ответа, пока страница не сменила состояние. */
  var requestListeners = [];
  var responseListeners = [];
  var stats = {};
  var statKeys = 0;
  var newSourceHandler = null;
  var installed = false;
  var socketsHooked = false;

  function isApiUrl(url) { return typeof url === 'string' && url.indexOf(API_MARKER) !== -1; }

  // ---- серверы VK (4.65, код ревью 4.64)
  // Единственный список серверов в скриптах: раньше он был переписан регулярными выражениями в пяти местах, и копии
  // разошлись (у клипов не было vkvideo.ru). Списки — те же, что в config/AppConfig.kt (VK_DOMAINS, IMAGE_CDN_DOMAINS,
  // VIDEO_CDN_DOMAINS, AUDIO_DOMAINS); совпадение проверяет модульный тест HostListsTest.
  var HOSTS = {
    vk: ['vk.com', 'vk.ru', 'vk.me', 'vkuser.net', 'userapi.com', 'vk-cdn.net', 'vkvideo.ru', 'vkuserphoto.ru'],
    imageCdn: ['mycdn.me', 'okcdn.ru'],
    videoCdn: ['vkuservideo.net'],
    audio: ['vkuseraudio.ru', 'vkuseraudio.net', 'vkuseraudio.com', 'okcdn.ru']
  };
  /** Что можно с какого сервера — как AppConfig.isImageHost / isVideoHost / isAudioHost. */
  var HOST_KINDS = {
    vk: ['vk'],
    image: ['vk', 'imageCdn'],
    video: ['vk', 'imageCdn', 'videoCdn'],
    audio: ['vk', 'audio']
  };

  /** Строка-адрес https — она сама; иначе '' (4.76: одна проверка вместо своей в каждом скрипте). */
  function https(value) { return typeof value === 'string' && value.indexOf('https://') === 0 ? value : ''; }

  /** https-адрес на сервере VK нужного вида ('vk' | 'image' | 'video' | 'audio'), с поддоменами. */
  function onHost(url, kind) {
    if (typeof url !== 'string' || url.indexOf('https://') !== 0) return false;
    var host;
    try { host = new URL(url).hostname.toLowerCase().replace(/\.$/, ''); } catch (e) { return false; }
    var groups = HOST_KINDS[kind] || [];
    for (var g = 0; g < groups.length; g++) {
      var list = HOSTS[groups[g]];
      for (var i = 0; i < list.length; i++) {
        var d = list[i];
        if (host === d || (host.length > d.length && host.slice(-d.length - 1) === '.' + d)) return true;
      }
    }
    return false;
  }

  /** Что за запрос: сервер и шаблон адреса (номера длиннее двух цифр — «#», параметры отброшены, кроме act). */
  function describe(url, source) {
    var host = '?';
    var path = '';
    var act = '';
    try {
      var u = new URL(String(url), location.href);
      host = u.hostname;
      path = u.pathname;
      var m = ACT_PARAM.exec(u.search);
      act = m ? m[1] : '';
    } catch (e) { /* не адрес */ }
    var endpoint;
    var at = path.indexOf(API_MARKER);
    if (source === 'ws') endpoint = 'ws';
    else if (at !== -1) endpoint = path.slice(at + 1);
    else endpoint = path.replace(/^\//, '').replace(/-?\d{3,}/g, '#').toLowerCase() || '/';
    if (act && source !== 'ws') endpoint += '?act=' + act;
    return { host: host, endpoint: endpoint.slice(0, 48), source: source };
  }

  function record(info, bytes, failed, status) {
    var key = info.host + ' ' + info.endpoint + ' ' + info.source;
    var s = stats[key];
    if (!s) {
      if (statKeys >= MAX_KEYS) {
        info = { host: '*', endpoint: '*', source: info.source };
        key = '* * ' + info.source;
        s = stats[key];
      }
      if (!s) {
        s = stats[key] = { h: info.host, e: info.endpoint, s: info.source, n: 0, b: 0, err: 0 };
        statKeys++;
        if (newSourceHandler) { try { newSourceHandler(s); } catch (e) { /* журнал не должен ломать страницу */ } }
      }
    }
    s.n++;
    s.b += bytes || 0;
    if (failed) {
      s.err++;
      // 5.00.12: код последней ошибки - по сводке трафика видно, что именно ответил сервер (отчёт 11.11: getMainTab ×12)
      if (status) s.st = status;
    }
  }

  function bodyText(body) {
    if (typeof body === 'string') return body;
    if (typeof URLSearchParams === 'function' && body instanceof URLSearchParams) return String(body);
    // Форма с файлами: текстовые поля (параметры запроса API), файлы пропускаются.
    if (typeof FormData === 'function' && body instanceof FormData && typeof URLSearchParams === 'function') {
      var fields = new URLSearchParams();
      try { body.forEach(function (v, k) { if (typeof v === 'string') fields.append(k, v); }); } catch (e) { return ''; }
      return String(fields);
    }
    return '';
  }

  function notifyRequest(url, source) {
    for (var i = 0; i < requestListeners.length; i++) {
      try { requestListeners[i](url, source); } catch (e) { /* подписчик не ломает запрос страницы */ }
    }
  }

  function isTextResponse(r) {
    try {
      var headers = r.headers;
      var type = (headers && headers.get('content-type')) || '';
      if (type && !TEXT_TYPE.test(type)) return false;
      var length = +((headers && headers.get('content-length')) || 0);
      return !(length > MAX_TEXT_BYTES);
    } catch (e) { return false; }
  }

  // Response metadata only; callers must redact the URL before saving diagnostics.
  function responseInfo(url, source, info) {
    responseListeners.forEach(function (fn) { try { fn(url, source, info); } catch (e) {} });
  }
  function header(headers, name) {
    try { return headers.get(name) || ''; } catch (e) { return ''; }
  }
  function payloadKind(text) {
    text = String(text || '').replace(/^[\s\uFEFF]+/, '');
    if (text.indexOf('#EXTM3U') === 0) return 'hls';
    if (/^(?:<\?xml[^>]*>\s*)?<(?:[A-Za-z_][\w.-]*:)?MPD[\s>]/i.test(text)) return 'dash';
    if (text.slice(4, 8) === 'ftyp') return 'mp4';
    return 'other';
  }
  function decodeBytes(bytes) {
    if (typeof TextDecoder !== 'function') return '';
    return new TextDecoder('utf-8').decode(bytes);
  }
  /** Only explicit manifest subscribers may inspect binary responses; media files are never read to the end. */
  async function readPlaylist(response) {
    if (+header(response.headers, 'content-length') > MAX_PLAYLIST_BYTES) return { kind: 'limit', text: '' };
    var reader, timer;
    try {
      var copy = response.clone();
      if (!copy.body || typeof copy.body.getReader !== 'function') return { kind: 'unreadable', text: '' };
      reader = copy.body.getReader();
      var expired = false;
      timer = setTimeout(function () { expired = true; try { reader.cancel().catch(function () {}); } catch (e) {} }, 5000);
      var parts = [], size = 0, prefix = new Uint8Array(64), prefixSize = 0, kind = 'other';
      while (true) {
        var chunk = await reader.read();
        if (expired) return { kind: 'timeout', text: '' };
        if (chunk.done) break;
        var bytes = chunk.value;
        size += bytes.byteLength;
        var take = Math.min(bytes.byteLength, 64 - prefixSize);
        if (take > 0) { prefix.set(bytes.subarray(0, take), prefixSize); prefixSize += take; }
        kind = payloadKind(decodeBytes(prefix.subarray(0, prefixSize)));
        if (size > MAX_PLAYLIST_BYTES) return { kind: 'limit', text: '' };
        if (kind !== 'hls' && kind !== 'dash' && prefixSize >= 64) return { kind: kind, text: '' };
        parts.push(bytes);
      }
      kind = payloadKind(decodeBytes(prefix.subarray(0, prefixSize)));
      if (kind !== 'hls' && kind !== 'dash') return { kind: kind, text: '' };
      var full = new Uint8Array(size), offset = 0;
      parts.forEach(function (part) { full.set(part, offset); offset += part.byteLength; });
      return { kind: kind, text: decodeBytes(full) };
    } catch (e) { return { kind: 'unreadable', text: '' }; }
    finally {
      if (timer) clearTimeout(timer);
      // Do not await cancellation of a tee branch: it may wait for the page to consume its own branch.
      if (reader) { try { reader.cancel().catch(function () {}); } catch (e) {} }
    }
  }

  /** Подписчики, которым нужен этот ответ (активные и пожелавшие его). */
  function interested(url, source) {
    var out = [];
    for (var i = 0; i < listeners.length; i++) {
      var l = listeners[i];
      if (source === 'ws' && !l.sockets) continue;
      try {
        if (l.active && !l.active()) continue;
        if (l.wants && !l.wants(url, source)) continue;
      } catch (e) { continue; }
      out.push(l);
    }
    return out;
  }

  function pageEpoch() {
    return window.__vkCompat && typeof window.__vkCompat.generation === 'function' ? window.__vkCompat.generation() : location.pathname + location.search;
  }
  function deliver(targets, url, body, text, source, route) {
    for (var i = 0; i < targets.length; i++) {
      if ((targets[i].samePage || targets[i].samePageApi && isApiUrl(url)) && route != null && route !== pageEpoch()) continue;
      try { targets[i].onText(url, body, text, source); } catch (e) { /* ошибку своего разбора подписчик показывает сам */ }
    }
  }

  // ---- вызовы API от имени страницы

  // Данные входа (access_token, версия API, сервер) берутся из запросов самой страницы к /method/… — так вызов идёт тем
  // же путём, что у страницы (лайк записи, догрузка истории переписки, ответ на сообщение). Данные никуда, кроме сервера
  // API самого VK, не уходят.
  //
  // 3.67: данные входа страницы сохраняются в хранилище самого сайта VK (localStorage его адреса — туда, где страница и
  // так держит свои данные) вместе со сроком жизни токена. Новое окно (запуск приложения, окно чтения сообщений) может
  // сразу действовать от имени страницы, не дожидаясь, пока она сама обратится к API: раньше «прочитано» и свои экраны
  // при запуске ждали её первого запроса. Срок — из ответа login.vk.ru/?act=web_token (поле expires) или из самого
  // токена, если в нём он есть (JWT exp); без срока сохранённое годится не дольше [UNKNOWN_EXP_MS].
  var AUTH_FRESH_MS = 5 * 60 * 1000;
  var AUTH_KEY = '__vkweb_auth';
  var EXPIRY_MARGIN_MS = 60 * 1000;
  var UNKNOWN_EXP_MS = 30 * 60 * 1000;
  var VK_API_HOST = /(^|\.)(vk\.com|vk\.ru|vkvideo\.ru)$/i;
  var WEB_TOKEN_URL = /^https:\/\/login\.vk\.(com|ru)\/.*[?&]act=web_token/i;
  var auth = null;

  // События данных входа — в журнал окна (3.68): взят ли сохранённый токен, сколько ему осталось, истёк ли, обновила ли
  // его страница, был ли ответ «устарел». Само значение токена в журнал не попадает. До первого подписчика копятся.
  var authListeners = [];
  var authPending = [];
  function authEvent(level, message) {
    var e = { level: level, message: message };
    if (!authListeners.length) { if (authPending.length < 20) authPending.push(e); return; }
    for (var i = 0; i < authListeners.length; i++) { try { authListeners[i](e); } catch (x) { /* журнал не ломает страницу */ } }
  }
  function minutesLeft(exp) { return exp ? Math.round((exp - Date.now()) / 60000) + ' мин' : 'неизвестен'; }

  /** Срок токенов из ответов web_token: токен → мс. */
  var knownExp = {};

  function formParams(text) {
    var p = {};
    try { new URLSearchParams(text || '').forEach(function (v, k) { p[k] = v; }); } catch (e) { /* не форма */ }
    return p;
  }

  /** Срок жизни, зашитый в сам токен (JWT: exp в секундах); 0 — в токене его нет. */
  function tokenExp(token) {
    try {
      var parts = String(token).split('.');
      if (parts.length !== 3 || parts[1].length < 8) return 0;
      var json = JSON.parse(atob(parts[1].replace(/-/g, '+').replace(/_/g, '/')));
      var exp = Number(json && json.exp);
      return exp > 1e9 && exp < 1e11 ? exp * 1000 : 0;
    } catch (e) { return 0; }
  }

  function expOf(token) { return knownExp[token] || tokenExp(token) || 0; }

  function authValid(a) {
    if (!a || !a.token) return false;
    var now = Date.now();
    if (a.exp) return a.exp - EXPIRY_MARGIN_MS > now;
    return now - (a.savedAt || a.at || 0) < UNKNOWN_EXP_MS;
  }

  function saveAuth() {
    try {
      if (!auth) { window.localStorage.removeItem(AUTH_KEY); return; }
      window.localStorage.setItem(AUTH_KEY, JSON.stringify({ base: auth.base, token: auth.token, v: auth.v, clientId: auth.clientId, exp: auth.exp || 0, savedAt: Date.now() }));
    } catch (e) { /* хранилище недоступно — данные входа живут до конца страницы */ }
  }

  function loadAuth() {
    try {
      var a = JSON.parse(window.localStorage.getItem(AUTH_KEY) || 'null');
      if (!a || typeof a.token !== 'string' || typeof a.base !== 'string') return;
      var u = new URL(a.base);
      if (u.protocol !== 'https:' || !VK_API_HOST.test(u.hostname) || u.pathname.indexOf(API_MARKER) === -1) return;
      if (!authValid(a)) {
        window.localStorage.removeItem(AUTH_KEY);
        authEvent('info', 'данные входа: сохранённый токен истёк — ждём токен страницы');
        return;
      }
      auth = { base: a.base, token: a.token, v: String(a.v || '5.199'), clientId: String(a.clientId || ''), exp: Number(a.exp) || 0, at: 0, savedAt: Number(a.savedAt) || 0, stored: true };
      authEvent('info', 'данные входа: взят сохранённый токен, срок — ' + minutesLeft(auth.exp) +
        (auth.exp ? '' : ' (сохранён ' + Math.round((Date.now() - auth.savedAt) / 60000) + ' мин назад)'));
    } catch (e) { /* нет сохранённого */ }
  }

  function dropAuth() {
    auth = null;
    saveAuth();
  }

  /** Идёт свой запрос api(): его данные входа — уже известные, повторно их не «захватываем» (код ревью 3.59). */
  var ownCall = false;

  function captureAuth(url, body) {
    if (ownCall) return;
    if (!isApiUrl(String(url))) return;
    var u;
    try { u = new URL(String(url), location.href); } catch (e) { return; }
    var at = u.pathname.indexOf(API_MARKER);
    if (at === -1) return;
    // Только сервер API самого VK: данные входа не должны уйти туда, куда страница их не отправляла бы.
    if (u.protocol !== 'https:' || !VK_API_HOST.test(u.hostname)) return;
    var q = formParams(u.search.slice(1));
    var b = formParams(body);
    var token = b.access_token || q.access_token;
    if (!token) return;
    if (auth && auth.token === token && !auth.stored && Date.now() - auth.at < AUTH_FRESH_MS) return;
    var changed = !auth || auth.token !== token;
    auth = {
      base: u.origin + u.pathname.slice(0, at) + API_MARKER,
      token: token,
      v: b.v || q.v || '5.199',
      clientId: q.client_id || b.client_id || '',
      exp: expOf(token),
      at: Date.now()
    };
    saveAuth();
    if (changed) authEvent('debug', 'данные входа: токен из запроса страницы, срок — ' + minutesLeft(auth.exp));
  }

  /** Ответ login.vk.ru/?act=web_token: новый токен страницы и его срок. */
  function onWebToken(text) {
    var data;
    try { data = JSON.parse(text); } catch (e) { return; }
    var d = data && data.data;
    if (!d || typeof d.access_token !== 'string') return;
    var exp = Number(d.expires) > 1e9 ? Number(d.expires) * 1000 : 0;
    if (exp) knownExp[d.access_token] = exp;
    if (auth && (auth.token === d.access_token || auth.stored)) {
      // Тот же сервер и версия, новый токен: страница сейчас же начнёт слать его сама.
      auth.token = d.access_token;
      auth.exp = exp || expOf(d.access_token);
      auth.at = Date.now();
      auth.stored = false;
      saveAuth();
    }
    authEvent('debug', 'данные входа: страница обновила токен, срок — ' + minutesLeft(exp));
  }

  listeners.push({
    wants: function (url) { return WEB_TOKEN_URL.test(String(url)); },
    onText: function (url, body, text) { onWebToken(text); }
  });

  loadAuth();

  var AUTH_WAIT_STEP_MS = 500;
  var AUTH_WAIT_STEPS = 40;

  /** done(ошибка, ответ): ошибка — причина для журнала (без данных входа), ответ — поле response. */
  function api(method, params, done, retried, options) {
    if (options && options.signal && options.signal.aborted) { done('запрос отменён'); return; }
    if (auth && !authValid(auth)) dropAuth();
    if (!auth) { done('страница ещё не обращалась к API — данных входа нет'); return; }
    var used = auth;
    var form = new URLSearchParams();
    Object.keys(params).forEach(function (k) { form.append(k, String(params[k])); });
    form.append('access_token', used.token);
    form.append('v', used.v);
    var url = used.base + method + (used.clientId ? '?client_id=' + encodeURIComponent(used.clientId) : '');
    // Через window.fetch (с перехватом): ответ получат и подписчики — например, окно чтения разберёт историю как обычно.
    var request;
    ownCall = true;
    try { request = window.fetch(url, { method: 'POST', body: form, credentials: 'include', signal: options && options.signal }); } finally { ownCall = false; }
    request.then(function (r) { return r.json(); }).then(function (data) {
      if (data && data.response !== undefined) { done(null, data.response); return; }
      var err = data && data.error;
      if (err && err.error_code === 5 && !retried) {
        // Токен устарел (сохранённый или страница уже сменила его): ждём свежего от самой страницы и повторяем один раз.
        authEvent('warn', 'данные входа: токен ' + (used.stored ? 'сохранённый ' : '') + 'устарел (ошибка 5) на ' + method + ' — ждём свежий от страницы');
        if (auth === used) dropAuth();
        waitAuth(function (ok) {
          authEvent(ok ? 'info' : 'warn', ok ? 'данные входа: свежий токен получен — повтор ' + method : 'данные входа: свежего токена страница не дала — ' + method + ' не выполнен');
          if (ok) api(method, params, done, true, options);
          else done('ошибка API 5: данные входа устарели, свежих страница не дала');
        });
        return;
      }
      if (err && err.error_code === 5 && auth === used) dropAuth();
      done('ошибка API ' + (err ? err.error_code + ': ' + String(err.error_msg || '').slice(0, 80) : 'без ответа'));
    }, function (e) { done('сеть: ' + (e && e.message ? e.message : String(e))); });
  }

  function waitAuth(cb) {
    var steps = 0;
    (function tick() {
      if (auth && authValid(auth)) { cb(true); return; }
      if (++steps > AUTH_WAIT_STEPS) { cb(false); return; }
      setTimeout(tick, AUTH_WAIT_STEP_MS);
    })();
  }

  function installFetch() {
    var original = window.fetch;
    if (typeof original !== 'function') return;
    window.fetch = function (input, init) {
      try { captureAuth(typeof input === 'string' ? input : (input && input.url) || '', bodyText(init && init.body)); } catch (e) { /* без данных входа */ }
      var url = typeof input === 'string' ? input : (input && input.url) || '';
      var route = pageEpoch();
      notifyRequest(url, 'fetch');
      var result = original.apply(this, arguments);
      var info = describe(url, 'fetch');
      result.then(function (r) {
        var textual = r && isTextResponse(r);
        var targets = (r && r.ok) ? interested(url, 'fetch') : [];
        responseInfo(url, 'fetch', { status: r ? r.status : 0, mime: header(r && r.headers, 'content-type'),
          size: +header(r && r.headers, 'content-length') || 0, mode: textual ? 'text' : 'binary' });
        if (!textual) targets = targets.filter(function (target) { return target.playlist === true; });
        var failed = !r || r.status >= 400;
        if (!targets.length) {
          var length = 0;
          try { length = +((r && r.headers && r.headers.get('content-length')) || 0); } catch (e) { /* без длины */ }
          record(info, length, failed, r ? r.status : 0);
          return;
        }
        var body = bodyText(init && init.body);
        if (!textual) {
          readPlaylist(r).then(function (value) {
            record(info, value.text.length, false);
            responseInfo(url, 'fetch', { payload: value.kind });
            if (value.text) deliver(targets, url, body, value.text, 'fetch', route);
          });
          return;
        }
        r.clone().text().then(function (text) {
          record(info, text.length, false);
          responseInfo(url, 'fetch', { payload: payloadKind(text.slice(0, 256)) });
          deliver(targets, url, body, text, 'fetch', route);
        }, function () { record(info, 0, true); });
      }, function () { record(info, 0, true); responseInfo(url, 'fetch', { status: 0, mode: 'rejected' }); });
      return result; // ответ странице отдаём как есть
    };
  }

  function installXhr() {
    var proto = XMLHttpRequest.prototype;
    var originalOpen = proto.open;
    var originalSend = proto.send;
    proto.open = function (method, url) {
      this.__vkTapUrl = String(url);
      return originalOpen.apply(this, arguments);
    };
    proto.send = function (body) {
      // Объект XHR могут использовать повторно (open → send → open → send): адрес и тело берутся на момент ответа.
      this.__vkTapBody = bodyText(body);
      this.__vkTapRoute = pageEpoch();
      try { captureAuth(this.__vkTapUrl || '', this.__vkTapBody); } catch (e) { /* без данных входа */ }
      notifyRequest(this.__vkTapUrl || '', 'xhr');
      if (!this.__vkTapped) {
        this.__vkTapped = true;
        this.addEventListener('load', function () {
          try {
            var url = this.__vkTapUrl || '';
            var route = this.__vkTapRoute;
            var info = describe(url, 'xhr');
            var rt = this.responseType;
            var text = (rt === '' || rt === 'text') ? this.responseText : (rt === 'json' ? null : '');
            var failed = this.status >= 400;
            var targets = failed ? [] : interested(url, 'xhr');
            responseInfo(url, 'xhr', { status: this.status, mime: this.getResponseHeader('content-type') || '',
              size: +this.getResponseHeader('content-length') || 0, mode: rt || 'text' });
            if (text === null && targets.length) text = JSON.stringify(this.response);
            // XHR players can request even an HLS manifest as ArrayBuffer/Blob.
            var binaryTargets = targets.filter(function (target) { return target.playlist === true; });
            if (binaryTargets.length && rt === 'arraybuffer' && this.response) {
              var binary = new Uint8Array(this.response);
              var prefix = decodeBytes(binary.subarray(0, 256));
              var kind = payloadKind(prefix);
              responseInfo(url, 'xhr', { payload: binary.byteLength > MAX_PLAYLIST_BYTES ? 'limit' : kind });
              if (binary.byteLength <= MAX_PLAYLIST_BYTES && (kind === 'hls' || kind === 'dash')) {
                deliver(binaryTargets, url, this.__vkTapBody || '', decodeBytes(binary), 'xhr', route);
              }
            } else if (binaryTargets.length && rt === 'blob' && this.response && this.response.size <= MAX_PLAYLIST_BYTES &&
                typeof this.response.text === 'function') {
              var requestBody = this.__vkTapBody || '';
              this.response.text().then(function (value) {
                var kind = payloadKind(value.slice(0, 256));
                responseInfo(url, 'xhr', { payload: kind });
                if (kind === 'hls' || kind === 'dash') deliver(binaryTargets, url, requestBody, value, 'xhr', route);
              }, function () {});
            }
            record(info, text ? text.length : 0, failed, this.status);
            if (targets.length && typeof text === 'string' && text) {
              responseInfo(url, 'xhr', { payload: payloadKind(text.slice(0, 256)) });
              deliver(targets, url, this.__vkTapBody || '', text, 'xhr', route);
            }
          } catch (e) { /* чужой ответ не ломает страницу */ }
        });
        this.addEventListener('error', function () { record(describe(this.__vkTapUrl || '', 'xhr'), 0, true); });
      }
      return originalSend.apply(this, arguments);
    };
  }

  /** WebSocket: обновления (новые сообщения) приходят и так. Ставится только если кто-то подписан на сокеты. */
  function hookSockets() {
    if (socketsHooked) return;
    socketsHooked = true;
    try {
      var Native = window.WebSocket;
      if (typeof Native !== 'function') return;
      var Hooked = function (url, protocols) {
        var socket = protocols === undefined ? new Native(url) : new Native(url, protocols);
        var info = describe(url, 'ws');
        socket.addEventListener('message', function (event) {
          var data = event.data;
          if (typeof data !== 'string' || data.length > MAX_SOCKET_CHARS) { record(info, 0, false); return; }
          record(info, data.length, false);
          var targets = interested(String(url), 'ws');
          if (targets.length) deliver(targets, String(url), '', data, 'ws');
        });
        socket.addEventListener('error', function () { record(info, 0, true); });
        return socket;
      };
      Hooked.prototype = Native.prototype;
      ['CONNECTING', 'OPEN', 'CLOSING', 'CLOSED'].forEach(function (k) { Hooked[k] = Native[k]; });
      window.WebSocket = Hooked;
    } catch (e) { /* без WebSocket-обновлений */ }
  }

  function install() {
    if (installed) return;
    installed = true;
    try { installFetch(); } catch (e) { /* без перехвата приложение покажет страницы VK */ }
    try { installXhr(); } catch (e) { /* то же */ }
  }

  Object.defineProperty(window, '__vkNetTap', {
    value: Object.freeze({
      isApiUrl: isApiUrl,
      /** https-адрес на сервере VK вида kind ('vk' | 'image' | 'video' | 'audio') (4.65). */
      onHost: onHost,
      /** Строка-адрес https — она сама; иначе '' (4.76). */
      https: https,
      bodyText: bodyText,
      install: install,
      /** Вызов API от имени страницы: api(method, params, done(ошибка, ответ)). */
      api: api,
      apiReady: function () { return !!auth && authValid(auth); },
      /** События данных входа для журнала окна: fn({level, message}); накопленные до подписки — сразу. */
      onAuthEvent: function (fn) {
        authListeners.push(fn);
        var pending = authPending;
        authPending = [];
        pending.forEach(function (e) { try { fn(e); } catch (x) { /* журнал не ломает страницу */ } });
      },
      /**
       * Подписка на ответы. spec: {active(): bool, wants(url, source): bool, sockets: bool, onText(url, body, text, source)},
       * source — 'fetch' | 'xhr' | 'ws'. Подписчик получает только текстовые ответы.
       */
      listen: function (spec) {
        listeners.push(spec);
        install();
        if (spec.sockets) hookSockets();
      },
      /** Начало запроса страницы (fetch, XHR): fn(url, source) — адрес и способ, до ответа (4.99.2). */
      onRequest: function (fn) {
        requestListeners.push(fn);
        install();
      },
      /** Response status/type and bounded payload classification, without recording its body. */
      onResponse: function (fn) { responseListeners.push(fn); install(); },
      /** Сводка по серверам и адресам: [{h: сервер, e: шаблон адреса, s: способ, n: запросов, b: байт, err: ошибок}]. */
      stats: function () { return Object.keys(stats).map(function (k) { return stats[k]; }); },
      /** Сводка, после чего счётчики обнуляются (новый период). */
      drain: function () {
        var out = Object.keys(stats).map(function (k) { return stats[k]; });
        stats = {};
        statKeys = 0;
        return out;
      },
      /**
       * Готовое сообщение приложению со сводкой (type = "traffic", до 15 самых частых адресов) и обнуление счётчиков;
       * null — за период ничего не было. Отправляет его мост того окна, которое просили (главное или чтение сообщений).
       */
      trafficMessage: function () {
        var items = Object.keys(stats).map(function (k) { return stats[k]; })
          .sort(function (a, b) { return b.n - a.n; }).slice(0, 15);
        stats = {};
        statKeys = 0;
        if (!items.length) return null;
        return { type: 'traffic', page: location.pathname.replace(/\d{3,}/g, '#'), items: items };
      },
      /** Вызывается один раз для каждого нового сервера/адреса: в журнале видно, когда появился новый источник данных. */
      onNewSource: function (fn) { newSourceHandler = typeof fn === 'function' ? fn : null; }
    }),
    enumerable: false,
    configurable: false,
    writable: false
  });
})();

/*
 * Необработанные ошибки скриптов документа (4.11). Раньше исключение в своём коде вне try (таймер, наблюдатель за
 * разметкой, обработчик нажатия) пропадало бесследно: свой экран просто не обновлялся, а в журнале было пусто.
 * Теперь первые MAX ошибок документа описываются и отдаются подписчикам (vk_lite/40_screens.js → журнал главного окна,
 * vk_reader.js → журнал окна чтения). Подключается вместе с перехватом, поэтому видит ошибки с самого начала;
 * до подписки они копятся.
 *
 * Своё отличается от чужого по адресу файла: свои скрипты встроены без адреса, скрипты VK — с адресом (в описание
 * попадает только сервер). «Script error.» без адреса — ошибка чужого скрипта, подробности которой браузер скрывает.
 * Числа от трёх цифр заменяются на «#», текст обрезается: в журнал не попадают id и содержимое.
 */
(function () {
  'use strict';
  if (window.__vkErrorTap) return;

  var MAX = 20;
  var MAX_TEXT = 160;
  var count = 0;
  var buffer = [];
  var listeners = [];

  function clean(text) {
    return String(text == null ? '' : text).replace(/\d{3,}/g, '#').replace(/\s+/g, ' ').trim().slice(0, MAX_TEXT);
  }

  function hostOf(file) {
    try { return new URL(file, location.href).host || '?'; } catch (e) { return '?'; }
  }

  /** {own, text}: own — ошибка своего (встроенного) скрипта. */
  function describe(message, file, line, column) {
    var msg = clean(message);
    if (!file && /^script error\.?$/i.test(msg)) return { own: false, text: 'ошибка чужого скрипта (подробности скрыты браузером)' };
    if (!file) return { own: true, text: 'ошибка скрипта приложения: ' + msg + ' @' + (line || '?') + ':' + (column || '?') };
    return { own: false, text: 'ошибка скрипта страницы: ' + msg + ' @' + hostOf(file) };
  }

  function emit(item) {
    if (count >= MAX) return;
    count++;
    if (!listeners.length) { buffer.push(item); return; }
    listeners.forEach(function (fn) { try { fn(item); } catch (e) { /* подписчик не должен ломать остальных */ } });
  }

  window.addEventListener('error', function (e) {
    // Ошибки загрузки картинок и скриптов приходят сюда же (захват), но у них цель — элемент: это не исключения.
    if (!e || (e.target && e.target !== window)) return;
    emit(describe(e.message, e.filename, e.lineno, e.colno));
  }, true);

  window.addEventListener('unhandledrejection', function (e) {
    var reason = e && e.reason;
    var text = reason && reason.message ? reason.message : reason;
    emit({ own: false, text: 'необработанный отказ обещания: ' + clean(text) });
  });

  Object.defineProperty(window, '__vkErrorTap', {
    value: Object.freeze({
      /** Подписаться; накопленное до подписки приходит сразу. */
      listen: function (fn) {
        if (typeof fn !== 'function') return;
        listeners.push(fn);
        var pending = buffer;
        buffer = [];
        pending.forEach(function (item) { try { fn(item); } catch (e) { /* то же */ } });
      },
      describe: describe
    }),
    enumerable: false,
    configurable: false,
    writable: false
  });
})();
