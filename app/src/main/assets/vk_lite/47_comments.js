// Часть assets/vk_lite/: Комментарии к видео и клипам для своего окна (4.45).
// Выполняется внутри общей функции из 00_core.js (см. web/PageScripts.kt), отдельно не запускается.
// Использует то, что объявлено в 40_screens.js (Diag, VkApi, postToApp).

  // Раньше «комментарии» в своём проигрывателе открывали страницу клипа или видео в главном окне: её проигрыватель
  // поверх, комментарии где-то внизу, «Назад» — в переписку. Теперь — свой список: video.getComments и
  // video.createComment от имени страницы (клип VK — тоже видео). Итог — сообщения «comments» и «commentSent».
  var Comments = (function () {
    var PAGE_SIZE = 30;
    // 4.46: ответы. Первые THREAD_INLINE приходят вместе с комментарием (thread_items_count), всю ветку — запросом с
    // comment_id. Если VK запрос ветки не понимает (вернул обычный список), ответы остаются только первые — честно.
    var THREAD_INLINE = 3;
    var THREAD_PAGE = 100;
    var MAX_TEXT = 4000;

    /** «владелец_номер» → {owner, id}; иначе null. */
    function target(value) {
      var m = /^(-?\d{1,12})_(\d{1,12})$/.exec(String(value || ''));
      return m ? { owner: m[1], id: m[2], key: m[1] + '_' + m[2] } : null;
    }


    /** «[id1|Имя]» и «[club1|Название]» — упоминания: в тексте остаётся только имя. */
    function plainText(text) {
      return String(text || '').replace(/\[(?:id|club|public|event)\d+\|([^\]]+)\]/g, '$1').slice(0, MAX_TEXT);
    }

    /** Чем комментарий, кроме текста: стикер, фото, видео… (подпись для списка; содержимое не загружается). */
    function attachmentKind(list) {
      if (!Array.isArray(list) || !list.length) return '';
      var t = list[0] && list[0].type;
      return typeof t === 'string' ? t.slice(0, 20) : '';
    }

    /** Картинка стикера (самая крупная до 256 px) — стикер в комментариях частый, без неё комментарий пустой. */
    function stickerImage(list) {
      var s = Array.isArray(list) && list[0] && list[0].type === 'sticker' ? list[0].sticker : null;
      return bestImage(s && s.images, 256, 'width');
    }

    function authors(r) {
      var map = {};
      (Array.isArray(r.profiles) ? r.profiles : []).forEach(function (p) {
        if (p && typeof p.id === 'number') map[p.id] = { name: ((p.first_name || '') + ' ' + (p.last_name || '')).trim(), photo: NetTap.https(p.photo_100) };
      });
      (Array.isArray(r.groups) ? r.groups : []).forEach(function (g) {
        if (g && typeof g.id === 'number') map[-g.id] = { name: String(g.name || ''), photo: NetTap.https(g.photo_100) };
      });
      return map;
    }

    /** Комментарий API → для приложения; null — удалённый или пустой. */
    function mapComment(c, who) {
      if (!c || typeof c.id !== 'number') return null;
      var a = who[c.from_id] || { name: '', photo: '' };
      var text = plainText(c.text);
      var kind = attachmentKind(c.attachments);
      if (!text && !kind) return null;
      var thread = [];
      if (c.thread && Array.isArray(c.thread.items)) {
        c.thread.items.forEach(function (r) { var m = mapComment(r, who); if (m) thread.push(m); });
      }
      return {
        id: c.id,
        author: a.name,
        photo: a.photo,
        text: text,
        date: typeof c.date === 'number' ? c.date : 0,
        likes: c.likes && typeof c.likes.count === 'number' ? c.likes.count : 0,
        attach: kind,
        sticker: stickerImage(c.attachments),
        replies: c.thread && typeof c.thread.count === 'number' ? c.thread.count : 0,
        thread: thread
      };
    }

    /** Ответ относится к ветке [parent]: VK пишет родителей в parents_stack (или reply_to_comment у прямого ответа). */
    function inThread(c, parent) {
      if (!c) return false;
      if (Array.isArray(c.parents_stack) && c.parents_stack.indexOf(parent) !== -1) return true;
      return c.reply_to_comment === parent;
    }

    function load(value, offset, parentValue) {
      var t = target(value);
      if (!t) return false;
      var from = Math.max(0, Math.min(100000, Number(offset) || 0));
      var parent = Math.max(0, Number(parentValue) || 0);
      var reply = function (fields) {
        var msg = { type: 'comments', id: t.key, offset: from, parent: parent, ok: false, total: 0, items: [], reason: '' };
        Object.keys(fields).forEach(function (k) { msg[k] = fields[k]; });
        postToApp(msg);
      };
      if (!VkApi.ready()) { reply({ reason: 'нет данных входа' }); return true; }
      var params = parent ?
        { owner_id: t.owner, video_id: t.id, need_likes: 1, comment_id: parent, offset: from, count: THREAD_PAGE, sort: 'asc', extended: 1, fields: 'photo_100' } :
        { owner_id: t.owner, video_id: t.id, need_likes: 1, offset: from, count: PAGE_SIZE, sort: 'desc', extended: 1, fields: 'photo_100',
          thread_items_count: THREAD_INLINE };
      VkApi.call('video.getComments', params, function (error, r) {
        if (error || !r || !Array.isArray(r.items)) {
          Diag.warn('комментарии не получены: ' + String(error || 'нет списка').slice(0, 100));
          reply({ reason: String(error || 'нет списка').slice(0, 120) });
          return;
        }
        var who = authors(r);
        if (parent) {
          var replies = r.items.filter(function (c) { return inThread(c, parent); });
          if (r.items.length && !replies.length) {
            // VK вернул обычный список вместо ветки — запрос ветки он здесь не поддерживает.
            Diag.debug('комментарии: ветка ответов не получена (вернулся обычный список)');
            reply({ reason: 'ветка не поддерживается' });
            return;
          }
          r = { items: replies, count: typeof r.count === 'number' && replies.length === r.items.length ? r.count : replies.length };
        }
        var items = [];
        r.items.forEach(function (c) { var m = mapComment(c, who); if (m) items.push(m); });
        Diag.debug('комментарии: получено ' + items.length + ' из ' + (r.count || 0) + ', с ' + from);
        reply({ ok: true, total: typeof r.count === 'number' ? r.count : items.length, items: items, next: from + r.items.length,
          more: r.items.length > 0 && from + r.items.length < (r.count || 0) });
      });
      return true;
    }

    function send(value, text, replyTo) {
      var t = target(value);
      var to = Math.max(0, Number(replyTo) || 0);
      var message = String(text || '').trim().slice(0, MAX_TEXT);
      if (!t || !message) return false;
      var reply = function (ok, reason) { postToApp({ type: 'commentSent', id: t.key, replyTo: to, ok: ok, reason: reason || '' }); };
      if (!VkApi.ready()) { reply(false, 'нет данных входа'); return true; }
      var params = { owner_id: t.owner, video_id: t.id, message: message };
      if (to) params.reply_to_comment = to;
      VkApi.call('video.createComment', params, function (error) {
        if (error) {
          Diag.warn('комментарий не отправлен: ' + String(error).slice(0, 100));
          reply(false, String(error).slice(0, 120));
          return;
        }
        Diag.event('info', 'комментарий отправлен');
        reply(true);
      });
      return true;
    }

    function command(action, a, b, c) {
      if (action === 'get') return load(a, b, c);
      if (action === 'send') return send(a, b, c);
      return false;
    }

    return { command: command };
  })();
