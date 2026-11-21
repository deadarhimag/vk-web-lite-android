// Часть assets/vk_lite/: Реклама и клипы: вырезаются из ответов API до отрисовки, запасной путь — по вёрстке.
// Выполняется внутри общей функции из 00_core.js (см. web/PageScripts.kt), отдельно не запускается.

  // ------------------------------------------------- реклама в данных (до отрисовки)

  // Посты, рекламные блоки и рекомендации приходят из API VK (/method/...) JSON-ом. Проще и надёжнее
  // вырезать рекламу из ответа, чем искать её в вёрстке: она вообще не попадёт на страницу.
  var blockAdData = CONFIG.blockAdData === true;
  var blockClips = CONFIG.blockClips === true;
  // Блоки коротких видео в ленте, группах и профилях. Вложение-клип в посте тоже убирается.
  // Подборки и карусели клипов; отдельный клип (пост, вложение) остаётся и открывается.
  var CLIP_TYPES = ['clips', 'clips_block', 'clips_carousel', 'clip_block', 'short_videos'];
  var AD_KEYS = ['marked_as_ads'];
  var AD_TYPES = ['ads', 'ad', 'promo_post', 'ads_post', 'ad_post', 'sponsored'];

  function truthy(v) { return v === 1 || v === true || v === '1'; }

  function isAdItem(o) {
    if (!o || typeof o !== 'object' || Array.isArray(o)) return false;
    for (var i = 0; i < AD_KEYS.length; i++) if (truthy(o[AD_KEYS[i]])) return true;
    var type = typeof o.type === 'string' ? o.type.toLowerCase() : '';
    var postType = typeof o.post_type === 'string' ? o.post_type.toLowerCase() : '';
    return AD_TYPES.indexOf(type) !== -1 || AD_TYPES.indexOf(postType) !== -1;
  }

  function isClipItem(o) {
    if (!o || typeof o !== 'object' || Array.isArray(o)) return false;
    var type = typeof o.type === 'string' ? o.type.toLowerCase() : '';
    if (CLIP_TYPES.indexOf(type) !== -1) return true;
    return false;
  }

  function isBlockedItem(o) {
    return (blockAdData && isAdItem(o)) || (blockClips && isClipItem(o));
  }

  /** Вырезает рекламные элементы и клипы из массивов на любой глубине. Возвращает, сколько вырезано. */
  function stripAds(node, depth) {
    if (!node || typeof node !== 'object' || (depth || 0) > 40) return 0;
    var removed = 0;
    if (Array.isArray(node)) {
      for (var i = node.length - 1; i >= 0; i--) {
        if (isBlockedItem(node[i])) { node.splice(i, 1); removed++; }
        else removed += stripAds(node[i], (depth || 0) + 1);
      }
      return removed;
    }
    for (var key in node) {
      if (Object.prototype.hasOwnProperty.call(node, key)) removed += stripAds(node[key], (depth || 0) + 1);
    }
    return removed;
  }

  /** Строка JSON -> отфильтрованная строка, либо null, если рекламы нет или это не JSON. */
  function filterJsonText(text) {
    if (typeof text !== 'string' || text.charAt(0) !== '{' && text.charAt(0) !== '[') return null;
    try {
      var data = JSON.parse(text);
      return stripAds(data) > 0 ? JSON.stringify(data) : null;
    } catch (e) { return null; }
  }

  var isApiUrl = NetTap.isApiUrl;

  // fetch
  try {
    var originalFetch = window.fetch;
    if (typeof originalFetch === 'function') {
      window.fetch = function (input) {
        var result = originalFetch.apply(this, arguments);
        var url = typeof input === 'string' ? input : (input && input.url) || '';
        if (!isApiUrl(url)) return result;
        return result.then(function (response) {
          if (!(blockAdData || blockClips) || !response || !response.ok) return response;
          var type = (response.headers && response.headers.get('content-type')) || '';
          if (type.indexOf('json') === -1) return response;
          return response.clone().text().then(function (text) {
            var filtered = filterJsonText(text);
            if (filtered === null) return response;
            var headers = new Headers(response.headers);
            headers.delete('content-length');
            return new Response(filtered, { status: response.status, statusText: response.statusText, headers: headers });
          }).catch(function () { return response; });
        });
      };
    }
  } catch (e) { /* без фильтра, но страница работает */ }

  // XMLHttpRequest
  try {
    var xhrProto = XMLHttpRequest.prototype;
    var originalOpen = xhrProto.open;
    xhrProto.open = function (method, url) {
      this.__vkLiteUrl = String(url);
      return originalOpen.apply(this, arguments);
    };
    ['responseText', 'response'].forEach(function (prop) {
      var desc = Object.getOwnPropertyDescriptor(xhrProto, prop);
      if (!desc || !desc.get) return;
      Object.defineProperty(xhrProto, prop, {
        configurable: true,
        enumerable: desc.enumerable,
        get: function () {
          var value = desc.get.call(this);
          if (!(blockAdData || blockClips) || this.readyState !== 4 || !isApiUrl(this.__vkLiteUrl)) return value;
          if (typeof value === 'string') {
            var cache = this.__vkLiteCache || (this.__vkLiteCache = {});
            if (cache.source !== value) { cache.source = value; var f = filterJsonText(value); cache.result = f === null ? value : f; }
            return cache.result;
          }
          if (value && typeof value === 'object') stripAds(value); // responseType = 'json'
          return value;
        }
      });
    });
  } catch (e) { /* без фильтра, но страница работает */ }

  // Данные, которые VK кладёт в страницу заранее (window.cur.apiPrefetchCache): первая порция ленты
  // приходит не через сеть, а вшитой в HTML, поэтому фильтруем и её в момент присвоения.
  try {
    var curValue;
    Object.defineProperty(window, 'cur', {
      configurable: true,
      enumerable: true,
      get: function () { return curValue; },
      set: function (value) {
        curValue = value;
        if (!value || typeof value !== 'object') return;
        var cache;
        Object.defineProperty(value, 'apiPrefetchCache', {
          configurable: true,
          enumerable: true,
          get: function () { return cache; },
          set: function (entries) {
            if ((blockAdData || blockClips) && Array.isArray(entries)) {
              entries.forEach(function (entry) { if (entry && entry.response) stripAds(entry.response); });
            }
            cache = entries;
            try { ResponseTap.prefetch(entries); } catch (e) { /* свои экраны получат данные из сети */ }
          }
        });
      }
    });
  } catch (e) { /* без фильтра, но страница работает */ }

  function setAdData(enabled) { blockAdData = enabled === true; }

  function setClips(enabled) {
    blockClips = enabled === true;
    if (blockClips && document.body) processClipLinks(document.body);
  }

  // ------------------------------------------------- реклама в вёрстке (запасной путь)

  var AD_LABEL_MAX_LEVELS = 14;
  var AD_ITEM_MAX_CHARS = 8000;
  var MIN_SAME_SIBLINGS = 2;

  function isAdLabel(text) {
    return text === 'реклама' || text.indexOf('erid:') !== -1 || /(^|\s)erid\s+[a-z0-9]/.test(text);
  }

  /**
   * Пост в ленте — элемент списка: у него есть минимум два «брата» с тем же тегом и точно тем же набором
   * классов. Поднимаемся от пометки «Реклама» до самого верхнего такого элемента.
   */
  function findListItem(anchor) {
    var found = null;
    var el = anchor;
    for (var i = 0; el && !isProtected(el) && i < AD_LABEL_MAX_LEVELS; i++, el = el.parentElement) {
      var parent = el.parentElement;
      if (!parent || isProtected(parent)) break;
      var same = 0;
      for (var k = 0; k < parent.children.length; k++) {
        var sibling = parent.children[k];
        if (sibling !== el && sibling.tagName === el.tagName && sibling.className === el.className) same++;
      }
      if (same >= MIN_SAME_SIBLINGS && norm(el.textContent).length <= AD_ITEM_MAX_CHARS) found = el;
    }
    return found;
  }

  function processAdLabels(node) {
    if (!blockAdData || !node || node.nodeType !== 1) return;
    var walker = document.createTreeWalker(node, NodeFilter.SHOW_TEXT, null);
    var textNode;
    while ((textNode = walker.nextNode())) {
      var text = norm(textNode.nodeValue);
      if (!text || text.length > 400 || !isAdLabel(text)) continue;
      var anchor = textNode.parentElement;
      if (!anchor || isUserContent(anchor) || anchor.closest('[' + HIDDEN_ATTR + ']')) continue;
      var item = findListItem(anchor);
      if (item) hideBlock(item);
    }
  }

  // ------------------------------------------------- клипы в вёрстке

  var CLIP_PATH = /^\/(clips(\/|$|\?|#|-?\d+(_\d+)?(\/|$|\?|#))|clip(\/|$|\?|#))/;

  function isClipLink(a) {
    var href = a.getAttribute('href') || '';
    if (!href) return false;
    var path = href;
    if (/^https?:/i.test(href)) {
      try {
        var url = new URL(href);
        if (!/(^|\.)(vk\.(com|ru)|vkvideo\.ru)$/i.test(url.hostname)) return false;
        path = url.pathname;
      } catch (e) { return false; }
    }
    return CLIP_PATH.test(path);
  }

  /** Ссылка на клип → прячем элемент списка, в котором она стоит (карточка, блок, пост с клипом). */
  /** Страница отдельного клипа: плеер не прячется, даже когда блоки клипов скрыты. */
  var CLIP_PAGE = /^\/clip-?\d+_\d+/;
  function syncClipPage() {
    try {
      var on = CLIP_PAGE.test(location.pathname);
      var root = document.documentElement;
      if (root && root.hasAttribute('data-vklite-clip-page') !== on) {
        if (on) root.setAttribute('data-vklite-clip-page', '1'); else root.removeAttribute('data-vklite-clip-page');
      }
    } catch (e) { /* страница без доступа к адресу */ }
  }

  function processClipLinks(node) {
    syncClipPage();
    if (!blockClips || !node || node.nodeType !== 1) return;
    var links = node.tagName === 'A' ? [node] : Array.prototype.slice.call(node.querySelectorAll('a[href]'));
    links.forEach(function (a) {
      if (!isClipLink(a) || isUserContent(a) || a.closest('[' + HIDDEN_ATTR + ']')) return;
      hideBlock(findListItem(a) || a);
    });
  }
