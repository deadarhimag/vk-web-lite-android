/*
 * Обезличивание ответов для дампа разработчика (общая часть, 3.68): главное окно (assets/vk_lite/80_dump.js) и окно
 * чтения сообщений (assets/vk_reader.js) пишут дамп одинаково. Подключается вместе с net_tap.js (web/NetTapScript.kt).
 *
 * Из ответов убирается всё личное: токены, ключи и подписи — XXX, имена людей, тексты и контакты — «•••(длина)»,
 * у ссылок — параметры. Названия сообществ, плейлистов и треков остаются: по ним видно, что где лежит.
 * Длинные списки — первые MAX_ITEMS элементов.
 */
(function () {
  'use strict';
  if (window.__vkDumpCore) return;
  var SECRET = /^(token|key|hash|secret|sign|signature|session|lrt|auth|cookie|extra|password|access_token)$|(token|key|hash|secret|sign|signature)$/i;
  var HASHLIKE = /^(?=.{10,}$)[0-9a-f]*(\/[0-9a-f]*)+$|^[0-9a-f]{16,}$/i;
  var PRIVATE = /^(first_name|last_name|first_name_\w+|last_name_\w+|maiden_name|nickname|screen_name|domain|bdate|mobile_phone|home_phone|phone|email|skype|site|status|activity|about|home_town|contact_name|text|message|description|caption)$/i;
  // Параметры запросов с личным: текст сообщения, поисковый запрос, имена (код ревью 3.59 — раньше в форме оставались
  // первые 100 знаков текста отправленного сообщения).
  var PRIVATE_PARAM = /^(msg|q|query|name|title|snippet|body|forward|reply_to|comment)$/i;
  // Длинные строки внутри «кортежей» (массивы значений без имён — так бывают устроены строки переписки) — это тексты.
  var TUPLE_TEXT_MAX = 60;
  // 3.68: 20 элементов вместо 3 — по 3 не было видно, как меняются счётчики всех сообществ и как сливается история.
  var MAX_ITEMS = 20;
  var MAX_STR = 300;
  var MAX_DEPTH = 30;
  var MAX_PARAM = 100;


  function cleanUrl(s) { return s.replace(/((?:https?:)?\/\/[^\s"?#]+)[?#][^\s"]*/g, '$1?…'); }

  /**
   * Поле «key» с именем-идентификатором (вкладки клипов «for_you», разделы каталога) — не секрет: оно нужно, чтобы
   * понять устройство страницы (3.84: ключи вкладок shortVideo.getHeaderTabs скрывались). Ключи доступа — с цифрами.
   */
  function plainKey(k, value) { return k === 'key' && typeof value === 'string' && /^[a-z_]{1,40}$/.test(value); }

  function maskPrivate(value) { return value ? '•••(' + value.length + ')' : ''; }

  /** Обезличенная копия ответа: структура и типы те же, личного нет. */
  function shrink(v, depth) {
    if (depth > MAX_DEPTH) return '…';
    if (typeof v === 'string') {
      if (HASHLIKE.test(v)) return 'XXX';
      var s = cleanUrl(v);
      return s.length > MAX_STR ? s.slice(0, MAX_STR) + '…' : s;
    }
    if (Array.isArray(v)) {
      var isTuple = v.length > 0 && v.length <= 40 && typeof v[0] !== 'object';
      var limit = isTuple ? v.length : MAX_ITEMS;
      var out = v.slice(0, limit).map(function (x) {
        if (isTuple && typeof x === 'string' && x.length > TUPLE_TEXT_MAX && !/^https?:/.test(x)) return maskPrivate(x);
        return shrink(x, depth + 1);
      });
      if (v.length > limit) out.push('… ещё ' + (v.length - limit));
      return out;
    }
    if (v && typeof v === 'object') {
      var o = {};
      for (var k in v) {
        if (!Object.prototype.hasOwnProperty.call(v, k)) continue;
        if (SECRET.test(k) && !plainKey(k, v[k])) o[k] = 'XXX';
        else if (PRIVATE.test(k) && typeof v[k] === 'string') o[k] = maskPrivate(v[k]);
        else o[k] = shrink(v[k], depth + 1);
      }
      return o;
    }
    return v;
  }

  /** Параметры запроса: форма или JSON (batch.call), без секретов. */
  function paramsOf(body) {
    if (!body) return null;
    if (body.charAt(0) === '{' || body.charAt(0) === '[') {
      try { return shrink(JSON.parse(body), 0); } catch (e) { /* не JSON */ }
    }
    var o = {};
    try {
      new URLSearchParams(body).forEach(function (v, k) {
        if (SECRET.test(k) && !plainKey(k, v)) o[k] = 'XXX';
        else if (PRIVATE.test(k) || PRIVATE_PARAM.test(k)) o[k] = maskPrivate(String(v));
        else o[k] = String(v).slice(0, MAX_PARAM);
      });
    } catch (e) { return null; }
    return o;
  }

  function methodOf(url) {
    var m = /\/method\/([\w.]+)/.exec(String(url));
    return m ? m[1] : cleanUrl(String(url)).slice(0, 200);
  }

  // Какие параметры запроса — подписи/токены (их надо держать свежими), и постоянны ли они в сеансе.
  // Нужно, чтобы понять, что обновлять при фоновом обновлении страницы: см. web/SessionRefresher.kt.
  var SIGN_PARAM = /token|hash|sign|sig|key|lrt|session|_ad_|rl_|cc_/i;
  var sessionSeen = {};
  function signInfo(body) {
    if (!body || (body.charAt(0) === '{' || body.charAt(0) === '[')) return null;
    var out = null;
    try {
      new URLSearchParams(body).forEach(function (v, k) {
        if (!SIGN_PARAM.test(k) || !v) return;
        out = out || {};
        var prev = sessionSeen[k];
        out[k] = { len: v.length, constant: prev === undefined ? null : prev === v };
        sessionSeen[k] = v;
      });
    } catch (e) { return null; }
    return out;
  }


  /** Запись дампа об ответе [text] на запрос [url] с телом [body]; null — ответ не JSON. */
  function record(url, body, text, source) {
    var first = String(text || '').charAt(0);
    if (first !== '{' && first !== '[') return null;
    var data;
    try { data = JSON.parse(text); } catch (e) { return null; }
    return {
      type: 'dump',
      page: location.host + location.pathname,
      at: new Date().toISOString(),
      method: source === 'ws' ? 'ws ' + cleanUrl(String(url)).slice(0, 120) : methodOf(url),
      params: paramsOf(body),
      sizeKb: Math.round(text.length / 1024),
      signals: signInfo(body),
      response: shrink(data, 0)
    };
  }

  Object.defineProperty(window, '__vkDumpCore', { value: Object.freeze({ record: record, shrink: shrink }), configurable: false });
})();
