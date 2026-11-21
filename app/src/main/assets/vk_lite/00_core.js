/*
 * VK Lite: скрипт, который приложение встраивает в страницы VK.
 * Собирается из частей assets/vk_lite/ по порядку имён (web/PageScripts.kt) в одну функцию:
 *  00_core    — стили, отслеживание плеера, Media Session, отчёт приложению, команды плеера;
 *  10_promo   — окна «Откройте приложение» и баннеры;
 *  20_ads     — реклама и клипы (в данных и в вёрстке);
 *  30_shell   — отступ под мессенджером;
 *  40_screens — общее для своих экранов: ResponseTap, createChannel, PageList;
 *  50_music, 60_people, 70_owner — данные своих экранов;
 *  80_dump    — сбор обезличенных ответов для разработки (настройка, по умолчанию выключен);
 *  90_api     — window.__vkLite и завершение функции.
 * Части — не самостоятельные скрипты: открывающая скобка функции здесь, закрывающая — в 90_api.
 *
 * Конфигурацию (JSON) приложение подставляет на место плейсхолдера ниже.
 * Скрипт идемпотентен: повторный запуск только восстанавливает стили.
 */
(function () {
  'use strict';
  // Общий перехват ответов (assets/shared/net_tap.js): подключается раньше этого скрипта (web/PageScripts.kt).
  var NetTap = window.__vkNetTap;

  if (window.top !== window) return;
  if (window.__vkLite) {
    if (typeof window.__vkLite.ensureCss === 'function') window.__vkLite.ensureCss();
    return;
  }

  var CONFIG = /*CONFIG*/null;
  if (!CONFIG) return;

  // ---------------------------------------------------------------- общие помощники частей (4.66)
  // Раньше каждая часть держала свою копию (клипы, VK Видео, комментарии, страница владельца).

  /**
   * Самая крупная https-картинка из [{url, width, height}], у которой сторона dim ('width' или 'height') не больше max;
   * нет такой — ''.
   */
  function bestImage(list, max, dim) {
    var best = '', bestSide = 0;
    (Array.isArray(list) ? list : []).forEach(function (im) {
      if (!im || typeof im.url !== 'string' || im.url.indexOf('https://') !== 0) return;
      var side = im[dim] || 0;
      if (side <= max && side > bestSide) { best = im.url; bestSide = side; }
    });
    return best;
  }

  /** Ключ открытой страницы — её путь без косых черт в конце («/» для главной). */
  function pathKey() { return location.pathname.replace(/\/+$/, '') || '/'; }

  // ---------------------------------------------------------------- стили

  var STYLE_ID = 'vk-lite-css';
  var cssText = CONFIG.css || '';

  function ensureCss() {
    var existing = document.getElementById(STYLE_ID);
    if (existing) {
      if (existing.textContent !== cssText) existing.textContent = cssText;
      return;
    }
    var parent = document.head || document.documentElement;
    if (!parent) {
      document.addEventListener('DOMContentLoaded', ensureCss, { once: true });
      return;
    }
    var style = document.createElement('style');
    style.id = STYLE_ID;
    style.textContent = cssText;
    parent.appendChild(style);
  }

  // ------------------------------------------- видимость окна и смена адреса

  // Для страницы VK окно всегда «видимо» (assets/page_visibility.js). Своя работа скрипта сверяется с настоящей
  // видимостью: пока окна не видно (экран выключен, приложение в фоне), тратить на неё батарею незачем.
  var visibilityApi = window.__vkWebVisibility || null;

  function realPageHidden() {
    try { return visibilityApi ? visibilityApi.hidden() : false; } catch (e) { return false; }
  }

  /** Задача «как можно скорее»: в скрытом окне таймеры Chromium выравнивает до секунды, а это — без задержки. */
  function soonTask(fn) {
    if (visibilityApi && typeof visibilityApi.soon === 'function') visibilityApi.soon(fn); else setTimeout(fn, 0);
  }

  /** Страница прокручена приложением: в скрытом окне событие прокрутки и пересечения элементов — вручную. */
  function afterScroll(target) {
    if (visibilityApi && typeof visibilityApi.afterScroll === 'function') { visibilityApi.afterScroll(target); return; }
    try { (target || window).dispatchEvent(new Event('scroll')); } catch (e) { /* старый движок */ }
  }

  /** fn(hidden) при смене настоящей видимости окна. */
  function onRealVisibility(fn) {
    try { if (visibilityApi) visibilityApi.onChange(fn); } catch (e) { /* без уведомлений */ }
  }

  // VK — одностраничное приложение: адрес меняется через history без загрузки страницы.
  var routeListeners = [];
  var routeTimer = 0;
  var lastRoute = location.pathname + location.search;

  function notifyRoute() {
    routeTimer = 0;
    var route = location.pathname + location.search;
    if (route === lastRoute) return;
    lastRoute = route;
    routeListeners.forEach(function (fn) { try { fn(); } catch (e) { /* чужая ошибка не мешает остальным */ } });
  }

  function scheduleRouteCheck() {
    if (!routeTimer) routeTimer = setTimeout(notifyRoute, 0);
  }

  /** fn() после смены адреса страницы (pushState, replaceState, «Назад»). */
  function onRouteChange(fn) { routeListeners.push(fn); }

  try {
    ['pushState', 'replaceState'].forEach(function (name) {
      var original = history[name];
      if (typeof original !== 'function') return;
      history[name] = function () {
        var result = original.apply(this, arguments);
        scheduleRouteCheck();
        return result;
      };
    });
    window.addEventListener('popstate', scheduleRouteCheck);
  } catch (e) { /* без отслеживания адреса: проверки идут при загрузке страницы */ }

  // ---------------------------------------------------- отслеживание медиа

  // seeked и durationchange — чтобы своя полоса проигрывания знала позицию после перемотки и длину трека.
  var MEDIA_EVENTS = ['play', 'playing', 'pause', 'ended', 'loadedmetadata', 'emptied', 'seeked', 'durationchange'];
  var tracked = new Set();
  var current = null;
  // Последний трек доиграл до конца и ничего не запустилось следом. Отличается от паузы: плеер можно закрыть.
  // Флаг, а не el.ended: многие плееры после конца сбрасывают src, и ended снова становится false.
  var endedSeen = false;

  function isActive(el) {
    return !el.paused && !el.ended;
  }

  function onMediaEvent(event) {
    if (event.type === 'play' || event.type === 'playing') {
      current = event.target;
      endedSeen = false;
    } else if (event.type === 'ended' && event.target === current) {
      endedSeen = true;
      musicTrackEnded();
    }
    scheduleReport();
  }

  function track(el) {
    if (!el || tracked.has(el)) return;
    tracked.add(el);
    MEDIA_EVENTS.forEach(function (type) { el.addEventListener(type, onMediaEvent); });
  }

  function untrack(el) {
    MEDIA_EVENTS.forEach(function (type) { el.removeEventListener(type, onMediaEvent); });
    tracked.delete(el);
  }

  /** Забываем отключённые от страницы и остановленные элементы, чтобы не держать их в памяти. */
  function prune() {
    tracked.forEach(function (el) {
      if (el !== current && !el.isConnected && !isActive(el)) untrack(el);
    });
  }

  function currentMedia() {
    if (current) return current;
    var first = tracked.values().next();
    if (!first.done) return first.value;
    return document.querySelector('audio,video');
  }

  // Плееры часто создают new Audio() без добавления в DOM: такие элементы
  // не видны через события документа, поэтому перехватываем play().
  // Части объединяются в одну функцию: var в другом файле мог перезаписать nativePlay.
  // Ссылка на предыдущий обработчик принадлежит только этому замыканию.
  (function (playBeforeTracking) {
    HTMLMediaElement.prototype.play = function () {
      track(this);
      current = this;
      endedSeen = false;
      return playBeforeTracking.apply(this, arguments);
    };
  })(HTMLMediaElement.prototype.play);

  // Элементы в DOM, запущенные без вызова play() (autoplay, встроенные кнопки).
  document.addEventListener('play', function (event) {
    track(event.target);
    current = event.target;
    endedSeen = false;
    scheduleReport();
  }, true);

  // ------------------------------------------------------- Media Session

  var actionHandlers = {};
  var session = navigator.mediaSession;

  if (session && typeof session.setActionHandler === 'function') {
    var nativeSetActionHandler = session.setActionHandler.bind(session);
    session.setActionHandler = function (action, handler) {
      actionHandlers[action] = handler;
      try {
        nativeSetActionHandler(action, handler);
      } catch (err) {
        // действие не поддерживается этой версией WebView
      }
    };
  }

  // Сообщаем о смене трека, когда страница обновляет metadata.
  var SessionProto = window.MediaSession && window.MediaSession.prototype;
  var metadataProp = SessionProto && Object.getOwnPropertyDescriptor(SessionProto, 'metadata');
  if (metadataProp && metadataProp.set && metadataProp.configurable) {
    Object.defineProperty(SessionProto, 'metadata', {
      configurable: true,
      enumerable: metadataProp.enumerable,
      get: metadataProp.get,
      set: function (value) {
        metadataProp.set.call(this, value);
        scheduleReport();
      }
    });
  }

  // ------------------------------------------------ отчёт приложению

  var REPORT_DELAY_MS = 250;
  var reportTimer = 0;
  var lastSent = '';

  function scheduleReport() {
    clearTimeout(reportTimer);
    reportTimer = setTimeout(report, REPORT_DELAY_MS);
  }

  function artworkOf(meta) {
    var list = meta && meta.artwork;
    if (!list || typeof list.length !== 'number') return '';
    var best = '';
    var bestSize = -1;
    for (var i = 0; i < list.length; i++) {
      var a = list[i];
      var src = a && typeof a.src === 'string' ? a.src : '';
      if (src.indexOf('https://') !== 0) continue;
      var size = parseInt(String(a.sizes || '0').split('x')[0], 10) || 0;
      if (size >= bestSize) { best = src; bestSize = size; }
    }
    return best;
  }

  /** Адрес звука играющего трека [id]: https-адрес плеера страницы, иначе известный поток трека ('' — нет). */
  function audioUrlOf(media, id) {
    var src = media ? String(media.currentSrc || media.src || '') : '';
    if (src.indexOf('https://') === 0 && NetTap.onHost(src, 'audio')) return src;
    try { return id ? musicStreamFor(id) : ''; } catch (e) { return ''; }
  }

  function report() {
    prune();
    var playing = false;
    tracked.forEach(function (el) { if (isActive(el)) playing = true; });

    // 4.45: заиграл другой трек — не конец ли загруженного списка (50_music.js: продолжение вместо круга).
    if (playing) { try { onPlaybackTick(); } catch (e) { /* продолжение — не повод терять отчёт */ } }
    var meta = navigator.mediaSession && navigator.mediaSession.metadata;
    var nowTrack = playing ? musicNowPlaying() : null;
    var nowFacts = { id: '', added: null };
    try { nowFacts = playingTrackFacts(currentMedia()); } catch (e) { /* без номера трека */ }
    // Позиция — на момент события (запуск, пауза, перемотка): между событиями приложение досчитывает её само,
    // поэтому сообщения не идут каждую секунду.
    var media = currentMedia();
    var position = media && isFinite(media.currentTime) ? Math.round(media.currentTime * 10) / 10 : 0;
    var duration = media && isFinite(media.duration) ? Math.round(media.duration * 10) / 10 : 0;
    var message = JSON.stringify({
      type: 'state',
      playing: playing,
      ended: !playing && endedSeen,
      // Мобильная VK не всегда сообщает название (mediaSession пустой): тогда — из списка треков страницы
      // (assets/vk_lite/50_music.js), иначе в уведомлении и на экране блокировки было пусто.
      title: (meta && meta.title) || (nowTrack && nowTrack.title) || (playing ? document.title : '') || '',
      artist: (meta && meta.artist) || (nowTrack && nowTrack.artist) || '',
      position: position,
      duration: duration,
      // 4.0: обложка — для экрана блокировки и шторки (самая большая из тех, что страница дала плееру).
      cover: artworkOf(meta),
      // 4.46: какой трек играет и добавлен ли он к себе — кнопка «добавить» в плеере приложения (50_music.js).
      trackId: nowFacts.id,
      trackAdded: nowFacts.added,
      // Поток нужен приложению для локального кэша; принимаются только доверенные аудиохосты (SafeUrls.audio). Играет
      // страница через MSE — у элемента адрес blob:, тогда — поток трека из ответа API (50_music.js, 4.95).
      audioUrl: audioUrlOf(media, nowFacts.id)
    });
    if (message === lastSent) return;

    var bridge = window[CONFIG.bridge];
    if (!bridge || typeof bridge.postMessage !== 'function') return;
    lastSent = message;
    bridge.postMessage(message);
  }

  // ------------------------------------------------------ команды

  var SESSION_ACTIONS = { play: 'play', pause: 'pause', next: 'nexttrack', prev: 'previoustrack' };

  function clickFirst(selector) {
    if (!selector) return false;
    var el = document.querySelector(selector);
    if (!el) return false;
    el.click();
    return true;
  }

  function safePlay(el) {
    var result = el.play();
    if (result && typeof result.catch === 'function') result.catch(function () {});
  }

  /** Запасной путь, если страница не зарегистрировала обработчики Media Session. */
  function fallback(name, media) {
    switch (name) {
      case 'play':
        if (media) safePlay(media);
        break;
      case 'pause':
        if (media) media.pause();
        break;
      case 'next':
        if (!clickFirst(CONFIG.nextSelector) && media && isFinite(media.duration)) {
          media.currentTime = media.duration;
        }
        break;
      case 'prev':
        if (!clickFirst(CONFIG.prevSelector) && media) media.currentTime = 0;
        break;
    }
  }
