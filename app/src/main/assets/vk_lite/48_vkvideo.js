// Часть assets/vk_lite/: Каталог VK Видео для своего экрана (overlay/VideoOverlay.kt, 3.78).
// Выполняется внутри общей функции из 00_core.js (см. web/PageScripts.kt), отдельно не запускается.
// Использует объявленное в 40_screens.js (ResponseTap, createChannel, PageList, decodeEntities).

  // Главная VK Видео и её подборки (m.vkvideo.ru/, /trends, /for_kids…): ролики приходят ответами страницы
  // (catalog.getVideo, catalog.getSection, catalog.getBlockItems — дамп 3.75: у каждого ролика адреса файлов mp4/HLS,
  // превью, длительность, просмотры; авторы — в groups/profiles). Свой экран показывает их списком, ролик играет
  // проигрыватель приложения.
  var VkVideo = (function () {
    var NOTIFY_MS = 250;
    var MAX_VIDEOS = 300;
    var METHODS = /^catalog\.(getVideo|getSection|getBlockItems)$/;
    var MP4_ORDER = ['mp4_720', 'mp4_480', 'mp4_1080', 'mp4_360', 'mp4_240', 'mp4_144'];

    var state = null;
    var channel = createChannel(NOTIFY_MS, buildPayload);

    // ---- разделы каталога (3.85): «Для вас», «Тренды», «Фильмы»… — фильтры своего экрана
    // catalog.getVideo присылает весь список разделов (catalog.sections: title, url, у некоторых — subsections). Он
    // общий для всех страниц каталога: запоминается один раз и обновляется с каждым ответом.
    var nav = [];            // [{id, title, path, subs:[{id, title, path}]}]
    var defaultSection = '';

    /** Путь раздела на этом же сайте (другие поддомены — vksport — переходом внутри страницы не открыть). */
    function sectionPath(url) {
      try {
        var u = new URL(String(url || ''), location.href);
        if (u.protocol !== 'https:' || u.host !== location.host) return '';
        return u.pathname.replace(/\/+$/, '') || '/';
      } catch (e) { return ''; }
    }

    function rememberNav(cat) {
      if (!cat || !Array.isArray(cat.sections)) return;
      var list = [];
      cat.sections.forEach(function (sec) {
        if (!sec || typeof sec.title !== 'string' || list.length >= 20) return;
        var path = sectionPath(sec.url);
        if (!path) return;
        var subs = [];
        (Array.isArray(sec.subsections) ? sec.subsections : []).forEach(function (sub) {
          var p = sub && typeof sub.title === 'string' ? sectionPath(sub.url) : '';
          if (p && subs.length < 12) subs.push({ id: String(sub.id || ''), title: decodeEntities(sub.title).slice(0, 40), path: p });
        });
        list.push({ id: String(sec.id || ''), title: decodeEntities(sec.title).slice(0, 40), path: path, subs: subs });
      });
      if (list.length >= 2) nav = list;
      if (typeof cat.default_section === 'string') defaultSection = cat.default_section;
    }

    /** Разделы для приложения с отметкой открытого (на главной «/» — раздел по умолчанию, «Для вас»). */
    function navForApp(key) {
      return nav.map(function (sec) {
        var subSelected = '';
        sec.subs.forEach(function (sub) { if (sub.path === key) subSelected = sub.path; });
        var selected = sec.path === key || !!subSelected || (key === '/' && sec.id === defaultSection);
        return {
          title: sec.title, path: sec.path, url: location.origin + sec.path, selected: selected,
          // Открыт сам раздел («/politics») — отмечен его подраздел с тем же адресом («Все»).
          subs: sec.subs.map(function (sub) { return { title: sub.title, path: sub.path, url: location.origin + sub.path, selected: sub.path === key || (!subSelected && selected && sub.path === sec.path) }; })
        };
      });
    }

    function isVideoSite() { return /(^|\.)vkvideo\.ru$/i.test(location.hostname); }

    /** Главная и подборки VK Видео: одно-два слова в пути (или ничего: «/politics/media»), не ролик, канал, плейлист. */
    function isCatalogPage() {
      return isVideoSite() && /^\/([a-z_]{2,40}(\/[a-z_]{2,40})?\/?)?$/i.test(location.pathname) &&
        !/^\/(video|clip|clips|playlist|playlists|live|search|settings|audio)(\/|$)/i.test(location.pathname);
    }


    function current() {
      var key = pathKey();
      if (!state || state.key !== key) {
        state = { key: key, videos: {}, order: [], who: {}, hasMore: false, seen: false };
        channel.reset();
      }
      return state;
    }

    // Серверы видео — общий список (shared/net_tap.js, 4.65).
    function fileUrl(u) { return NetTap.onHost(u, 'video') ? u : ''; }


    function rememberWho(s, r) {
      (Array.isArray(r.profiles) ? r.profiles : []).forEach(function (p) {
        if (p && typeof p.id === 'number') s.who[p.id] = { name: decodeEntities(((p.first_name || '') + ' ' + (p.last_name || '')).trim()), photo: p.photo_100 || p.photo_50 || '' };
      });
      (Array.isArray(r.groups) ? r.groups : []).forEach(function (g) {
        if (g && typeof g.id === 'number') s.who[-g.id] = { name: decodeEntities(g.name || ''), photo: g.photo_100 || g.photo_50 || '' };
      });
    }

    function onCall(method, params, body) {
      if (!isCatalogPage()) return false;
      var r = body && body.response;
      if (!r || typeof r !== 'object') return false;
      var s = current();
      rememberWho(s, r);
      s.seen = true;
      var added = 0;
      (Array.isArray(r.videos) ? r.videos : []).forEach(function (v) {
        if (!v || typeof v.owner_id !== 'number' || typeof v.id !== 'number' || v.type === 'short_video') return;
        // Рекламный ролик — is_ads. Поле ads — настройки рекламы ВНУТРИ обычного ролика (преролл, мидролл): оно есть почти
        // у всех, и прежняя проверка «ads» отбрасывала почти весь каталог (отчёт 3.80: 2–22 ролика вместо сотен).
        if (v.is_ads === 1 || v.is_ads === true) return;
        var key = v.owner_id + '_' + v.id;
        if (!s.videos[key]) {
          if (s.order.length >= MAX_VIDEOS) return;
          s.order.push(key);
          added++;
        }
        s.videos[key] = v;
      });
      // Продолжение: у раздела или блока есть next_from.
      var more = false;
      rememberNav(r.catalog);
      var cat = r.catalog || r.section || r.block;
      JSON.stringify(cat || {}, function (k, val) { if (k === 'next_from' && val) more = true; return val; });
      if (cat || added) s.hasMore = more;
      return true;
    }

    function toItem(s, v) {
      var files = v.files && typeof v.files === 'object' ? v.files : {};
      var mp4 = '';
      for (var i = 0; i < MP4_ORDER.length && !mp4; i++) mp4 = fileUrl(files[MP4_ORDER[i]]);
      var author = s.who[v.owner_id] || { name: '', photo: '' };
      return {
        id: v.owner_id + '_' + v.id,
        title: decodeEntities(String(v.title || '')).slice(0, 300),
        cover: bestImage(v.image, 800, 'width') || bestImage(v.first_frame, 800, 'width'),
        duration: typeof v.duration === 'number' ? v.duration : 0,
        views: typeof v.views === 'number' ? v.views : 0,
        date: typeof v.date === 'number' ? v.date : 0,
        live: v.live === 1 || v.live === true,
        author: author.name,
        avatar: NetTap.https(author.photo),
        mp4: mp4,
        hls: fileUrl(files.hls) || fileUrl(files.hls_fmp4),
        player: fileUrl(v.player)
      };
    }

    function buildPayload() {
      if (!isCatalogPage()) return null;
      var s = current();
      if (!s.seen) return null;
      return { type: 'vkvideo', key: s.key, hasMore: s.hasMore, sections: navForApp(s.key), items: s.order.map(function (k) { return toItem(s, s.videos[k]); }) };
    }

    ResponseTap.listenApi(isCatalogPage, METHODS, onCall, function () { channel.schedule(); });

    /**
     * Все качества файла ролика [id] (3.86) — по запросу при открытии: в самом каталоге их нет, иначе сообщение со
     * списком в сотни роликов выросло бы до мегабайт. Итог — {type:"videofiles", id, files:{"720": url…}, hls}.
     */
    function postFiles(id) {
      var s = state;
      var v = s && s.videos[String(id || '')];
      if (!v) return false;
      var files = v.files && typeof v.files === 'object' ? v.files : {};
      var qualities = {};
      Object.keys(files).forEach(function (k) {
        var m = /^mp4_(\d{3,4})$/.exec(k);
        var u = m ? fileUrl(files[k]) : '';
        if (u) qualities[m[1]] = u;
      });
      postToApp({ type: 'videofiles', id: String(id), files: qualities, hls: fileUrl(files.hls) || fileUrl(files.hls_fmp4) });
      return true;
    }

    return {
      isCatalogPage: isCatalogPage,
      /** Команды своего экрана: 'more' — догрузить (прокрутка страницы), 'sync' — прислать заново. */
      command: function (action, value) {
        if (!isCatalogPage()) return false;
        if (action === 'more') { PageList.scrollToEnd(); return true; }
        if (action === 'sync') { channel.resend(); return true; }
        if (action === 'files') return postFiles(value);
        return false;
      }
    };
  })();
