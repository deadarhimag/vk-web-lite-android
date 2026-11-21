// Часть assets/vk_lite/: Видео и клипы на страницах VK — журнал состояния плеера, причина отказа и один запуск, если не началось.
// Выполняется внутри общей функции из 00_core.js (см. web/PageScripts.kt), отдельно не запускается.
// Использует Diag, ResponseTap и onRouteChange (00_core.js, 40_screens.js).
//
// Видео и клип из переписки открываются страницей VK (m.vk.ru → m.vkvideo.ru). Если плеер не начинает играть или страница сама
// уходит на каталог, в отчёте должно быть видно почему: что ответил VK о видео (ошибка или ограничение), есть ли плеер, что
// показано поверх него, что нажимали и куда ушла страница. Тексты, имена и адреса в журнал не попадают: только имена полей,
// числа, признаки и названия классов вёрстки.

  var VIDEO_PAGE = /^\/(video|clip)-?\d+_\d+/;
  var videoDiag = { path: '', playedByPage: false, assisted: false, events: 0, clicks: 0, started: 0 };
  var VIDEO_EVENTS = ['loadstart', 'loadedmetadata', 'canplay', 'play', 'playing', 'pause', 'waiting', 'stalled', 'error', 'ended', 'seeking', 'seeked'];

  function videoKind() { return /^\/clip/.test(location.pathname) ? 'клип' : 'видео'; }

  function maskPath(path) { return String(path || '').replace(/\d{3,}/g, '#'); }

  function mediaHost(v) {
    var src = v.currentSrc || v.src || '';
    if (!src) return 'нет';
    if (src.indexOf('blob:') === 0) return 'blob';
    try { return new URL(src).hostname; } catch (e) { return 'непонятный'; }
  }

  /** Сколько секунд можно перематывать: конец последнего доступного отрезка. */
  function seekableEnd(v) {
    try {
      var r = v.seekable;
      return r && r.length ? Math.round(r.end(r.length - 1)) : 0;
    } catch (e) { return -1; }
  }

  function area(v) {
    var r = v.getBoundingClientRect();
    return Math.round(r.width) * Math.round(r.height);
  }

  /** Коротко об одном элементе video: пауза, звук, готовность, позиция/длина, перемотка до, ошибка, источник, размер. */
  function describeVideo(v) {
    var r = v.getBoundingClientRect();
    return '{' + (v.paused ? 'пауза' : 'идёт') + (v.muted ? ',без звука' : '') + ',готов=' + v.readyState + ',сеть=' + v.networkState +
      ',поз=' + Math.round(v.currentTime || 0) + '/' + (isFinite(v.duration) ? Math.round(v.duration) : '?') +
      ',перемотка до=' + seekableEnd(v) + ',ошибка=' + (v.error ? v.error.code : 0) + ',источник=' + mediaHost(v) +
      ',controls=' + v.controls + ',' + Math.round(r.width) + 'x' + Math.round(r.height) + '}';
  }

  /** Главный плеер страницы: самый большой видимый элемент с источником; превью (без звука, без источника) не считаются. */
  function mainVideo() {
    var list = document.querySelectorAll('video');
    var best = null;
    for (var i = 0; i < list.length; i++) {
      var v = list[i];
      if (!(v.currentSrc || v.src)) continue;
      if (!best || area(v) > area(best)) best = v;
    }
    return best;
  }

  /** Классы элемента для журнала (названия вёрстки, без текста): tag.класс1.класс2. */
  function brief(el) {
    if (!el || !el.tagName) return '-';
    var cls = typeof el.className === 'string' ? el.className.trim().split(/\s+/).slice(0, 3).join('.') : '';
    return el.tagName.toLowerCase() + (cls ? '.' + cls.slice(0, 70) : '');
  }

  /** Что поверх плеера: предложения приложения (по тексту — только считаются) и кнопки запуска; классы первых элементов. */
  function pageHints() {
    var nodes = document.querySelectorAll('button,[role="button"],a');
    var play = 0;
    var offers = [];
    for (var i = 0; i < nodes.length && i < 500; i++) {
      var b = nodes[i];
      var label = ((b.getAttribute('aria-label') || '') + ' ' + (b.getAttribute('title') || '')).toLowerCase();
      if (/воспроиз|смотр|play/.test(label)) play++;
      var text = (b.textContent || '').slice(0, 60).toLowerCase();
      if (/приложени|скачать|установить|открыть в/.test(text) && offers.length < 3) {
        offers.push(brief(b) + (b.closest('[data-vklite-hidden]') ? '(скрыто приложением)' : '') + (b.offsetHeight ? '' : '(не видно)'));
      }
    }
    return 'кнопок запуска=' + play + ', предложения приложения=[' + offers.join('; ') + ']';
  }

  function videoFacts() {
    var list = document.querySelectorAll('video');
    var main = mainVideo();
    var previews = 0;
    for (var i = 0; i < list.length; i++) if (!(list[i].currentSrc || list[i].src)) previews++;
    return pageHints() + ', video=' + list.length + ' (превью без источника=' + previews + ')' +
      (main ? ', главный ' + describeVideo(main) : ', главного плеера нет') +
      ', страница=' + document.readyState + ', окно=' + window.innerWidth + 'x' + window.innerHeight;
  }

  function logVideoEvent(e) {
    if (videoDiag.events >= 40) return;
    videoDiag.events++;
    if (e.type === 'playing' && e.target !== undefined && (e.target.currentSrc || e.target.src)) videoDiag.playedByPage = true;
    Diag.event(e.type === 'error' ? 'warn' : 'info', videoKind() + ': ' + e.type + ' ' + describeVideo(e.target));
  }

  /** Страница сама не запустила главный плеер: пробуем один раз. Превью и ленту рядом не трогаем. */
  function assistPlay() {
    if (videoDiag.assisted || videoDiag.playedByPage) return;
    // Клипы играет своё окно приложения (отчёт 3.80: «запуск отклонён: AbortError» — помощь страничному ролику спорила
    // с заглушением).
    if (typeof Clips !== 'undefined' && Clips && Clips.isSilenced && Clips.isSilenced()) return;
    var v = mainVideo();
    if (!v) { Diag.event('info', videoKind() + ': запускать нечего — главного плеера нет'); return; }
    if (!v.paused || v.ended) return;
    videoDiag.assisted = true;
    Diag.event('info', videoKind() + ': страница не запустила воспроизведение, пробуем один раз ' + describeVideo(v));
    try {
      var result = v.play();
      if (result && typeof result.then === 'function') {
        result.then(
          function () { Diag.event('info', videoKind() + ': запуск удался'); },
          function (err) { Diag.event('warn', videoKind() + ': запуск отклонён: ' + (err && err.name ? err.name : 'неизвестно')); }
        );
      }
    } catch (e) { Diag.event('warn', videoKind() + ': запуск не удался: ' + Diag.errorText(e)); }
  }

  // ---- ответы VK о видео: ошибка или ограничение — частая причина «страница вместо плеера»

  var DETAIL_KEY = /restrict|private|can_|is_|live|adult|access|platform|type|state|status|region|blocked|deleted|paid|donut/i;
  var SKIP_KEY = /(^|_)id$|date|count|views|likes|comments|reposts|duration|width|height|size/i;

  function scalarFacts(o) {
    var names = [];
    var facts = [];
    Object.keys(o).forEach(function (k) {
      names.push(k);
      var v = o[k];
      if (SKIP_KEY.test(k) || !DETAIL_KEY.test(k)) return;
      if (typeof v === 'boolean' || (typeof v === 'number' && isFinite(v))) facts.push(k + '=' + v);
      else if (typeof v === 'string' && /^[a-z_]{1,24}$/.test(v)) facts.push(k + '=' + v);
      else if (v && typeof v === 'object' && !Array.isArray(v)) facts.push(k + '={' + Object.keys(v).slice(0, 6).join(',') + '}');
    });
    return { names: names, facts: facts };
  }

  function onVideoApi(method, params, body) {
    if (!body || typeof body !== 'object') return false;
    if (body.error) {
      var e = body.error;
      Diag.event('warn', videoKind() + ': ответ ' + method + ' с ошибкой: код=' + (e.error_code || e.code || '?') + ', сообщение=' +
        String(e.error_msg || e.message || '').replace(/\d{3,}/g, '#').slice(0, 90));
      return false;
    }
    var r = body.response !== undefined ? body.response : body;
    var item = Array.isArray(r) ? r[0] : (r && Array.isArray(r.items) ? r.items[0] : r);
    if (!item || typeof item !== 'object') {
      Diag.event('info', videoKind() + ': ответ ' + method + ' пустой (элементов нет)');
      return false;
    }
    var s = scalarFacts(item);
    Diag.event('info', videoKind() + ': ответ ' + method + ': поля=' + s.names.slice(0, 40).join(',') + '; признаки=' + s.facts.join(','));
    if (method === 'video.getByIds' && item.type === 'short_video') openAsClip();
    return false;
  }

  /**
   * Клип, открытый по пути обычного видео (/video-1_2): страница VK вместо плеера показывает «открыть в приложении».
   * Тот же ролик по пути клипа (/clip-1_2) играет в самой странице, поэтому переходим на него. Не чаще раза на адрес.
   */
  function openAsClip() {
    var path = location.pathname;
    if (!/^\/video-?\d+_\d+/.test(path)) return;
    var key = 'vklite_clip_redirect';
    try {
      if (sessionStorage.getItem(key) === path) return;
      sessionStorage.setItem(key, path);
    } catch (e) { /* без хранилища: защита от повтора — только этот документ */ }
    var target = path.replace(/^\/video/, '/clip') + location.search;
    // Клипы открываются на основном сайте; у видеосайта (m.vkvideo.ru) своего адреса для них может не быть.
    var origin = /(^|\.)vkvideo\.ru$/.test(location.hostname) ? 'https://m.vk.ru' : '';
    Diag.event('info', 'видео: это клип (short_video), открываем как клип: ' + maskPath(path) + ' → ' + maskPath(target.split('?')[0]));
    location.replace(origin + target);
  }

  ResponseTap.listenApi(function () { return VIDEO_PAGE.test(location.pathname); }, /^video\.(getByIds|get|getPlayerConfig)$/, onVideoApi, function () {});

  // ---- ход страницы: что нажимали, куда ушла, не выгрузилась ли

  document.addEventListener('click', function (e) {
    if (!VIDEO_PAGE.test(location.pathname) || videoDiag.clicks >= 12) return;
    videoDiag.clicks++;
    var t = e.target;
    var link = t && t.closest ? t.closest('a[href]') : null;
    var href = link ? maskPath((link.getAttribute('href') || '').split('?')[0].replace(/^https?:\/\/[^/]+/, '')) : '';
    Diag.event('info', videoKind() + ': нажатие ' + brief(t) + (href ? ', ссылка=' + href : ''));
  }, true);

  window.addEventListener('pagehide', function () {
    if (VIDEO_PAGE.test(location.pathname)) Diag.event('info', videoKind() + ': страница выгружается через ' + Math.round((Date.now() - videoDiag.started) / 1000) + ' с');
  });

  var lastVideoPath = location.pathname;
  onRouteChange(function () {
    var now = location.pathname;
    if (VIDEO_PAGE.test(lastVideoPath) && !VIDEO_PAGE.test(now)) {
      Diag.event('info', 'видео: страница сама перешла с ' + maskPath(lastVideoPath) + ' на ' + maskPath(now) + ' через ' + Math.round((Date.now() - videoDiag.started) / 1000) + ' с');
    }
    lastVideoPath = now;
  });

  // События элементов video не всплывают — слушаем на этапе перехвата. Только на страницах видео и клипов.
  VIDEO_EVENTS.forEach(function (name) {
    document.addEventListener(name, function (e) {
      if (VIDEO_PAGE.test(location.pathname) && e.target && e.target.tagName === 'VIDEO') logVideoEvent(e);
    }, true);
  });

  function startVideoDiag() {
    if (!VIDEO_PAGE.test(location.pathname) || videoDiag.path === location.pathname) return;
    var path = location.pathname;
    videoDiag = { path: path, playedByPage: false, assisted: false, events: 0, clicks: 0, started: Date.now() };
    Diag.event('info', videoKind() + ': страница открыта, хост=' + location.hostname + ', ' + videoFacts());
    [1500, 4000, 9000].forEach(function (ms) {
      setTimeout(function () {
        if (location.pathname !== path) return;
        Diag.event('info', videoKind() + ': через ' + ms + ' мс ' + videoFacts());
        if (ms === 4000) assistPlay();
      }, ms);
    });
  }

  onRouteChange(startVideoDiag);
  if (document.readyState === 'loading') document.addEventListener('DOMContentLoaded', startVideoDiag, { once: true });
  else startVideoDiag();


  // ---- перемотка: полоска внизу экрана для клипа (у клипа VK нет полосы времени, которую можно потянуть)
  // 3.57: и в ленте клипов (раздел «Клипы», /clips…), а не только на странице одного клипа (/clip-1_2).

  var SEEK_PAGE = /^\/(clip-?\d+_\d+|clips)(\/|$|-|_|\?)/;

  function isSeekPage() { return SEEK_PAGE.test(location.pathname); }

  /** Сколько ролика видно на экране (площадь в пикселях). */
  function visibleArea(r) {
    var w = Math.min(r.right, window.innerWidth) - Math.max(r.left, 0);
    var h = Math.min(r.bottom, window.innerHeight) - Math.max(r.top, 0);
    return w > 0 && h > 0 ? w * h : 0;
  }

  var seekBar = null;
  var SEEK_BAR_HEIGHT = 36;

  function formatTime(sec) {
    sec = Math.max(0, Math.round(sec || 0));
    var m = Math.floor(sec / 60);
    var r = sec % 60;
    return m + ':' + (r < 10 ? '0' : '') + r;
  }

  /**
   * Ролик, который сейчас смотрят: в ленте клипов на странице сразу несколько роликов (следующий готовится заранее),
   * поэтому — идущий и больше всех видный на экране, иначе просто больше всех видный.
   */
  function clipVideo() {
    var list = document.querySelectorAll('video');
    var best = null, bestScore = 0;
    for (var i = 0; i < list.length; i++) {
      var v = list[i];
      if (!(v.currentSrc || v.src) || !isFinite(v.duration) || v.duration <= 0) continue;
      var r = v.getBoundingClientRect();
      if (r.width < 100) continue;
      var seen = visibleArea(r);
      if (!seen) continue;
      // Идущий ролик важнее любого стоящего: прибавка больше любой площади экрана.
      var score = seen + (v.paused ? 0 : 1e9);
      if (score > bestScore) { best = v; bestScore = score; }
    }
    return best;
  }

  function makeSeekBar() {
    var root = document.createElement('div');
    root.setAttribute('data-vklite-seek', '1');
    root.style.cssText = 'position:fixed;left:0;right:0;bottom:0;height:' + SEEK_BAR_HEIGHT + 'px;z-index:2147483000;touch-action:none;display:none;';
    var time = document.createElement('div');
    time.style.cssText = 'position:absolute;left:50%;bottom:' + (SEEK_BAR_HEIGHT + 6) + 'px;transform:translateX(-50%);padding:2px 8px;border-radius:10px;' +
      'background:rgba(0,0,0,.6);color:#fff;font:12px sans-serif;display:none;pointer-events:none;';
    var track = document.createElement('div');
    track.style.cssText = 'position:absolute;left:0;right:0;bottom:10px;height:3px;background:rgba(255,255,255,.35);pointer-events:none;';
    var fill = document.createElement('div');
    fill.style.cssText = 'position:absolute;left:0;top:0;bottom:0;width:0;background:#fff;';
    var thumb = document.createElement('div');
    thumb.style.cssText = 'position:absolute;top:50%;width:12px;height:12px;margin:-6px 0 0 -6px;border-radius:50%;background:#fff;left:0;display:none;';
    track.appendChild(fill);
    track.appendChild(thumb);
    root.appendChild(track);
    root.appendChild(time);
    var state = { dragging: false };

    function seekTo(clientX) {
      var v = clipVideo();
      if (!v) return;
      var rect = root.getBoundingClientRect();
      var fraction = Math.min(1, Math.max(0, (clientX - rect.left) / Math.max(1, rect.width)));
      var end = seekableEnd(v) || v.duration;
      try { v.currentTime = Math.min(end, v.duration) * fraction; } catch (e) { /* ролик не принимает перемотку */ }
      draw(v);
      time.textContent = formatTime(v.currentTime) + ' / ' + formatTime(v.duration);
    }

    function draw(v) {
      var percent = v && v.duration > 0 ? Math.min(100, (v.currentTime / v.duration) * 100) : 0;
      fill.style.width = percent + '%';
      thumb.style.left = percent + '%';
    }

    // События не передаются странице: иначе жест по полоске листал бы клипы или ставил на паузу.
    function stop(e) { e.stopPropagation(); if (e.cancelable) e.preventDefault(); }
    root.addEventListener('pointerdown', function (e) {
      stop(e);
      state.dragging = true;
      try { root.setPointerCapture(e.pointerId); } catch (err) { /* без захвата */ }
      thumb.style.display = 'block';
      time.style.display = 'block';
      Diag.event('info', 'клип: перемотка — начато перетаскивание');
      seekTo(e.clientX);
    });
    root.addEventListener('pointermove', function (e) { if (state.dragging) { stop(e); seekTo(e.clientX); } });
    ['pointerup', 'pointercancel'].forEach(function (name) {
      root.addEventListener(name, function (e) {
        if (!state.dragging) return;
        stop(e);
        state.dragging = false;
        thumb.style.display = 'none';
        time.style.display = 'none';
        var v = clipVideo();
        Diag.event('info', 'клип: перемотка — отпущено на ' + (v ? formatTime(v.currentTime) + ' из ' + formatTime(v.duration) : '?'));
      });
    });
    ['click', 'touchstart', 'touchmove', 'mousedown'].forEach(function (name) {
      root.addEventListener(name, function (e) { e.stopPropagation(); });
    });

    return { root: root, draw: draw, state: state };
  }

  /**
   * Открыто окно поверх клипа (комментарии, «Поделиться», меню): полоса внизу перекрыла бы его поле ввода и кнопки
   * (код ревью 3.59) — прячем её, пока окно открыто.
   */
  var SHEET_SELECTOR = '[role="dialog"],[aria-modal="true"],[class*="ModalPage"],[class*="BottomSheet"],[class*="Popout"],[class*="bottom_sheet"]';

  function sheetOpen() {
    var list = document.querySelectorAll(SHEET_SELECTOR);
    for (var i = 0; i < list.length; i++) {
      var r = list[i].getBoundingClientRect();
      if (r.width > 0 && r.height > 0 && r.bottom > 0 && r.top < window.innerHeight) return true;
    }
    return false;
  }

  function updateSeekBar() {
    // Окно не видно (экран выключен, приложение в фоне) — считать положение роликов незачем.
    if (realPageHidden()) return;
    var v = isSeekPage() && !sheetOpen() ? clipVideo() : null;
    if (!v) {
      if (seekBar) seekBar.root.style.display = 'none';
      return;
    }
    if (!seekBar) {
      seekBar = makeSeekBar();
      (document.body || document.documentElement).appendChild(seekBar.root);
    } else if (!seekBar.root.isConnected) {
      (document.body || document.documentElement).appendChild(seekBar.root);
    }
    seekBar.root.style.display = 'block';
    if (!seekBar.state.dragging) seekBar.draw(v);
  }

  // Обновляем по ходу воспроизведения (timeupdate не всплывает — на этапе перехвата) и раз в полсекунды, но только пока
  // открыта страница клипа: на остальных страницах таймера нет (экономия батареи).
  var seekTimer = 0;

  function syncSeekTimer() {
    var onClip = isSeekPage();
    if (onClip && !seekTimer) seekTimer = setInterval(updateSeekBar, 500);
    if (!onClip && seekTimer) {
      clearInterval(seekTimer);
      seekTimer = 0;
      if (seekBar) seekBar.root.style.display = 'none';
    }
  }

  document.addEventListener('timeupdate', function () { if (isSeekPage()) updateSeekBar(); }, true);
  onRouteChange(syncSeekTimer);
  syncSeekTimer();

  // ---- листание клипов (3.75): отчёт 3.74 — в ленте клипов ролики не переключались смахиванием. Страница сама получает
  // список ленты (shortVideo.getRecom: ролики по порядку). Если после смахивания вверх/вниз страница за полсекунды не
  // сменила ни адрес, ни идущий ролик — следующий (или прежний) клип открывает приложение переходом силами страницы.

  var clipFeed = [];          // «владелец_номер» роликов ленты по порядку
  // Переживает загрузку страницы клипа (на ней самой ленты нет).
  try { clipFeed = JSON.parse(sessionStorage.getItem('__vkweb_clipfeed') || '[]').filter(function (k) { return /^-?\d+_\d+$/.test(k); }).slice(0, 500); } catch (e) { clipFeed = []; }
  function saveClipFeed() { try { sessionStorage.setItem('__vkweb_clipfeed', JSON.stringify(clipFeed)); } catch (e) { /* без хранилища */ } }
  var CLIP_SWIPE_MIN_PX = 70;
  var CLIP_SWIPE_CHECK_MS = 600;
  var clipSwipeLogged = 0;

  ResponseTap.listenApi(function () { return isSeekPage(); }, /^shortVideo\.(getRecom|getFeed|get\w*)$/, function (method, params, body) {
    var feed = body && body.response && body.response.feed;
    var items = feed && Array.isArray(feed.items) ? feed.items : (body && body.response && Array.isArray(body.response.items) ? body.response.items : []);
    var added = 0;
    // Первая порция новой ленты (без продолжения) — список начинается заново.
    if (method === 'shortVideo.getRecom' && params && !params.start_from && !params.page_anchor && !params.next_from) clipFeed = [];
    items.forEach(function (it) {
      // Рекламные ролики (short_video_mytarget_…) и витрины не листаются: их приложение пропускает.
      if (!it || (it.type && !/^short_video_full/.test(it.type))) return;
      var v = it.item || it;
      if (!v || typeof v.owner_id !== 'number' || typeof v.id !== 'number') return;
      var key = v.owner_id + '_' + v.id;
      if (clipFeed.indexOf(key) === -1 && clipFeed.length < 500) { clipFeed.push(key); added++; }
    });
    if (added) saveClipFeed();
    return added > 0;
  }, function () {});

  function currentClipKey() {
    var m = /^\/clip(-?\d+_\d+)/.exec(location.pathname);
    return m ? m[1] : '';
  }

  var swipeStart = null;
  document.addEventListener('touchstart', function (e) {
    if (!isSeekPage() || !e.touches || e.touches.length !== 1) { swipeStart = null; return; }
    var t = e.touches[0];
    // Полоса перемотки внизу — не смахивание.
    if (t.clientY > window.innerHeight - SEEK_BAR_HEIGHT - 8) { swipeStart = null; return; }
    swipeStart = { x: t.clientX, y: t.clientY, at: Date.now() };
  }, { capture: true, passive: true });

  document.addEventListener('touchend', function (e) {
    var s = swipeStart;
    swipeStart = null;
    if (!s || !isSeekPage() || !e.changedTouches || !e.changedTouches.length) return;
    var t = e.changedTouches[0];
    var dy = t.clientY - s.y;
    var dx = t.clientX - s.x;
    if (Math.abs(dy) < CLIP_SWIPE_MIN_PX || Math.abs(dy) < Math.abs(dx) * 2 || Date.now() - s.at > 1500) return;
    var dir = dy < 0 ? 1 : -1; // вверх — следующий
    var pathBefore = location.pathname;
    var videoBefore = clipVideo();
    var srcBefore = videoBefore ? (videoBefore.currentSrc || videoBefore.src) : '';
    setTimeout(function () {
      var videoNow = clipVideo();
      var srcNow = videoNow ? (videoNow.currentSrc || videoNow.src) : '';
      if (location.pathname !== pathBefore || videoNow !== videoBefore || srcNow !== srcBefore) return; // страница листает сама
      var here = currentClipKey();
      var i = clipFeed.indexOf(here);
      var target = i === -1 ? (dir > 0 ? clipFeed[0] : '') : clipFeed[i + dir];
      if (clipSwipeLogged < 5) {
        clipSwipeLogged++;
        Diag.event('info', 'клип: смахивание ' + (dir > 0 ? 'вверх' : 'вниз') + ' — страница не переключила; в ленте ' + clipFeed.length +
          ', ' + (target ? 'открыт соседний клип' : 'соседнего клипа нет'));
      }
      if (!target || target === here) return;
      var want = '/clip' + target;
      navigateInPage(want);
      // Переход силами страницы на клип у VK иногда показывает ленту, а не клип (см. MainActivity.openFromChat):
      // не вышло за полторы секунды — обычная загрузка клипа.
      setTimeout(function () {
        if (location.pathname !== want) { Diag.event('info', 'клип: соседний клип — обычной загрузкой'); location.href = want; }
      }, 1500);
    }, CLIP_SWIPE_CHECK_MS);
  }, { capture: true, passive: true });
