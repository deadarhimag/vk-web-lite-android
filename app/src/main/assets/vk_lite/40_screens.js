// Часть assets/vk_lite/: Общее для своих экранов приложения: разбор значений, канал сообщений, перехват ответов, догрузка.
// Выполняется внутри общей функции из 00_core.js (см. web/PageScripts.kt), отдельно не запускается.

  // ------------------------------------------------- общее для своих экранов приложения

  // Свои экраны (музыка, друзья, сообщества, страницы людей и сообществ) показывают данные, которые
  // страница VK получает сама: здесь ничего не запрашивается. Ниже — то, что нужно им всем:
  // разбор значений из ответов, отправка сообщений приложению, перехват ответов и догрузка списков.

  function decodeEntities(value) {
    return String(value || '')
      .replace(/&#(\d+);/g, function (m, n) { try { return String.fromCodePoint(+n); } catch (e) { return ''; } })
      .replace(/&#x([0-9a-f]+);/gi, function (m, h) { try { return String.fromCodePoint(parseInt(h, 16)); } catch (e) { return ''; } })
      .replace(/&quot;/g, '"').replace(/&#39;/g, "'").replace(/&lt;/g, '<').replace(/&gt;/g, '>').replace(/&amp;/g, '&')
      .trim();
  }

  /** Строка из ответа (с HTML-кодами VK) или пусто. */
  function str(v) { return typeof v === 'string' ? decodeEntities(v) : ''; }

  /** Число из ответа (в том числе строкой из цифр, со знаком) или 0. */
  function num(v) {
    if (typeof v === 'number') return isFinite(v) ? v : 0;
    return typeof v === 'string' && /^-?\d+$/.test(v) ? +v : 0;
  }

  /** Первый https-адрес среди аргументов (фото разных размеров: берём первый, какой есть). */
  function firstUrl() {
    for (var i = 0; i < arguments.length; i++) {
      if (NetTap.https(arguments[i])) return arguments[i];
    }
    return '';
  }

  /**
   * Общие поля человека — для списка друзей (60_people.js) и шапки страницы (70_owner.js): имя, пол, присутствие в
   * сети, город, можно ли написать. Свои поля каждый экран добавляет сам.
   */
  function personBasics(u) {
    var info = u.online_info && typeof u.online_info === 'object' ? u.online_info : {};
    var last = u.last_seen && typeof u.last_seen === 'object' ? u.last_seen : {};
    return {
      id: num(u.id),
      name: (str(u.first_name) + ' ' + str(u.last_name)).trim(),
      sex: num(u.sex),
      online: info.is_online === true || truthy(u.online),
      mobile: info.is_mobile === true || truthy(u.online_mobile),
      lastSeen: num(info.last_seen) || num(last.time),
      seen: typeof info.status === 'string' ? info.status : '',
      city: u.city && typeof u.city === 'object' ? str(u.city.title) : '',
      canWrite: truthy(u.can_write_private_message),
      deactivated: typeof u.deactivated === 'string' ? u.deactivated : ''
    };
  }

  /** Общие поля сообщества — для списка сообществ и шапки страницы. */
  function groupBasics(g) {
    return {
      id: num(g.id),
      name: str(g.name),
      activity: str(g.activity),
      groupType: typeof g.type === 'string' ? g.type : '',
      verified: truthy(g.verified),
      closed: num(g.is_closed)
    };
  }

  /** Дополняет объект полями другого: сведения об одном человеке приходят разными ответами. */
  function mergeInto(target, src) {
    target = target || {};
    if (!src || typeof src !== 'object') return target;
    for (var k in src) if (Object.prototype.hasOwnProperty.call(src, k) && src[k] !== undefined) target[k] = src[k];
    return target;
  }

  function queryParam(name) {
    var m = new RegExp('[?&]' + name + '=([^&#]*)').exec(location.search);
    if (!m) return '';
    try { return decodeURIComponent(m[1]); } catch (e) { return m[1]; }
  }

  /**
   * Журнал событий скрипта: что получено, что пропущено и почему, где не совпала структура ответа.
   * Записи уходят приложению (его журнал: «Журнал и диагностика» в настройках). Содержимого страниц и данных
   * людей здесь нет — только названия методов, счётчики и тексты ошибок. Одинаковая запись — один раз на
   * страницу, не больше MAX_PER_PAGE на страницу: поток ответов не должен засорять журнал.
   */
  var Diag = (function () {
    var MAX_PER_PAGE = 60;
    var MAX_MESSAGE = 200;
    var path = '';
    var sent = 0;
    var seen = {};

    function post(level, message) {
      var bridge = window[CONFIG.bridge];
      if (!bridge || typeof bridge.postMessage !== 'function') return;
      if (path !== location.pathname) { path = location.pathname; sent = 0; seen = {}; }
      var key = level + message;
      if (seen[key] || sent >= MAX_PER_PAGE) return;
      seen[key] = true;
      sent++;
      bridge.postMessage(JSON.stringify({
        type: 'log',
        level: level,
        message: String(message).slice(0, MAX_MESSAGE),
        page: location.pathname.replace(/\d{3,}/g, '#')
      }));
    }

    function errorText(e) { return e && e.message ? e.message : String(e); }

    // События для разбора «не нажимается/не играет»: в отличие от обычных записей не склеиваются с одинаковыми
    // (повторное нажатие на тот же трек тоже должно остаться в журнале) и считаются отдельно от общего лимита.
    var EVENT_MAX = 300;
    var EVENT_MESSAGE_MAX = 700;
    var eventsSent = 0;
    function event(level, message) {
      var bridge = window[CONFIG.bridge];
      if (!bridge || typeof bridge.postMessage !== 'function' || eventsSent >= EVENT_MAX) return;
      eventsSent++;
      bridge.postMessage(JSON.stringify({
        type: 'log',
        level: level,
        message: String(message).slice(0, EVENT_MESSAGE_MAX),
        page: location.pathname.replace(/\d{3,}/g, '#')
      }));
    }

    return {
      event: event,
      debug: function (message) { post('debug', message); },
      info: function (message) { post('info', message); },
      warn: function (message) { post('warn', message); },
      error: function (message) { post('error', message); },
      errorText: errorText
    };
  })();

  // Необработанные ошибки документа (4.11, shared/net_tap.js): свои — предупреждением, ошибки скриптов VK — только
  // в подробном журнале (их много и они не наши, но помогают понять, почему страница повела себя странно).
  if (window.__vkErrorTap) {
    window.__vkErrorTap.listen(function (item) {
      if (item.own) Diag.warn(item.text); else Diag.debug(item.text);
    });
  }

  /**
   * Канал своего экрана: сообщение собирает build() (объект или null — слать нечего), отправляется
   * не чаще раза в delayMs и не повторяется, если не изменилось.
   */
  function createChannel(delayMs, build) {
    var timer = 0;
    var last = '';

    var queued = false;

    function flush() {
      queued = false;
      var data = build();
      if (!data) return;
      var text = JSON.stringify(data);
      if (text === last) return;
      var bridge = window[CONFIG.bridge];
      if (!bridge || typeof bridge.postMessage !== 'function') return;
      last = text;
      bridge.postMessage(text);
      Diag.debug('отправлено приложению: ' + data.type);
    }

    return {
      schedule: function () {
        clearTimeout(timer);
        // Окно скрыто (открыт свой экран): таймер сработал бы только через секунду. Данные уходят следующей задачей —
        // всё, что пришло одной пачкой, всё равно соберётся в одно сообщение; повторы отбрасывает приложение.
        if (realPageHidden()) {
          if (!queued) { queued = true; soonTask(flush); }
          return;
        }
        timer = setTimeout(flush, delayMs);
      },
      /** Экран приложения открылся заново: прислать текущее, даже если оно не менялось. */
      resend: function () { last = ''; flush(); },
      /** Сменилась страница: следующее сообщение отправится в любом случае. */
      reset: function () { last = ''; }
    };
  }

  /**
   * Перехват ответов страницы для своих экранов — один на всех (fetch, XHR и данные, вшитые в страницу).
   * Ответ странице отдаётся без изменений; читаются только ответы, нужные активному экрану.
   *  - listen(active, onText) — любой ответ текстом (музыка: /audio?act=…);
   *  - listenApi(active, methods, onCall, onChanged) — вызовы API /method/…, в том числе внутри batch.call:
   *    onCall(method, params, body) возвращает true, если данные изменились; тогда вызывается onChanged().
   */
  var ResponseTap = (function () {
    var API_METHOD = /\/method\/([\w.]+)/;
    var textListeners = [];
    var apiListeners = [];

    function isActive(listener) {
      try { return listener.active(); } catch (e) { return false; }
    }

    function anyActive() {
      return textListeners.some(isActive) || apiListeners.some(isActive);
    }

    function parseParams(body) {
      var params = {};
      if (!body) return params;
      var json = window.__vkCompat.parse(body);
      if (json && typeof json === 'object' && !Array.isArray(json)) return json;
      try { new URLSearchParams(body).forEach(function (v, k) { params[k] = v; }); } catch (e) { /* не форма */ }
      return params;
    }

    /** Ответ API → список вызовов {method, params, body}; batch.call раскрывается по id запросов. */
    function callsOf(method, body, data) {
      if (method === 'batch.call') return window.__vkCompat.batch(body, data);
      var canonical = window.__vkCompat.api(data);
      return canonical ? [{ method: method, params: parseParams(body), body: canonical }] : [];
    }

    function deliver(listeners, calls) {
      listeners.forEach(function (l) {
        var changed = false;
        calls.forEach(function (c) {
          if (!l.methods.test(c.method)) return;
          try {
            var accepted = l.onCall(c.method, c.params, c.body);
            changed = accepted || changed;
            Diag.debug((accepted ? 'ответ принят: ' : 'ответ пропущен (не тот список или не те параметры): ') + c.method);
          } catch (e) {
            // Структура ответа изменилась или не та, что ждали: экран не получит эти данные.
            Diag.warn('ошибка разбора ' + c.method + ': ' + Diag.errorText(e));
          }
        });
        // 4.95: слушатель без onChanged (только запоминает) не должен обрывать доставку остальным.
        if (changed && typeof l.onChanged === 'function') {
          try { l.onChanged(); } catch (e) { Diag.warn('ошибка обновления экрана: ' + Diag.errorText(e)); }
        }
      });
    }

    function onApiText(url, body, text) {
      var listeners = apiListeners.filter(isActive);
      if (!listeners.length) return;
      var m = API_METHOD.exec(String(url));
      if (!m) return;
      var method = m[1];
      if (method !== 'batch.call' && !listeners.some(function (l) { return l.methods.test(method); })) return;
      var data = window.__vkCompat.parse(text);
      if (!data) return;
      deliver(listeners, callsOf(method, body, data));
    }

    function onText(url, body, text) {
      if (typeof text !== 'string') return;
      textListeners.forEach(function (l) {
        if (!isActive(l)) return;
        try { l.onText(url, body, text); } catch (e) { Diag.warn('ошибка разбора ответа: ' + Diag.errorText(e)); }
      });
      onApiText(url, body, text);
    }

    /** Данные, вшитые в страницу (window.cur.apiPrefetchCache): первая порция иногда приходит так. */
    function prefetch(entries) {
      if (!Array.isArray(entries)) return;
      var listeners = apiListeners.filter(isActive);
      if (!listeners.length) return;
      var calls = [];
      entries.forEach(function (e) {
        if (e && e.method && e.response) calls.push({ method: String(e.method), params: e.params || {}, body: { response: e.response } });
      });
      deliver(listeners, calls);
    }

    // Перехват fetch/XHR — в общем модуле (assets/shared/net_tap.js): он же нужен окну чтения сообщений. Хуки ставятся здесь,
    // после скрипта рекламы (20_ads.js): экраны получают ответы с уже вырезанной рекламой. Пока ни один экран не открыт,
    // тела ответов не копируются (active), а учёт серверов идёт всегда.
    NetTap.listen({
      samePageApi: true,
      active: anyActive,
      onText: function (url, body, text) { onText(url, body, text); }
    });

    return {
      listen: function (active, handler) { textListeners.push({ active: active, onText: handler }); },
      listenApi: function (active, methods, onCall, onChanged) {
        apiListeners.push({ active: active, methods: methods, onCall: onCall, onChanged: onChanged });
      },
      prefetch: prefetch
    };
  })();

  /** Сообщение приложению вне каналов экранов (ответ на команду): один JSON. */
  function postToApp(data) {
    var bridge = window[CONFIG.bridge];
    if (bridge && typeof bridge.postMessage === 'function') bridge.postMessage(JSON.stringify(data));
  }

  /** Вызов API VK от имени открытой страницы (лайк записи): данные входа берёт общий перехват (shared/net_tap.js). */
  var VkApi = { call: NetTap.api, ready: NetTap.apiReady };

  // Новый сервер или адрес в трафике страницы — в журнал: видно, когда и откуда появился источник данных.
  NetTap.onNewSource(function (s) { Diag.debug('источник данных: ' + s.h + ' ' + s.e + ' (' + s.s + ')'); });
  if (NetTap.onAuthEvent) {
    NetTap.onAuthEvent(function (e) {
      if (e.level === 'warn') Diag.warn(e.message);
      else if (e.level === 'debug') Diag.debug(e.message);
      else Diag.event('info', e.message);
    });
  }

  // 4.0, по дампу с телефона: кнопка «Показать ещё» у части списков (плейлисты, «Мои треки») — обычная ссылка, и нажатие
  // перезагружало страницу целиком (375 КБ) вместо догрузки; список начинался заново, «есть ещё» оставалось, и
  // приложение просило снова — 15 перезагрузок за 12 секунд. Теперь: страница загрузилась сразу после такого нажатия —
  // догрузка этого списка отключается на 10 минут (в памяти вкладки), и приложению сообщается «больше нет».
  var MORE_GUARD_KEY = 'vkweb_more_guard';
  var MORE_BLOCK_KEY = 'vkweb_more_block';
  var MORE_GUARD_MS = 8000;
  var MORE_BLOCK_MS = 10 * 60 * 1000;

  function moreListKey() {
    var act = (location.search.match(/[?&]act=([^&]*)/) || [])[1] || '';
    return location.pathname + (act ? '?act=' + act : '');
  }

  function moreBlocks() {
    try { return JSON.parse(sessionStorage.getItem(MORE_BLOCK_KEY) || '{}') || {}; } catch (e) { return {}; }
  }

  (function detectMoreReload() {
    var raw = null;
    try { raw = sessionStorage.getItem(MORE_GUARD_KEY); sessionStorage.removeItem(MORE_GUARD_KEY); } catch (e) { return; }
    if (!raw) return;
    var guard;
    try { guard = JSON.parse(raw); } catch (e) { return; }
    if (!guard || guard.key !== moreListKey() || Date.now() - guard.at > MORE_GUARD_MS) return;
    var blocks = moreBlocks();
    blocks[guard.key] = Date.now();
    try { sessionStorage.setItem(MORE_BLOCK_KEY, JSON.stringify(blocks)); } catch (e) { /* без памяти вкладки */ }
    Diag.warn('догрузка списка перезагружает страницу — для этого списка отключена на 10 мин');
  })();

  /** Догрузка списков на странице, когда свой экран долистали до конца. */
  var PageList = {
    /** Догрузка этого списка перезагружает страницу (см. выше): не догружать и не обещать «есть ещё». */
    isBlocked: function () {
      var at = moreBlocks()[moreListKey()];
      return !!at && Date.now() - at < MORE_BLOCK_MS;
    },

    // Только явные «показать/загрузить ещё»: просто «Ещё» на страницах VK — это кнопка меню.
    MORE_TEXTS: ['показать ещё', 'показать еще', 'загрузить ещё', 'загрузить еще'],

    /**
     * Догрузить список: нажать кнопку «Показать ещё» (сначала по переданным [selectors] — классы кнопок конкретного
     * списка, затем по подписи), иначе прокрутить страницу до конца. Один путь для музыки, плейлистов, друзей и записей.
     */
    loadMore: function (selectors) {
      if (PageList.isBlocked()) return false;
      for (var s = 0; selectors && s < selectors.length; s++) {
        var button = null;
        try { button = document.querySelector(selectors[s]); } catch (e) { /* селектор устарел в новой вёрстке */ }
        if (button && PageList.clickMore(button)) return true;
      }
      // 4.95 (код ревью): подпись сравнивается целиком. Поиск подстроки во всём тексте ссылки нажимал чужое — ссылку в
      // записи или «Показать ещё» соседнего блока рекомендаций.
      var nodes = document.querySelectorAll('button,[role="button"],a,.show_more');
      for (var i = 0; i < nodes.length; i++) {
        var labels = [nodes[i].getAttribute('aria-label'), nodes[i].getAttribute('title'), nodes[i].textContent || nodes[i].value];
        var matches = labels.some(function (label) { return !!label && PageList.MORE_TEXTS.indexOf(norm(label)) !== -1; });
        if (matches && PageList.clickMore(nodes[i])) return true;
      }
      PageList.scrollToEnd();
      return true;
    },

    /** Нажатие «Показать ещё» с отметкой: если страница после него загрузится заново — это ссылка, а не догрузка. */
    clickMore: function (el) {
      if (!el || !el.isConnected) {
        Diag.debug('кнопка догрузки исчезла до нажатия');
        return false;
      }
      try { sessionStorage.setItem(MORE_GUARD_KEY, JSON.stringify({ key: moreListKey(), at: Date.now() })); } catch (e) { /* без памяти */ }
      setTimeout(function () {
        try {
          var raw = sessionStorage.getItem(MORE_GUARD_KEY);
          if (raw && JSON.parse(raw).key === moreListKey()) sessionStorage.removeItem(MORE_GUARD_KEY);
        } catch (e) { /* без памяти */ }
      }, MORE_GUARD_MS);
      el.click();
      return true;
    },

    /** VK догружает списки при прокрутке страницы до конца. */
    scrollToEnd: function () {
      var el = document.scrollingElement || document.documentElement;
      if (el) el.scrollTop = el.scrollHeight;
      window.scrollTo(0, document.body ? document.body.scrollHeight : 0);
      // Окно скрыто своим экраном: событие прокрутки и «метка конца ленты на экране» — вручную.
      afterScroll(window);
    }
  };
