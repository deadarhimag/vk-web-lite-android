// Часть assets/vk_lite/: Лента клипов для своего проигрывателя (ClipsActivity, 3.76).
// Выполняется внутри общей функции из 00_core.js (см. web/PageScripts.kt), отдельно не запускается.
// Использует объявленное в 40_screens.js (ResponseTap, Diag, postToApp, VkApi) и 45_video.js (isSeekPage).

  // Отчёт 3.74: в ленте клипов страницы был звук, но не было картинки, и ролики не листались. Лента приходит одним
  // запросом (shortVideo.getRecom — по 10–13 роликов, у каждого прямые адреса mp4 и HLS, автор, описание, счётчики):
  // приложение показывает её своим проигрывателем, а страница при этом молчит (её ролики ставятся на паузу).
  var Clips = (function () {
    var MAX_CLIPS = 300;
    var MP4_ORDER = ['mp4_720', 'mp4_480', 'mp4_1080', 'mp4_360', 'mp4_240', 'mp4_144'];

    var items = [];
    var anchor = '';
    var lastParams = null;
    /** Каким запросом пришла лента — им же и продолжение (4.41: у «Подписок» свой). */
    var lastMethod = 'shortVideo.getRecom';
    var loading = false;
    var silenced = false;

    // Серверы видео — общий список (shared/net_tap.js, 4.65).
    function httpsVideo(u) { return NetTap.onHost(u, 'video') ? u : ''; }

    // Кадр-обложка по высоте — общий помощник bestImage (00_core.js, 4.66).
    function cover(list) { return bestImage(list, 1280, 'height'); }

    function names(r) {
      var map = {};
      (Array.isArray(r.profiles) ? r.profiles : []).forEach(function (p) {
        if (p && typeof p.id === 'number') map[p.id] = { name: decodeEntities(((p.first_name || '') + ' ' + (p.last_name || '')).trim()), photo: p.photo_100 || p.photo_50 || '' };
      });
      (Array.isArray(r.groups) ? r.groups : []).forEach(function (g) {
        if (g && typeof g.id === 'number') map[-g.id] = { name: decodeEntities(g.name || ''), photo: g.photo_100 || g.photo_50 || '' };
      });
      return map;
    }

    function toClip(v, who) {
      if (!v || typeof v.owner_id !== 'number' || typeof v.id !== 'number') return null;
      var files = v.files && typeof v.files === 'object' ? v.files : {};
      var mp4 = '';
      for (var i = 0; i < MP4_ORDER.length && !mp4; i++) mp4 = httpsVideo(files[MP4_ORDER[i]]);
      var hls = httpsVideo(files.hls) || httpsVideo(files.hls_fmp4);
      if (!mp4 && !hls) return null;
      var author = who[v.owner_id] || { name: '', photo: '' };
      // 4.42: у ответа «Подписок» (getSubscriptionsShortVideos) другие поля — first_frames/covers вместо
      // first_frame/image, engagement вместо likes/comments, duration_seconds вместо duration. Без них у роликов
      // «Подписок» не было превью и счётчиков.
      var eng = v.engagement && typeof v.engagement === 'object' ? v.engagement : null;
      return {
        id: v.owner_id + '_' + v.id,
        mp4: mp4,
        hls: hls,
        cover: cover(v.first_frame) || cover(v.first_frames) || cover(v.image) || cover(v.covers),
        author: author.name,
        avatar: NetTap.https(author.photo),
        text: decodeEntities(String(v.description || '')).slice(0, 2000),
        likes: v.likes && typeof v.likes.count === 'number' ? v.likes.count
          : (eng && typeof eng.like_count === 'number' ? eng.like_count : 0),
        liked: !!(v.likes && v.likes.user_likes) || !!(eng && (eng.is_liked || eng.user_likes)),
        comments: typeof v.comments === 'number' ? v.comments
          : (eng && typeof eng.comment_count === 'number' ? eng.comment_count : 0),
        duration: typeof v.duration === 'number' ? v.duration
          : (typeof v.duration_seconds === 'number' ? v.duration_seconds : 0),
        width: v.width || 0,
        height: v.height || 0
      };
    }

    /**
     * 4.41: вкладка «Подписки» (/clips/subscriptions) — своим запросом, shortVideo.getSubscriptionsShortVideos, а
     * getRecom страница шлёт и там (заранее, для «Для вас»). Раньше перехватывался только getRecom: на «Подписках»
     * приложению уходила лента рекомендаций, и вкладка «не открывалась». Теперь на «Подписках» — только их ответ, на
     * остальных вкладках — только getRecom.
     */
    function isSubscriptionsPage() { return /^\/clips\/subscriptions(\/|$)/i.test(location.pathname); }

    function onRecom(method, params, body) {
      if ((method === 'shortVideo.getSubscriptionsShortVideos') !== isSubscriptionsPage()) return false;
      var r = body && body.response;
      var feed = r && r.feed;
      if (!feed || !Array.isArray(feed.items)) return false;
      var continuation = !!(params && (params.page_anchor || params.start_from || params.next_from));
      if (!continuation) { items = []; }
      lastParams = {};
      Object.keys(params || {}).forEach(function (k) {
        if (k !== 'access_token' && k !== 'v' && k !== 'page_anchor' && k !== 'start_from') lastParams[k] = params[k];
      });
      anchor = typeof feed.page_anchor === 'string' ? feed.page_anchor : '';
      lastMethod = method;
      var who = names(r);
      var known = {};
      items.forEach(function (c) { known[c.id] = true; });
      var added = 0;
      feed.items.forEach(function (it) {
        // Только обычные ролики: рекламные (short_video_mytarget_…) и витрины товаров — нет.
        if (!it || (it.type && !/^short_video_full/.test(it.type))) return;
        var c = toClip(it.item || it, who);
        if (c && !known[c.id] && items.length < MAX_CLIPS) { items.push(c); known[c.id] = true; added++; }
      });
      loading = false;
      if (added || !continuation) send(!continuation);
      return added > 0;
    }

    function send(reset) {
      postToApp({ type: 'clips', reset: !!reset, hasMore: !!anchor, items: items, tabs: tabsForApp() });
    }

    ResponseTap.listenApi(function () { return isSeekPage(); }, /^shortVideo\.(getRecom|getSubscriptionsShortVideos)$/, onRecom, function () {});

    // ---- вкладки раздела клипов (3.84): «общий список клипов с его фильтрами»
    // Страница клипов сама запрашивает вкладки (shortVideo.getHeaderTabs — 4 штуки) и рисует их над лентой. Свой экран
    // показывает их фильтрами; выбор вкладки — нажатие самой вкладки страницы: VK сам запрашивает её ленту
    // (shortVideo.getRecom с нужными параметрами), перехват присылает её приложению заново (reset).
    var headerTabs = 0;
    var headerKeys = [];
    // Ключи вкладок из shortVideo.getHeaderTabs (дамп 3.91: rec, shops, trends, subscriptions) и как они подписаны
    // на странице: если общий поиск ряда вкладок промахнулся, вкладка ищется по своей подписи.
    var TAB_TITLES = {
      rec: ['для вас', 'рекомендации', 'рекомендуем'],
      shops: ['покупки', 'магазин', 'магазины', 'товары', 'шопинг'],
      trends: ['тренды', 'в тренде', 'популярное'],
      subscriptions: ['подписки']
    };
    var tabsLogged = false;
    var TAB_TEXT_MAX = 30;

    function textOf(el) { return String(el.textContent || '').replace(/\s+/g, ' ').trim(); }

    function clickable(el) {
      var tag = el.tagName;
      return tag === 'A' || tag === 'BUTTON' || el.getAttribute('role') === 'tab' || el.hasAttribute('tabindex') || typeof el.onclick === 'function';
    }

    function isSelected(el) {
      if (el.getAttribute('aria-selected') === 'true' || el.getAttribute('aria-current')) return true;
      return /(^|[\s_-])(active|selected|current)|--(active|selected|current)|(Active|Selected|Current)\b/.test(String(el.className && el.className.baseVal !== undefined ? el.className.baseVal : el.className || ''));
    }

    /** Отмечена ли вкладка: сама или её обёртка (подпись по подписи — span внутри кнопки). */
    function selectedUp(el) {
      for (var up = el, n = 0; up && n < 3; up = up.parentElement, n++) if (isSelected(up)) return true;
      return false;
    }

    // Подписи пунктов меню самого сайта VK (4.12). Отчёт: над клипами фильтрами стояло меню сайта («Главная»,
    // «Мессенджер», «Клипы»…) — общий поиск ряда вкладок брал первый попавшийся ряд, а меню сайта тоже ряд коротких
    // нажимаемых подписей (и бывает с role="tab"). Такой ряд — не вкладки раздела.
    var SITE_NAV_LABELS = ['главная', 'новости', 'лента', 'мессенджер', 'сообщения', 'звонки', 'клипы', 'видео', 'музыка',
      'друзья', 'сообщества', 'группы', 'сервисы', 'уведомления', 'профиль', 'меню', 'игры', 'фото', 'фотографии', 'ещё',
      'еще', 'поиск', 'вконтакте', 'vk'];
    var CLIP_TAB_LABELS = Object.keys(TAB_TITLES).reduce(function (all, k) { return all.concat(TAB_TITLES[k]); }, []);

    /** Сколько рядов-меню сайта отброшено при последнем поиске (для журнала). */
    var navRejected = 0;

    function labelsOf(group) { return group.map(function (el) { return textOf(el).toLowerCase(); }); }

    /** Сколько подписей ряда — известные вкладки клипов («Для вас», «Подписки»…). */
    function clipScore(group) {
      return labelsOf(group).filter(function (l) { return CLIP_TAB_LABELS.indexOf(l) !== -1; }).length;
    }

    /** Ряд — меню сайта: два и больше пунктов меню VK или ряд в навигации без единой вкладки клипов. */
    function isSiteNav(group) {
      var navHits = labelsOf(group).filter(function (l) { return SITE_NAV_LABELS.indexOf(l) !== -1; }).length;
      if (navHits >= 2) return true;
      var inNav = group.some(function (el) { return el.closest && el.closest('nav, [role="navigation"]'); });
      return inNav && clipScore(group) === 0;
    }

    /** Ряды [role="tab"]: у каждого списка вкладок свой (раньше все вкладки документа шли одним рядом). */
    function roleTabGroups() {
      var groups = [];
      var roleTabs = document.querySelectorAll('[role="tab"]');
      for (var i = 0; i < roleTabs.length && i < 100; i++) {
        var el = roleTabs[i];
        var owner = (el.closest && el.closest('[role="tablist"]')) || el.parentElement;
        var g = null;
        for (var j = 0; j < groups.length; j++) if (groups[j].owner === owner) { g = groups[j]; break; }
        if (!g) { g = { owner: owner, items: [] }; groups.push(g); }
        if (g.items.length < 8) g.items.push(el);
      }
      return groups.filter(function (g) { return g.items.length >= 2; }).map(function (g) { return g.items; });
    }

    /** Ряды коротких нажимаемых подписей с «tab» в классе — у каждого родителя свой. */
    function classTabGroups() {
      var byParent = [];
      var cands = document.querySelectorAll('[class*="Tab"], [class*="tab"]');
      for (var i = 0; i < cands.length && i < 400; i++) {
        var el = cands[i];
        var label = textOf(el);
        if (!label || label.length > TAB_TEXT_MAX || !clickable(el)) continue;
        var parent = el.parentElement;
        var g = null;
        for (var j = 0; j < byParent.length; j++) if (byParent[j].parent === parent) { g = byParent[j]; break; }
        if (!g) { g = { parent: parent, items: [] }; byParent.push(g); }
        if (g.items.length < 8) g.items.push(el);
      }
      return byParent.filter(function (g) { return g.items.length >= 2; }).map(function (g) { return g.items; });
    }

    /**
     * Вкладки раздела: [{el, label, selected}]. Кандидаты — ряды role="tab", ряды с «tab» в классе и подписи ключей
     * getHeaderTabs; меню сайта отбрасывается ([isSiteNav]); из оставшихся — ряд, больше всего похожий на вкладки
     * клипов. Ничего похожего — вкладок нет (лучше без фильтров, чем с меню сайта).
     */
    function findTabs() {
      var groups = roleTabGroups();
      if (!groups.length) groups = classTabGroups();
      if (headerKeys.length) {
        var byTitle = tabsByTitle();
        if (byTitle.length >= 2) groups.push(byTitle);
      }
      var best = null;
      var bestScore = -1;
      navRejected = 0;
      groups.forEach(function (g) {
        if (isSiteNav(g)) { navRejected++; return; }
        var score = clipScore(g);
        if (score > bestScore) { best = g; bestScore = score; }
      });
      if (!best) return [];
      return best.map(function (el) { return { el: el, label: textOf(el).slice(0, TAB_TEXT_MAX), selected: selectedUp(el) }; })
        .filter(function (t) { return t.label; });
    }

    /**
     * Вкладки по подписям известных ключей: самый глубокий элемент с ровно такой подписью. Нажатие всплывает к
     * обработчику страницы на предке, а нажатие предка до обработчика на потомке не дошло бы.
     */
    function tabsByTitle() {
      var found = [];
      var all = document.body ? document.body.getElementsByTagName('*') : [];
      headerKeys.forEach(function (key) {
        var titles = TAB_TITLES[key];
        if (!titles) return;
        var hit = null;
        for (var i = 0; i < all.length && i < 5000; i++) {
          var el = all[i];
          if (titles.indexOf(textOf(el).toLowerCase()) === -1) continue;
          // Следующее совпадение внутри прежнего — глубже, берём его; вне — прежнее уже найдено.
          if (!hit || hit.contains(el)) hit = el; else break;
        }
        if (hit && found.indexOf(hit) === -1) found.push(hit);
      });
      return found;
    }

    function tabsForApp() {
      var tabs = [];
      try { tabs = findTabs(); } catch (e) { tabs = []; }
      if (!tabsLogged && (tabs.length || headerTabs)) {
        tabsLogged = true;
        if (tabs.length) {
          Diag.event('info', 'клипы: вкладки страницы — ' + tabs.map(function (t) { return t.label + (t.selected ? '*' : ''); }).join(' | ') + ' (у VK: ' + headerKeys.join(',') + (navRejected ? '; меню сайта отброшено: ' + navRejected : '') + ')');
        } else {
          var sample = [];
          var any = document.querySelectorAll('[class*="Tab"], [class*="tab"]');
          for (var i = 0; i < any.length && sample.length < 5; i++) sample.push(any[i].tagName.toLowerCase() + '.' + String(any[i].className).split(/\s+/)[0].slice(0, 40) + '«' + textOf(any[i]).slice(0, 20) + '»');
          Diag.warn('клипы: вкладок на странице не нашлось (у VK: ' + headerTabs + '; меню сайта отброшено: ' + navRejected + '); role=tab ' + document.querySelectorAll('[role="tab"]').length + ', элементы с tab: ' + (sample.join(' | ') || 'нет'));
        }
      }
      return tabs.map(function (t) { return { label: t.label, selected: t.selected }; });
    }

    ResponseTap.listenApi(function () { return isSeekPage(); }, /^shortVideo\.getHeaderTabs$/, function (method, params, body) {
      var r = body && body.response;
      headerTabs = r && Array.isArray(r.tabs) ? r.tabs.length : 0;
      headerKeys = (r && Array.isArray(r.tabs) ? r.tabs : []).map(function (t) { return t && typeof t.key === 'string' ? t.key : ''; }).filter(Boolean).slice(0, 8);
      // Вкладки рисуются после ответа: прислать их, когда появятся в вёрстке.
      setTimeout(function () { if (items.length) send(false); }, 1200);
      return false;
    }, function () {});

    /** Нажать вкладку [index] страницы. */
    function pickTab(index) {
      var tabs = findTabs();
      var t = tabs[+index];
      if (!t) return false;
      try { t.el.click(); } catch (e) { return false; }
      Diag.event('info', 'клипы: выбрана вкладка «' + t.label + '»');
      // Подсветка выбранной на своём экране — сразу, лента придёт ответом страницы.
      setTimeout(function () { send(false); }, 300);
      return true;
    }

    /** Ролики страницы молчат, пока клипы показывает приложение (иначе звук шёл бы дважды). */
    // Ролики, которым звук выключили мы (код ревью 3.81): при «release» звук возвращается только им. Раньше они оставались
    // без звука — открытая из проигрывателя страница клипа (комментарии) молчала.
    var mutedByUs = [];

    function hush(v) {
      try {
        if (!v.muted) { v.muted = true; if (mutedByUs.indexOf(v) === -1) mutedByUs.push(v); }
        if (!v.paused) v.pause();
      } catch (e) { /* чужой ролик */ }
    }

    function silencePage() {
      var list = document.querySelectorAll('video');
      for (var i = 0; i < list.length; i++) hush(list[i]);
    }

    function releasePage() {
      mutedByUs.forEach(function (v) { try { v.muted = false; } catch (e) { /* ролика уже нет */ } });
      mutedByUs = [];
    }

    document.addEventListener('play', function (e) {
      if (silenced && e.target && e.target.tagName === 'VIDEO') hush(e.target);
    }, true);

    /**
     * 4.0: «Нравится» из своего проигрывателя клипов — от имени страницы (как лайк записи в 70_owner.js). Клип VK —
     * видео (type short_video), поэтому сначала тип «video»; не принят — «clip». Итог — {type:"clipLike"}. [value] —
     * «владелец_номер:1» (поставить) или «…:0» (снять).
     */
    function likeClip(value) {
      var m = /^(-?\d+)_(\d+):([01])$/.exec(String(value || ''));
      if (!m || !VkApi.ready()) return false;
      var on = m[3] === '1';
      var id = m[1] + '_' + m[2];
      var reply = function (ok, count, reason) {
        // Своя лента знает о лайке: при следующей отправке ленты отметка не откатится.
        items.forEach(function (c) { if (c.id === id && ok) { c.liked = on; if (typeof count === 'number') c.likes = count; } });
        postToApp({ type: 'clipLike', id: id, ok: ok, liked: ok ? on : !on, likes: typeof count === 'number' ? count : -1, reason: reason || '' });
      };
      var attempt = function (types) {
        VkApi.call(on ? 'likes.add' : 'likes.delete', { type: types[0], owner_id: m[1], item_id: m[2] }, function (error, r) {
          if (error && types.length > 1) { attempt(types.slice(1)); return; }
          if (error) { Diag.warn('клипы: отметка «нравится» не принята: ' + String(error).slice(0, 100)); reply(false, null, String(error).slice(0, 120)); return; }
          reply(true, r && typeof r.likes === 'number' ? r.likes : null);
        });
      };
      attempt(['video', 'clip']);
      return true;
    }

    function command(action, value) {
      if (action === 'sync') { if (items.length) send(true); return items.length > 0; }
      if (action === 'tab') return pickTab(value);
      if (action === 'silence') { silenced = true; silencePage(); return true; }
      if (action === 'release') { silenced = false; releasePage(); return true; }
      if (action === 'like') return likeClip(value);
      if (action === 'more') {
        if (loading || !anchor || !lastParams || !VkApi.ready()) return false;
        loading = true;
        var params = {};
        Object.keys(lastParams).forEach(function (k) { params[k] = lastParams[k]; });
        params.page_anchor = anchor;
        // Ответ разберёт перехват (onRecom) — как ответ самой страницы.
        VkApi.call(lastMethod, params, function (error) {
          // Ответ без ленты (или его не разобрал перехват — ушли со страницы клипов) не должен оставить «загружается»
          // навсегда: иначе продолжение больше не просилось бы (код ревью 3.81).
          loading = false;
          if (error) Diag.warn('клипы: продолжение ленты не получено: ' + String(error).slice(0, 100));
        });
        return true;
      }
      return false;
    }

    return {
      command: command,
      /** Клипы показывает приложение: ролики страницы не запускать (45_video.js не «помогает» им заиграть). */
      isSilenced: function () { return silenced; }
    };
  })();
