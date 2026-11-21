// Часть assets/vk_lite/: Свои списки друзей и сообществ (модуль People).
// Выполняется внутри общей функции из 00_core.js (см. web/PageScripts.kt), отдельно не запускается.

  // ------------------------------------------------- друзья и сообщества для своих экранов

  // Друзья — friends.get (список), friends.getRequests (заявки), friends.getRecommendations («Возможно, вы знакомы»,
  // дамп 3.82: 25 человек с общими друзьями; страница сама запрашивает при открытии своего списка друзей).
  // Сообщества — catalog.getGroups / catalog.getSection (каталог «Мои подписки»), запасной путь — groups.get.
  // Подписки — блок каталога с data_type вида groups_all_groups…: в нём порядок id (group_items),
  // сами сообщества лежат рядом, в response.groups. Продолжение — next_from этого блока.
  var People = (function () {
    var NOTIFY_MS = 200;
    var FRIENDS_PATH = /^\/friends(\/|$)/;
    var GROUPS_PATH = /^\/groups(\/|$)/;
    var METHODS = /^(friends\.get|friends\.getRequests|friends\.getRecommendations|catalog\.getGroups|catalog\.getSection|catalog\.getBlockItems|groups\.get)$/;
    var MY_GROUPS_BLOCK = /all_groups|my_groups|groups_my|subscriptions/i;
    var MIN_FRIENDS_PAGE = 10; // friends.get с count 3 — превью на чужой странице, не список

    var state = null;
    var channel = createChannel(NOTIFY_MS, buildPayload);

    function kind() {
      if (FRIENDS_PATH.test(location.pathname)) return 'friends';
      if (GROUPS_PATH.test(location.pathname)) return 'groups';
      return '';
    }

    function pageKey() {
      return location.pathname + location.search.replace(/[?&](offset|from|section|q)=[^&]*/g, '');
    }

    /** Состояние текущего списка; сменилась страница — список начинается заново. */
    function current() {
      var key = pageKey();
      if (!state || state.key !== key) {
        state = {
          key: key, kind: kind(), received: false, listSeen: false, total: 0, hasMore: false,
          friends: {}, friendOrder: [], requests: {}, requestOrder: [], suggestions: {}, suggestOrder: [],
          groups: {}, groupOrder: [], counters: {}, myBlockSeen: false,
          apiOffset: 0, apiBusy: false, requestsAsked: false
        };
        channel.reset();
      }
      return state;
    }

    // ---- друзья

    function toFriend(u) {
      if (!u || typeof u !== 'object' || !Number.isSafeInteger(num(u.id)) || !(num(u.id) > 0) ||
          typeof u.first_name !== 'string' && typeof u.last_name !== 'string') return null;
      return mergeInto(personBasics(u), {
        photo: firstUrl(u.photo_100, u.photo_200, u.photo_base, u.photo_50),
        mutual: (u.mutual && num(u.mutual.count)) || num(u.common_count)
      });
    }

    function rememberFriend(s, u, asRequest) {
      var f = toFriend(u);
      if (!f) return;
      var id = String(f.id);
      var map = asRequest ? s.requests : s.friends;
      var order = asRequest ? s.requestOrder : s.friendOrder;
      if (!map[id]) order.push(id);
      map[id] = f;
    }

    function onFriendsGet(s, params, r) {
      if (!r || !Array.isArray(r.items)) return false;
      var count = num(params && params.count);
      if (count && count < MIN_FRIENDS_PAGE) return false;
      // Чужие друзья (/friends?id=…): берём только список этого человека.
      var owner = queryParam('id');
      if (owner && params && params.user_id && String(params.user_id) !== owner) return false;
      if (!window.__vkCompat.validReadList(r.items, function (u) { return !!toFriend(u); })) return false;
      if (!r.items.length && num(r.count) > 0 && !num(params && params.offset)) return false;
      if (r.count != null && !/^\d+$/.test(String(r.count))) return false;
      if (!num(params && params.offset)) { s.friends = {}; s.friendOrder = []; }
      r.items.forEach(function (u) { rememberFriend(s, u, false); });
      if (r.count != null) s.total = num(r.count);
      s.hasMore = s.total > s.friendOrder.length && r.items.length > 0;
      s.listSeen = true;
      return true;
    }

    var MAX_SUGGESTIONS = 50;

    /** «Возможно, вы знакомы» — только в своём списке друзей; уже друзья и заявки сюда не попадают. */
    function onFriendSuggestions(s, r) {
      if (queryParam('id') || !r || !Array.isArray(r.items)) return false;
      var changed = false;
      r.items.forEach(function (u) {
        var f = toFriend(u);
        if (!f || u.deactivated) return;
        var id = String(f.id);
        if (!s.suggestions[id] && s.suggestOrder.length >= MAX_SUGGESTIONS) return;
        if (!s.suggestions[id]) s.suggestOrder.push(id);
        s.suggestions[id] = f;
        changed = true;
      });
      return changed;
    }

    function onFriendRequests(s, r) {
      if (!r || !Array.isArray(r.items)) return false;
      // Без extended VK отдаёт одни id — такие пропускаем: показать нечего.
      r.items.forEach(function (u) { if (u && typeof u === 'object') rememberFriend(s, u.user || u, true); });
      return true;
    }

    // ---- сообщества

    function toGroup(g, counter) {
      if (!g || typeof g !== 'object' || !(num(g.id) > 0) || typeof g.name !== 'string') return null;
      return mergeInto(groupBasics(g), {
        photo: firstUrl(g.photo_100, g.photo_200, g.photo_base, g.photo_50),
        members: str(g.members_count_text),
        membersCount: num(g.members_count),
        admin: truthy(g.is_admin),
        counter: num(counter)
      });
    }

    function rememberGroupOrder(s, id) {
      if (id > 0 && s.groupOrder.indexOf(String(id)) === -1) s.groupOrder.push(String(id));
    }

    function onGroupsCatalog(s, r) {
      if (!r || typeof r !== 'object') return false;
      // Validate the whole recognized block before mutating the last good group state.
      if (r.groups != null && !window.__vkCompat.validReadList(r.groups, function (g) {
        return g && Number.isSafeInteger(num(g.id)) && num(g.id) > 0 && typeof g.name === 'string';
      })) return false;
      var checkSections = r.catalog && Array.isArray(r.catalog.sections) ? r.catalog.sections.slice() : [];
      if (r.section) checkSections.push(r.section);
      var checkBlocks = r.block ? [r.block] : [];
      checkSections.forEach(function (sec) { if (sec && Array.isArray(sec.blocks)) checkBlocks = checkBlocks.concat(sec.blocks); });
      for (var index = 0; index < checkBlocks.length; index++) {
        var block = checkBlocks[index];
        if (!block || !MY_GROUPS_BLOCK.test(String(block.data_type || ''))) continue;
        var references = Array.isArray(block.group_items) ? block.group_items : block.group_ids;
        if (!window.__vkCompat.validReadList(references, function (value) {
          var id = value && typeof value === 'object' ? num(value.id) : num(value);
          return Number.isSafeInteger(id) && id > 0;
        })) return false;
      }
      if (Array.isArray(r.groups)) r.groups.forEach(function (g) { if (g && num(g.id) > 0) s.groups[g.id] = g; });
      var sections = [];
      if (r.catalog && Array.isArray(r.catalog.sections)) sections = sections.concat(r.catalog.sections);
      if (r.section) sections.push(r.section);
      var blocks = [];
      sections.forEach(function (sec) { if (sec && Array.isArray(sec.blocks)) blocks = blocks.concat(sec.blocks); });
      if (r.block) blocks.push(r.block);
      blocks.forEach(function (b) {
        if (!b || typeof b !== 'object' || !MY_GROUPS_BLOCK.test(String(b.data_type || ''))) return;
        var list = Array.isArray(b.group_items) ? b.group_items : (Array.isArray(b.group_ids) ? b.group_ids : []);
        s.myBlockSeen = true;
        list.forEach(function (x) {
          var id = x && typeof x === 'object' ? num(x.id) : num(x);
          if (x && typeof x === 'object' && x.counter) s.counters[id] = x.counter;
          rememberGroupOrder(s, id);
        });
        s.hasMore = !!b.next_from;
      });
      // Блок подписок (может быть и пустым) или главная каталога, где подписки — сообщества с is_member.
      if (s.myBlockSeen || (Array.isArray(r.groups) && r.groups.length)) s.listSeen = true;
      return true;
    }

    function onGroupsGet(s, params, r) {
      if (!r || !Array.isArray(r.items) || queryParam('id') || !truthy(params && params.extended)) return false;
      if (!window.__vkCompat.validReadList(r.items, function (g) {
        return g && Number.isSafeInteger(num(g.id)) && num(g.id) > 0 && typeof g.name === 'string';
      })) return false;
      if (r.count != null && !/^\d+$/.test(String(r.count))) return false;
      if (!r.items.length && num(r.count) > 0 && !num(params && params.offset)) return false;
      if (!num(params && params.offset)) { s.groups = {}; s.groupOrder = []; }
      r.items.forEach(function (g) {
        if (!g || !(num(g.id) > 0)) return;
        s.groups[g.id] = g;
        rememberGroupOrder(s, num(g.id));
      });
      s.myBlockSeen = true;
      if (r.count != null) s.total = num(r.count);
      s.hasMore = s.total > s.groupOrder.length;
      s.listSeen = true;
      return true;
    }

    /** Сначала порядок из блока подписок, затем остальные сообщества, где человек состоит. */
    function groupItems(s) {
      var ids = s.groupOrder.filter(function (id) { return s.groups[id]; });
      Object.keys(s.groups).forEach(function (id) {
        var g = s.groups[id];
        if (ids.indexOf(id) === -1 && (truthy(g.is_member) || num(g.member_status) === 1)) ids.push(id);
      });
      return ids.map(function (id) { return toGroup(s.groups[id], s.counters[id]); }).filter(Boolean);
    }

    // ---- обмен с приложением

    function onCall(method, params, body) {
      var r = body.response;
      if (r && r.response && typeof r.response === 'object' && !Array.isArray(r.response)) r = r.response;
      var s = current();
      var changed;
      if (s.kind === 'friends') {
        if (method === 'friends.get') changed = onFriendsGet(s, params, r);
        else if (method === 'friends.getRequests') changed = onFriendRequests(s, r);
        else if (method === 'friends.getRecommendations') changed = onFriendSuggestions(s, r);
      } else if (method === 'groups.get') {
        changed = onGroupsGet(s, params, r);
      } else if (method.indexOf('catalog.') === 0) {
        changed = onGroupsCatalog(s, r);
      }
      if (changed) s.received = true;
      return !!changed;
    }

    function buildPayload() {
      if (!kind()) return null;
      var s = current();
      // Данных этой страницы ещё не было (вернулись «Назад», и страница взяла список из своего кэша):
      // пустой список не шлём — приложение оставит показанный.
      if (!s.received) return null;
      var friends = s.kind === 'friends';
      return {
        type: 'people',
        kind: s.kind,
        key: s.key,
        own: !queryParam('id'),
        hasMore: s.hasMore,
        // Сам список уже приходил (а не только заявки): до этого пустой список ничего не значит.
        listSeen: s.listSeen,
        total: s.total,
        items: friends ? s.friendOrder.map(function (id) { return s.friends[id]; }) : groupItems(s),
        requests: friends ? s.requestOrder.filter(function (id) { return !s.friends[id]; }).map(function (id) { return s.requests[id]; }) : [],
        suggestions: friends ? s.suggestOrder.filter(function (id) { return !s.friends[id] && !s.requests[id]; }).map(function (id) { return s.suggestions[id]; }) : []
      };
    }

    ResponseTap.listenApi(function () { return !!kind(); }, METHODS, onCall, channel.schedule);

    // Друзья запросом от имени страницы. По собранным данным страниц: m.vk.ru отдаёт список друзей уже в вёрстке, а
    // friends.get не вызывает (только users.get за фото) — своему экрану было не из чего строить список, и он через
    // 12 с показывал страницу VK. Ответ проходит через общий перехват и разбирается, как ответ страницы.
    var API_FRIENDS_PAGE = 500;
    var FRIEND_FIELDS = 'photo_100,photo_50,online,sex,last_seen,city,occupation';

    /** Страница ещё не обращалась к API (данных входа нет) — пробуем снова, пока список не придёт сам или запросом. */
    function friendsWhenReady(s, attempt) {
      if (state !== s || s.listSeen || attempt > 8) return;
      if (!friendsByApi(s)) setTimeout(function () { friendsWhenReady(s, attempt + 1); }, 800);
    }

    function friendsByApi(s) {
      if (s.apiBusy || !NetTap.apiReady || !NetTap.apiReady()) return false;
      var owner = queryParam('id');
      var params = { order: 'hints', count: API_FRIENDS_PAGE, offset: s.apiOffset, fields: FRIEND_FIELDS };
      if (owner) params.user_id = owner;
      s.apiBusy = true;
      NetTap.api('friends.get', params, function (error, r) {
        s.apiBusy = false;
        if (error) { Diag.warn('друзья запросом не пришли: ' + error); return; }
        s.apiOffset += r && Array.isArray(r.items) ? r.items.length : 0;
        Diag.debug('друзья запросом: получено=' + (r && Array.isArray(r.items) ? r.items.length : 0) + ', всего=' + (r && num(r.count)));
      });
      // Заявки в друзья — один раз и только в своём списке.
      if (!owner && !s.requestsAsked) {
        s.requestsAsked = true;
        NetTap.api('friends.getRequests', { extended: 1, count: 100, fields: FRIEND_FIELDS }, function () { /* разбор — перехватом */ });
      }
      return true;
    }

    return {
      /**
       * Другие пути той же страницы сообщества (для перехода нажатием ссылки самой страницы): «/club5» в списке VK
       * может быть ссылкой «/короткое_имя», «/public5» или «/event5».
       */
      aliases: function (path) {
        var m = /^\/(club|public|event)(\d+)$/.exec(String(path || ''));
        if (!m) return [];
        var id = m[2];
        var list = ['/club' + id, '/public' + id, '/event' + id];
        var g = state && state.groups ? state.groups[id] : null;
        var name = g && typeof g.screen_name === 'string' ? g.screen_name : '';
        if (/^[A-Za-z0-9_.]{2,64}$/.test(name)) list.push('/' + name);
        return list;
      },
      /** Команды своих экранов: 'more' — догрузить, 'sync' — прислать список заново. */
      command: function (action) {
        if (!kind()) return false;
        var s = current();
        if (action === 'more') {
          // Список пришёл запросом — продолжение тоже запросом; иначе — прокруткой страницы, как раньше.
          if (s.kind === 'friends' && s.apiOffset > 0 && s.hasMore) return friendsByApi(s);
          return PageList.loadMore();
        }
        if (action === 'sync') {
          if (s.kind === 'friends' && !s.listSeen) friendsWhenReady(s, 0);
          channel.resend();
          return true;
        }
        return false;
      }
    };
  })();
