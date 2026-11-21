// Часть assets/vk_lite/: альбомы и фото для своего экрана (5.00.12).
// Выполняется внутри общей функции из 00_core.js (см. web/PageScripts.kt), отдельно не запускается.
// Использует то, что объявлено в 40_screens.js (Diag, VkApi, postToApp).

  // Страницы /albums, /album и /photos рисуются вёрсткой VK со своим оформлением, а превью в них часто не грузятся
  // (отчёт 11.11: ERR_BLOCKED_BY_ORB). Свой экран берёт списки из API от имени страницы: photos.getAlbums,
  // photos.get и photos.getAll. Итог - сообщения "photos" (порция списка) и "photosAll" (все адреса альбома для
  // сохранения в папку).
  var Photos = (function () {
    var PAGE_SIZE = 60;
    var ALL_PAGE = 1000;
    var ALL_MAX = 10000;
    var MAX_TEXT = 300;
    var THUMB_PX = 400;

    /** Номер владельца: число со знаком; иначе null. */
    function ownerOf(value) {
      return /^-?\d{1,12}$/.test(String(value || '')) ? String(value) : null;
    }

    /** Номер альбома API: число со знаком или пусто (все фото владельца); иначе null. */
    function albumOf(value) {
      var s = String(value == null ? '' : value);
      return s === '' || /^-?\d{1,12}$/.test(s) ? s : null;
    }

    /** Размеры фото -> адрес ближе всего к [px] по большей стороне, не меньше его, если есть. */
    function sized(sizes, px) {
      var list = (Array.isArray(sizes) ? sizes : []).filter(function (s) { return s && NetTap.https(s.url || s.src); });
      if (!list.length) return '';
      list.sort(function (a, b) { return Math.max(a.width || 0, a.height || 0) - Math.max(b.width || 0, b.height || 0); });
      for (var i = 0; i < list.length; i++) {
        if (Math.max(list[i].width || 0, list[i].height || 0) >= px) return list[i].url || list[i].src;
      }
      var last = list[list.length - 1];
      return last.url || last.src;
    }

    /** Все адреса размеров, от большего к меньшему, без повторов: запасные, если сервер не отдаёт нужный. */
    function allSizes(sizes) {
      var list = (Array.isArray(sizes) ? sizes : []).filter(function (s) { return s && NetTap.https(s.url || s.src); });
      list.sort(function (a, b) { return Math.max(b.width || 0, b.height || 0) - Math.max(a.width || 0, a.height || 0); });
      var seen = {};
      var out = [];
      list.forEach(function (s) {
        var u = s.url || s.src;
        if (!seen[u]) { seen[u] = true; out.push(u); }
      });
      return out;
    }

    /** Самый крупный адрес: оригинал, иначе самый большой размер. */
    function fullUrl(p) {
      var orig = p.orig_photo && NetTap.https(p.orig_photo.url) ? p.orig_photo.url : '';
      return orig || sized(p.sizes, 100000);
    }

    function mapAlbum(a) {
      if (!a || typeof a.id !== 'number' || typeof a.owner_id !== 'number') return null;
      var cover = sized(a.sizes, THUMB_PX) || (NetTap.https(a.thumb_src) ? a.thumb_src : '');
      return {
        id: a.id,
        owner: a.owner_id,
        title: String(a.title || '').slice(0, MAX_TEXT),
        size: typeof a.size === 'number' ? a.size : 0,
        cover: cover,
        system: a.id < 0
      };
    }

    function mapPhoto(p) {
      if (!p || typeof p.id !== 'number' || typeof p.owner_id !== 'number') return null;
      var thumb = sized(p.sizes, THUMB_PX);
      var full = fullUrl(p);
      if (!thumb && !full) return null;
      return {
        id: p.id,
        owner: p.owner_id,
        album: typeof p.album_id === 'number' ? p.album_id : 0,
        thumb: thumb || full,
        full: full || thumb,
        fallback: allSizes(p.sizes).filter(function (u) { return u !== full; }).slice(0, 4),
        w: p.orig_photo && p.orig_photo.width || 0,
        h: p.orig_photo && p.orig_photo.height || 0,
        date: typeof p.date === 'number' ? p.date : 0,
        text: String(p.text || '').slice(0, MAX_TEXT)
      };
    }

    /** Запрос порции фото: альбом через photos.get, "все фото" - через photos.getAll. */
    function photosRequest(owner, album, offset, count) {
      if (album === '') {
        return { method: 'photos.getAll', params: { owner_id: owner, offset: offset, count: Math.min(count, 200), photo_sizes: 1, extended: 0 } };
      }
      return { method: 'photos.get', params: { owner_id: owner, album_id: album, offset: offset, count: count, photo_sizes: 1, rev: 1 } };
    }

    function loadAlbums(owner, offset) {
      var reply = function (fields) {
        var msg = { type: 'photos', kind: 'albums', owner: Number(owner), album: '', offset: offset, ok: false, total: 0, items: [], next: offset, more: false, reason: '' };
        Object.keys(fields).forEach(function (k) { msg[k] = fields[k]; });
        postToApp(msg);
      };
      if (!VkApi.ready()) { reply({ reason: 'нет данных входа' }); return true; }
      VkApi.call('photos.getAlbums', { owner_id: owner, offset: offset, count: PAGE_SIZE, need_covers: 1, photo_sizes: 1, need_system: 1 }, function (error, r) {
        if (error || !r || !Array.isArray(r.items)) {
          Diag.warn('альбомы не получены: ' + String(error || 'нет списка').slice(0, 100));
          reply({ reason: String(error || 'нет списка').slice(0, 120) });
          return;
        }
        var items = [];
        r.items.forEach(function (a) { var m = mapAlbum(a); if (m) items.push(m); });
        var total = typeof r.count === 'number' ? r.count : items.length;
        Diag.debug('альбомы: получено ' + items.length + ' из ' + total + ', с ' + offset);
        reply({ ok: true, total: total, items: items, next: offset + r.items.length, more: r.items.length > 0 && offset + r.items.length < total });
      });
      return true;
    }

    function loadPhotos(owner, album, offset) {
      var reply = function (fields) {
        var msg = { type: 'photos', kind: 'album', owner: Number(owner), album: album, offset: offset, ok: false, total: 0, items: [], next: offset, more: false, reason: '' };
        Object.keys(fields).forEach(function (k) { msg[k] = fields[k]; });
        postToApp(msg);
      };
      if (!VkApi.ready()) { reply({ reason: 'нет данных входа' }); return true; }
      var q = photosRequest(owner, album, offset, PAGE_SIZE);
      VkApi.call(q.method, q.params, function (error, r) {
        if (error || !r || !Array.isArray(r.items)) {
          Diag.warn('фото не получены: ' + String(error || 'нет списка').slice(0, 100));
          reply({ reason: String(error || 'нет списка').slice(0, 120) });
          return;
        }
        var items = [];
        r.items.forEach(function (p) { var m = mapPhoto(p); if (m) items.push(m); });
        var total = typeof r.count === 'number' ? r.count : items.length;
        Diag.debug('фото: получено ' + items.length + ' из ' + total + ', с ' + offset);
        reply({ ok: true, total: total, items: items, next: offset + r.items.length, more: r.items.length > 0 && offset + r.items.length < total });
      });
      return true;
    }

    /**
     * Все фото альбома для сохранения: порциями по ALL_PAGE, пока не кончатся (не больше ALL_MAX). Каждая порция
     * уходит приложению сразу - сохранение начинается, не дожидаясь конца списка, пока подписи адресов свежие.
     */
    function loadAll(owner, album, job) {
      var send = function (fields) {
        var msg = { type: 'photosAll', job: job, owner: Number(owner), album: album, ok: true, items: [], done: false, total: 0, reason: '' };
        Object.keys(fields).forEach(function (k) { msg[k] = fields[k]; });
        postToApp(msg);
      };
      if (!VkApi.ready()) { send({ ok: false, done: true, reason: 'нет данных входа' }); return true; }
      var step = function (offset) {
        var q = photosRequest(owner, album, offset, ALL_PAGE);
        VkApi.call(q.method, q.params, function (error, r) {
          if (error || !r || !Array.isArray(r.items)) {
            Diag.warn('список фото для сохранения не получен: ' + String(error || 'нет списка').slice(0, 100));
            send({ ok: false, done: true, reason: String(error || 'нет списка').slice(0, 120) });
            return;
          }
          var items = [];
          r.items.forEach(function (p) {
            var m = mapPhoto(p);
            if (m) items.push({ id: m.id, owner: m.owner, full: m.full, fallback: m.fallback, date: m.date });
          });
          var total = typeof r.count === 'number' ? r.count : 0;
          var next = offset + r.items.length;
          var done = !r.items.length || next >= Math.min(total, ALL_MAX);
          send({ items: items, done: done, total: Math.min(total, ALL_MAX) });
          if (!done) step(next);
        });
      };
      step(0);
      return true;
    }

    function command(action, owner, album, offset) {
      var o = ownerOf(owner);
      var a = albumOf(album);
      if (!o || a === null) return false;
      var from = Math.max(0, Math.min(100000, Number(offset) || 0));
      if (action === 'albums') return loadAlbums(o, from);
      if (action === 'photos') return loadPhotos(o, a, from);
      // В "all" четвёртый аргумент - номер задания сохранения, а не отступ
      if (action === 'all') return loadAll(o, a, String(offset || '').slice(0, 40));
      return false;
    }

    return { command: command };
  })();
