// Часть assets/vk_lite/: Публичный API (window.__vkLite) и завершение скрипта.
// Выполняется внутри общей функции из 00_core.js (см. web/PageScripts.kt), отдельно не запускается.

  /** Смена стилей без перезагрузки (настройка «Скрывать рекламу»). */
  function setCss(text) {
    cssText = text || '';
    ensureCss();
  }

  function command(name, value) {
    if (name === 'seek') { seek(Number(value)); return; }
    if (name === 'navigate') { navigateInPage(value); return; }
    // «Назад» — выбор человека: возврат к первому треку после него — не круг плеера (50_music.js). «Вперёд» на последнем
    // треке, наоборот, должен продолжить список, поэтому не отмечается.
    if (name === 'prev') noteMusicCommand();
    // «Назад»: трек перед играющим из списка страницы; не нашёлся — обычный путь (обработчик Media Session, кнопка, начало).
    if (name === 'prev' && previousTrack()) { scheduleReport(); return; }
    var action = SESSION_ACTIONS[name];
    if (!action) return;

    var handled = false;
    var handler = actionHandlers[action];
    if (typeof handler === 'function') {
      try {
        handler({ action: action });
        handled = true;
      } catch (err) {
        handled = false;
      }
    }
    if (!handled) fallback(name, currentMedia());
    scheduleReport();
  }

  // Номер фото живёт только в памяти документа и позволяет сравнить два маршрута в одном сеансе.
  var PhotoRoute = (function () {
    var photos = [];
    var emitted = 0;
    var documentNumber = Math.floor(Math.random() * 1000000);
    function describe(event, raw, status) {
      if (emitted >= 40) return;
      try {
        var u = new URL(raw, location.href);
        if (!NetTap.onHost(u.href, 'vk')) return;
        var direct = /^\/photo(-?\d+_\d+)\/?$/.exec(u.pathname);
        var z = /^photo(-?\d+_\d+)(?:\/|$)/.exec(u.searchParams.get('z') || '');
        var fragment = /(?:^#|[?&#]z=)photo(-?\d+_\d+)(?:[/?&#]|$)/.exec(u.hash);
        var id = direct || z || fragment;
        if (!id) return;
        var index = photos.indexOf(id[1]);
        if (index < 0) { index = photos.length; photos.push(id[1]); }
        var paramCount = 0;
        u.searchParams.forEach(function () { paramCount++; });
        emitted++;
        var message = 'маршрут фото: ' + event + ', документ=' + documentNumber + ', цель=' + (index + 1) + ', вид=' +
          (direct ? 'photo' : (z ? 'z' : 'fragment')) + ', хост=' + u.hostname +
          ', параметры=' + paramCount + ', ключ=' + u.searchParams.has('access_key') +
          ', список=' + (u.searchParams.has('list') || !!(z && /\/.+/.test(u.searchParams.get('z')))) +
          ', кодирование=' + /%[0-9a-f]{2}/i.test(u.search + u.hash) + ', фрагмент=' + !!u.hash +
          (Number.isInteger(status) ? ', HTTP=' + status : '');
        postToApp({ type: 'log', level: 'debug', message: message, page: '' });
      } catch (e) { /* диагностика не влияет на переход */ }
    }
    document.addEventListener('click', function (event) {
      var a = event.target && event.target.closest && event.target.closest('a[href]');
      if (a && event.isTrusted) describe('нажатие веб-ссылки', a.getAttribute('href'));
    }, true);
    onRouteChange(function () { describe('адрес страницы', location.href); });
    window.addEventListener('hashchange', function () { describe('фрагмент страницы', location.href); });
    NetTap.onRequest(function (url, transport) { describe(transport === 'xhr' ? 'запрос XHR' : 'запрос fetch', url); });
    NetTap.onResponse(function (url, transport, info) {
      if (info && Number.isInteger(info.status)) describe(transport === 'xhr' ? 'ответ XHR' : 'ответ fetch', url, info.status);
    });
    return { command: function (url) { describe('переход приложения', url); } };
  })();

  /**
   * Переход силами самой страницы (как нажатие ссылки): VK — одностраничное приложение и сам меняет содержимое,
   * не перезагружая документ, поэтому его плеер продолжает играть. Полная загрузка (webView.loadUrl) уничтожала
   * документ вместе с плеером — музыка обрывалась при каждом переходе в меню.
   */
  function navigateInPage(url) {
    PhotoRoute.command(url);
    // Есть на странице своя ссылка туда же (сообщество в списке «Мои сообщества») — нажимается она: VK при этом
    // передаёт, откуда пришли (track_code и source в open_group). Своя ссылка без них — отчёт 3.64: open_group уходил
    // с пустым track_code, а счётчик новых записей сообщества в списке не сбрасывался.
    var own = pageLinkTo(url);
    if (own) {
      try {
        own.click();
        Diag.event('info', 'переход: нажата ссылка самой страницы');
        return;
      } catch (e) { /* ниже — своей ссылкой */ }
    }
    try {
      var a = document.createElement('a');
      a.href = url;
      a.style.display = 'none';
      (document.body || document.documentElement).appendChild(a);
      a.click();
      a.remove();
    } catch (e) {
      location.href = url;
    }
  }

  /** Ссылка самой страницы ровно на [url] (тот же путь и запрос, тот же сайт), если она есть в вёрстке. */
  function pageLinkTo(url) {
    try {
      var target = new URL(url, location.href);
      if (target.origin !== location.origin) return null;
      if (target.pathname === location.pathname && target.search === location.search) return null;
      var wanted = [target.pathname + target.search];
      if (!target.search && typeof People !== 'undefined' && People.aliases) wanted = wanted.concat(People.aliases(target.pathname));
      var links = document.querySelectorAll('a[href]');
      for (var i = 0; i < links.length && i < 3000; i++) {
        var link = links[i];
        if (link.target && link.target !== '_self') continue;
        if (link.hasAttribute('download')) continue;
        var u;
        try { u = new URL(link.getAttribute('href'), location.href); } catch (e) { continue; }
        if (u.origin !== location.origin || wanted.indexOf(u.pathname + u.search) === -1) continue;
        return link;
      }
      var byId = communityLink(target.pathname);
      if (byId) return byId;
      describeMiss(target.pathname);
    } catch (e) { /* старый движок */ }
    return null;
  }

  /**
   * Сообщество в списке «Мои сообщества» (отчёт 3.80, разбор промаха): ссылка там — короткое имя с trackcode
   * («/имя?trackcode=…»), а номер сообщества — в data-id. Нажатие именно её передаёт VK, откуда пришли, — счётчик новых
   * записей в списке сбрасывается так же, как при нажатии человеком.
   */
  function communityLink(path) {
    var m = /^\/(club|public|event)(\d+)$/.exec(path);
    if (!m) return null;
    var list = document.querySelectorAll('a[data-id="' + m[2] + '"]');
    for (var i = 0; i < list.length; i++) {
      var link = list[i];
      if (link.target && link.target !== '_self') continue;
      var u;
      try { u = new URL(link.getAttribute('href') || '', location.href); } catch (e) { continue; }
      // Только ссылка на саму страницу сообщества (одно слово в пути), не на запись, фото или раздел.
      if (u.origin === location.origin && /^\/[A-Za-z0-9_.]+$/.test(u.pathname)) return link;
    }
    return null;
  }

  var missLogged = 0;

  /**
   * Ссылки на сообщество в списке не нашлось (отчёт 3.69: ни разу) — как список устроен на самом деле: ссылки, где есть
   * номер сообщества (номер — «{id}», прочие числа — «#»), и элементы, у которых он в атрибутах. По этому видно, чем
   * нажимать сообщество так, как это делает человек.
   */
  function describeMiss(path) {
    var m = /^\/(club|public|event)(\d+)$/.exec(path);
    if (!m || missLogged >= 3 || !/^\/groups/.test(location.pathname)) return;
    missLogged++;
    var id = m[2];
    var mask = function (v) { return String(v).split(id).join('{id}').replace(/\d{3,}/g, '#').slice(0, 90); };
    var hrefs = [];
    var anchors = document.querySelectorAll('a[href]');
    for (var i = 0; i < anchors.length && hrefs.length < 4; i++) {
      var h = anchors[i].getAttribute('href') || '';
      if (h.indexOf(id) !== -1) hrefs.push(mask(h));
    }
    var attrs = [];
    var all = document.body ? document.body.getElementsByTagName('*') : [];
    for (var j = 0; j < all.length && j < 6000 && attrs.length < 4; j++) {
      var el = all[j];
      for (var k = 0; k < el.attributes.length; k++) {
        var at = el.attributes[k];
        if (at.name !== 'href' && at.value.indexOf(id) !== -1) { attrs.push(el.tagName.toLowerCase() + '[' + at.name + '=' + mask(at.value) + ']'); break; }
      }
    }
    Diag.event('info', 'переход: ссылки страницы на сообщество не нашлось; ссылок всего=' + anchors.length +
      ', с номером: ' + (hrefs.join(' | ') || 'нет') + '; элементы с номером: ' + (attrs.join(' | ') || 'нет') +
      ', пример ссылки=' + (anchors[0] ? mask(anchors[0].getAttribute('href')) : 'нет'));
  }

  /**
   * Перемотка на [seconds]. Сначала — обработчиком самой страницы (её полоса проигрывания и счётчики остаются верными),
   * иначе — напрямую у звучащего элемента.
   */
  function seek(seconds) {
    if (!isFinite(seconds) || seconds < 0) return;
    var handled = false;
    var handler = actionHandlers.seekto;
    if (typeof handler === 'function') {
      try {
        handler({ action: 'seekto', seekTime: seconds, fastSeek: false });
        handled = true;
      } catch (err) {
        handled = false;
      }
    }
    if (!handled) {
      var media = currentMedia();
      if (media) {
        try { media.currentTime = seconds; } catch (err) { /* элемент ещё не готов */ }
      }
    }
    scheduleReport();
  }

  // ------------------------------------------------------- публичный API

  Object.defineProperty(window, '__vkLite', {
    value: Object.freeze({ protocol: 1, contract: function () { return window.__vkCompat.describe('lite', window.__vkLite); }, ensureCss: ensureCss, setCss: setCss, setHides: setHides, setAdData: setAdData, setClips: setClips, music: music, people: People.command, owner: Owner.command, clips: Clips.command, comments: Comments.command, photos: Photos.command, vkvideo: VkVideo.command, setDump: Dump.set, fitShell: fitShell, command: command }),
    writable: false,
    configurable: false
  });

  // На время загрузки VK ставит стиль pointer-events:none на ссылки и кнопки и снимает его,
  // когда догрузятся его скрипты. Если что-то не догрузилось, страница остаётся «мёртвой»:
  // тогда через несколько секунд после загрузки снимаем блокировку сами.
  function releaseTouches() {
    var el = window._preventPointerEventsElement;
    if (el && el.parentNode) el.parentNode.removeChild(el);
  }
  window.addEventListener('load', function () { setTimeout(releaseTouches, 3000); });
  // Скрипт видимости должен встать раньше этого (порядок скриптов начала документа = порядок установки). Если нет —
  // страница VK уже могла увидеть настоящую видимость окна: в журнале это видно, а не остаётся допущением.
  window.addEventListener('load', function () {
    if (!visibilityApi) Diag.warn('скрипт видимости не встал раньше скриптов страницы: музыка может останавливаться при блокировке экрана');
  });
  setTimeout(releaseTouches, 8000);

  ensureCss();
})();
