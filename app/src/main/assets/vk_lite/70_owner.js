// Часть assets/vk_lite/: Свои страницы людей и сообществ (модуль Owner).
// Выполняется внутри общей функции из 00_core.js (см. web/PageScripts.kt), отдельно не запускается.

  // ------------------------------------------------- страницы человека и сообщества

  // Свой экран профиля/сообщества: шапка и стена.
  //  - шапка: users.get / groups.getById (все ответы про владельца объединяются);
  //  - стена: owners.getMainTab (запасной путь — wall.get), продолжение — next_from;
  //  - чья страница: owner_id запроса стены, utils.resolveScreenName для адреса или сам адрес (/id1, /club1).
  // Какие адреса действительно страницы владельцев, решает приложение (owner/OwnerPath.kt).
  var Owner = (function () {
    var NOTIFY_MS = 250;
    var PATH_ID = /^\/(id|club|public|event)(\d+)\/?$/;
    var PATH_NAME = /^\/([A-Za-z0-9_.]{2,64})\/?$/;
    var METHODS = /^(users\.get|groups\.getById|utils\.resolveScreenName|owners\.getMainTab|wall\.get|newsfeed\.getFeedExp|newsfeed\.get)$/;
    // 3.77: лента новостей (/feed) — тем же своим экраном, что стена: записи ленты устроены так же (дамп 3.75,
    // newsfeed.getFeedExp). У ленты нет владельца — условный номер FEED_ID, шапки нет.
    var FEED_PATH = /^\/feed\/?$/;
    var FEED_ID = 1000000000000000;
    var MAX_POSTS = 300;
    var MAX_POST_TEXT = 6000;
    var MAX_DESCRIPTION = 4000;
    var MAX_ATTACHMENTS = 10;
    var FEED_PHOTO_MAX_WIDTH = 820;
    var COVER_MAX_WIDTH = 1100;

    var state = null;
    var channel = createChannel(NOTIFY_MS, buildPayload);
    var refreshJob = null;
    var resending = false;

    function isFeedPage() { return FEED_PATH.test(location.pathname) && !/[?&]section=/.test(location.search); }

    function isOwnerPage() { return PATH_ID.test(location.pathname) || PATH_NAME.test(location.pathname) || isFeedPage(); }


    function current() {
      var key = pathKey();
      if (!state || state.key !== key) {
        var m = PATH_ID.exec(location.pathname);
        state = {
          key: key,
          ownerId: m ? (m[1] === 'id' ? +m[2] : -m[2]) : (isFeedPage() ? FEED_ID : 0),
          users: {}, groups: {}, posts: {}, order: [], hasMore: false, wallSeen: false
        };
        channel.reset();
      }
      return state;
    }

    function rememberAll(map, list) {
      if (!Array.isArray(list)) return;
      list.forEach(function (o) { if (o && num(o.id) > 0) map[o.id] = mergeInto(map[o.id], o); });
    }

    // ---- вложения

    /** Подходящий для ленты размер (не шире FEED_PHOTO_MAX_WIDTH) из списка {url, width, height}. */
    function pickSize(list) {
      var sizes = (Array.isArray(list) ? list : []).filter(function (s) { return s && typeof s.url === 'string' && s.width; });
      if (!sizes.length) return null;
      sizes.sort(function (a, b) { return a.width - b.width; });
      var pick = sizes[0];
      sizes.forEach(function (s) { if (s.width <= FEED_PHOTO_MAX_WIDTH) pick = s; });
      return { pick: pick, largest: sizes[sizes.length - 1] };
    }

    function photoOf(p) {
      var sizes = pickSize(p.sizes) || (p.orig_photo ? pickSize([p.orig_photo]) : null);
      if (!sizes) return null;
      var full = (p.orig_photo && p.orig_photo.url) || sizes.largest.url;
      // Страница самого фото — с лайками и комментариями (режим «фото страницей»).
      var page = num(p.owner_id) && num(p.id) ? '/photo' + p.owner_id + '_' + p.id +
        (typeof p.access_key === 'string' && /^[0-9a-z]+$/i.test(p.access_key) ? '?access_key=' + p.access_key : '') : '';
      return { url: sizes.pick.url, width: num(sizes.pick.width), height: num(sizes.pick.height), full: full, open: '', page: page };
    }

    /** Клип (короткое видео) открывается страницей клипа, обычное видео — страницей видео. */
    function videoPathOf(v, type) {
      var clip = type === 'clip' || v.type === 'short_video' || v.is_clip === 1 || v.is_clip === true;
      return (clip ? '/clip' : '/video') + v.owner_id + '_' + v.id;
    }

    function videoPreviewOf(v, type) {
      var sizes = pickSize(v.image) || pickSize(v.first_frame) || pickSize(v.thumb);
      if (!sizes) return null;
      return {
        url: sizes.pick.url, width: num(sizes.pick.width), height: num(sizes.pick.height), full: '',
        open: videoPathOf(v, type),
        // 4.14: ключ доступа ролика — без него файлы закрытого ролика сообщества не отдаются.
        key: typeof v.access_key === 'string' && /^[0-9a-z]+$/i.test(v.access_key) ? v.access_key : ''
      };
    }

    /** Адреса потоков треков из записей («владелец_номер» → адрес); больше 500 — начинаем заново. */
    var postStreams = {};
    var postStreamCount = 0;

    function extraOf(type, o) {
      var url = typeof o.url === 'string' ? o.url : '';
      switch (type) {
        case 'video': return { kind: 'video', title: str(o.title), url: videoPathOf(o, type) };
        case 'clip': return { kind: 'video', title: str(o.title) || 'Клип', url: videoPathOf(o, type) };
        case 'audio':
          // Адрес потока уже в записи (дамп 3.63) — запасной, если audio.getById не даст свежий.
          if (typeof o.url === 'string' && typeof o.owner_id === 'number' && typeof o.id === 'number') {
            if (++postStreamCount > 500) { postStreams = {}; postStreamCount = 0; }
            postStreams[o.owner_id + '_' + o.id] = o.url;
          }
          return { kind: 'audio', title: [str(o.artist), str(o.title)].filter(Boolean).join(' — '), url: '',
          id: typeof o.owner_id === 'number' && typeof o.id === 'number' ? o.owner_id + '_' + o.id : '',
          key: typeof o.access_key === 'string' ? o.access_key : '' };
        case 'audio_playlist': case 'playlist': return { kind: 'audio', title: str(o.title), url: '' };
        case 'link': return { kind: 'link', title: str(o.title) || str(o.caption) || url, url: url };
        case 'article': return { kind: 'link', title: str(o.title), url: url };
        case 'doc': return { kind: 'doc', title: str(o.title), url: '/doc' + o.owner_id + '_' + o.id };
        case 'poll': return { kind: 'poll', title: str(o.question), url: '' };
        default: return null;
      }
    }

    /** Вложения поста: фото и превью видео — картинками, остальное — строками. */
    function attachmentsOf(list) {
      var photos = [];
      var extras = [];
      (Array.isArray(list) ? list : []).forEach(function (a) {
        var o = a && typeof a === 'object' ? a[a.type] : null;
        if (!o || typeof o !== 'object') return;
        var picture = a.type === 'photo' ? photoOf(o) : ((a.type === 'video' || a.type === 'clip') ? videoPreviewOf(o, a.type) : null);
        if (picture) { photos.push(picture); return; }
        var extra = a.type === 'photo' ? null : extraOf(a.type, o);
        if (extra && extra.title) extras.push(extra);
      });
      return { photos: photos.slice(0, MAX_ATTACHMENTS), extras: extras.slice(0, MAX_ATTACHMENTS) };
    }

    // ---- посты

    function authorOf(s, id) {
      var o = id > 0 ? s.users[id] : s.groups[-id];
      if (!o) return { name: '', photo: '' };
      return {
        name: id > 0 ? (str(o.first_name) + ' ' + str(o.last_name)).trim() : str(o.name),
        photo: firstUrl(o.photo_100, o.photo_base, o.photo_50, o.photo_200)
      };
    }

    function repostOf(s, copy) {
      var a = attachmentsOf(copy.attachments);
      var from = num(copy.from_id) || num(copy.owner_id);
      var author = authorOf(s, from);
      return {
        fromId: from, fromName: author.name, fromPhoto: author.photo,
        date: num(copy.date), text: str(copy.text).slice(0, MAX_POST_TEXT),
        photos: a.photos, extras: a.extras,
        url: '/wall' + copy.owner_id + '_' + copy.id
      };
    }

    function toPost(s, p) {
      var a = attachmentsOf(p.attachments);
      var copy = Array.isArray(p.copy_history) ? p.copy_history.filter(function (c) { return c && typeof c === 'object'; })[0] : null;
      var from = num(p.from_id) || num(p.owner_id);
      var author = authorOf(s, from);
      return {
        key: p.owner_id + '_' + p.id,
        url: '/wall' + p.owner_id + '_' + p.id,
        fromId: from, fromName: author.name, fromPhoto: author.photo,
        date: num(p.date),
        pinned: truthy(p.is_pinned),
        text: str(p.text).slice(0, MAX_POST_TEXT),
        photos: a.photos, extras: a.extras,
        repost: copy ? repostOf(s, copy) : null,
        likes: (p.likes && num(p.likes.count)) || 0,
        liked: !!(p.likes && truthy(p.likes.user_likes)),
        comments: (p.comments && num(p.comments.count)) || 0,
        reposts: (p.reposts && num(p.reposts.count)) || 0,
        views: (p.views && num(p.views.count)) || 0
      };
    }

    function isPost(p) {
      return p && typeof p === 'object' && typeof p.id === 'number' && typeof p.owner_id === 'number' &&
        (p.type === 'post' || p.post_type === 'post' || p.post_type === 'copy' || typeof p.text === 'string');
    }

    function onWall(s, params, r) {
      if (!r || !Array.isArray(r.items)) return false;
      var feed = s.ownerId === FEED_ID;
      var expectedOwner = feed ? 0 : (s.ownerId || num(params && params.owner_id));
      var readable = function (p) {
        if (feed && p && p.type && p.type !== 'post') return false;
        if (feed && p && typeof p.post_id === 'number' && typeof p.source_id === 'number' && typeof p.id !== 'number') {
          p = mergeInto({}, p); p.id = p.post_id; p.owner_id = p.source_id;
        }
        return isPost(p) && Number.isSafeInteger(p.id) && p.id > 0 && Number.isSafeInteger(p.owner_id) && p.owner_id !== 0 &&
          (!expectedOwner || p.owner_id === expectedOwner);
      };
      if (feed ? r.items.length > 0 && !r.items.some(readable) : !window.__vkCompat.validReadList(r.items, readable)) return false;
      if (r.count != null && !/^\d+$/.test(String(r.count))) return false;
      if (r.next_from != null && typeof r.next_from !== 'string') return false;
      if (!r.items.length && num(r.count) > 0 && !num(params && params.offset) && !(params && params.start_from)) return false;
      var ownerId = feed ? 0 : num(params && params.owner_id);
      if (s.ownerId && ownerId && ownerId !== s.ownerId) return false;
      if (!s.ownerId && ownerId) s.ownerId = ownerId;
      if (!num(params && params.offset) && !(params && params.start_from)) {
        s.headSeen = true;
        s.posts = {};
        s.order = [];
      }
      rememberAll(s.users, r.profiles);
      rememberAll(s.groups, r.groups);
      s.wallSeen = true;
      r.items.forEach(function (p) {
        // Записи newsfeed.get приходят с source_id/post_id вместо owner_id/id (код ревью 3.81: такие молча
        // пропускались) — приводятся к виду записи стены.
        if (feed && p && typeof p.id !== 'number' && typeof p.post_id === 'number' && typeof p.source_id === 'number') {
          p = mergeInto({}, p);
          p.id = p.post_id;
          p.owner_id = p.source_id;
        }
        if (!readable(p) || truthy(p.marked_as_ads) || (!feed && s.ownerId && p.owner_id !== s.ownerId)) return;
        // В ленте — только записи (не рекомендации друзей, не реклама).
        if (feed && p.type && p.type !== 'post') return;
        var k = p.owner_id + '_' + p.id;
        if (!s.posts[k]) {
          if (s.order.length >= MAX_POSTS) return;
          s.order.push(k);
        }
        s.posts[k] = p;
      });
      s.hasMore = typeof r.next_from === 'string' ? r.next_from.length > 0 : num(r.count) > s.order.length;
      // Больше MAX_POSTS записей не храним: «есть ещё» означало бы бесконечное «загрузка…» и лишние запросы (код ревью 3.59).
      if (s.order.length >= MAX_POSTS) s.hasMore = false;
      return true;
    }

    function onResolve(s, params, r) {
      var m = PATH_NAME.exec(location.pathname);
      if (!m || !params || String(params.screen_name).toLowerCase() !== m[1].toLowerCase()) return false;
      if (!r || !num(r.object_id)) return false;
      if (r.type === 'user') s.ownerId = num(r.object_id);
      else if (r.type === 'group' || r.type === 'page' || r.type === 'event') s.ownerId = -num(r.object_id);
      else return false;
      return true;
    }

    // ---- шапка

    function userHeader(u) {
      var c = u.counters && typeof u.counters === 'object' ? u.counters : {};
      return mergeInto(personBasics(u), {
        kind: 'user',
        photo: firstUrl(u.photo_200, u.photo_400, u.photo_base, u.photo_100),
        photoFull: firstUrl(u.photo_max_orig, u.photo_400_orig, u.photo_max, u.photo_400, u.photo_200),
        status: str(u.status),
        occupation: u.occupation && typeof u.occupation === 'object' ? str(u.occupation.name) : '',
        verified: truthy(u.verified),
        closed: u.is_closed === true && u.can_access_closed === false,
        counters: {
          friends: num(c.friends), followers: num(c.followers) || num(u.followers_count), photos: num(c.photos),
          videos: num(c.videos), groups: num(c.groups) + num(c.pages)
        }
      });
    }

    function coverOf(g) {
      if (!g.cover || !truthy(g.cover.enabled) || !Array.isArray(g.cover.images)) return '';
      var images = g.cover.images.filter(function (i) { return i && i.url; });
      images.sort(function (a, b) { return (a.width || 0) - (b.width || 0); });
      var pick = '';
      images.forEach(function (i) { if (!pick || (i.width || 0) <= COVER_MAX_WIDTH) pick = i.url; });
      return pick;
    }

    function groupHeader(g) {
      return mergeInto(groupBasics(g), {
        kind: 'group',
        photo: firstUrl(g.photo_200, g.photo_base, g.photo_100, g.photo_50),
        photoFull: firstUrl(g.photo_max_orig, g.photo_400_orig, g.photo_200),
        cover: coverOf(g),
        status: str(g.status),
        description: str(g.description).slice(0, MAX_DESCRIPTION),
        members: num(g.members_count),
        membersText: str(g.members_count_text),
        isMember: truthy(g.is_member) || num(g.member_status) === 1,
        canWrite: truthy(g.can_message) && !truthy(g.is_messages_blocked),
        deactivated: typeof g.deactivated === 'string' ? g.deactivated : ''
      });
    }

    function headerOf(s) {
      if (s.ownerId > 0 && s.users[s.ownerId]) return userHeader(s.users[s.ownerId]);
      if (s.ownerId < 0 && s.groups[-s.ownerId]) return groupHeader(s.groups[-s.ownerId]);
      return null;
    }

    // ---- обмен с приложением

    function onCall(method, params, body) {
      var r = body.response;
      if (r && r.response && typeof r.response === 'object' && !Array.isArray(r.response)) r = r.response;
      var s = current();
      switch (method) {
        case 'users.get':
          rememberAll(s.users, Array.isArray(r) ? r : (r && r.items));
          return true;
        case 'groups.getById':
          rememberAll(s.groups, Array.isArray(r) ? r : (r && r.groups));
          if (r && r.profiles) rememberAll(s.users, r.profiles);
          return true;
        case 'utils.resolveScreenName':
          return onResolve(s, params, r);
        default:
          if (!onWall(s, params, r)) return false;
          if ((method === 'owners.getMainTab' || method === 'wall.get') && params && s.ownerId &&
              s.ownerId !== FEED_ID && num(params.owner_id) === s.ownerId && !num(params.offset) &&
              (!s.wallRequest || !params.start_from)) {
            // Первую страницу обновляем тем же методом и с теми же настройками выдачи.
            var query = { owner_id: s.ownerId, offset: 0 };
            ['count', 'ref', 'fields', 'filters', 'forced_blocks', 'device_info', 'isFirstLanding',
              'filter', 'extended'].forEach(function (k) {
              if (typeof params[k] === 'string' || typeof params[k] === 'number' || typeof params[k] === 'boolean') query[k] = params[k];
            });
            // 5.00.16: пустой start_from не передаётся - страница его так не шлёт, а VK на него отвечал ошибкой
            // (отчёт 22.11: каждое обновление сообщества - ответ около 1 КБ и "сетевое обновление не выполнено")
            s.wallRequest = { method: method, query: query };
          }
          return true;
      }
    }

    function buildPayload() {
      if (!isOwnerPage()) return null;
      var s = current();
      if (!s.ownerId) return null;
      var header = s.ownerId === FEED_ID ? null : headerOf(s);
      if (!header && !s.wallSeen) return null;
      return {
        type: 'owner',
        key: s.key,
        snapshot: resending,
        needsWallHead: s.ownerId < 0 && !!s.wallRequest && !s.headSeen,
        ownerId: s.ownerId,
        header: header,
        wallSeen: s.wallSeen,
        hasMore: s.hasMore,
        posts: s.order.map(function (k) { return toPost(s, s.posts[k]); })
      };
    }

    ResponseTap.listenApi(isOwnerPage, METHODS, onCall, function () { channel.schedule(); retrySeen(); });

    function cancelRefresh() {
      if (!refreshJob) return;
      var job = refreshJob;
      refreshJob = null;
      clearTimeout(job.timer);
      job.controller.abort();
    }

    onRouteChange(function () {
      if (refreshJob && refreshJob.generation !== window.__vkCompat.generation()) cancelRefresh();
    });

    function refresh(operation, key) {
      if (!/^\d{1,16}$/.test(operation) || pathKey() !== key) return false;
      var s = current();
      cancelRefresh();
      function result(reason, payload, detail) {
        postToApp({ type: 'ownerRefresh', operation: operation, key: key, reason: reason, payload: payload || null,
          detail: String(detail || '').slice(0, 160) });
      }
      if (!s.wallRequest || !VkApi.ready()) { result('unavailable'); return true; }
      var request = s.wallRequest;
      var job = { generation: window.__vkCompat.generation(), controller: new AbortController(), timer: 0 };
      refreshJob = job;
      function finish(error, response) {
        if (refreshJob !== job) return;
        cancelRefresh();
        if (s !== current() || job.generation !== window.__vkCompat.generation()) return;
        // Текст ошибки - в журнал: по нему видно, что ответил VK
        if (error) { result(/^ошибка API 15(?::|$)/.test(error) ? 'denied' : 'request', null, error); return; }
        if (response && response.response && typeof response.response === 'object') response = response.response;
        if (!onWall(s, request.query, response)) {
          var shape = response && typeof response === 'object' ? Object.keys(response).slice(0, 8).join(',') : typeof response;
          result('invalid', null, 'поля ответа: ' + shape);
          return;
        }
        result('', buildPayload());
      }
      job.timer = setTimeout(function () { finish('timeout'); }, 10000);
      try {
        VkApi.call(request.method, request.query, finish, false,
          { signal: job.controller.signal, skipPageApi: true });
      } catch (e) { finish('request'); }
      return true;
    }

    /**
     * Лайк записи [key] («владелец_номер»): likes.add / likes.delete от имени страницы. Итог — сообщением type = "like"
     * {key, ok, liked, likes, reason}; при успехе запись в данных страницы обновляется и стена уходит приложению заново.
     */
    function like(key, on) {
      var m = /^(-?\d+)_(\d+)$/.exec(key);
      if (!m) return false;
      // Состояние страницы, на которой поставили лайк: ответ мог прийти, когда открыта уже другая (current() тогда
      // сбросил бы её данные — код ревью 3.59).
      var s = current();
      VkApi.call(on ? 'likes.add' : 'likes.delete', { type: 'post', owner_id: m[1], item_id: m[2] }, function (error, r) {
        var count = r && typeof r === 'object' ? num(r.likes) : 0;
        var ok = !error && r && typeof r === 'object' && typeof r.likes === 'number';
        var post = s.posts[key];
        if (ok && post) {
          post.likes = mergeInto(post.likes && typeof post.likes === 'object' ? post.likes : {}, { count: count, user_likes: on ? 1 : 0 });
          if (s === state) channel.schedule();
        }
        if (!ok) Diag.warn('лайк записи не поставлен: ' + (error || 'неожиданный ответ'));
        postToApp({ type: 'like', key: key, ok: !!ok, liked: on, likes: count, reason: ok ? '' : String(error || 'ответ').slice(0, 120) });
      });
      return true;
    }

    // ---- прочитанные записи: счётчик «новых записей» сообщества у VK уменьшается, когда запись побывала на экране
    // страницы. Страница скрыта под своим экраном и сама не прокручивается — поэтому записи, которые человек увидел на
    // своём экране, по очереди выводятся на экран и страницы (её же наблюдатель пересечений и отметит их как увиденные).

    var SEEN_DWELL_MS = 1200;
    var seenQueue = [];
    var seenTimer = 0;
    var seenDone = {};
    var seenMissLogged = false;

    /** Запись «владелец_номер» в вёрстке страницы: точно по номеру («-1_2» не совпадает с «-1_23», код ревью 3.59). */
    function postElement(key) {
      var safe = String(key).replace(/[^0-9_-]/g, '');
      if (!safe) return null;
      var direct = document.querySelector('[data-post-id="' + safe + '"],[id="post' + safe + '"]');
      if (direct) return direct;
      var exact = new RegExp('/wall' + safe.replace(/-/g, '\\-') + '(?=$|[?#/])');
      var links = document.querySelectorAll('a[href*="/wall' + safe + '"]');
      for (var i = 0; i < links.length; i++) {
        if (!exact.test(links[i].getAttribute('href') || '')) continue;
        return links[i].closest('[class*="post"],[class*="Post"],article,[data-post-id]') || links[i];
      }
      return null;
    }

    var seenPage = '';
    var seenMissed = [];

    function stopSeen() {
      clearTimeout(seenTimer);
      seenTimer = 0;
      seenQueue = [];
      reportSeenRound();
    }

    function seenStep() {
      seenTimer = 0;
      // Страницу видно (человек переключился на страницу VK) или открыта уже другая — прокручивать её нельзя: она бы
      // прыгала у него перед глазами (код ревью 3.59).
      if (!realPageHidden() || location.pathname !== seenPage) { stopSeen(); return; }
      var key = seenQueue.shift();
      if (!key) { reportSeenRound(); return; }
      var el = postElement(key);
      if (el) {
        seenDone[key] = true;
        seenRound.shown++;
        try { el.scrollIntoView({ block: 'center' }); } catch (e) { try { el.scrollIntoView(); } catch (e2) { /* без прокрутки */ } }
        afterScroll(window);
      } else {
        // Записи ещё нет в вёрстке (страница не догрузила): попробуем снова, когда придут записи.
        if (seenMissed.indexOf(key) === -1 && seenMissed.length < 100) seenMissed.push(key);
        seenRound.missing++;
        if (!seenMissLogged) {
          seenMissLogged = true;
          // Что есть в вёрстке (отчёт 3.58: записи не находились) — по этому видно, как страница рисует записи.
          var sample = document.querySelector('a[href*="/wall"]');
          Diag.event('info', 'страница: увиденной записи пока нет в вёрстке страницы — повтор, когда страница её покажет; ' +
            'ссылок /wall=' + document.querySelectorAll('a[href*="/wall"]').length +
            ', [data-post-id]=' + document.querySelectorAll('[data-post-id]').length +
            ', article=' + document.querySelectorAll('article').length +
            ', [class*=post]=' + document.querySelectorAll('[class*="post"],[class*="Post"]').length +
            ', пример ссылки=' + (sample ? String(sample.getAttribute('href') || '').replace(/\d{3,}/g, '#').slice(0, 40) : 'нет') +
            ', высота=' + Math.round(document.documentElement.scrollHeight));
        }
      }
      seenTimer = setTimeout(seenStep, SEEN_DWELL_MS);
    }

    // Итог круга вывода записей (3.68): сколько выведено и что страница после этого отправила VK — по этому видно,
    // отмечает ли она просмотр (счётчик новых записей сообщества) или вывод на страницу ничего не даёт.
    var seenRound = { shown: 0, missing: 0, calls: {} };

    function reportSeenRound() {
      var r = seenRound;
      seenRound = { shown: 0, missing: 0, calls: {} };
      if (!r.shown && !r.missing) return;
      var calls = Object.keys(r.calls).map(function (k) { return k + '×' + r.calls[k]; }).join(', ');
      Diag.event('info', 'страница: выведено увиденных записей ' + r.shown + (r.missing ? ', не найдено ' + r.missing : '') +
        '; страница отправила: ' + (calls || 'ничего'));
    }

    function enqueueSeen(k) {
      if (!seenDone[k] && seenQueue.indexOf(k) === -1) seenQueue.push(k);
    }

    /** Записи [keys] («владелец_номер» через пробел) человек увидел на своём экране. */
    function seen(keys) {
      if (!isOwnerPage() || !realPageHidden()) return false;
      if (seenPage !== location.pathname) { stopSeen(); seenMissed = []; seenPage = location.pathname; }
      String(keys || '').split(/\s+/).forEach(function (k) { if (/^-?\d+_\d+$/.test(k)) enqueueSeen(k); });
      if (seenQueue.length > 50) seenQueue = seenQueue.slice(-50);
      if (!seenTimer && seenQueue.length) seenStep();
      return true;
    }

    /** Пришли записи стены — не найденные раньше увиденные пробуем снова. */
    function retrySeen() {
      if (!seenMissed.length || location.pathname !== seenPage || !realPageHidden()) return;
      var again = seenMissed;
      seenMissed = [];
      again.forEach(enqueueSeen);
      if (!seenTimer && seenQueue.length) seenTimer = setTimeout(seenStep, SEEN_DWELL_MS);
    }

    onRouteChange(function () { if (location.pathname !== seenPage) stopSeen(); });

    // Чем страница отмечает просмотр (для отчёта): имена методов и адресов с «view/seen/mark/track», без параметров.
    var viewCallsLogged = {};
    NetTap.listen({
      // Только на страницах людей и сообществ: PATH_NAME подходит и под разделы (/audio, /feed) — отчёт 3.58 показал
      // «отметку просмотра» на странице музыки.
      active: function () { return isOwnerPage() && !/^\/(audio|feed|clips|friends|groups|mail|im|video|music|search|settings)(\/|$|\?)/.test(location.pathname); },
      wants: function (url) {
        var m = /\/method\/([\w.]+)/.exec(url) || /\/(\w*(seen|view)\w*\.php)/i.exec(url);
        var name = m && m[1];
        if (!name || !/seen|view|mark|track|stat/i.test(name)) return false;
        if (seenTimer || seenQueue.length) seenRound.calls[name] = (seenRound.calls[name] || 0) + 1;
        if (viewCallsLogged[name]) return false;
        viewCallsLogged[name] = true;
        Diag.event('info', 'страница: отметка просмотра — запрос ' + name);
        return false;
      },
      onText: function () {}
    });

    // ---- музыка в записях (3.63): адреса потоков треков записи — приложение играет их само, как музыку из переписки


    /** Треки [list] («владелец_номер_ключ» через запятую), нажатый — [wanted]: audio.getById, итог — {type:"postaudio"}. */
    function postAudio(wanted, list) {
      var audios = String(list || '').split(',').filter(function (x) { return /^-?\d+_\d+(_[0-9a-zA-Z]+)?$/.test(x); }).slice(0, 100);
      if (!audios.length) return false;
      VkApi.call('audio.getById', { audios: audios.join(',') }, function (error, r) {
        var arr = Array.isArray(r) ? r : (r && Array.isArray(r.items) ? r.items : []);
        var items = [];
        var got = {};
        arr.forEach(function (a) {
          if (!a || typeof a.url !== 'string' || !NetTap.onHost(a.url, 'audio') || /audio_api_unavailable/.test(a.url)) return;
          got[a.owner_id + '_' + a.id] = true;
          items.push({ id: a.owner_id + '_' + a.id, url: a.url, title: str(a.title), artist: str(a.artist), duration: num(a.duration) });
        });
        // По номеру не дали (или запрос не прошёл) — адрес из самой записи.
        audios.forEach(function (full) {
          var id = full.split('_').slice(0, 2).join('_');
          var url = postStreams[id];
          if (!got[id] && url && NetTap.onHost(url, 'audio') && !/audio_api_unavailable/.test(url)) items.push({ id: id, url: url, title: '', artist: '', duration: 0 });
        });
        postToApp({ type: 'postaudio', wanted: String(wanted || ''), items: items, reason: error ? String(error).slice(0, 120) : '' });
      });
      return true;
    }

    /**
     * Файлы ролика из записи [id] («владелец_номер», [key] — ключ доступа) для своего проигрывателя (4.14). Раньше ролик
     * из записи открывался страницей VK Видео: там он играл в чужом проигрывателе, а «Назад» перезагружал страницу
     * сообщества. video.get, иначе video.getByIds (так спрашивает сам сайт видео); итог — {type:"postvideo", id,
     * files:{"720": адрес…}, hls, reason}. Файлов нет — пустые: приложение откроет страницу ролика, как раньше.
     */
    function postVideo(id, key) {
      var safe = String(id || '');
      if (!/^-?\d+_\d+$/.test(safe)) return false;
      var full = safe + (/^[0-9a-zA-Z]+$/.test(String(key || '')) ? '_' + key : '');
      function filesOf(r) {
        var items = r && (Array.isArray(r.items) ? r.items : (Array.isArray(r) ? r : []));
        var v = items && items[0];
        return v && v.files && typeof v.files === 'object' ? v.files : null;
      }
      function send(files, reason) {
        var qualities = {};
        var hls = '';
        if (files) {
          Object.keys(files).forEach(function (k) {
            var m = /^mp4_(\d{3,4})$/.exec(k);
            if (m && NetTap.https(files[k])) qualities[m[1]] = files[k];
          });
          hls = [files.hls, files.hls_fmp4].filter(NetTap.https)[0] || '';
        }
        postToApp({ type: 'postvideo', id: safe, files: qualities, hls: hls, reason: reason || '' });
      }
      VkApi.call('video.get', { videos: full, extended: 0 }, function (error, r) {
        var files = !error && filesOf(r);
        if (files) { send(files, 'video.get'); return; }
        VkApi.call('video.getByIds', { videos: full, video_fields: 'files,image' }, function (error2, r2) {
          var files2 = !error2 && filesOf(r2);
          send(files2 || null, files2 ? 'video.getByIds' : String(error2 || error || 'нет файлов').slice(0, 120));
        });
      });
      return true;
    }

    return {
      /** Команды своего экрана страницы: 'more' — догрузить стену, 'sync' — прислать заново, 'like' — лайк записи. */
      command: function (action, a, b) {
        if (action === 'like') return like(String(a || ''), b === '1');
        if (action === 'audio') return postAudio(a, b);
        if (action === 'video') return postVideo(a, b);
        if (!isOwnerPage()) return false;
        // Только прокрутка: на странице профиля кнопки «Ещё» открывают меню, а не записи.
        if (action === 'more') {
          // Догрузка листает страницу в конец — очередь увиденных подождёт, чтобы не увести прокрутку от конца списка.
          if (seenTimer) { clearTimeout(seenTimer); seenTimer = setTimeout(seenStep, SEEN_DWELL_MS * 2); }
          PageList.scrollToEnd();
          return true;
        }
        if (action === 'sync') {
          resending = true;
          try { channel.resend(); } finally { resending = false; }
          return true;
        }
        if (action === 'refresh') return refresh(String(a || ''), String(b || ''));
        if (action === 'seen') return seen(a);
        return false;
      }
    };
  })();
