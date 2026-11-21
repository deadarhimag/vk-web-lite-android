// Часть assets/vk_lite/: Плейлисты для своего экрана музыки (вкладка «Плейлисты»).
// Выполняется внутри общей функции из 00_core.js (см. web/PageScripts.kt), отдельно не запускается.
// Использует то, что объявлено в 40_screens.js (Diag, createChannel, ResponseTap, PageList) и 50_music.js.

  // ------------------------------------------------- плейлисты: список и открытие

  // Как мобильный VK размечает плейлисты, надёжно не известно, поэтому плейлисты ищутся несколькими способами:
  //  1) в JSON-ответах страницы — объекты вида {id, owner_id|ownerId, title, count|totalCount|photo|coverUrl…};
  //  2) в вёрстке — ссылки на плейлист (…audio_playlist<владелец>_<номер>, …/playlist/<владелец>_<номер>).
  // Что нашлось, уходит приложению сообщением type = "playlists"; не нашлось — в журнал пишется, какие ссылки есть на
  // странице (без номеров), чтобы по отчёту было видно, как их искать.
  var PLAYLIST_PAGE = /(^|[?&])act=audio_playlist(-?\d+)_(\d+)/;
  var PLAYLISTS_PAGE = /(^|[?&])act=audio_playlists/;
  var PLAYLIST_DISCOVERY_MS = 6000;
  var MAX_PLAYLISTS = 500;

  var playlistsMap = {};
  var playlistsOrder = [];
  var playlistsNone = false;
  var discoveryTimer = 0;
  var discoveryRootRetry = false;

  /**
   * Ссылка именно на страницу плейлиста (а не любая, где в адресе есть его номер): путь /audio с act=audio_playlist…
   * или /playlist/…, /music/playlist/… на этом же сайте. Раньше годился любой адрес с «audio_playlist<номер>» — и
   * первой попадалась кнопка «Поделиться» (/like?…object=audio_playlist…): вместо плейлиста открывалось окно «поделиться»,
   * а ссылки на чужие страницы (/audios-…) уводили на страницу сообщества. Возвращает [_, владелец, номер, ключ] или null.
   */
  function playlistLink(href) {
    if (!href) return null;
    var u;
    try { u = new URL(href, location.href); } catch (e) { return null; }
    if (u.origin !== location.origin) return null;
    if (u.pathname === '/audio') {
      var q = /(?:^|[?&])act=audio_playlist(-?\d+)_(\d+)(?:_([0-9a-f]{6,}))?(?:&|$)/i.exec(u.search);
      if (!q) return null;
      var key = /[?&]access_(?:hash|key)=([0-9a-z]+)/i.exec(u.search);
      return [q[0], q[1], q[2], q[3] || (key ? key[1] : '')];
    }
    var p = /^\/(?:music\/)?playlist\/(-?\d+)_(\d+)(?:_([0-9a-f]{6,}))?\/?$/i.exec(u.pathname);
    return p ? [p[0], p[1], p[2], p[3] || ''] : null;
  }

  /** Свой список плейлистов: /audio?act=audio_playlists<мой номер> (или без номера). Чужие списки в «мои» не идут. */
  function isOwnPlaylistsPage() {
    if (location.pathname !== '/audio') return false;
    var m = /(?:^|[?&])act=audio_playlists(-?\d*)(?:&|$)/.exec(location.search);
    if (!m) return false;
    var me = window.vk && window.vk.id;
    // Свой номер страница ещё не сообщила — сверить не с чем, список принимается.
    return !m[1] || !isNumber(me) || String(me) === m[1];
  }

  function isPlaylistPage() { return location.pathname === '/audio' && PLAYLIST_PAGE.test(location.search); }

  function isPlaylistsPage() { return location.pathname === '/audio' && PLAYLISTS_PAGE.test(location.search); }

  function isNumber(v) { return typeof v === 'number' && isFinite(v); }


  /** Адрес в пределах сайта (путь и запрос) или пустая строка для чужого. */
  function sitePath(href) {
    try {
      var u = new URL(href, location.href);
      return u.origin === location.origin ? u.pathname + u.search : '';
    } catch (e) { return ''; }
  }

  function builtPlaylistUrl(owner, pid, hash) {
    return '/audio?act=audio_playlist' + owner + '_' + pid + (hash ? '&access_hash=' + hash : '');
  }

  /** Свой номер: от страницы (vk.id) или из адреса своего списка плейлистов (act=audio_playlists<номер>). */
  function myOwnerId() {
    var me = window.vk && window.vk.id;
    if (isNumber(me) && me > 0) return String(me);
    var m = /(?:^|[?&])act=audio_playlists(\d+)(?:&|$)/.exec(location.search);
    return m ? m[1] : '';
  }

  // ---- своя медиатека плейлистов (3.72): свои и добавленные к себе чужие — без недавно слушанных
  // На странице плейлистов VK вперемешку показывает свои, добавленные и недавно слушанные (отчёт 3.70: «было 6,
  // стало 13»). Различить их по вёрстке нельзя, поэтому состав медиатеки берётся у VK запросом от имени страницы
  // (audio.getPlaylists своего номера — свои и добавленные; у добавленных — ссылка на оригинал, original). Пока ответа
  // нет или он не пришёл (ошибка) — показывается всё, что на странице, как раньше.
  var library = null;          // id → true: что входит в медиатеку; null — состав не известен
  var libraryAlias = {};       // id копии в медиатеке → id оригинала (у добавленных)
  var libraryAliasHash = {};   // id копии → ключ доступа оригинала (у копии свой, к оригиналу он не подходит)
  var libraryAskedAt = 0;
  var librarySkipped = 0;
  var LIBRARY_REFRESH_MS = 60000;

  function fetchLibrary() {
    var me = myOwnerId();
    if (!me || !isOwnPlaylistsPage() || !VkApi.ready()) return;
    if (Date.now() - libraryAskedAt < LIBRARY_REFRESH_MS) return;
    libraryAskedAt = Date.now();
    VkApi.call('audio.getPlaylists', { owner_id: me, count: 200 }, function (error, r) {
      var items = r && Array.isArray(r.items) ? r.items : null;
      if (error || !items) {
        Diag.debug('плейлисты: состав медиатеки не получен (' + String(error || 'нет списка').slice(0, 80) + ') — показано всё со страницы');
        return;
      }
      var set = {};
      var alias = {};
      var aliasHash = {};
      var followed = 0;
      items.forEach(function (o) {
        if (!o || !isNumber(o.id)) return;
        var own = (isNumber(o.owner_id) ? o.owner_id : me) + '_' + o.id;
        var orig = o.original && isNumber(o.original.owner_id) && isNumber(o.original.playlist_id) ?
          o.original.owner_id + '_' + o.original.playlist_id : '';
        set[own] = true;
        if (orig && orig !== own) {
          set[orig] = true;
          alias[own] = orig;
          aliasHash[own] = String(o.original.access_key || '').replace(/[^0-9a-zA-Z]/g, '');
          followed++;
        }
        else if (own.split('_')[0] !== String(me)) followed++;
      });
      library = set;
      libraryAlias = alias;
      libraryAliasHash = aliasHash;
      // Уже собранное, чего нет в медиатеке (недавно слушанные), — убрать; копии добавленных — к оригиналу.
      var before = playlistsOrder.length;
      playlistsOrder = playlistsOrder.filter(function (id) {
        if (alias[id] && playlistsMap[alias[id]]) { delete playlistsMap[id]; return false; }
        if (set[id]) return true;
        delete playlistsMap[id];
        return false;
      });
      librarySkipped += before - playlistsOrder.length;
      // Что есть в ответе, но ещё не на странице, — из самого ответа.
      collectPlaylists(items, 0);
      Diag.debug('плейлисты: медиатека — ' + items.length + ' (добавленных чужих ' + followed + '); убрано не из медиатеки: ' + librarySkipped);
      schedulePlaylistsNotify();
    });
  }

  function rememberPlaylist(p) {
    // Копия добавленного плейлиста в медиатеке — тот же плейлист, что оригинал на странице: один пункт.
    if (libraryAlias[p.id]) {
      var orig = libraryAlias[p.id];
      if (playlistsMap[orig]) return false;
      var parts = orig.split('_');
      // Код ревью 3.81: адрес оригинала собирался с ключом доступа копии — чужой закрытый плейлист не открывался.
      p.hash = libraryAliasHash[p.id] || '';
      p.id = orig;
      if (!p.fromDom) p.url = builtPlaylistUrl(parts[0], parts[1], p.hash);
    }
    if (library && !library[p.id]) { librarySkipped++; return false; }
    var known = playlistsMap[p.id];
    if (!known) {
      if (playlistsOrder.length >= MAX_PLAYLISTS) return false;
      playlistsMap[p.id] = p;
      playlistsOrder.push(p.id);
      playlistsNone = false;
      return true;
    }
    var changed = false;
    ['title', 'cover', 'subtitle', 'hash'].forEach(function (key) {
      if (p[key] && p[key] !== known[key]) { known[key] = p[key]; changed = true; }
    });
    if (p.count && p.count !== known.count) { known.count = p.count; changed = true; }
    // Адрес из вёрстки (с настоящим ключом доступа) надёжнее собранного.
    if (p.url && p.fromDom && (!known.fromDom || known.url !== p.url)) { known.url = p.url; known.fromDom = true; changed = true; }
    return changed;
  }

  // ---- JSON

  function bestCover(o) {
    var direct = NetTap.https(o.coverUrl) || NetTap.https(o.cover) || NetTap.https(o.thumb_url);
    if (direct) return direct;
    var photo = (o.photo && typeof o.photo === 'object') ? o.photo : ((o.thumb && typeof o.thumb === 'object') ? o.thumb : null);
    if (photo) {
      var keys = ['photo_1200', 'photo_600', 'photo_300', 'photo_270', 'photo_135', 'photo_68', 'photo_34'];
      for (var i = 0; i < keys.length; i++) { var url = NetTap.https(photo[keys[i]]); if (url) return url; }
      var best = '';
      var bestArea = -1;
      (Array.isArray(photo.sizes) ? photo.sizes : []).forEach(function (s) {
        var area = (s && s.width || 0) * (s && s.height || 0);
        if (s && NetTap.https(s.url) && area > bestArea) { bestArea = area; best = s.url; }
      });
      return best;
    }
    return '';
  }

  /** Объект JSON похож на плейлист? Возвращает запись или null. */
  function playlistFromObject(o) {
    if (!o || typeof o !== 'object' || Array.isArray(o)) return null;
    var owner = isNumber(o.owner_id) ? o.owner_id : (isNumber(o.ownerId) ? o.ownerId : null);
    var pid = isNumber(o.id) ? o.id : null;
    if (owner === null || pid === null || typeof o.title !== 'string' || !o.title.trim()) return null;
    // Не трек, не видео, не фото, не запись стены.
    if ('artist' in o || 'duration' in o || 'text' in o || 'sizes' in o || 'views' in o || 'player' in o ||
        'files' in o || 'image' in o || 'date' in o) return null;
    var count = isNumber(o.count) ? o.count : (isNumber(o.totalCount) ? o.totalCount : (isNumber(o.size) ? o.size : 0));
    var cover = bestCover(o);
    var hash = String(o.access_key || o.accessHash || o.access_hash || '');
    // Хотя бы один признак плейлиста, иначе любой {id, owner_id, title} (альбом, документ) был бы принят за него.
    if (!count && !cover && !hash && !(Array.isArray(o.list) && o.list.length)) return null;
    var id = owner + '_' + pid;
    return {
      id: id,
      title: decodeEntities(o.title.trim()),
      subtitle: '',
      cover: cover,
      count: count,
      hash: hash,
      url: builtPlaylistUrl(owner, pid, hash),
      fromDom: false
    };
  }

  function collectPlaylists(node, depth) {
    if (!node || typeof node !== 'object' || depth > 30) return false;
    var changed = false;
    if (Array.isArray(node)) {
      if (isTrackTuple(node[0])) return false; // список треков — плейлистов внутри нет
      for (var i = 0; i < node.length; i++) changed = collectPlaylists(node[i], depth + 1) || changed;
      return changed;
    }
    var p = playlistFromObject(node);
    if (p) changed = rememberPlaylist(p) || changed;
    for (var key in node) {
      if (!Object.prototype.hasOwnProperty.call(node, key)) continue;
      if (p && key === 'list') continue; // треки плейлиста собирает 50_music.js
      changed = collectPlaylists(node[key], depth + 1) || changed;
    }
    return changed;
  }

  function onPlaylistsResponse(url, body, text) {
    if (!isOwnPlaylistsPage()) return;
    var first = text.charAt(0);
    if (first !== '{' && first !== '[') return;
    // Дешёвая проверка до разбора: ответ без названий и признаков плейлиста разбирать незачем.
    if (text.indexOf('"title"') === -1 || (text.indexOf('owner') === -1 && text.indexOf('Owner') === -1)) return;
    var data;
    try { data = JSON.parse(text); } catch (e) { return; }
    if (collectPlaylists(data, 0)) schedulePlaylistsNotify();
  }

  ResponseTap.listen(isMusicPage, onPlaylistsResponse);

  // ---- вёрстка

  function textLines(el) {
    var lines = [];
    try {
      var walker = document.createTreeWalker(el, 4 /* NodeFilter.SHOW_TEXT */);
      var node;
      while ((node = walker.nextNode())) {
        var t = (node.nodeValue || '').replace(/\s+/g, ' ').trim();
        if (t) lines.push(t);
        if (lines.length >= 12) break;
      }
    } catch (e) { /* без разбора текста */ }
    return lines;
  }

  var COUNT_LINE = /^(\d[\d\s\u00a0]*)\s*(?:трек|аудио|песн|композиц)/i;

  function cardCover(card) {
    var imgs = card.querySelectorAll('img');
    for (var i = 0; i < imgs.length; i++) {
      var url = NetTap.https(imgs[i].getAttribute('src')) || NetTap.https(imgs[i].getAttribute('data-src'));
      if (url) return url;
    }
    var styled = card.querySelectorAll('[style*="url("]');
    for (var j = 0; j < styled.length; j++) {
      var m = /url\(["']?(https:[^"')]+)/.exec(styled[j].getAttribute('style') || '');
      if (m) return m[1];
    }
    return '';
  }

  function distinctPlaylistIds(el) {
    var ids = {};
    var anchors = el.querySelectorAll('a[href]');
    for (var i = 0; i < anchors.length; i++) {
      var m = playlistLink(anchors[i].getAttribute('href'));
      if (m) ids[m[1] + '_' + m[2]] = true;
    }
    return Object.keys(ids).length;
  }

  function playlistFromAnchor(a, m) {
    var href = a.getAttribute('href') || '';
    var path = sitePath(href);
    if (!path) return null;
    // Карточка — самый большой предок, в котором только этот плейлист: в нём его название, обложка и число треков.
    var card = a;
    for (var depth = 0; depth < 4; depth++) {
      var up = card.parentElement;
      if (!up || up === document.body || up === document.documentElement) break;
      if (distinctPlaylistIds(up) > 1) break;
      card = up;
    }
    var title = (a.getAttribute('title') || a.getAttribute('aria-label') || '').trim();
    var count = 0;
    var subtitle = '';
    var lines = textLines(card);
    var rest = [];
    lines.forEach(function (line) {
      var c = COUNT_LINE.exec(line);
      if (c) { count = parseInt(c[1].replace(/\D+/g, ''), 10) || count; } else { rest.push(line); }
    });
    if (!title && rest.length) title = rest[0];
    if (rest.length > 1 && rest[1] !== title) subtitle = rest[1];
    if (!title) title = 'Плейлист';
    var hashMatch = /[?&]access_(?:hash|key)=([0-9a-z]+)/i.exec(href);
    return {
      id: m[1] + '_' + m[2],
      title: decodeEntities(title.slice(0, 200)),
      subtitle: decodeEntities(subtitle.slice(0, 120)),
      cover: cardCover(card),
      count: count,
      hash: m[3] || (hashMatch ? hashMatch[1] : ''),
      url: path,
      fromDom: true
    };
  }

  function scanPlaylistsDom(root) {
    if (!root || !root.querySelectorAll) return false;
    var changed = false;
    // Плейлисты собираются только со своего списка: на главной музыки и на чужих страницах стоят рекомендованные и
    // чужие плейлисты — раньше они попадали во вкладку первыми, а свои подгружались позже («прыгающий» список).
    if (!isOwnPlaylistsPage()) return false;
    var anchors = root.querySelectorAll('a[href]');
    for (var i = 0; i < anchors.length; i++) {
      var m = playlistLink(anchors[i].getAttribute('href'));
      if (!m) continue;
      var entry = playlistFromAnchor(anchors[i], m);
      if (entry && rememberPlaylist(entry)) changed = true;
    }
    if (root.matches && root.tagName === 'A') {
      var own = playlistLink(root.getAttribute('href'));
      var ownEntry = own && playlistFromAnchor(root, own);
      if (ownEntry && rememberPlaylist(ownEntry)) changed = true;
    }
    return changed;
  }

  function findPlaylistAnchor(id) {
    var anchors = document.querySelectorAll('a[href]');
    for (var i = 0; i < anchors.length; i++) {
      var m = playlistLink(anchors[i].getAttribute('href'));
      if (m && m[1] + '_' + m[2] === id) return anchors[i];
    }
    return null;
  }

  // ---- сообщение приложению

  function playlistsHasMore() {
    return isOwnPlaylistsPage() && !PageList.isBlocked() && !!document.querySelector('.show_more,[class*="showMore"]');
  }

  function buildPlaylistsPayload() {
    if (!isMusicPage()) return null;
    // Список собирается только со своей страницы плейлистов; пока его нет, слать нечего (на других страницах — только
    // уже собранное, по просьбе приложения).
    if (!playlistsOrder.length && !playlistsNone) return null;
    return {
      type: 'playlists',
      key: 'playlists',
      hasMore: playlistsHasMore(),
      // Весь список — только со страницы плейлистов и без «Показать ещё»: иначе удалённые плейлисты остались бы навсегда.
      complete: isOwnPlaylistsPage() && !playlistsHasMore(),
      none: !playlistsOrder.length,
      items: playlistsOrder.map(function (id) {
        var p = playlistsMap[id];
        return { id: p.id, title: p.title, subtitle: p.subtitle, cover: p.cover, count: p.count, url: p.url };
      })
    };
  }

  var playlistsChannel = createChannel(MUSIC_NOTIFY_MS, buildPlaylistsPayload);

  function schedulePlaylistsNotify() {
    if (isMusicPage()) playlistsChannel.schedule();
  }

  /** Для наблюдателя за вёрсткой (50_music.js): новый узел мог принести плейлисты. */
  function onPlaylistNode(node) {
    if (!isMusicPage()) return;
    if (isOwnPlaylistsPage()) fetchLibrary();
    if (scanPlaylistsDom(node)) schedulePlaylistsNotify();
    if (discoveryTimer && playlistsOrder.length) finishDiscovery();
  }

  // ---- поиск страницы плейлистов и диагностика

  /** Ссылка на страницу плейлистов: по адресу или по подписи («Плейлисты», «Показать все» рядом с заголовком). */
  function playlistsSectionLink() {
    var anchors = document.querySelectorAll('a[href]');
    var i;
    for (i = 0; i < anchors.length; i++) {
      if (/[?&]act=audio_playlists/.test(anchors[i].getAttribute('href') || '')) return anchors[i];
    }
    var titles = { 'плейлисты': 1, 'мои плейлисты': 1, 'все плейлисты': 1, 'плейлисты и альбомы': 1 };
    for (i = 0; i < anchors.length; i++) {
      var t = (anchors[i].textContent || '').replace(/\s+/g, ' ').trim().toLowerCase();
      if (titles[t]) return anchors[i];
    }
    // «Показать все» рядом с заголовком «Плейлисты»: заголовок — не ссылка, ссылка лежит в том же блоке.
    var heads = document.querySelectorAll('h1,h2,h3,h4,span,div,a');
    var seen = 0;
    for (i = 0; i < heads.length && seen < 3000; i++, seen++) {
      var h = heads[i];
      if (h.children.length) continue;
      var text = (h.textContent || '').replace(/\s+/g, ' ').trim().toLowerCase();
      if (!titles[text]) continue;
      var box = h;
      for (var up = 0; up < 3 && box.parentElement; up++) {
        box = box.parentElement;
        var link = box.querySelector('a[href*="act=block"],a[href*="playlists"]');
        if (link) return link;
      }
    }
    return null;
  }

  /** Какие ссылки есть на странице — без номеров и ключей: по этому видно, как искать плейлисты. */
  function playlistDiagnostics() {
    var seen = {};
    var list = [];
    var anchors = document.querySelectorAll('a[href]');
    for (var i = 0; i < anchors.length && list.length < 6; i++) {
      var href = anchors[i].getAttribute('href') || '';
      if (!/(playlist|audio\?act=|\/music)/i.test(href)) continue;
      var clean = href.replace(/-?\d+/g, '#').replace(/([?&][a-z_]+=)[^&]{12,}/gi, '$1…').slice(0, 44);
      if (!seen[clean]) { seen[clean] = true; list.push(clean); }
    }
    // Сообщение журнала — не длиннее 200 знаков (Diag): страница и самые показательные ссылки.
    return ('стр=' + (location.pathname + location.search.replace(/-?\d+/g, '#').replace(/=[^&]{12,}/g, '=…')).slice(0, 40) +
      '; ссылки: ' + (list.join(' | ') || 'нет')).slice(0, 150);
  }

  function finishDiscovery() {
    clearTimeout(discoveryTimer);
    discoveryTimer = 0;
    if (playlistsOrder.length) { schedulePlaylistsNotify(); return; }
    playlistsNone = true;
    Diag.warn('плейлисты не найдены. ' + playlistDiagnostics());
    schedulePlaylistsNotify();
  }

  /**
   * Прислать плейлисты приложению. Уже известные — сразу; иначе — найти страницу плейлистов (ссылка в вёрстке),
   * перейти на неё силами страницы и собрать. Не нашлось за PLAYLIST_DISCOVERY_MS — приложению сообщается «нет»,
   * а в журнал уходит диагностика.
   */
  function discoverPlaylists() {
    scanPlaylistsDom(document);
    if (playlistsOrder.length) { playlistsChannel.resend(); return 'known'; }
    clearTimeout(discoveryTimer);
    discoveryTimer = setTimeout(finishDiscovery, PLAYLIST_DISCOVERY_MS);
    playlistsNone = false;
    if (isOwnPlaylistsPage()) { fetchLibrary(); return 'here'; }
    // Свой список плейлистов — по прямому адресу: ссылки «Плейлисты» на главной ведут и на подборки (act=block),
    // а плейлисты собираются только со своего списка.
    var me = window.vk && window.vk.id;
    if (isNumber(me) && me > 0) {
      Diag.debug('плейлисты: переход на свой список плейлистов');
      navigateInPage('/audio?act=audio_playlists' + me);
      return 'direct';
    }
    var link = playlistsSectionLink();
    if (link && /[?&]act=audio_playlists/.test(link.getAttribute('href') || '')) {
      Diag.debug('плейлисты: номер не известен, переход по ссылке на список плейлистов');
      link.click();
      return 'link';
    }
    if (!isMyAudiosPage() && !discoveryRootRetry) {
      // Мы на странице плейлиста или другой: ссылка на плейлисты — на главной музыки.
      discoveryRootRetry = true;
      Diag.debug('плейлисты: ссылки нет, возврат на главную музыки');
      navigateInPage('/audio');
      return 'root';
    }
    return 'none';
  }

  /** Главная музыки загрузилась после возврата с другой страницы: повторить поиск ссылки на плейлисты. */
  function retryDiscoveryOnRoot() {
    if (!discoveryTimer || !discoveryRootRetry || !isMyAudiosPage() || playlistsOrder.length) return;
    discoveryRootRetry = false;
    var link = playlistsSectionLink();
    if (link && /[?&]act=audio_playlists/.test(link.getAttribute('href') || '')) link.click();
  }

  function loadMorePlaylists() { return PageList.loadMore(['.show_more', '[class*="showMore"]']); }

  /** Открыть плейлист: нажать его ссылку на странице (так переход идёт «как у человека»), иначе перейти по адресу. */
  function openPlaylist(id, url) {
    // Страница уже на этом плейлисте (его открыли снова): перехода не будет, и треки не пришли бы — присылаем заново.
    // Отчёт 3.48: повторно открытый плейлист оставался пустым.
    var here = PLAYLIST_PAGE.exec(location.search);
    if (isPlaylistPage() && here && here[2] + '_' + here[3] === id) {
      Diag.debug('плейлист уже открыт на странице: треки отправлены заново');
      scanMusicDom(document);
      musicChannel.resend();
      return true;
    }
    var anchor = findPlaylistAnchor(id);
    if (anchor) { anchor.click(); return true; }
    var entry = playlistsMap[id];
    var target = (entry && entry.url) || (typeof url === 'string' ? sitePath(url) : '');
    if (!target && /^-?\d+_\d+$/.test(id)) target = '/audio?act=audio_playlist' + id;
    if (!target) return false;
    navigateInPage(target);
    return true;
  }

  /** Вернуть страницу на главную музыки («Мои треки»), не перезагружая её. */
  function goHome() {
    if (isMyAudiosPage() || isMyAudiosBlock()) return true;
    navigateInPage('/audio');
    return true;
  }
