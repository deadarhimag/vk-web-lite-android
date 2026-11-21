/*
 * VK Reader: читает данные со страниц сообщений VK и отдаёт их приложению
 * структурированно (JSON), чтобы приложение показало их своим интерфейсом.
 *
 * Внедряется ТОЛЬКО в окна чтения сообщений (экран сообщений и фоновая проверка) и
 * только когда включён свой интерфейс сообщений (по умолчанию включён, настройка
 * native_messages). Ничего никуда не отправляет, кроме как
 * приложению через свой мост. Селекторы приходят в CONFIG и подобраны
 * предположительно: реальную вёрстку VK правьте в ReaderConfig.kt.
 *
 * Источники данных, по надёжности:
 *   1. Ответы API, которые мессенджер VK сам запрашивает (/method/..., fetch и XHR),
 *      данные, вшитые в страницу (window.cur.apiPrefetchCache), и JSON из WebSocket.
 *      Структура ответа распознаётся по форме объектов, а не по имени метода, поэтому
 *      переживает пакетные вызовы и переименования. Список сообщений в DOM виртуализирован,
 *      а в ответах приходят все сообщения порции — отсюда полная история.
 *   2. Вёрстка страницы — запасной путь, если данных API нет; и для новых сообщений,
 *      которые мессенджер получил без отдельного запроса (long poll).
 * Страница сама ничего не отправляет наружу: только читаем то, что она и так загрузила.
 *
 * Контракт (страница → приложение), одно сообщение — один JSON:
 *   { type:"dialogs", items:[ {peerId,title,snippet,time,unread,unreadCount,online,avatar,canWrite} ] }
 *   { type:"history", peerId, title, items:[ {id,out,text,time,date,sender,photos:[{url,width,height,id}…],videos:[{id,title,cover,duration,key}…],cards:[{kind,title,subtitle,url|path}…],voices:[{id,duration,url}…],forwards:[{sender,time,depth,text,photos,videos,cards,voices}…]} ] }
 *   (размеры фото — чтобы место под картинку резервировалось сразу и список не прыгал при загрузке)
 *   { type:"unavailable", reason:"login" | "empty" | "unknown" }
 *   { type:"sent", ok:true|false }
 *   { type:"forwarded", ok, target, reason }   { type:"video", id, ok, mp4, hls, player, title, live, reason }
 *   карточка звонка: {kind:"call", title, subtitle, url?} (url — «присоединиться» к групповому звонку)
 *
 * Команды (приложение → страница): window.__vkReader.scrape(kind), .send(text), .loadOlder(), .stage(...) и .attachAndSend(text) для вложений, .sendVoice(peer, randomId) — голосовое (4.59),
 * .playAudio(id, title) и .audio('toggle' | 'stop') — музыка из сообщения, .forward(target, cmids) — переслать,
 * .me() — свой номер, .video(id, key) — файлы видео или клипа для проигрывателя приложения, .audioUrl(id, key, title) —
 * адрес звука трека из сообщения ({type:"audiourl", id, ok, url, title, duration, how, reason}): его играет приложение.
 */
(function () {
  'use strict';

  if (window.top !== window) return;
  if (window.__vkReader) return;

  var CONFIG = /*CONFIG*/null;
  if (!CONFIG) return;

  // ------------------------------------------------------------ утилиты

  function post(obj) {
    var bridge = window[CONFIG.bridge];
    if (bridge && typeof bridge.postMessage === 'function') bridge.postMessage(JSON.stringify(obj));
  }

  function pick(root, selector) {
    if (!selector) return null;
    // Селекторы разделены запятыми, но querySelector и так берёт первый совпавший.
    try { return root.querySelector(selector); } catch (e) { return null; }
  }

  function pickAll(root, selector) {
    if (!selector) return [];
    try { return Array.prototype.slice.call(root.querySelectorAll(selector)); } catch (e) { return []; }
  }

  function text(el) {
    return el ? (el.textContent || '').replace(/\s+/g, ' ').trim() : '';
  }

  function loggedOut() {
    // На странице входа нет ленты диалогов, зато есть форма логина.
    return !!pick(document, CONFIG.loginMarker);
  }

  /** Достаёт peer id из ссылки reforged (/mail/convo/12345) или старых схем. */
  function peerFromHref(href) {
    if (!href) return null;
    var m = href.match(/\/mail\/convo\/(-?\d+)/) || href.match(/[?&](?:peer|sel|id)=(-?\d+)/);
    return m ? m[1] : null;
  }

  // ------------------------------------------------------------ время и текст

  function pad(n) { return ('0' + n).slice(-2); }

  /** Время для списка диалогов: сегодня — часы, иначе дата. */
  function formatTime(unix) {
    if (!unix) return '';
    var d = new Date(unix * 1000);
    if (d.toDateString() === new Date().toDateString()) return pad(d.getHours()) + ':' + pad(d.getMinutes());
    return pad(d.getDate()) + '.' + pad(d.getMonth() + 1);
  }

  /** Время под сообщением: сегодня — часы, иначе дата и часы. */
  function formatMessageTime(unix) {
    if (!unix) return '';
    var d = new Date(unix * 1000);
    var hm = pad(d.getHours()) + ':' + pad(d.getMinutes());
    if (d.toDateString() === new Date().toDateString()) return hm;
    return pad(d.getDate()) + '.' + pad(d.getMonth() + 1) + ' ' + hm;
  }

  function codePoint(n) {
    try { return String.fromCodePoint(n); } catch (e) { return ''; }
  }

  /** API отдаёт текст с HTML-сущностями (&#128071; &quot;) и <br>. */
  function decodeText(value) {
    return String(value || '')
      .replace(/<br\s*\/?>/gi, '\n')
      .replace(/&#(\d+);/g, function (m, n) { return codePoint(+n); })
      .replace(/&#x([0-9a-f]+);/gi, function (m, h) { return codePoint(parseInt(h, 16)); })
      .replace(/&quot;/g, '"').replace(/&lt;/g, '<').replace(/&gt;/g, '>').replace(/&amp;/g, '&')
      .trim();
  }

  var ATTACHMENT_LABELS = {
    photo: 'Фото', video: 'Видео', audio: 'Аудио', doc: 'Файл', sticker: 'Стикер',
    audio_message: 'Голосовое сообщение', call: 'Звонок', link: 'Ссылка', wall: 'Запись',
    wall_reply: 'Комментарий', gift: 'Подарок', graffiti: 'Граффити', poll: 'Опрос',
    market: 'Товар', story: 'История', geo: 'Место', money_transfer: 'Перевод', article: 'Статья',
    video_message: 'Видеосообщение', clip: 'Клип'
  };

  /** Фото в исходном разрешении: orig_photo, иначе самый большой из sizes. {url, width, height} или null. */
  function photoOriginal(photo) {
    if (!photo || typeof photo !== 'object') return null;
    var orig = photo.orig_photo;
    if (orig && typeof orig.url === 'string') return { url: orig.url, width: orig.width || 0, height: orig.height || 0 };
    var best = null, bestArea = -1;
    (Array.isArray(photo.sizes) ? photo.sizes : []).forEach(function (size) {
      if (!size || typeof size.url !== 'string') return;
      var area = (size.width || 0) * (size.height || 0);
      if (area > bestArea) { bestArea = area; best = { url: size.url, width: size.width || 0, height: size.height || 0 }; }
    });
    return best;
  }

  var MAX_PHOTOS = 10;

  /**
   * Стикер или граффити — картинкой (раньше подписью «[Стикер]»): картинка стикера около 256 пикселей (с фоном —
   * для тёмной темы не нужен), граффити — его url. {url, width, height, sticker:true} или null.
   */
  function stickerImage(a) {
    if (a.type === 'sticker' && a.sticker && Array.isArray(a.sticker.images)) {
      var best = null;
      a.sticker.images.forEach(function (s) {
        if (!s || !httpsUrl(s.url)) return;
        if (!best || Math.abs((s.width || 0) - 256) < Math.abs((best.width || 0) - 256)) best = s;
      });
      return best ? { url: best.url, width: best.width || 0, height: best.height || 0, sticker: true } : null;
    }
    if (a.type === 'graffiti' && a.graffiti && httpsUrl(a.graffiti.url)) {
      return { url: a.graffiti.url, width: a.graffiti.width || 0, height: a.graffiti.height || 0, sticker: true };
    }
    return null;
  }

  function messagePhotos(m) {
    var photos = [];
    (Array.isArray(m.attachments) ? m.attachments : []).forEach(function (a) {
      if (a && (a.type === 'sticker' || a.type === 'graffiti') && photos.length < MAX_PHOTOS) {
        var image = stickerImage(a);
        if (image) photos.push(image);
        return;
      }
      if (a && a.type === 'photo' && photos.length < MAX_PHOTOS) {
        var original = photoOriginal(a.photo);
        if (original) {
          // Номер фото в VK («владелец_номер»): по нему приложение узнаёт то же фото в разных ответах — адреса у них разные
          // (подпись, размер, сервер), и сообщение иначе считалось бы изменённым при каждом обновлении.
          if (a.photo && isNum(a.photo.id) && isNum(a.photo.owner_id)) original.id = a.photo.owner_id + '_' + a.photo.id;
          photos.push(original);
        }
      }
    });
    return photos;
  }

  var MAX_VIDEOS = 5;

  /** Обложка видео: наименьшая из картинок шириной от 480, иначе самая крупная (оригинал в тысячу пикселей для карточки лишний). */
  function videoCover(video) {
    var all = [];
    ['image', 'first_frame', 'thumb'].forEach(function (key) {
      (Array.isArray(video[key]) ? video[key] : []).forEach(function (s) {
        if (s && typeof s.url === 'string') all.push({ url: s.url, width: s.width || 0 });
      });
    });
    // Старый вид обложек: photo_320, photo_640, photo_800, photo_1280.
    [320, 640, 800, 1280].forEach(function (w) {
      if (typeof video['photo_' + w] === 'string') all.push({ url: video['photo_' + w], width: w });
    });
    all.sort(function (a, b) { return a.width - b.width; });
    for (var i = 0; i < all.length; i++) if (all[i].width >= 480) return all[i].url;
    return all.length ? all[all.length - 1].url : '';
  }

  // ---- обложки, которых нет в ответах API: берём картинку из вёрстки мессенджера

  var noCoverLogged = {};
  var noCoverLogCount = 0;
  var domCovers = {};

  /** Один раз на ролик: какие поля прислал сервер вместо обложки (только имена и тип, без значений). */
  function noteNoCover(v, isClip) {
    var key = v.owner_id + '_' + v.id;
    if (noCoverLogged[key] || noCoverLogCount >= 8) return;
    noCoverLogged[key] = true;
    noCoverLogCount++;
    var names = Object.keys(v).slice(0, 40).join(',');
    post({ type: 'log', level: 'info', page: location.pathname.replace(/\d{3,}/g, '#'),
      message: (isClip ? 'клип' : 'видео') + ' во вложении без обложки в ответе API: тип=' + (typeof v.type === 'string' ? v.type : '-') + ', поля=' + names });
  }

  function cssUrl(value) {
    var m = /url\(["']?(https:[^"')]+)["']?\)/.exec(value || '');
    return m ? m[1] : '';
  }

  /** Первая картинка внутри элемента: <img> или фон (в стиле элемента или вычисленный). */
  function pictureIn(root) {
    var imgs = root.querySelectorAll('img');
    for (var i = 0; i < imgs.length; i++) {
      var src = imgs[i].currentSrc || imgs[i].src || '';
      if (src.indexOf('https://') === 0) return src;
    }
    var nodes = [root].concat(Array.prototype.slice.call(root.querySelectorAll('*'), 0, 40));
    for (var j = 0; j < nodes.length; j++) {
      var url = cssUrl(nodes[j].style && nodes[j].style.backgroundImage);
      if (!url) { try { url = cssUrl(window.getComputedStyle(nodes[j]).backgroundImage); } catch (e) { url = ''; } }
      if (url) return url;
    }
    return '';
  }

  /** Обложка ролика «владелец_номер» из вёрстки: ссылка на /video… или /clip… с картинкой внутри. Пусто — не нашли. */
  function domVideoCover(id) {
    if (domCovers[id]) return domCovers[id];
    var safe = String(id).replace(/[^0-9_-]/g, '');
    if (!safe) return '';
    var exactHref = new RegExp('/(video|clip)' + safe.replace(/-/g, '\\-') + '(?![0-9])');
    var links = Array.prototype.filter.call(document.querySelectorAll('a[href*="/video' + safe + '"],a[href*="/clip' + safe + '"]'),
      function (a) { return exactHref.test(a.getAttribute('href') || ''); }); // «-1_2» не совпадает с «-1_23»
    for (var i = 0; i < links.length; i++) {
      var url = pictureIn(links[i]);
      if (url) { domCovers[id] = url; return url; }
    }
    return '';
  }

  /** Видео во вложениях: {id «владелец_номер», title, cover, duration, key}; по ним сообщение показывает карточки и открывает видео. */
  function messageVideos(m) {
    var videos = [];
    (Array.isArray(m.attachments) ? m.attachments : []).forEach(function (a) {
      var isClip = !!a && a.type === 'clip';
      var v = a && (isClip ? a.clip : (a.type === 'video' && a.video));
      if (!v || !isNum(v.id) || !isNum(v.owner_id) || videos.length >= MAX_VIDEOS) return;
      var cover = videoCover(v);
      if (!cover) noteNoCover(v, isClip);
      // 3.72: у видео во вложении сообщения уже есть адреса файлов (files: mp4_*, hls) — дамп 3.70. Видео открывается
      // сразу по ним, без запроса video.get (тот остаётся запасным).
      if (v.files && typeof v.files === 'object') rememberVideoFiles(v.owner_id + '_' + v.id, v);
      videos.push({
        clip: isClip || v.type === 'short_video' || v.is_clip === 1 || v.is_clip === true,
        id: v.owner_id + '_' + v.id,
        title: decodeText(v.title || ''),
        cover: cover,
        duration: isNum(v.duration) ? v.duration : 0,
        key: typeof v.access_key === 'string' ? v.access_key : ''
      });
    });
    return videos;
  }

  var MAX_CARDS = 6;

  /** Размер файла словами: «320 КБ», «1.5 МБ». */
  function fileSize(bytes) {
    if (!isNum(bytes) || bytes <= 0) return '';
    if (bytes < 1024) return bytes + ' Б';
    if (bytes < 1048576) return Math.round(bytes / 1024) + ' КБ';
    return (Math.round(bytes / 104857.6) / 10) + ' МБ';
  }

  function httpsUrl(value) { return window.__vkNetTap.https(value); } // общая проверка (shared/net_tap.js, 4.76)

  function hostOf(url) {
    try { return new URL(url).hostname.replace(/^www\./, ''); } catch (e) { return ''; }
  }

  /** «1:15», «1:02:30». */
  function clock(seconds) {
    var h = Math.floor(seconds / 3600), m = Math.floor(seconds % 3600 / 60), s = Math.floor(seconds % 60);
    return (h ? h + ':' + (m < 10 ? '0' : '') : '') + m + ':' + (s < 10 ? '0' : '') + s;
  }

  var MAX_VOICES = 4;

  /** Голосовые сообщения: {id, duration, url} — запись mp3 (иначе ogg), только https; приложение проигрывает их само. */
  function messageVoices(m) {
    var voices = [];
    (Array.isArray(m.attachments) ? m.attachments : []).forEach(function (a) {
      var v = a && a.type === 'audio_message' && a.audio_message;
      if (!v || typeof v !== 'object' || voices.length >= MAX_VOICES) return;
      var url = httpsUrl(v.link_mp3) || httpsUrl(v.link_ogg);
      if (!url) return;
      voices.push({
        id: isNum(v.id) && isNum(v.owner_id) ? v.owner_id + '_' + v.id : '',
        duration: isNum(v.duration) ? v.duration : 0,
        url: url
      });
    });
    return voices;
  }

  /** Номер объекта владельца у записи на стене: owner_id, иначе to_id, иначе from_id. */
  function postOwner(w) {
    return isNum(w.owner_id) ? w.owner_id : (isNum(w.to_id) ? w.to_id : (isNum(w.from_id) ? w.from_id : null));
  }

  /**
   * Вложения, которые открываются: ссылка, статья, файл, запись на стене, клип. Карточка {kind, title, subtitle, url | path}:
   * url — внешний адрес (ссылка, файл), path — страница на сайте VK (запись, клип). Остальное остаётся подписью в тексте.
   */
  function messageCards(m) {
    var cards = [];
    (Array.isArray(m.attachments) ? m.attachments : []).forEach(function (a) {
      if (!a || cards.length >= MAX_CARDS) return;
      var type = a.type;
      var o = a[type];
      if (!o || typeof o !== 'object') return;
      if (type === 'link' || type === 'article') {
        var url = httpsUrl(o.url);
        if (!url) return;
        cards.push({ kind: type === 'article' ? 'article' : 'link', title: decodeText(o.title || '') || hostOf(url),
          subtitle: decodeText(o.caption || o.owner_name || '') || hostOf(url), url: url });
      } else if (type === 'doc') {
        var docUrl = httpsUrl(o.url);
        if (!docUrl) return;
        cards.push({ kind: 'doc', title: decodeText(o.title || '') || 'Файл',
          subtitle: [typeof o.ext === 'string' ? o.ext.toUpperCase() : '', fileSize(o.size)].filter(Boolean).join(' · '), url: docUrl });
      } else if (type === 'wall') {
        var owner = postOwner(o);
        if (owner === null || !isNum(o.id)) return;
        var first = decodeText(o.text || '').split('\n')[0].slice(0, 120);
        cards.push({ kind: 'wall', title: first || 'Запись на стене', subtitle: first ? 'Запись на стене' : '', path: '/wall' + owner + '_' + o.id });
      } else if (type === 'audio') {
        // Прямого адреса звука в сообщении нет: у карточки обложка и номер трека («владелец_номер»), по которому приложение
        // включает его в музыке VK; без номера — поиск по названию.
        var artist = decodeText(o.artist || '');
        var name = decodeText(o.title || '');
        if (!artist && !name) return;
        rememberAudioUrl(o);
        cards.push({ kind: 'audio', title: [artist, name].filter(Boolean).join(' — ').slice(0, 200),
          subtitle: 'Музыка' + (isNum(o.duration) && o.duration > 0 ? ' · ' + clock(o.duration) : ''),
          cover: audioCover(o),
          id: isNum(o.owner_id) && isNum(o.id) ? o.owner_id + '_' + o.id : '',
          key: typeof o.access_key === 'string' ? o.access_key : '' });
      } else if (type === 'call' || type === 'group_call_in_progress' || type === 'group_call') {
        var call = callCard(o, type);
        if (call) cards.push(call);
      } else if (type === 'video_message') {
        var mp4 = httpsUrl(o.link_mp4);
        if (!mp4) return;
        cards.push({ kind: 'video_message', title: 'Видеосообщение', subtitle: isNum(o.duration) && o.duration > 0 ? clock(o.duration) : '', url: mp4 });
      }
    });
    return cards;
  }

  /**
   * Звонок: {kind:'call', title, subtitle, url?}. Направление — по тому, кто звонил (initiator_id), итог — по state:
   * reached — состоялся (длительность), canceled_by_initiator — отменён звонившим (для меня входящий — пропущен),
   * canceled_by_receiver — отклонён. Групповой звонок — участники и ссылка «присоединиться» (только https VK).
   */
  function callCard(o, type) {
    var video = o.video === true || o.video === 1 || o.is_video === true;
    if (type !== 'call') {
      var count = o.participants && isNum(o.participants.count) ? o.participants.count : 0;
      var link = httpsUrl(o.join_link);
      if (link && !/^https:\/\/([a-z0-9-]+\.)*(vk\.com|vk\.ru|vk\.me)\//i.test(link)) link = '';
      var by = isNum(o.initiator_id) ? (names[o.initiator_id] || '') : '';
      var group = { kind: 'call', title: 'Групповой звонок' + (o.state === 'finished' || type === 'group_call' ? '' : ' идёт'),
        subtitle: [by ? 'начал(а) ' + by : '', count > 0 ? 'участников: ' + count : ''].filter(Boolean).join(' · ') };
      if (link) group.url = link;
      return group;
    }
    var me = myId();
    var outgoing = isNum(o.initiator_id) && me ? o.initiator_id === me : null;
    var what = video ? 'видеозвонок' : 'звонок';
    var title = outgoing === null ? (video ? 'Видеозвонок' : 'Звонок') : (outgoing ? 'Исходящий ' : 'Входящий ') + what;
    var state = typeof o.state === 'string' ? o.state : '';
    var subtitle;
    if (state === 'reached') subtitle = isNum(o.duration) && o.duration > 0 ? 'Длительность ' + clock(o.duration) : 'Состоялся';
    else if (state === 'canceled_by_initiator') subtitle = outgoing === false ? 'Вы не ответили' : 'Отменён';
    else if (state === 'canceled_by_receiver') subtitle = outgoing === false ? 'Вы отклонили' : 'Собеседник отклонил';
    else if (state) subtitle = state.replace(/_/g, ' ');
    else subtitle = isNum(o.duration) && o.duration > 0 ? 'Длительность ' + clock(o.duration) : '';
    if (state === 'canceled_by_initiator' && outgoing === false) title = 'Пропущенный ' + what;
    return { kind: 'call', title: title, subtitle: subtitle };
  }

  // ---- адрес звука трека: в ответах API у трека есть url (поток HLS на vkuseraudio), его играет само приложение

  var AUDIO_URL_TTL_MS = 30 * 60 * 1000;
  var audioUrls = {}; // «владелец_номер» → {url, at}

  /** Адрес потока трека: https на серверах звука VK; заглушка «трек недоступен» не годится. */
  function audioStreamUrl(url) {
    var u = httpsUrl(url);
    if (!u || /audio_api_unavailable/.test(u)) return '';
    // Серверы звука — общий список (shared/net_tap.js, 4.65), как AppConfig.isAudioHost.
    return NetTap.onHost(u, 'audio') ? u : '';
  }

  /**
   * Адрес звука трека из объекта API (4.88). VK отдаёт звук двумя путями: старое поле url (vkuseraudio.ru, теперь
   * «…/acmp/….mp3») и audio_streams на okcdn.ru (mp3, aac, HLS) — ими играет и сама страница. Сначала mp3 из
   * audio_streams, затем url; [retry] (прежний адрес не заиграл) — наоборот: повтор идёт другим путём.
   */
  function trackStreamUrl(a, retry) {
    if (!a || typeof a !== 'object') return '';
    var stream = '';
    if (Array.isArray(a.audio_streams)) {
      for (var i = 0; i < a.audio_streams.length && !stream; i++) {
        var st = a.audio_streams[i];
        if (st && st.type === 'mp3') stream = audioStreamUrl(st.url) || audioStreamUrl(st.fallback_url);
      }
    }
    var legacy = audioStreamUrl(a.url);
    return retry ? (legacy || stream) : (stream || legacy);
  }

  function rememberAudioUrl(o) {
    var url = trackStreamUrl(o);
    if (url && isNum(o.owner_id) && isNum(o.id)) audioUrls[o.owner_id + '_' + o.id] = { url: url, at: Date.now() };
  }

  function audioNorm(value) {
    return decodeText(String(value || '')).toLowerCase().replace(/ё/g, 'е').replace(/[^0-9a-zа-я]+/gi, ' ').trim();
  }

  /** Трек из списка ответа API по названию «Исполнитель — Название»: сначала точное совпадение, затем «название с добавкой». */
  function audioByTitle(list, title) {
    var parts = String(title || '').split(' — ');
    var artist = audioNorm(parts[0]);
    var name = audioNorm(parts.slice(1).join(' — '));
    if (!artist || !name || !Array.isArray(list)) return null;
    var loose = null;
    for (var i = 0; i < list.length; i++) {
      var a = list[i];
      if (!a || typeof a !== 'object' || !trackStreamUrl(a)) continue;
      var aa = audioNorm(a.artist), an = audioNorm(a.title);
      if (aa === artist && an === name) return a;
      if (!loose && aa === artist && an.indexOf(name) === 0) loose = a;
    }
    return loose;
  }

  /**
   * Адрес звука трека [id] из сообщения («владелец_номер», ключ доступа [key]), чтобы его играло само приложение — без
   * нажатий по вёрстке и без перехода в раздел музыки. По порядку: адрес из ответа с сообщением (свежий), audio.getById,
   * поиск по названию (catalog.getAudioSearch — так ищет сама страница музыки; тот же трек часто лежит у другого
   * владельца). Итог — {type:"audiourl", id, ok, url, title, duration, how, reason}.
   */
  function audioUrl(id, key, title, fresh) {
    var safe = String(id || '').replace(/[^0-9_-]/g, '');
    var name = String(title || '').slice(0, 200);
    function done(a, how) {
      post({ type: 'audiourl', id: safe, ok: true, url: a.cachedUrl || trackStreamUrl(a, fresh), how: how,
        title: [decodeText(a.artist || ''), decodeText(a.title || '')].filter(Boolean).join(' — ') || name,
        duration: isNum(a.duration) ? a.duration : 0 });
    }
    function fail(reason) { post({ type: 'audiourl', id: safe, ok: false, reason: String(reason || 'нет адреса').slice(0, 160) }); }
    function bySearch(previous) {
      if (!name) { fail(previous); return; }
      NetTap.api('catalog.getAudioSearch', { query: name, need_blocks: 0 }, function (error, r) {
        var found = audioByTitle(r && r.audios, name);
        if (found) done(found, 'поиск');
        else fail((previous ? previous + '; ' : '') + (error || 'в поиске нет'));
      });
    }
    var cached = fresh ? null : audioUrls[safe];
    if (cached && Date.now() - cached.at < AUDIO_URL_TTL_MS) { done({ cachedUrl: cached.url, title: name }, 'сообщение'); return; }
    if (!/^-?\d+_\d+$/.test(safe)) { bySearch('нет номера'); return; }
    var full = safe + (key ? '_' + String(key).replace(/[^0-9a-zA-Z]/g, '') : '');
    NetTap.api('audio.getById', { audios: full }, function (error, r) {
      var a = Array.isArray(r) ? r[0] : (r && Array.isArray(r.items) ? r.items[0] : null);
      if (a && trackStreamUrl(a, fresh)) { done(a, 'номер'); return; }
      bySearch(error || 'по номеру без адреса');
    });
  }

  var MAX_QUEUE = 100;

  /**
   * Очередь музыки переписки (3.60): адреса потоков всех треков [list] ([{id, key, title}], по порядку) — одним
   * audio.getById (адрес из ответа с сообщением — запасной); трек [wanted] без адреса — ещё и поиском по названию.
   * Итог — {type:"audioqueue", wanted, items:[{id, url, title, artist, duration}], how, reason}.
   */
  function audioQueue(list, wanted, wantedTitle) {
    var items = (Array.isArray(list) ? list : []).slice(0, MAX_QUEUE);
    var want = String(wanted || '').replace(/[^0-9_-]/g, '');
    var found = {};
    var need = [];
    function remember(key, a) {
      var url = trackStreamUrl(a);
      if (!url) return;
      found[key] = { id: key, url: url, title: decodeText((a && a.title) || ''), artist: decodeText((a && a.artist) || ''),
        duration: a && isNum(a.duration) ? a.duration : 0 };
    }
    items.forEach(function (it) {
      var id = String(it && it.id || '').replace(/[^0-9_-]/g, '');
      if (!/^-?\d+_\d+$/.test(id)) return;
      var key = String(it.key || '').replace(/[^0-9a-zA-Z]/g, '');
      need.push(id + (key ? '_' + key : ''));
    });
    var how = 'номер';
    function finish(reason) {
      var out = [];
      items.forEach(function (it) {
        var id = String(it && it.id || '').replace(/[^0-9_-]/g, '');
        if (found[id]) out.push(found[id]);
      });
      if (!want && found['']) out.push(found['']);
      post({ type: 'audioqueue', wanted: want, items: out, how: how, reason: String(reason || '').slice(0, 160) });
    }
    function searchWanted(reason) {
      // Отчёт 3.58: адрес из ответа с сообщением не проигрался, а свежий по номеру — играл. Поэтому сначала свежие
      // (audio.getById), адрес из сообщения — только если по номеру не дали.
      items.forEach(function (it) {
        var id = String(it && it.id || '').replace(/[^0-9_-]/g, '');
        var cached = audioUrls[id];
        if (id && !found[id] && cached && Date.now() - cached.at < AUDIO_URL_TTL_MS) found[id] = { id: id, url: cached.url, title: '', artist: '', duration: 0 };
      });
      if (found[want] || !wantedTitle) { finish(reason); return; }
      NetTap.api('catalog.getAudioSearch', { query: String(wantedTitle).slice(0, 200), need_blocks: 0 }, function (error, r) {
        var a = audioByTitle(r && r.audios, wantedTitle);
        if (a) { remember(want, a); how = 'поиск'; }
        finish(reason || error || '');
      });
    }
    if (!need.length) { searchWanted(''); return; }
    NetTap.api('audio.getById', { audios: need.join(',') }, function (error, r) {
      var arr = Array.isArray(r) ? r : (r && Array.isArray(r.items) ? r.items : []);
      arr.forEach(function (a) { if (a && isNum(a.owner_id) && isNum(a.id)) remember(a.owner_id + '_' + a.id, a); });
      searchWanted(error || '');
    });
  }

  /** Обложка трека из вложения-музыки: альбом (thumb), затем собственные поля трека; размер — не больше 300 пикселей. */
  function audioCover(o) {
    var sources = [o.album && o.album.thumb, o.thumb, o.cover, o.track_cover];
    var sizes = ['photo_300', 'photo_270', 'photo_135', 'photo_68', 'photo_600', 'photo_34'];
    for (var i = 0; i < sources.length; i++) {
      var t = sources[i];
      if (typeof t === 'string' && httpsUrl(t)) return t;
      if (!t || typeof t !== 'object') continue;
      for (var j = 0; j < sizes.length; j++) if (httpsUrl(t[sizes[j]])) return t[sizes[j]];
    }
    return '';
  }

  /** У вложения есть карточка (то же правило, что в messageCards: без адреса или номера остаётся подпись). */
  function hasCard(a) {
    return messageCards({ attachments: [a] }).length > 0;
  }

  var MAX_FORWARDS = 10;
  var MAX_FORWARD_DEPTH = 3;

  /**
   * Пересланные сообщения: {fromId, date, depth, text, photos, videos, cards, voices} — по порядку, вложенные
   * (переслано внутри пересланного) идут следом с глубиной на единицу больше. Автор хранится номером: имена приходят
   * в том же ответе (profiles, groups), но могут быть разобраны позже самих сообщений.
   */
  function messageForwards(m) {
    var list = [];
    function walk(items, depth) {
      if (!Array.isArray(items) || depth > MAX_FORWARD_DEPTH) return;
      items.forEach(function (f) {
        if (!f || typeof f !== 'object' || list.length >= MAX_FORWARDS) return;
        var entry = {
          fromId: isNum(f.from_id) ? f.from_id : 0,
          date: isNum(f.date) ? f.date : 0,
          depth: depth,
          text: messageText(f),
          photos: messagePhotos(f),
          videos: messageVideos(f),
          cards: messageCards(f),
          voices: messageVoices(f)
        };
        var nested = Array.isArray(f.fwd_messages) && f.fwd_messages.length > 0;
        // Пустое пересланное (только вложенные дальше) всё равно показывается: в нём автор цепочки.
        if (entry.text || entry.photos.length || entry.videos.length || entry.cards.length || entry.voices.length || nested) list.push(entry);
        walk(f.fwd_messages, depth + 1);
      });
    }
    walk(m.fwd_messages, 1);
    return list;
  }

  /**
   * Ответ на сообщение: {cmid, fromId, text} того, на что ответили. Текст — первые строки (или вид вложения), по номеру
   * в беседе приложение переходит к самому сообщению.
   */
  /**
   * Реакции на сообщение (4.61): [{reaction_id, count, user_ids}] и своя — reaction_id. Итог — [{id, count, mine}]
   * по убыванию числа, не больше 8. Картинки реакций — отдельно (reactionAssets), по номеру.
   */
  function messageReactions(m) {
    var list = Array.isArray(m.reactions) ? m.reactions : [];
    var mine = isNum(m.reaction_id) ? m.reaction_id : 0;
    var out = [];
    list.forEach(function (r) {
      if (!r || !isNum(r.reaction_id) || !isNum(r.count) || r.count <= 0) return;
      out.push({ id: r.reaction_id, count: r.count, mine: r.reaction_id === mine });
    });
    out.sort(function (a, b) { return b.count - a.count; });
    return out.slice(0, 8);
  }

  function messageReply(m) {
    var r = m.reply_message;
    if (!r || typeof r !== 'object') return null;
    var text = messageText(r).replace(/\s+/g, ' ').trim().slice(0, 200);
    if (!text && Array.isArray(r.fwd_messages) && r.fwd_messages.length) text = '[Пересланные сообщения]';
    return { cmid: isNum(r.conversation_message_id) ? r.conversation_message_id : 0, fromId: isNum(r.from_id) ? r.from_id : 0, text: text };
  }

  /** Текст сообщения для показа: сам текст, вложения, пересланное, служебные события. */
  function messageText(m) {
    var parts = [];
    var body = decodeText(m.text);
    if (body) parts.push(body);
    (Array.isArray(m.attachments) ? m.attachments : []).forEach(function (a) {
      var type = a && a.type;
      if (!type) return;
      if (type === 'photo' && photoOriginal(a.photo)) return; // фото показывается картинкой
      if ((type === 'sticker' || type === 'graffiti') && stickerImage(a)) return; // стикер — картинкой
      if (type === 'video' && a.video && isNum(a.video.id) && isNum(a.video.owner_id)) return; // видео — карточкой
      if (type === 'clip' && a.clip && isNum(a.clip.id) && isNum(a.clip.owner_id)) return; // клип — карточкой с обложкой, как видео
      if (hasCard(a)) return; // ссылка, файл, запись, клип, музыка, видеосообщение — карточкой
      if (type === 'audio_message' && messageVoices({ attachments: [a] }).length) return; // голосовое — проигрывателем
      parts.push('[' + (ATTACHMENT_LABELS[type] || 'Вложение') + ']');
    });
    // Пересланные сообщения показываются своим блоком (messageForwards), а не подписью в тексте.
    // Ответ на сообщение показывается цитатой над текстом (messageReply), а не подписью.
    if (m.geo && !m.attachments) parts.push('[Место]');
    if (m.action) parts.push('[Служебное сообщение]');
    return parts.join('\n');
  }

  // ------------------------------------------------------------ хранилище

  var CHAT_PEER_MIN = 2000000000;
  var MAX_HISTORY = 2000;
  var names = {};    // подписанный id → имя (пользователи > 0, сообщества < 0)
  var online = {};   // id пользователя → онлайн
  // 3.72: когда был в сети и с телефона ли (online_info в ответах API) — подпись под именем в переписке.
  var presence = {}; // id пользователя → {lastSeen, mobile}
  var avatars = {};  // подписанный id → аватар
  var chatAvatars = {}; // peerId беседы → аватар
  var convs = {};    // peerId → {title, lastDate, snippet, unread, order}
  var history = {};  // peerId → {ключ → сообщение}
  var historyUse = {};
  var historyClock = 0;
  var MAX_HISTORY_PEERS = 16;

  function historyBucket(peer) {
    peer = String(peer);
    historyUse[peer] = ++historyClock;
    var bucket = history[peer] || (history[peer] = {});
    var peers = Object.keys(history).sort(function (a, b) { return historyUse[a] - historyUse[b]; });
    var opened = String(currentPeer() || '');
    while (peers.length > MAX_HISTORY_PEERS) {
      var victim = peers.find(function (id) { return id !== peer && id !== opened; });
      if (!victim) break;
      delete history[victim];
      delete historyUse[victim];
      peers.splice(peers.indexOf(victim), 1);
    }
    return bucket;
  }

  function trimHistory(peer) {
    var bucket = history[peer];
    if (!bucket || Object.keys(bucket).length <= MAX_HISTORY + 32) return;
    sortedMessages(peer).slice(0, -MAX_HISTORY).forEach(function (message) { delete bucket[message.key]; });
  }
  var hasApiData = false;

  function myId() {
    var vk = window.vk;
    return vk && typeof vk.id === 'number' ? vk.id : 0;
  }

  function isNum(v) { return typeof v === 'number' && isFinite(v); }

  function rememberProfile(u) {
    var name = ((u.first_name || '') + ' ' + (u.last_name || '')).trim();
    if (name) names[u.id] = decodeText(name);
    var photo = u.photo_200 || u.photo_100 || u.photo_50;
    if (typeof photo === 'string' && photo) avatars[u.id] = photo;
    if (u.online_info) {
      online[u.id] = u.online_info.is_online === true;
      presence[u.id] = { lastSeen: isNum(u.online_info.last_seen) ? u.online_info.last_seen : 0, mobile: u.online_info.is_mobile === true };
    }
    else if ('online' in u) online[u.id] = u.online === 1;
  }

  function rememberGroup(g) {
    if (g.name) names[-g.id] = decodeText(g.name);
    var photo = g.photo_200 || g.photo_100 || g.photo_50;
    if (typeof photo === 'string' && photo) avatars[-g.id] = photo;
  }

  function titleFor(peerId, chatTitle) {
    if (chatTitle) return decodeText(chatTitle);
    if (peerId >= CHAT_PEER_MIN) return 'Беседа';
    return names[peerId] || (peerId < 0 ? 'Сообщество ' + (-peerId) : 'id' + peerId);
  }

  function conv(peerId) {
    return convs[peerId] || (convs[peerId] = { title: '', lastDate: 0, snippet: '', unread: false, order: 0 });
  }

  /** Объект conversation из API: {peer:{id,type}, chat_settings, in_read_cmid, sort_id, …}. */
  function ingestConversation(c) {
    var peerId = c.peer.id;
    var entry = conv(peerId);
    if (c.chat_settings && c.chat_settings.title) entry.title = c.chat_settings.title;
    if (Array.isArray(c.folder_ids)) entry.folderIds = c.folder_ids.filter(isNum).slice(0, 20);
    var chatPhoto = c.chat_settings && c.chat_settings.photo;
    if (chatPhoto && !chatPhoto.is_default_photo) {
      var url = chatPhoto.photo_200 || chatPhoto.photo_100 || chatPhoto.photo_50;
      if (typeof url === 'string' && url) chatAvatars[peerId] = url;
    }
    var last = c.last_conversation_message_id || 0;
    entry.unread = (c.unread_count || 0) > 0 || c.is_marked_unread === true ||
      (!!last && (c.in_read_cmid || 0) < last);
    // 3.94: сколько именно непрочитанных — число в списке диалогов (без числа — точка, как раньше).
    entry.unreadCount = isNum(c.unread_count) && c.unread_count > 0 ? c.unread_count : 0;
    // 3.72 (данные дампа, которые не использовались): до какого сообщения собеседник прочитал свои, можно ли писать,
    // отключены ли уведомления беседы.
    if (isNum(c.out_read_cmid)) entry.outRead = c.out_read_cmid;
    entry.canWrite = !(c.can_write && c.can_write.allowed === false);
    var ps = c.push_settings;
    var until = ps && isNum(ps.disabled_until) ? ps.disabled_until : 0;
    entry.muted = !!ps && (ps.disabled_forever === true || until === -1 || until > Date.now() / 1000);
    var sort = c.sort_id;
    var order = sort ? (sort.major_id || 0) * 1e12 + (sort.minor_id || 0) : (c.last_message_id || 0);
    if (order) entry.order = order;
  }

  // ---- папки мессенджера (3.79): «Каналы», «Бизнес», «Непрочитанные» и свои (messages.getDiff — дамп 3.70/3.75).
  // У беседы — folder_ids; у папки — included_peer_ids (явно включённые) и тип (unread — все непрочитанные).
  var folders = [];

  function ingestFolders(list) {
    var out = [];
    list.forEach(function (f) {
      if (!f || !isNum(f.id) || typeof f.name !== 'string' || out.length >= 20) return;
      out.push({
        id: f.id,
        name: decodeText(f.name).slice(0, 60),
        type: typeof f.type === 'string' ? f.type : '',
        peers: (Array.isArray(f.included_peer_ids) ? f.included_peer_ids : []).filter(isNum).slice(0, 500)
      });
    });
    if (out.length) folders = out;
  }

  function isMessage(o) {
    return isNum(o.date) && isNum(o.from_id) && isNum(o.peer_id) && ('text' in o) &&
      (isNum(o.conversation_message_id) || isNum(o.id));
  }

  function messageKey(m) {
    return isNum(m.conversation_message_id) && m.conversation_message_id > 0
      ? 'c' + m.conversation_message_id : 'i' + m.id;
  }

  function normalizeForMatch(value) { return String(value || '').replace(/\s+/g, ' ').trim(); }

  /** Сообщения, добавленные по вёрстке или при отправке, заменяются настоящими из API. */
  function dropProvisional(bucket, out, text) {
    var target = normalizeForMatch(text);
    Object.keys(bucket).forEach(function (key) {
      var item = bucket[key];
      if (!item.provisional || item.out !== out) return;
      var own = normalizeForMatch(item.text);
      // Настоящий текст с вложением — «подпись [Вложение]»: подпись, отправленная вместе с файлом, тоже заменяется,
      // а не остаётся отдельным пузырём рядом с вложением.
      if (own === target || (own && target.indexOf(own + ' [') === 0)) delete bucket[key];
    });
  }

  function ingestMessage(m) {
    if (m.is_hidden === true) return;
    var peerId = m.peer_id;
    var bucket = historyBucket(peerId);
    var me = myId();
    var out = m.out === 1 || m.out === true || (!!me && m.from_id === me);
    var text = messageText(m);
    var photos = messagePhotos(m);
    var videos = messageVideos(m);
    var cards = messageCards(m);
    var voices = messageVoices(m);
    var forwards = messageForwards(m);
    var reply = messageReply(m);
    var reactions = messageReactions(m);
    if (!text && !photos.length && !videos.length && !cards.length && !voices.length && !forwards.length) return;
    dropProvisional(bucket, out, text);
    var key = messageKey(m);
    var preview = m.__vkPreview === true;
    var known = bucket[key];
    // Предпросмотр не заменяет версию из истории: иначе список диалогов, пришедший позже, «обеднял» бы сообщение.
    if (!(preview && known && !known.preview)) {
      bucket[key] = {
        key: key,
        cmid: m.conversation_message_id || 0,
        date: m.date,
        out: out,
        fromId: m.from_id,
        text: text,
        photos: photos,
        videos: videos,
        cards: cards,
        voices: voices,
        forwards: forwards,
        reply: reply,
        reactions: reactions,
        provisional: false,
        preview: preview
      };
    }
    trimHistory(peerId);
    var entry = conv(peerId);
    if (m.date >= entry.lastDate) {
      entry.lastDate = m.date;
      entry.snippet = (text || snippetOf(photos, videos, cards, voices, forwards)).replace(/\s+/g, ' ');
      if (!entry.order) entry.order = m.date;
    }
    hasApiData = true;
  }

  /** Строка в списке диалогов для сообщения без текста: что в нём (раньше любое такое было «[Фото]»). */
  function snippetOf(photos, videos, cards, voices, forwards) {
    if (photos.length) return photos[0].sticker ? '[Стикер]' : '[Фото]';
    if (videos.length) return videos[0].clip ? '[Клип]' : '[Видео]' + (videos[0].title ? ' ' + videos[0].title : '');
    if (voices.length) return '[Голосовое сообщение]';
    if (cards.length) {
      var c = cards[0];
      if (c.kind === 'call') return c.title + (c.subtitle ? ' · ' + c.subtitle : '');
      if (c.kind === 'audio') return '[Музыка] ' + c.title;
      if (c.kind === 'doc') return '[Файл] ' + c.title;
      if (c.kind === 'video_message') return '[Видеосообщение]';
      return c.title;
    }
    if (forwards.length) return '[Пересланные сообщения]';
    return '';
  }

  var MAX_DEPTH = 40;

  /** Обходит любой JSON и забирает из него профили, сообщества, диалоги и сообщения. */
  function ingest(node, depth) {
    depth = depth || 0;
    if (!node || typeof node !== 'object' || depth > MAX_DEPTH) return;
    if (Array.isArray(node)) {
      for (var i = 0; i < node.length; i++) ingest(node[i], depth + 1);
      return;
    }
    // Элемент списка диалогов: {conversation, last_message}. У last_message peer_id бывает не указан —
    // берём его из беседы, иначе диалог выпадет из списка.
    var pairPeer = node.conversation && node.conversation.peer && node.conversation.peer.id;
    var pairLast = node.last_message;
    // Последнее сообщение из списка диалогов — предпросмотр: в нём бывает урезано то, что есть в истории (вложения,
    // ответ, размеры фото). Помечаем, чтобы он не заменял уже известную полную версию (ingestMessage).
    // Работаем с копией: объекты страницы не меняем (они бывают заморожены — присваивание в строгом режиме бросило бы
    // исключение и оборвало чтение; код ревью 3.59).
    var skipLast = false;
    if (node.conversation && pairLast && typeof pairLast === 'object' && !Array.isArray(pairLast)) {
      var copy = Object.assign({}, pairLast);
      if (isNum(pairPeer) && !isNum(copy.peer_id)) {
        copy.peer_id = pairPeer;
        if (!isNum(copy.from_id)) copy.from_id = copy.out ? myId() : pairPeer;
        if (!isNum(copy.id) && !isNum(copy.conversation_message_id)) {
          copy.conversation_message_id = node.conversation.last_conversation_message_id || 0;
        }
      }
      copy.__vkPreview = true;
      ingest(copy, depth + 1);
      skipLast = true;
    }
    if (isMessage(node)) {
      ingestMessage(node);
      return; // вложенные fwd_messages/reply_message принадлежат другим беседам — не разбираем
    }
    if (node.peer && isNum(node.peer.id) && typeof node.peer.type === 'string') {
      ingestConversation(node);
      hasApiData = true;
    }
    if (node.folders && Array.isArray(node.folders.items)) ingestFolders(node.folders.items);
    if (isNum(node.id) && typeof node.first_name === 'string') rememberProfile(node);
    else if (isNum(node.id) && typeof node.name === 'string' &&
      (typeof node.screen_name === 'string' || /^(page|group|event)$/.test(node.type))) rememberGroup(node);
    // Массивы profiles/groups в ответах API — однозначно пользователи и сообщества.
    if (Array.isArray(node.profiles)) node.profiles.forEach(function (u) { if (u && isNum(u.id)) rememberProfile(u); });
    if (Array.isArray(node.groups)) node.groups.forEach(function (g) { if (g && isNum(g.id)) rememberGroup(g); });
    for (var key in node) {
      if (!Object.prototype.hasOwnProperty.call(node, key) || !node[key] || typeof node[key] !== 'object') continue;
      if (skipLast && key === 'last_message') continue;
      // Закреплённое — старое сообщение посреди беседы: в историю его не кладём, иначе счёт загруженных (смещение
      // догрузки старых) сбивался и сообщения пропускались (код ревью 3.59). Оно придёт с историей само.
      if (key === 'pinned_message') continue;
      ingest(node[key], depth + 1);
    }
  }

  function ingestText(text) {
    if (typeof text !== 'string') return;
    var data = window.__vkCompat.parse(text);
    if (!data) return;
    try { ingest(data); } catch (e) { return; }
    scheduleNotify();
  }

  // ------------------------------------------------------------ перехват данных страницы

  // Перехват fetch/XHR/WebSocket — в общем модуле (assets/shared/net_tap.js), он же нужен главному скрипту.
  // Здесь — подписка: ответы API (/method/…) и всё, что приходит по WebSocket (новые сообщения).
  var NetTap = window.__vkNetTap;

  try {
    NetTap.listen({
      sockets: true,
      wants: function (url, source) { return source === 'ws' || NetTap.isApiUrl(url); },
      onText: function (url, body, text, source) {
        if (source !== 'ws' && SEND_URL.test(url)) onSendResponse(body, text);
        if (source !== 'ws' && RECENT_STICKERS_URL.test(url)) onRecentStickers(text);
        if (source !== 'ws' && STICKER_WORDS_URL.test(url)) onStickerWords(text);
        ingestText(text);
      }
    });
  } catch (e) { /* без перехвата: останется чтение вёрстки */ }

  // ---- долгий опрос мессенджера (queuev4…/im…?act=a_check): страница узнаёт о новом сообщении сразу, но само сообщение
  // приходит не методом API, а событием — раньше окно чтения его не видело, и новое появлялось только после перезагрузки
  // страницы по таймеру (раз в минуту; от перезагрузок переписка и «прыгала»). Теперь непустой ответ опроса — сигнал:
  // свежие сообщения открытой переписки (или список диалогов) запрашиваются методом API, и перехват разбирает их как
  // обычно (3.63).
  // 3.70: мессенджер опрашивает и api.vk.com/ruim<номер> (act=a_check — в теле запроса, не в адресе): по дампу 3.69
  // именно там приходят события переписки, а прежний шаблон этот адрес не узнавал — свежие сообщения ждали таймера.
  var LONG_POLL_URL = /\/(ru)?im\d*(\?|$)|act=a_check/;
  var pollFetchTimer = 0;
  var pollFetchLogged = false;

  function hasEvents(text) {
    var data;
    try { data = JSON.parse(text); } catch (e) { return false; }
    if (!data || typeof data !== 'object') return false;
    if (Array.isArray(data)) return data.some(function (part) { return part && Array.isArray(part.events) && part.events.length > 0; });
    if (Array.isArray(data.events)) return data.events.length > 0;
    if (!Array.isArray(data.updates)) return false;
    // События набора текста и «в сети» (коды 60–69 и 600–699, например [602, собеседник, 0] — каждые несколько секунд,
    // пока собеседник печатает) свежих сообщений не несут: на них историю не запрашиваем.
    return data.updates.some(function (u) {
      var code = Array.isArray(u) ? Number(u[0]) : NaN;
      return !(code >= 60 && code < 70) && !(code >= 600 && code < 700);
    });
  }

  function onLongPollEvents() {
    clearTimeout(pollFetchTimer);
    pollFetchTimer = setTimeout(function () {
      if (!NetTap.apiReady || !NetTap.apiReady()) return;
      var peer = currentPeer();
      var done = function () { lastPayload = ''; scheduleNotify(); };
      if (!pollFetchLogged) {
        pollFetchLogged = true;
        post({ type: 'log', level: 'info', page: location.pathname.replace(/\d{3,}/g, '#'), message: 'событие долгого опроса: свежие сообщения запрошены методом API' });
      }
      if (peer) NetTap.api('messages.getHistory', { peer_id: peer, count: 20, extended: 1, fields: 'photo_100,photo_50' }, done);
      else if (isDialogsPage()) NetTap.api('messages.getConversations', { count: 40, extended: 1, fields: 'photo_100,photo_50,online_info' }, done);
    }, 400);
  }

  /**
   * «Печатает…» (4.60): события набора из того же долгого опроса. Классический формат updates: [63, peer, [кто], …] —
   * печатают, [64, peer, [кто], …] — записывают голосовое; старые [61, кто, флаги] (личная переписка) и
   * [62, кто, номер беседы]; новый формат api.vk.com/ruim — [602, переписка, 0] (кто — не сказано). Итог приложению — {type:"typing", peer, users, names, voice}. Другие коды 60–69 и 600–699
   * пока не разобраны: первый такой — в журнал (только код), чтобы по отчёту дописать разбор.
   */
  var typingUnknownLogged = false;
  // Отчёт 4.85: [602, переписка, 0] приходит через ~0,2 с после каждой нашей отметки «прочитано» (messages.markAsRead) —
  // это отклик на прочтение, а не набор: «печатает…» висело при каждом открытии переписки. В это окно 602 не считается.
  var READ_ECHO_MS = 4000;
  var readSentAt = {};
  var queueShapes = {};
  var queueShapesCount = 0;

  /**
   * Вид события очереди без содержимого: буквы → «a», числа → «9», остальное (разделители) как есть; первые 40 знаков.
   * Пять разных видов за открытие страницы — в журнал.
   */
  function logQueueShape(e) {
    if (queueShapesCount >= 5) return;
    var shape;
    if (typeof e === 'string') shape = 'строка: ' + e.slice(0, 40).replace(/\d+/g, '9').replace(/[^\s\d.,:;|<>!{}\[\]"'=_\-+\/#]/g, 'a');
    else if (e && typeof e === 'object') shape = 'объект: ' + Object.keys(e).slice(0, 8).join(',');
    else shape = typeof e;
    if (queueShapes[shape]) return;
    queueShapes[shape] = true;
    queueShapesCount++;
    post({ type: 'log', level: 'info', page: location.pathname.replace(/\d{3,}/g, '#'), message: 'событие очереди: ' + shape });
  }

  function typingEvents(text) {
    var data;
    try { data = JSON.parse(text); } catch (e) { return []; }
    var updates = data && Array.isArray(data.updates) ? data.updates : [];
    // queuev4 (отчёт 4.62: на Pixel 7 опрос идёт так) — события в data.events; массивы разбираются как updates, вид
    // остальных — в журнал без содержимого (queueShape), чтобы по отчёту дописать разбор.
    if (!updates.length && data && Array.isArray(data.events)) {
      data.events.forEach(function (e) {
        var parsed = e;
        if (typeof e === 'string' && /^\s*\[/.test(e)) { try { parsed = JSON.parse(e); } catch (x) { parsed = e; } }
        if (Array.isArray(parsed)) updates.push(parsed);
        else logQueueShape(parsed);
      });
    }
    var out = [];
    updates.forEach(function (u) {
      if (!Array.isArray(u)) return;
      var code = Number(u[0]);
      var peer = 0;
      var users = [];
      var voice = false;
      var anyone = false;
      if (code === 63 || code === 64) {
        peer = u[1];
        users = Array.isArray(u[2]) ? u[2] : [];
        voice = code === 64;
      } else if (code === 61) {
        peer = u[1];
        users = [u[1]];
      } else if (code === 62) {
        peer = isNum(u[2]) ? CHAT_PEER_MIN + u[2] : 0;
        users = [u[1]];
      } else if (code === 602 && Date.now() - (readSentAt[String(u[1])] || 0) < READ_ECHO_MS) {
        return;
      } else if (code === 602) {
        // Новый формат опроса api.vk.com/ruim (дамп 3.69): [602, переписка, 0] — там печатают. Кто именно — не сказано:
        // в личной это собеседник, в беседе — «кто-то».
        peer = u[1];
        users = isNum(peer) && peer > 0 && peer < CHAT_PEER_MIN ? [peer] : [];
        anyone = peer >= CHAT_PEER_MIN;
      } else {
        if (!typingUnknownLogged && ((code >= 60 && code < 70) || (code >= 600 && code < 700))) {
          typingUnknownLogged = true;
          post({ type: 'log', level: 'info', page: location.pathname.replace(/\d{3,}/g, '#'), message: 'событие набора не разобрано: код ' + code });
        }
        return;
      }
      if (!isNum(peer) || !peer) return;
      users = users.filter(isNum).slice(0, 10);
      var me = myId();
      users = users.filter(function (id) { return id !== me; });
      if (!users.length && !anyone) return;
      out.push({ type: 'typing', peer: String(peer), users: users, names: users.map(function (id) { return names[id] || ''; }), voice: voice });
    });
    return out;
  }

  try {
    NetTap.listen({
      wants: function (url, source) { return source !== 'ws' && LONG_POLL_URL.test(String(url)) && !NetTap.isApiUrl(url); },
      onText: function (url, body, text) {
        typingEvents(text).forEach(post);
        if (hasEvents(text)) onLongPollEvents();
      }
    });
  } catch (e) { /* без опроса: обновление по таймеру, как раньше */ }

  // Новый сервер или адрес в трафике окна — в журнал (так же, как в главном окне, см. vk_lite/40_screens.js).
  if (NetTap.onAuthEvent) {
    NetTap.onAuthEvent(function (e) {
      post({ type: 'log', level: e.level, message: e.message, page: location.pathname.replace(/\d{3,}/g, '#') });
    });
  }

  // Необработанные ошибки документа (4.11, shared/net_tap.js) — в журнал окна чтения: свои — предупреждением.
  if (window.__vkErrorTap) {
    window.__vkErrorTap.listen(function (item) {
      post({ type: 'log', level: item.own ? 'warn' : 'debug', message: item.text, page: location.pathname.replace(/\d{3,}/g, '#') });
    });
  }

  // Дамп разработчика и из окна чтения (3.68): раньше писалось только главное окно — история, диалоги, ответы долгого
  // опроса и сообщения сокета мессенджера в дамп не попадали. Обезличивание то же (shared/dump_core.js).
  if (CONFIG.collectDump === true && window.__vkDumpCore) {
    try {
      NetTap.listen({
        sockets: true,
        wants: function () { return true; },
        onText: function (url, body, text, source) {
          var rec = window.__vkDumpCore.record(url, body, text, source);
          if (rec) { rec.compatibility = window.__vkCompat.describe('reader', window.__vkReader); post(rec); }
        }
      });
    } catch (e) { /* без дампа */ }
  }

  NetTap.onNewSource(function (s) {
    post({ type: 'log', level: 'debug', message: 'источник данных: ' + s.h + ' ' + s.e + ' (' + s.s + ')', page: location.pathname.replace(/\d{3,}/g, '#') });
  });

  function ingestPrefetch() {
    var cache = window.cur && window.cur.apiPrefetchCache;
    if (!Array.isArray(cache) || cache.__vkReaderSeen) return;
    try { Object.defineProperty(cache, '__vkReaderSeen', { value: true }); } catch (e) { /* заморожен — прочтём ещё раз */ }
    cache.forEach(function (entry) { if (entry && entry.response) ingest(entry.response); });
  }

  // ------------------------------------------------------------ сборка ответа приложению

  function currentPeer() {
    return peerFromHref(location.pathname + location.search);
  }

  function isDialogsPage() {
    return /^\/mail\/?$/.test(location.pathname);
  }

  function dialogsFromStore() {
    return Object.keys(convs)
      .filter(function (id) { return convs[id].lastDate || convs[id].order; })
      .sort(function (a, b) {
        return (convs[b].order || convs[b].lastDate) - (convs[a].order || convs[a].lastDate);
      })
      .map(function (id) {
        var peerId = +id;
        var entry = convs[id];
        return {
          peerId: id,
          title: titleFor(peerId, entry.title),
          snippet: entry.snippet,
          time: formatTime(entry.lastDate),
          unread: entry.unread,
          unreadCount: entry.unread ? (entry.unreadCount || 0) : 0,
          muted: entry.muted === true,
          folders: entry.folderIds || [],
          // 5.00.12: в диалог нельзя писать (только чтение) - у уведомления не будет «Ответить»
          canWrite: entry.canWrite !== false,
          online: peerId > 0 && peerId < CHAT_PEER_MIN && online[peerId] === true,
          avatar: (peerId >= CHAT_PEER_MIN ? chatAvatars[peerId] : avatars[peerId]) || ''
        };
      });
  }

  function sortedMessages(peerId) {
    var bucket = history[peerId];
    if (!bucket) return [];
    return Object.keys(bucket).map(function (k) { return bucket[k]; }).sort(function (a, b) {
      if (a.provisional !== b.provisional) return a.provisional ? 1 : -1; // черновые — в конец
      return a.date - b.date || a.cmid - b.cmid;
    });
  }

  var coverPending = false;

  /** Ролик без обложки из API: пробуем вёрстку; найденное запоминаем и в самом сообщении. */
  function withDomCover(v) {
    if (v.cover) return v;
    var url = domVideoCover(v.id);
    if (url) { v.cover = url; return v; }
    coverPending = true;
    return v;
  }

  function historyFromStore(peerId) {
    if (history[peerId]) historyBucket(peerId);
    coverPending = false;
    var isChat = peerId >= CHAT_PEER_MIN;
    return sortedMessages(peerId).slice(-MAX_HISTORY).map(function (m) {
      return {
        id: m.key,
        out: m.out,
        text: m.text,
        time: formatMessageTime(m.date),
        // 3.94: дата отдельным числом — приложение рисует разделители дней («Сегодня», «12 октября»).
        date: isNum(m.date) ? m.date : 0,
        sender: isChat && !m.out ? (names[m.fromId] || '') : '',
        // 4.31: номер автора в беседе — для списка участников из шапки (приложение открывает их страницы).
        senderId: isChat && !m.out && isNum(m.fromId) ? m.fromId : 0,
        // 4.53: фото автора в беседе — аватар у последнего сообщения его «реплики» (из тех же profiles/groups).
        senderAvatar: isChat && !m.out ? (avatars[m.fromId] || '') : '',
        photos: m.photos || [],
        videos: (m.videos || []).map(withDomCover),
        cards: m.cards || [],
        voices: m.voices || [],
        reply: m.reply ? { cmid: m.reply.cmid, sender: names[m.reply.fromId] || '', text: m.reply.text } : null,
        forwards: (m.forwards || []).map(function (f) {
          return {
            sender: names[f.fromId] || '',
            time: formatMessageTime(f.date),
            depth: f.depth,
            text: f.text,
            photos: f.photos || [],
            videos: (f.videos || []).map(withDomCover),
            cards: f.cards || [],
            voices: f.voices || []
          };
        }),
        reactions: m.reactions || [],
        // Приложение не даёт предпросмотру заменить уже известное (в том числе сохранённое) сообщение.
        preview: m.preview === true
      };
    });
  }

  function postDialogs(items) {
    post({ type: 'dialogs', items: items, folders: folders });
  }

  /**
   * 3.94: своя страница (имя и фото) — для шапки бокового меню приложения. Запрашивает экран сообщений (команда self),
   * фоновая проверка — нет: лишний запрос на каждую проверку не нужен. Один запрос за загрузку страницы.
   */
  /**
   * 3.94: список диалогов, пока открыта переписка (две колонки на широком экране): страница стоит на переписке и сама
   * список не обновляет — запрашиваем его методом API и присылаем, как со страницы списка. Ответ разбирается общим
   * перехватом (convs), поэтому список собирается чуть позже ответа.
   */
  function refreshDialogs() {
    if (!NetTap.apiReady || !NetTap.apiReady()) return;
    NetTap.api('messages.getConversations', { count: 40, extended: 1, fields: 'photo_100,photo_50,online_info' }, function (error) {
      if (error) return;
      setTimeout(function () {
        ingestPrefetch();
        var dialogs = dialogsFromStore();
        if (dialogs.length) postDialogs(dialogs);
      }, 50);
    });
  }

  /**
   * 4.32: участники беседы [peerId] (messages.getConversationMembers) — для списка из шапки переписки. Итог — сообщение
   * «members» {peerId, ok, items: [{id, name}]}: люди — положительные номера, сообщества — отрицательные.
   */
  var MAX_MEMBERS = 500;
  function chatMembers(peerId) {
    var peer = Number(peerId);
    if (!isNum(peer) || peer < CHAT_PEER_MIN) return;
    // extended=1 обязателен: без него VK отдаёт только номера, без profiles и groups (отчёт 4.33: «получено, людей 0»).
    NetTap.api('messages.getConversationMembers', { peer_id: peer, extended: 1, fields: 'photo_100' }, function (error, r) {
      if (error || !r || !Array.isArray(r.items)) {
        post({ type: 'members', peerId: String(peer), ok: false, items: [], reason: String(error || 'нет данных').slice(0, 160) });
        return;
      }
      var names = {};
      var photos = {};
      (Array.isArray(r.profiles) ? r.profiles : []).forEach(function (p) {
        if (!p || !isNum(p.id)) return;
        names[p.id] = decodeText(((p.first_name || '') + ' ' + (p.last_name || '')).trim());
        photos[p.id] = typeof p.photo_100 === 'string' ? p.photo_100 : '';
      });
      (Array.isArray(r.groups) ? r.groups : []).forEach(function (g) {
        if (!g || !isNum(g.id) || typeof g.name !== 'string') return;
        names[-g.id] = decodeText(g.name);
        photos[-g.id] = typeof g.photo_100 === 'string' ? g.photo_100 : '';
      });
      var items = [];
      var me = myId();
      r.items.forEach(function (m) {
        var id = m && m.member_id;
        // Себя в списке «к кому перейти» не показываем (4.37).
        if (isNum(id) && id !== me && names[id] && items.length < MAX_MEMBERS) items.push({ id: id, name: names[id], photo: photos[id] || '' });
      });
      post({ type: 'members', peerId: String(peer), ok: true, items: items });
    });
  }

  var selfSent = false;
  function sendSelf() {
    if (selfSent) return;
    selfSent = true;
    NetTap.api('users.get', { fields: 'photo_100' }, function (error, r) {
      var u = Array.isArray(r) ? r[0] : null;
      if (error || !u || !isNum(u.id)) {
        selfSent = false;
        post({ type: 'log', level: 'warn', page: 'self', message: 'своя страница не получена: ' + String(error || 'нет данных').slice(0, 160) });
        return;
      }
      var name = decodeText(((u.first_name || '') + ' ' + (u.last_name || '')).trim());
      post({ type: 'self', id: u.id, name: name, photo: typeof u.photo_100 === 'string' ? u.photo_100 : '' });
    });
  }

  function postHistory(peerId, items) {
    var entry = convs[peerId];
    post({
      type: 'history',
      peerId: String(peerId),
      title: entry ? titleFor(+peerId, entry.title) : text(pick(document, CONFIG.history.title)),
      items: items,
      outRead: entry && isNum(entry.outRead) ? entry.outRead : 0,
      canWrite: !entry || entry.canWrite !== false,
      online: +peerId > 0 && +peerId < CHAT_PEER_MIN && online[peerId] === true,
      mobile: !!(presence[peerId] && presence[peerId].mobile),
      lastSeen: presence[peerId] ? presence[peerId].lastSeen : 0
    });
  }

  var NOTIFY_DELAY_MS = 150;
  var notifyTimer = 0;
  var lastPayload = '';

  /** Данные изменились — отправляем приложению то, что относится к открытой странице. */
  var notifyQueued = false;

  function scheduleNotify() {
    clearTimeout(notifyTimer);
    // Окно чтения всегда скрыто: таймер Chromium выравнивает до секунды, и новое сообщение показывалось бы с
    // задержкой. Следующей задачей — всё, что пришло одной пачкой, всё равно соберётся в одно сообщение.
    var visibility = window.__vkWebVisibility;
    if (visibility && typeof visibility.soon === 'function' && visibility.hidden()) {
      if (notifyQueued) return;
      notifyQueued = true;
      visibility.soon(function () { notifyQueued = false; notifyNow(); });
      return;
    }
    notifyTimer = setTimeout(notifyNow, NOTIFY_DELAY_MS);
  }

  function notifyNow() {
    var payload = null;
    var peer = currentPeer();
    if (peer) {
      var items = historyFromStore(+peer);
      if (items.length) payload = { kind: 'history', peer: peer, items: items };
    } else if (isDialogsPage()) {
      var dialogs = dialogsFromStore();
      if (dialogs.length) payload = { kind: 'dialogs', items: dialogs, folders: folders };
    }
    if (!payload) return;
    var serialized = JSON.stringify(payload);
    retryCoversLater();
    if (serialized === lastPayload) return;
    lastPayload = serialized;
    if (payload.kind === 'history') postHistory(payload.peer, payload.items);
    else postDialogs(payload.items);
  }

  var coverRetries = 0;
  var coverRetryWindow = 0;

  /** Вёрстка ролика могла ещё не нарисоваться: пока обложек не хватает, пробуем снова (до 6 раз в минуту). */
  function retryCoversLater() {
    if (!coverPending) { coverRetries = 0; return; }
    var now = Date.now();
    if (now - coverRetryWindow > 60000) { coverRetryWindow = now; coverRetries = 0; }
    if (coverRetries >= 6) return;
    coverRetries++;
    setTimeout(scheduleNotify, 1500);
  }

  // ------------------------------------ новые сообщения, которые видны только в вёрстке

  var DOM_TAIL = 5;
  var DOM_CHECK_MS = 1500;

  /**
   * Новое сообщение, полученное мессенджером через long poll, API-запроса может не вызвать.
   * Тогда берём его из вёрстки как черновое; настоящее из API потом его заменит.
   */
  /**
   * Текст для сравнения вёрстки с данными сервера: без регистра, пробелов и знаков. В строке вёрстки бывает больше,
   * чем в тексте сообщения (время, «ред.», имя, подпись вложения), поэтому совпадением считается и вхождение одного
   * в другое. Признак «исходящее» не сравнивается: в вёрстке он определяется по селектору ненадёжно. Раньше из-за
   * буквального сравнения последние строки вёрстки каждый раз считались новыми и добавлялись дублями в конец переписки.
   */
  function matchText(value) {
    return String(value || '').toLowerCase().replace(/[^0-9a-zа-яё]+/gi, '');
  }

  function isKnownText(domText, knownTexts) {
    var d = matchText(domText);
    if (!d) return true; // пустое (фото, стикер) не угадываем по вёрстке
    for (var i = 0; i < knownTexts.length; i++) {
      var k = knownTexts[i];
      if (!k) continue;
      if (k === d || (k.length >= 3 && d.indexOf(k) !== -1) || (d.length >= 3 && k.indexOf(d) !== -1)) return true;
    }
    return false;
  }

  /** Слова строки для сверки: без регистра и знаков, числа (время, длительность, размер) не в счёт. */
  function words(value) {
    return String(value || '').toLowerCase().split(/[^0-9a-zа-яё]+/i).filter(function (w) { return w && !/^\d+$/.test(w); });
  }

  /**
   * Слова, которые в вёрстке стоят рядом с сообщением, но не его текст: имена авторов, подписи вложений (названия
   * треков, файлов, ссылок, роликов) и метки видов вложений. У сообщения только с вложением текста в API нет, а в
   * вёрстке строка — «Имя Фамилия + Исполнитель — Трек»: раньше она не узнавалась и добавлялась черновым пузырём,
   * и автор оказывался отдельно от вложения.
   */
  function decorationWords(messages) {
    var set = {};
    function add(value) { words(value).forEach(function (w) { set[w] = true; }); }
    Object.keys(ATTACHMENT_LABELS).forEach(function (k) { add(ATTACHMENT_LABELS[k]); });
    add('Музыка Видеосообщение Запись на стене Файл Клип');
    messages.forEach(function (m) {
      if (names[m.fromId]) add(names[m.fromId]);
      (m.cards || []).forEach(function (c) { add(c.title); add(c.subtitle); });
      (m.videos || []).forEach(function (v) { add(v.title); });
      // Цитата ответа в вёрстке: автор и начало того, на что ответили.
      if (m.reply) { if (names[m.reply.fromId]) add(names[m.reply.fromId]); add(m.reply.text); }
      // Пересланное в вёрстке — тем же рядом: автор, его текст и подписи его вложений.
      (m.forwards || []).forEach(function (f) {
        if (names[f.fromId]) add(names[f.fromId]);
        add(f.text);
        (f.cards || []).forEach(function (c) { add(c.title); add(c.subtitle); });
        (f.videos || []).forEach(function (v) { add(v.title); });
      });
    });
    add('Пересланное сообщение Пересланные сообщения сообщений');
    return set;
  }

  /** Строка вёрстки без «украшений» (имён, подписей вложений): что остаётся — возможный текст сообщения. */
  function withoutDecoration(value, decoration) {
    return words(value).filter(function (w) { return !decoration[w]; }).join(' ');
  }

  function mergeDomTail() {
    var peer = currentPeer();
    if (!peer || !history[peer]) return;
    var bucket = history[peer];
    var recentMessages = sortedMessages(+peer).slice(-100);
    var knownTexts = recentMessages.map(function (m) { return matchText(m.text); });
    var decoration = decorationWords(recentMessages.slice(-DOM_TAIL * 4));
    var rows = readHistory().slice(-DOM_TAIL);
    var now = Math.floor(Date.now() / 1000);
    var added = false;
    var skippedMedia = 0;
    var draftsAdded = 0;
    rows.forEach(function (row, index) {
      if (isKnownText(row.text, knownTexts)) return;
      // Строка с вложением черновиком не становится: её текст — подписи вложения, автор, размер файла, и угадать по нему
      // уже известное сообщение нельзя. Отчёт 3.48: файл в переписке давал два лишних пузыря. Сообщение с вложением
      // всё равно приходит от сервера.
      if (row.media) { skippedMedia++; return; }
      // Кроме имён и подписей вложений ничего нет — это уже известное сообщение с вложением.
      var rest = withoutDecoration(row.text, decoration);
      if (!rest || isKnownText(rest, knownTexts)) return;
      draftsAdded++;
      var signature = (row.out ? '1' : '0') + normalizeForMatch(row.text);
      var key = 'dom' + signature.length + '_' + signature.slice(0, 40);
      if (bucket[key]) return;
      bucket[key] = { key: key, cmid: 0, date: now + index, out: row.out, fromId: 0, text: row.text, provisional: true };
      knownTexts.push(matchText(row.text));
      added = true;
    });
    // В журнал — только числа (без текста): по отчёту видно, откуда взялись черновые пузыри.
    if (draftsAdded || skippedMedia) post({ type: 'log', level: 'debug', page: location.pathname.replace(/\d{3,}/g, '#'),
      message: 'хвост вёрстки: черновиков добавлено=' + draftsAdded + ', строк с вложением пропущено=' + skippedMedia });
    if (added) { trimHistory(peer); scheduleNotify(); }
  }

  // Раньше вёрстка переписки разбиралась таймером каждые 1,5 с, пока открыто окно (и в фоновой проверке тоже),
  // даже когда в ней ничего не менялось. Теперь — только после изменений в ней, не чаще раза в DOM_CHECK_MS.
  var tailTimer = 0;

  function scheduleDomTail() {
    if (tailTimer || !hasApiData || !currentPeer()) return;
    tailTimer = setTimeout(function () {
      tailTimer = 0;
      if (hasApiData && currentPeer()) mergeDomTail();
    }, DOM_CHECK_MS);
  }

  if (typeof MutationObserver === 'function') {
    new MutationObserver(scheduleDomTail).observe(document, { childList: true, subtree: true, characterData: true });
  } else {
    setInterval(function () { if (hasApiData && currentPeer()) mergeDomTail(); }, DOM_CHECK_MS);
  }

  // ------------------------------------------------------------ чтение диалогов

  function readDialogs() {
    var rows = pickAll(document, CONFIG.dialogs.row);
    var items = [];
    rows.forEach(function (row) {
      var link = pick(row, CONFIG.dialogs.link) || (row.tagName === 'A' ? row : null);
      var href = link && link.getAttribute('href');
      var peerId = peerFromHref(href) || row.getAttribute('data-peer') || row.getAttribute('data-id');
      if (!peerId) return;
      var title = text(pick(row, CONFIG.dialogs.title));
      items.push({
        peerId: String(peerId),
        title: title,
        snippet: text(pick(row, CONFIG.dialogs.snippet)),
        time: text(pick(row, CONFIG.dialogs.time)),
        unread: !!pick(row, CONFIG.dialogs.unread),
        online: !!pick(row, CONFIG.dialogs.online)
      });
    });
    return items;
  }

  // ------------------------------------------------------------ чтение переписки

  function readHistory() {
    var rows = pickAll(document, CONFIG.history.row);
    var items = [];
    rows.forEach(function (row, index) {
      var body = pick(row, CONFIG.history.text) || row;
      var value = text(body);
      if (!value) return;
      // Исходящее сообщение помечается классом/атрибутом; иначе считаем входящим.
      var out = matches(row, CONFIG.history.outMarker) || !!pick(row, CONFIG.history.outMarker);
      items.push({
        id: row.getAttribute('data-msgid') || row.id || ('i' + index),
        out: out,
        text: value,
        time: text(pick(row, CONFIG.history.time)),
        media: hasMedia(row)
      });
    });
    return items;
  }

  /** В строке вёрстки есть вложение (картинка, файл, музыка, видео, пересланное) — не просто текст. */
  var MEDIA_IN_ROW = 'video,audio,.audio_item,[data-audio],a[href*="/doc"],a[href*="/photo"],a[href*="/video"],a[href*="/clip"],' +
    'a[href*="/wall"],[class*="ttach"],[class*="orward"],[class*="Fwd"],[class*="fwd"]';
  function hasMedia(row) {
    try {
      if (row.querySelector(MEDIA_IN_ROW)) return true;
      // Картинка — вложение, если это не аватар автора (в беседах он есть у каждой строки) и не значок/эмодзи.
      var imgs = row.querySelectorAll('img');
      for (var i = 0; i < imgs.length; i++) {
        var img = imgs[i];
        if (img.closest('[class*="vatar"]') || /emoji|smile|sticker/i.test(img.className + ' ' + (img.getAttribute('src') || ''))) continue;
        return true;
      }
    } catch (e) { /* без разбора — как текст */ }
    return false;
  }

  function matches(el, selector) {
    if (!el || !selector) return false;
    try { return el.matches(selector); } catch (e) { return false; }
  }

  // ------------------------------------------------------------ отправка

  var SEND_URL = /\/method\/messages\.send(\?|$)/;
  var lastSent = { text: '', at: 0 };
  var deliveredCount = 0;

  /** Номер сообщения в беседе из ответа отправки (формы ответа разные: число, объект, массив объектов). */
  function findCmid(node, depth) {
    if (!node || typeof node !== 'object' || depth > 4) return 0;
    if (isNum(node.conversation_message_id)) return node.conversation_message_id;
    if (isNum(node.cmid)) return node.cmid; // ответ веб-клиента: {cmid, message_id}
    for (var key in node) {
      if (Object.prototype.hasOwnProperty.call(node, key)) {
        var found = findCmid(node[key], depth + 1);
        if (found) return found;
      }
    }
    return 0;
  }

  /** Поля тела запроса отправки: форма «a=1&b=2» или JSON. */
  function sendParams(body) {
    var result = { peer: '', message: '', files: false, forward: '', parsed: false };
    try {
      if (body && body.charAt(0) === '{') {
        var json = JSON.parse(body);
        result.peer = String(json.peer_id || String(json.peer_ids || '').split(',')[0] || '');
        result.message = typeof json.message === 'string' ? json.message : '';
        result.files = !!json.attachment;
        result.forward = typeof json.forward === 'string' ? json.forward : '';
        result.parsed = true;
      } else if (body) {
        var params = new URLSearchParams(body);
        result.peer = params.get('peer_id') || String(params.get('peer_ids') || '').split(',')[0] || '';
        result.message = params.get('message') || '';
        result.files = !!params.get('attachment');
        result.forward = params.get('forward') || '';
        result.parsed = true;
      }
    } catch (e) { /* тело другого вида: возьмём открытую переписку и последний отправленный текст */ }
    return result;
  }

  /**
   * Ответ messages.send без ошибки: сервер принял сообщение. «Нажато» ещё не «доставлено», а вот успешный ответ API —
   * доставка. Раньше приложение ждало, пока настоящее сообщение появится в истории окна чтения, а оно туда не попадало
   * (долгий опрос VK — не метод API), и через 12 секунд перезагружало страницу: сообщение всё это время висело как
   * «отправляется». Если ответ несёт номер сообщения в беседе, настоящее сообщение сразу попадает и в историю.
   */
  function onSendResponse(body, text) {
    var data;
    try { data = JSON.parse(text); } catch (e) { return; }
    if (!data || data.error || data.response === undefined || data.response === null) return;
    var sent = sendParams(body);
    var peer = sent.peer || currentPeer() || '';
    var cmidSent = findCmid(data.response, 0);
    // Ответ и пересылка — свои запросы приложения (sendReply, forwardMessages): это не текст из очереди и не
    // «доставлено». Раньше их ответ принимался за обычную отправку: чужой текст (последний отправленный) вставал в
    // историю под номером пересланного, а «доставлено» могло снять с очереди другое сообщение (код ревью 3.59).
    if (sent.forward) {
      attachSending = false;
      if (peer && String(peer) === String(currentPeer())) fetchSent(peer, cmidSent, 0);
      return;
    }
    // Последний отправленный текст — только если тело запроса не разобрать (другой вид запроса), а не когда в нём
    // просто нет текста.
    var message = sent.message || (!sent.parsed && Date.now() - lastSent.at < 60000 ? lastSent.text : '');
    // n — чтобы два одинаковых сообщения подряд («ок», «ок») не были приняты за повтор и не отброшены.
    post({ type: 'delivered', peerId: String(peer), text: message, cmid: cmidSent, n: ++deliveredCount });
    var cmid = cmidSent;
    // С вложениями подпись без них в историю не кладём: сообщение запрашивается целиком (с вложениями) сразу. Раньше
    // оно ждало следующего чтения истории, а мессенджер получает новое долгим опросом, не методом API, — и сообщение с
    // файлами появлялось в приложении только после перезахода в переписку (3.58).
    if (attachSending || sent.files) {
      attachSending = false;
      clearTimeout(attachSendingTimer);
      if (peer) fetchSent(peer, cmid, 0);
      return;
    }
    if (cmid && peer && message) {
      ingestMessage({
        conversation_message_id: cmid, date: Math.floor(Date.now() / 1000), from_id: myId(), peer_id: +peer, out: 1, text: message
      });
      lastPayload = '';
      scheduleNotify();
    }
  }

  var FETCH_SENT_RETRY_MS = [1500, 4000];

  /**
   * Только что отправленное сообщение целиком: по номеру в беседе (getByConversationMessageId), без номера — последние
   * сообщения переписки. Ответ разбирается перехватом, как любой ответ API; затем — история приложению.
   */
  function fetchSent(peer, cmid, attempt) {
    function after(error) {
      lastPayload = '';
      scheduleNotify();
      if (error && attempt < FETCH_SENT_RETRY_MS.length) setTimeout(function () { fetchSent(peer, cmid, attempt + 1); }, FETCH_SENT_RETRY_MS[attempt]);
    }
    if (cmid) NetTap.api('messages.getByConversationMessageId', { peer_id: peer, conversation_message_ids: cmid, extended: 1, fields: 'photo_100' }, after);
    else NetTap.api('messages.getHistory', { peer_id: peer, count: 5, extended: 1, fields: 'photo_100' }, after);
  }

  /**
   * Ответ на сообщение [cmid] этой переписки: messages.send от имени страницы с forward {is_reply}. В композере
   * страницы так не ответить (нужно её меню у сообщения), поэтому — запросом. Итог — сообщение type = "replied".
   */
  function sendReply(value, cmid) {
    var peer = currentPeer();
    var text = String(value || '');
    if (!peer || !isNum(+cmid) || !text.trim()) { post({ type: 'replied', ok: false, reason: 'нет переписки или текста' }); return; }
    lastSent = { text: text, at: Date.now() };
    NetTap.api('messages.send', {
      peer_id: peer,
      random_id: Math.floor(Math.random() * 2147483647),
      message: text,
      forward: JSON.stringify({ peer_id: +peer, conversation_message_ids: [+cmid], is_reply: true })
    }, function (error) {
      post({ type: 'replied', ok: !error, reason: error ? String(error).slice(0, 160) : '' });
    });
  }

  /**
   * 4.0: действия с беседой из списка диалогов (долгое нажатие): прочитана, непрочитана, без звука, со звуком, удалить
   * переписку. Методом API от имени страницы; память диалогов меняется сразу, приложение получает и итог
   * {type:"convaction"}, и обновлённый список.
   */
  var CONV_ACTIONS = {
    read: function (peer) { return ['messages.markAsRead', { peer_id: peer, mark_conversation_as_read: 1 }]; },
    unread: function (peer) { return ['messages.markAsUnreadConversation', { peer_id: peer }]; },
    mute: function (peer) { return ['account.setSilenceMode', { peer_id: peer, time: -1 }]; },
    unmute: function (peer) { return ['account.setSilenceMode', { peer_id: peer, time: 0 }]; },
    remove: function (peer) { return ['messages.deleteConversation', { peer_id: peer }]; }
  };

  function convAction(action, peerValue) {
    var peer = String(peerValue || '');
    var make = CONV_ACTIONS[action];
    if (!make || !/^-?\d{1,12}$/.test(peer)) { post({ type: 'convaction', action: String(action), peer: peer, ok: false, reason: 'неизвестное действие' }); return; }
    var call = make(peer);
    NetTap.api(call[0], call[1], function (error) {
      if (!error) {
        var entry = convs[peer];
        if (entry) {
          if (action === 'read') { entry.unread = false; entry.unreadCount = 0; }
          if (action === 'unread') entry.unread = true;
          if (action === 'mute') entry.muted = true;
          if (action === 'unmute') entry.muted = false;
          if (action === 'remove') delete convs[peer];
        }
        if (action === 'remove') delete history[+peer];
        lastPayload = '';
        var dialogs = dialogsFromStore();
        if (dialogs.length || action === 'remove') postDialogs(dialogs);
      }
      post({ type: 'convaction', action: action, peer: peer, ok: !error, reason: error ? String(error).slice(0, 160) : '' });
    });
  }

  /** Сообщение открытой переписки по номеру в беседе (без черновых догадок по вёрстке). */
  function storedByCmid(peer, cmid) {
    var bucket = history[+peer];
    if (!bucket) return null;
    var keys = Object.keys(bucket);
    for (var i = 0; i < keys.length; i++) {
      var item = bucket[keys[i]];
      if (item && !item.provisional && item.cmid === cmid) return { bucket: bucket, key: keys[i], item: item };
    }
    return null;
  }

  /**
   * 3.94: удалить сообщение [cmid] открытой переписки — у себя или у всех (messages.delete от имени страницы).
   * Удалённое убирается и из своей памяти: иначе следующая сборка истории вернула бы его. Итог — {type:"deleted"}.
   */
  /**
   * Картинки реакций (4.61): messages.getReactionsAssets → {assets:[{reaction_id, links:{static, …}}]}. Приложению —
   * {type:"reactionAssets", items:[{id, image}]}: только https-адреса; формат первой картинки — в журнал (по нему видно,
   * может ли Android её показать). Раз за открытие окна.
   */
  var reactionAssetsAsked = false;

  function reactionAssets() {
    if (reactionAssetsAsked || !NetTap.apiReady || !NetTap.apiReady()) return;
    reactionAssetsAsked = true;
    NetTap.api('messages.getReactionsAssets', {}, function (error, r) {
      var assets = r && Array.isArray(r.assets) ? r.assets : [];
      var items = [];
      assets.forEach(function (a) {
        var links = a && a.links;
        var image = links && (links.static || links.small_animation || links.big_animation);
        if (!a || !isNum(a.reaction_id) || typeof image !== 'string' || image.indexOf('https://') !== 0) return;
        items.push({ id: a.reaction_id, image: image.slice(0, 1000) });
      });
      var sample = items.length ? (items[0].image.split('?')[0].match(/\.([a-z0-9]{2,5})$/i) || [])[1] || 'без расширения' : '';
      post({ type: 'log', level: 'info', page: location.pathname.replace(/\d{3,}/g, '#'),
        message: 'картинки реакций: ' + (error ? 'ошибка ' + String(error).slice(0, 80) : items.length + ' шт., формат ' + sample) });
      if (!error) post({ type: 'reactionAssets', items: items.slice(0, 64) });
      else reactionAssetsAsked = false;
    });
  }

  /** Поставить реакцию [reactionId] на сообщение [cmid] открытой переписки; 0 — снять свою. Итог — «reacted». */
  function react(cmid, reactionId) {
    var peer = currentPeer();
    var id = +cmid;
    var rid = +reactionId;
    if (!peer || !isNum(id) || id <= 0 || !isNum(rid) || rid < 0) { post({ type: 'reacted', ok: false, cmid: id || 0, reason: 'нет переписки или сообщения' }); return; }
    var method = rid > 0 ? 'messages.sendReaction' : 'messages.deleteReaction';
    var params = rid > 0 ? { peer_id: peer, cmid: id, reaction_id: rid } : { peer_id: peer, cmid: id };
    NetTap.api(method, params, function (error) {
      if (error) { post({ type: 'reacted', ok: false, cmid: id, reason: String(error).slice(0, 160) }); return; }
      // Свежая версия сообщения (с реакциями) — перехват разберёт её как обычно.
      NetTap.api('messages.getByConversationMessageId', { peer_id: peer, conversation_message_ids: id, extended: 1, fields: 'photo_100' }, function () {
        lastPayload = '';
        scheduleNotify();
        post({ type: 'reacted', ok: true, cmid: id, reason: '' });
      });
    });
  }

  function deleteMessage(cmid, forAll) {
    var peer = currentPeer();
    var id = +cmid;
    if (!peer || !isNum(id) || id <= 0) { post({ type: 'deleted', ok: false, cmid: id || 0, reason: 'нет переписки или сообщения' }); return; }
    NetTap.api('messages.delete', { peer_id: peer, cmids: id, delete_for_all: forAll ? 1 : 0 }, function (error, r) {
      // 4.0: VK отвечает успехом всего запроса, а отказ по сообщению кладёт внутрь ответа (по отчёту с телефона:
      // «у всех» в «Избранном» — ошибка 15). Раньше такое считалось удалённым, а сообщение оставалось.
      var item = Array.isArray(r) ? r[0] : null;
      var itemError = item && item.error ? item.error : null;
      if (!error && itemError && forAll && itemError.code === 15) {
        // «У всех» нельзя (своя переписка, старое) — удаляем хотя бы у себя.
        deleteMessage(id, false);
        return;
      }
      var failed = error || itemError;
      if (!failed) {
        var found = storedByCmid(peer, id);
        if (found) delete found.bucket[found.key];
        lastPayload = '';
      }
      var reason = error ? String(error) : itemError ? 'ошибка ' + itemError.code + (itemError.description ? ': ' + itemError.description : '') : '';
      post({ type: 'deleted', ok: !failed, cmid: id, reason: reason.slice(0, 160) });
    });
  }

  /**
   * 3.94: изменить текст своего сообщения [cmid] (messages.edit; пересланное и ответ сохраняются). Итог —
   * {type:"edited", ok, cmid, text}.
   */
  function editMessage(cmid, value) {
    var peer = currentPeer();
    var id = +cmid;
    var text = String(value || '');
    if (!peer || !isNum(id) || id <= 0 || !text.trim()) { post({ type: 'edited', ok: false, cmid: id || 0, reason: 'нет переписки, сообщения или текста' }); return; }
    NetTap.api('messages.edit', { peer_id: peer, conversation_message_id: id, message: text, keep_forward_messages: 1, keep_snippets: 1 }, function (error) {
      if (!error) {
        var found = storedByCmid(peer, id);
        if (found) found.item.text = text;
        lastPayload = '';
      }
      post({ type: 'edited', ok: !error, cmid: id, text: error ? '' : text, reason: error ? String(error).slice(0, 160) : '' });
    });
  }

  /**
   * Переслать сообщения [cmids] открытой переписки в переписку [target] (себе — номер своей страницы): messages.send
   * от имени страницы с forward. Итог — {type:"forwarded", ok, target, reason}.
   */
  function forwardMessages(target, cmids) {
    var peer = currentPeer();
    var ids = (Array.isArray(cmids) ? cmids : [cmids]).map(Number).filter(function (n) { return isNum(n) && n > 0; }).slice(0, 100);
    var to = +target;
    if (!peer || !ids.length || !isNum(to) || !to) { post({ type: 'forwarded', ok: false, target: String(target), reason: 'нет переписки или сообщения' }); return; }
    NetTap.api('messages.send', {
      peer_id: to,
      random_id: Math.floor(Math.random() * 2147483647),
      forward: JSON.stringify({ peer_id: +peer, conversation_message_ids: ids })
    }, function (error) {
      post({ type: 'forwarded', ok: !error, target: String(to), reason: error ? String(error).slice(0, 160) : '' });
    });
  }

  /**
   * Открытая переписка прочитана: messages.markAsRead от имени страницы. Страница скрыта, и сама VK прочитанным её не
   * отмечала — счётчик непрочитанных на сайте и в приложениях VK не сбрасывался (3.58). Ошибка — в журнал.
   */
  var markReadWait = 0;
  var MARK_READ_WAIT_MS = 500;
  var MARK_READ_WAIT_STEPS = 40;

  function markRead() {
    var peer = currentPeer();
    if (!peer) return false;
    // Страница только открылась и ещё не обращалась к API (нет данных входа, отчёт 3.64): ждём первого её запроса,
    // а не теряем отметку — иначе переписка оставалась непрочитанной на сайте до следующего открытия.
    if (NetTap.apiReady && !NetTap.apiReady()) {
      clearTimeout(markReadWait);
      var steps = 0;
      var waitAuth = function () {
        markReadWait = 0;
        if (currentPeer() !== peer) return;
        if (NetTap.apiReady()) { markReadNow(peer); return; }
        if (++steps >= MARK_READ_WAIT_STEPS) { markReadNow(peer); return; }
        markReadWait = setTimeout(waitAuth, MARK_READ_WAIT_MS);
      };
      markReadWait = setTimeout(waitAuth, MARK_READ_WAIT_MS);
      return true;
    }
    markReadNow(peer);
    return true;
  }

  function markReadNow(peer) {
    readSentAt[String(peer)] = Date.now();
    NetTap.api('messages.markAsRead', { peer_id: peer, mark_conversation_as_read: 1 }, function (error) {
      if (error) {
        post({ type: 'log', level: 'warn', page: location.pathname.replace(/\d{3,}/g, '#'), message: 'переписка не отмечена прочитанной: ' + String(error).slice(0, 160) });
        return;
      }
      var entry = convs[peer];
      if (entry && entry.unread) { entry.unread = false; entry.unreadCount = 0; lastPayload = ''; scheduleNotify(); }
    });
  }

  // ------------------------------------------------------------ заранее (4.13)

  /**
   * Непрочитанные переписки — заранее и без отметки о прочтении: messages.getHistory (в отличие от открытия переписки
   * на сайте) прочитанной её не делает. Ответ разбирается общим перехватом; приложению уходит история этой переписки
   * (обычное «history» с номером) — оно её сохраняет, и переписку можно открыть потом, в том числе без сети. Прочитанной
   * она становится, когда её откроют в приложении. По одной, с паузой, не больше PREFETCH_MAX в очереди.
   */
  var PREFETCH_MAX = 8;
  var PREFETCH_PAUSE_MS = 700;
  var PREFETCH_COUNT = 30;
  var prefetchQueue = [];
  var prefetchBusy = false;

  function prefetch(peers) {
    if (!NetTap.apiReady || !NetTap.apiReady()) return false;
    (Array.isArray(peers) ? peers : []).forEach(function (value) {
      var peer = String(value);
      if (!/^-?\d{1,12}$/.test(peer) || prefetchQueue.indexOf(peer) !== -1 || prefetchQueue.length >= PREFETCH_MAX) return;
      prefetchQueue.push(peer);
    });
    if (!prefetchBusy) prefetchNext();
    return true;
  }

  function prefetchNext() {
    var peer = prefetchQueue.shift();
    if (!peer) { prefetchBusy = false; return; }
    prefetchBusy = true;
    NetTap.api('messages.getHistory', { peer_id: peer, count: PREFETCH_COUNT, extended: 1, fields: 'photo_100,photo_50' }, function (error) {
      // Ответ разбирается перехватом чуть позже обратного вызова.
      setTimeout(function () {
        var items = error ? [] : historyFromStore(+peer);
        post({ type: 'log', level: error ? 'warn' : 'debug', page: 'prefetch',
          message: error ? 'переписка заранее не загружена: ' + String(error).slice(0, 160) : 'переписка загружена заранее: сообщений=' + items.length });
        // Присылается и открытая (фоновая проверка ждёт ответа по каждой); экран сообщений открытую сам не просит.
        if (items.length) postHistory(peer, items);
        setTimeout(prefetchNext, PREFETCH_PAUSE_MS);
      }, 50);
    });
  }

  /** Номер своей страницы (для «переслать себе»); 0 — страница его не знает. */
  function me() { return myId(); }

  /** Адрес https файла видео на серверах VK (проигрыватель приложения грузит только с них). */
  function videoFileUrl(url) {
    var u = httpsUrl(url);
    if (!u) return '';
    // Тот же список, что у приложения (AppConfig.isVideoHost): иначе принятый здесь адрес там отбрасывался, а годный
    // следующий по качеству уже не передавался (код ревью 3.59). С 4.65 — общий список (shared/net_tap.js).
    return NetTap.onHost(u, 'video') ? u : '';
  }

  var VIDEO_QUALITIES = ['mp4_720', 'mp4_480', 'mp4_1080', 'mp4_360', 'mp4_240', 'mp4_144'];

  /** Из объекта видео API: {mp4, hls, player, title, duration}; mp4 — до 720p (дальше без нужды на телефоне). */
  function videoSources(v) {
    var files = v && v.files && typeof v.files === 'object' ? v.files : {};
    var mp4 = '';
    for (var i = 0; i < VIDEO_QUALITIES.length && !mp4; i++) mp4 = videoFileUrl(files[VIDEO_QUALITIES[i]]);
    // Все качества файла (3.86): приложение выбирает по настройке и переключает на ходу.
    var qualities = {};
    Object.keys(files).forEach(function (k) {
      var m = /^mp4_(\d{3,4})$/.exec(k);
      var u = m ? videoFileUrl(files[k]) : '';
      if (u) qualities[m[1]] = u;
    });
    return {
      mp4: mp4,
      files: qualities,
      hls: videoFileUrl(files.hls) || videoFileUrl(files.hls_fmp4),
      player: videoFileUrl(v && v.player),
      title: decodeText((v && v.title) || ''),
      live: !!(v && (v.live === 1 || v.live === true))
    };
  }

  /**
   * Файлы видео или клипа «владелец_номер» для проигрывателя приложения: video.get от имени страницы (у видео из
   * сообщения нужен его ключ доступа). Итог — {type:"video", id, ok, mp4, hls, player, title, reason}.
   */
  var videoFilesCache = {};
  var videoFilesCount = 0;
  var VIDEO_FILES_TTL_MS = 30 * 60 * 1000;   // адреса файлов подписаны и со временем истекают

  function rememberVideoFiles(id, v) {
    if (!videoFilesCache[id] && ++videoFilesCount > 200) { videoFilesCache = {}; videoFilesCount = 1; }
    videoFilesCache[id] = { v: v, at: Date.now() };
  }

  function videoInfo(id, key) {
    var safe = String(id || '').replace(/[^0-9_-]/g, '');
    var cached = videoFilesCache[safe];
    if (cached && Date.now() - cached.at < VIDEO_FILES_TTL_MS) {
      var src0 = videoSources(cached.v);
      if (src0.mp4 || src0.hls) {
        post({ type: 'video', id: safe, ok: true, mp4: src0.mp4, files: src0.files, hls: src0.hls, player: src0.player, title: src0.title, live: src0.live, reason: 'вложение' });
        return;
      }
    }
    var full = safe + (key ? '_' + String(key).replace(/[^0-9a-zA-Z]/g, '') : '');
    function done(error, response, method) {
      var items = response && (Array.isArray(response.items) ? response.items : (Array.isArray(response) ? response : []));
      var v = items && items[0];
      var src = v ? videoSources(v) : null;
      if (src && (src.mp4 || src.hls || src.player)) {
        post({ type: 'video', id: safe, ok: true, mp4: src.mp4, files: src.files, hls: src.hls, player: src.player, title: src.title, live: src.live, reason: method });
        return true;
      }
      return false;
    }
    if (!/^-?\d+_\d+$/.test(safe)) { post({ type: 'video', id: safe, ok: false, reason: 'неверный номер' }); return; }
    NetTap.api('video.get', { videos: full, extended: 0 }, function (error, response) {
      if (done(error, response, 'video.get')) return;
      // Сайт видео сам спрашивает так (дамп m.vkvideo.ru): другой метод, те же поля.
      NetTap.api('video.getByIds', { videos: full, video_fields: 'files,image' }, function (error2, response2) {
        if (done(error2, response2, 'video.getByIds')) return;
        post({ type: 'video', id: safe, ok: false, reason: String(error2 || error || 'нет файлов').slice(0, 160) });
      });
    });
  }

  function sendMessage(value, operation) {
    if (composerNeedsReset) {
      post({ type: 'sent', ok: false, reason: 'композер ожидает очистки' });
      post({ type: 'attach', stage: 'reset', detail: '' });
      return;
    }
    var sendPeer = String(currentPeer() || '');
    if (operation && !transferCurrent(operation)) return;
    lastSent = { text: String(value), at: Date.now() };
    var input = pick(document, CONFIG.compose.input);
    if (!input) { post({ type: 'sent', ok: false }); return; }

    input.focus();
    var isEditable = input.getAttribute('contenteditable') === 'true' || input.isContentEditable;
    if (isEditable) {
      // Богатый редактор VK слушает обычный ввод: insertText порождает нужные события.
      var ok = false;
      try { ok = document.execCommand('insertText', false, value); } catch (e) { ok = false; }
      if (!ok) {
        input.textContent = value;
        input.dispatchEvent(new InputEvent('input', { bubbles: true, data: value, inputType: 'insertText' }));
      }
    } else {
      setInputValue(input, value);
      input.dispatchEvent(new Event('input', { bubbles: true }));
      input.dispatchEvent(new Event('change', { bubbles: true }));
    }

    // Даём редактору обработать ввод, затем нажимаем «Отправить».
    setTimeout(function () {
      if (String(currentPeer() || '') !== sendPeer || (operation && !transferCurrent(operation))) return;
      var button = pick(document, CONFIG.compose.send);
      var ok = false;
      if (button && !button.disabled) { button.click(); ok = true; }
      else if (input.form) { input.form.requestSubmit ? input.form.requestSubmit() : input.form.submit(); ok = true; }
      if (ok && String(value).trim() && !attachSending) addPendingOut(value);
      if (ok && operation && transferCurrent(operation)) transfer.clicked = true;
      post({ type: 'sent', ok: ok });
    }, 120);
  }

  /**
   * Отправка запросом messages.send от имени страницы (4.22, фоновая отправка очереди): в скрытом окне спящего телефона
   * поле ввода страницы бывает не готово, и нажатие не происходит. random_id — постоянный номер сообщения очереди:
   * сервер не примет второе с тем же номером, повтор не задвоит. Итог — {type:"sent", ok, via:"api", reason}; успешный
   * ответ ловит перехват — приложение получает и «delivered».
   */
  function sendApi(peerValue, value, randomId) {
    var peer = String(peerValue || '') || String(currentPeer() || '');
    var text = String(value || '');
    var rid = Math.floor(Number(randomId));
    if (!/^-?\d{1,12}$/.test(peer) || !text.trim() || !(rid > 0 && rid <= 2147483647)) {
      post({ type: 'sent', ok: false, via: 'api', reason: 'нет переписки, текста или номера' });
      return;
    }
    if (!NetTap.apiReady || !NetTap.apiReady()) { post({ type: 'sent', ok: false, via: 'api', reason: 'нет данных входа' }); return; }
    lastSent = { text: text, at: Date.now() };
    NetTap.api('messages.send', { peer_id: peer, random_id: rid, message: text }, function (error) {
      if (error) { post({ type: 'sent', ok: false, via: 'api', reason: String(error).slice(0, 160) }); return; }
      if (String(currentPeer() || '') === peer) addPendingOut(text);
      post({ type: 'sent', ok: true, via: 'api' });
    });
  }

  /**
   * Голосовое сообщение (4.59): запись приложения (ogg/opus) приходит кусками через stage(), как вложения, и уходит
   * так же, как с сайта: docs.getMessagesUploadServer (type=audio_message) → загрузка файла на выданный сервер VK →
   * docs.save → messages.send с вложением doc…. Итог — {type:"voiceSent", ok, reason}. random_id — постоянный номер:
   * повтор не задвоит.
   */

  function sendVoice(peerValue, randomId, operation) {
    operation = operation || (transfer && transfer.id);
    var peer = String(peerValue || '') || String(currentPeer() || '');
    var rid = Math.floor(Number(randomId));
    var sendStarted = false;
    var documentId = '';
    var controller = typeof AbortController === 'function' ? new AbortController() : null;
    var options = controller ? { signal: controller.signal } : undefined;
    function done(ok, reason, uncertain) {
      if (!transferCurrent(operation)) return;
      post({ type: 'voiceSent', ok: ok, reason: String(reason || '').slice(0, 160), uncertain: !!uncertain, operation: operation, documentId: documentId });
      cancelTransfer(operation);
    }
    if (!transferCurrent(operation) || transfer.peer !== peer) return;
    transfer.abort = function () { if (controller) controller.abort(); };
    transferLater(function () { done(false, 'время ожидания истекло', sendStarted); }, 120000, operation);
    if (!/^-?\d{1,12}$/.test(peer) || !(rid > 0 && rid <= 2147483647)) { done(false, 'нет переписки или номера'); return; }
    if (!NetTap.apiReady || !NetTap.apiReady()) { done(false, 'нет данных входа'); return; }
    var files = buildFiles();
    staged = [];
    if (!files || files.length !== 1) { done(false, 'запись передана не полностью'); return; }
    NetTap.api('docs.getMessagesUploadServer', { type: 'audio_message', peer_id: peer }, function (error, server) {
      if (!transferCurrent(operation)) return;
      var url = server && server.upload_url;
      if (error || typeof url !== 'string' || !NetTap.onHost(url, 'vk')) { done(false, error || 'нет адреса загрузки'); return; }
      var form = new FormData();
      form.append('file', files[0], 'voice.ogg');
      fetch(url, { method: 'POST', body: form, signal: options && options.signal }).then(function (r) { return r.json(); }).then(function (uploaded) {
        if (!transferCurrent(operation)) return;
        if (!uploaded || typeof uploaded.file !== 'string' || !uploaded.file) { done(false, (uploaded && uploaded.error) || 'файл не принят'); return; }
        NetTap.api('docs.save', { file: uploaded.file }, function (error2, saved) {
          if (!transferCurrent(operation)) return;
          var a = saved && (saved.audio_message || (saved.type === 'audio_message' && saved.audio_message));
          if (error2 || !a || !isNum(a.owner_id) || !isNum(a.id)) { done(false, error2 || 'голосовое не сохранено'); return; }
          var attachment = 'doc' + a.owner_id + '_' + a.id + (a.access_key ? '_' + String(a.access_key).replace(/[^0-9a-zA-Z]/g, '') : '');
          documentId = a.owner_id + '_' + a.id;
          post({ type: 'voiceStage', operation: operation, documentId: documentId });
          sendStarted = true;
          NetTap.api('messages.send', { peer_id: peer, random_id: rid, attachment: attachment }, function (error3) {
            if (error3) { done(false, error3, true); return; }
            done(true, '');
          }, false, options);
        }, false, options);
      }).catch(function (e) { done(false, 'загрузка не удалась: ' + (e && e.message)); });
    }, false, options);
  }

  /** Отправленное показываем сразу; настоящее сообщение из API заменит черновое. */
  function addPendingOut(value) {
    var peer = currentPeer();
    if (!peer) return;
    var bucket = historyBucket(peer);
    var key = 'local' + Date.now();
    bucket[key] = {
      key: key, cmid: 0, date: Math.floor(Date.now() / 1000), out: true, fromId: myId(),
      text: String(value).trim(), provisional: true
    };
    trimHistory(peer);
    lastPayload = '';
    scheduleNotify();
  }

  // ------------------------------------------------------------ вложения
  // Файлы выбирает приложение и передаёт странице кусками (base64) через stage(): системный выбор файла из скрытой
  // страницы не открыть (WebView требует настоящего нажатия). Здесь из кусков собираются File, подставляются в поле
  // выбора файла композера (<input type="file">) обычным для браузера способом (DataTransfer + событие change), после чего
  // VK сам загружает их; когда «Отправить» доступно, сообщение уходит как обычно (sendMessage).

  var staged = [];
  // Once VK accepted files, clearing our staging does not clear its upload state.
  // Only a new document is a guaranteed clean composer after cancellation.
  var composerNeedsReset = false;
  var transfer = null;
  var legacyTransfer = 0;
  var MAX_STAGE_FILE_BYTES = 12 * 1024 * 1024;
  var MAX_STAGE_TOTAL_BYTES = 24 * 1024 * 1024;

  function beginTransfer(id, peer) {
    cancelTransfer();
    if (composerNeedsReset) {
      post({ type: 'attach', stage: 'reset', detail: '' });
      return false;
    }
    peer = String(peer || '');
    if (!id || !peer || peer !== String(currentPeer() || '')) return false;
    transfer = { id: String(id), peer: peer, timers: [], bytes: 0 };
    return true;
  }

  function transferCurrent(id) {
    return !!transfer && transfer.id === id && transfer.peer === String(currentPeer() || '');
  }

  function cancelTransfer(id) {
    if (id && (!transfer || transfer.id !== id)) return;
    var dirty = transfer && transfer.filesAttached && !transfer.clicked;
    if (transfer) transfer.timers.forEach(clearTimeout);
    if (transfer && transfer.abort) transfer.abort();
    transfer = null;
    staged = [];
    clearTimeout(attachSendingTimer);
    attachSending = false;
    if (dirty) {
      composerNeedsReset = true;
      post({ type: 'attach', stage: 'reset', detail: '' });
    }
  }

  function transferLater(callback, delay, id) {
    if (!transferCurrent(id)) return;
    var timers = transfer.timers;
    var timer = setTimeout(function () {
      var index = timers.indexOf(timer);
      if (index !== -1) timers.splice(index, 1);
      if (transferCurrent(id)) callback();
    }, delay);
    timers.push(timer);
  }
  // Идёт отправка с вложениями: подпись и вложение — одно сообщение, поэтому отдельного «чернового» пузыря с одной
  // подписью не рисуем; настоящее сообщение целиком (подпись вместе с вложением) приходит из истории.
  var attachSending = false;
  var attachSendingTimer = 0;
  var ATTACH_POLL_MS = 500;
  var ATTACH_MIN_WAIT_MS = 2000;
  var ATTACH_MAX_WAIT_MS = 90000;

  function step(stage, detail) { post({ type: 'attach', stage: stage, detail: detail || '' }); }

  /** Не получилось: приложению — причина, журналу — что есть в композере (без текста сообщений и без номеров). */
  function attachFail(reason) {
    cancelTransfer();
    step('error', reason);
    post({ type: 'log', level: 'warn', message: 'вложения: ' + reason + '; ' + describeComposer(), page: location.pathname.replace(/\d{3,}/g, '#') });
  }

  function describeComposer() {
    var inputs = document.querySelectorAll('input[type="file"]');
    var accepts = [];
    for (var i = 0; i < inputs.length && i < 4; i++) accepts.push((inputs[i].getAttribute('accept') || '*').slice(0, 24) + (inputs[i].multiple ? '+' : ''));
    var ids = [];
    var nodes = document.querySelectorAll('[data-testid*="attach"],[aria-label*="икрепить"],[aria-label*="ttach"]');
    for (var j = 0; j < nodes.length && ids.length < 4; j++) {
      ids.push((nodes[j].getAttribute('data-testid') || nodes[j].getAttribute('aria-label') || nodes[j].tagName).slice(0, 36));
    }
    return ('полей выбора файла=' + inputs.length + ' [' + accepts.join(',') + ']; кнопки=[' + ids.join(',') + ']').slice(0, 190);
  }

  /** Принять кусок файла: index — номер файла, part — номер куска, total — сколько кусков, data — base64. */
  function stageFile(index, name, type, total, part, data, operation) {
    // Первый кусок первого файла — новая отправка: файлы прежней (неудавшейся) попытки в неё не попадают. Раньше после ошибки
    // и повторной отправки с меньшим числом файлов убранный человеком файл уходил вместе с остальными.
    // Compatibility with older callers: still bind the first chunk to its original peer.
    if (!operation && index === 0 && part === 0) beginTransfer('legacy-' + (++legacyTransfer), currentPeer());
    operation = operation || (transfer && transfer.id);
    if (!transferCurrent(operation)) return false;
    if (!Number.isInteger(index) || index < 0 || index >= 10 || !Number.isInteger(total) || total < 1 || total > 64 ||
        !Number.isInteger(part) || part < 0 || part >= total || typeof data !== 'string' || data.length > 262144 ||
        data.length % 4 !== 0 || !/^[A-Za-z0-9+/]*={0,2}$/.test(data)) return false;
    var bytes = data.length / 4 * 3 - (data.endsWith('==') ? 2 : data.endsWith('=') ? 1 : 0);
    var f = staged[index] || (staged[index] = { name: name, type: type, total: total, parts: [], bytes: 0 });
    if (f.total !== total || f.parts[part] !== undefined || f.bytes + bytes > MAX_STAGE_FILE_BYTES ||
        transfer.bytes + bytes > MAX_STAGE_TOTAL_BYTES) return false;
    f.bytes += bytes;
    transfer.bytes += bytes;
    f.parts[part] = data;
    return true;
  }

  function base64ToBytes(b64) {
    var binary = atob(b64);
    var bytes = new Uint8Array(binary.length);
    for (var i = 0; i < binary.length; i++) bytes[i] = binary.charCodeAt(i);
    return bytes;
  }

  /** Собрать File из принятых кусков; null — каких-то кусков не хватает. */
  function buildFiles() {
    var files = [];
    for (var i = 0; i < staged.length; i++) {
      var f = staged[i];
      if (!f) return null;
      var blobs = [];
      for (var p = 0; p < f.total; p++) {
        if (typeof f.parts[p] !== 'string') return null;
        blobs.push(base64ToBytes(f.parts[p]));
      }
      files.push(new File(blobs, f.name, { type: f.type || 'application/octet-stream' }));
      f.parts = []; // File now owns its data; release base64 strings immediately.
    }
    return files;
  }

  /** Поле выбора файла под эти файлы: для картинок и видео — с accept на изображения, для остального — без ограничения. */
  function findFileInput(files) {
    var inputs = Array.prototype.slice.call(document.querySelectorAll('input[type="file"]'));
    if (!inputs.length) return null;
    var media = files.every(function (f) { return /^(image|video)\//.test(f.type); });
    function accept(i) { return (i.getAttribute('accept') || '').toLowerCase(); }
    var found = null;
    inputs.forEach(function (i) {
      if (found) return;
      var a = accept(i);
      if (media ? /image|video/.test(a) : (a === '' || a.indexOf('*/*') !== -1)) found = i;
    });
    return found || inputs[0];
  }

  /** Поля выбора файла в VK бывают за кнопкой «Прикрепить»: если поля нет — нажимаем её и ищем ещё раз. */
  function withFileInput(files, done, operation) {
    var input = findFileInput(files);
    if (input) { done(input); return; }
    var button = document.querySelector('[data-testid*="attach"],[aria-label*="икрепить"],[aria-label*="ttach"]');
    if (!button) { done(null); return; }
    button.click();
    transferLater(function () { done(findFileInput(files)); }, 700, operation);
  }

  function composerSendReady() {
    var button = pick(document, CONFIG.compose.send);
    return !!button && !button.disabled && button.getAttribute('aria-disabled') !== 'true';
  }

  /** Ждём, пока загрузка вложений закончится и «Отправить» станет доступно (дважды подряд, не раньше минимальной паузы). */
  function waitSendReady(done, operation) {
    var started = Date.now();
    var okInRow = 0;
    (function poll() {
      if (!transferCurrent(operation)) return;
      var waited = Date.now() - started;
      okInRow = composerSendReady() ? okInRow + 1 : 0;
      if (waited >= ATTACH_MIN_WAIT_MS && okInRow >= 2) { done(true); return; }
      if (waited > ATTACH_MAX_WAIT_MS) { done(false); return; }
      transferLater(poll, ATTACH_POLL_MS, operation);
    })();
  }

  /** Подставить принятые файлы в композер, дождаться загрузки и отправить сообщение с текстом [text] (он может быть пустым). */
  function attachAndSend(text, operation) {
    operation = operation || (transfer && transfer.id);
    var files = buildFiles();
    if (!files || !files.length) { attachFail('файлы переданы не полностью'); return; }
    if (!transferCurrent(operation)) return;
    step('files', String(files.length));
    withFileInput(files, function (input) {
      if (!transferCurrent(operation)) return;
      if (!input) { attachFail('поле выбора файла не найдено'); return; }
      if (files.length > 1 && !input.multiple) { attachFail('поле выбора файла не поддерживает несколько вложений'); return; }
      try {
        var selection = new DataTransfer();
        files.forEach(function (f) { selection.items.add(f); });
        transfer.filesAttached = true;
        input.files = selection.files;
        input.dispatchEvent(new Event('input', { bubbles: true }));
        input.dispatchEvent(new Event('change', { bubbles: true }));
      } catch (e) { attachFail('не удалось подставить файлы: ' + (e && e.message)); return; }
      step('uploading');
      waitSendReady(function (ready) {
        if (!transferCurrent(operation)) return;
        if (!ready) { attachFail('загрузка вложений не закончилась'); return; }
        staged = [];
        attachSending = true;
        clearTimeout(attachSendingTimer);
        attachSendingTimer = setTimeout(function () { attachSending = false; }, 30000);
        step('sending');
        sendMessage(text || '', operation);
      }, operation);
    }, operation);
  }

  // ------------------------------------------------------------ старые сообщения

  /** Прокручиваем список переписки на странице вверх: мессенджер сам догрузит историю, мы её перехватим. */
  /**
   * Прокручиваемая область переписки. Раньше бралась первая подходящая от первой строки вверх; если VK держит прокрутку
   * на другом элементе (или строк ещё нет), прокручивалось окно, и VK не догружал старое. Теперь — та из областей
   * над строками, у которой больше всего прокрутки, а без строк — самая прокручиваемая область страницы.
   */
  function historyScroller() {
    var best = null;
    var bestRange = 0;
    function consider(el) {
      if (!el || el === document.body || el === document.documentElement) return;
      var range = el.scrollHeight - el.clientHeight;
      if (range <= 10) return;
      var style = getComputedStyle(el);
      if (!/(auto|scroll)/.test(style.overflowY)) return;
      if (range > bestRange) { best = el; bestRange = range; }
    }
    var first = pick(document, CONFIG.history.row);
    for (var el = first && first.parentElement; el && el !== document.body; el = el.parentElement) consider(el);
    if (!best) {
      var all = document.querySelectorAll('div,section,main,ul');
      for (var i = 0; i < all.length && i < 3000; i++) consider(all[i]);
    }
    return best;
  }

  /** Кнопка «Показать предыдущие» (если VK её показывает вместо догрузки прокруткой). */
  function olderButton() {
    var candidates = document.querySelectorAll('button,a,[role="button"]');
    for (var i = 0; i < candidates.length; i++) {
      var t = (candidates[i].textContent || '').trim().toLowerCase();
      if (t.length < 40 && /(предыдущ|ранние|раньше|показать ещё|загрузить ещё)/.test(t)) return candidates[i];
    }
    return null;
  }

  /**
   * Догрузить старые сообщения: нажать «Показать предыдущие», а если её нет — прокрутить переписку к началу.
   * Прокрутка — в два шага (чуть ниже начала, затем к самому началу): VK догружает при движении вверх, а одно
   * присваивание scrollTop = 0, когда область уже наверху, движением не является.
   */
  /**
   * Старые сообщения — запросом messages.getHistory от имени страницы (смещение — сколько сообщений уже есть). Ответ
   * проходит через общий перехват и разбирается, как любой ответ страницы. Раньше старое догружалось только прокруткой
   * скрытой страницы, и мессенджер VK переставал отвечать на неё через несколько страниц — выше определённого места
   * переписка не листалась. Нет данных входа или ошибка — прежний путь (кнопка или прокрутка).
   */
  var OLDER_PAGE = 100;

  function loadOlder() {
    var peer = currentPeer();
    if (peer && NetTap.apiReady && NetTap.apiReady()) {
      var bucket = history[+peer] || {};
      var known = Object.keys(bucket).filter(function (k) { return !bucket[k].provisional; }).length;
      // Приложению уходят последние MAX_HISTORY: старше — не покажутся, запрашивать их незачем (код ревью 3.59).
      if (known >= MAX_HISTORY) { post({ type: 'older', done: true }); return 'limit'; }
      NetTap.api('messages.getHistory', { peer_id: peer, offset: known, count: OLDER_PAGE, extended: 1, fields: 'photo_100,photo_50' }, function (error, r) {
        if (error) {
          post({ type: 'log', level: 'warn', page: location.pathname.replace(/\d{3,}/g, '#'), message: 'старые сообщения запросом не пришли: ' + error });
          loadOlderOnPage();
          return;
        }
        var got = r && Array.isArray(r.items) ? r.items.length : 0;
        post({ type: 'log', level: 'debug', page: location.pathname.replace(/\d{3,}/g, '#'),
          message: 'старые сообщения запросом: было=' + known + ', получено=' + got + ', всего в переписке=' + (r && isNum(r.count) ? r.count : '?') });
        // Ответ уже разобран перехватом; если сообщений не прибавилось (начало переписки), приложению всё равно — ответ.
        if (!got) post({ type: 'older', done: true });
        scheduleNotify();
      });
      return 'api';
    }
    return loadOlderOnPage();
  }

  function loadOlderOnPage() {
    var button = olderButton();
    if (button) { button.click(); return 'button'; }
    var visibility = window.__vkWebVisibility;
    var el = historyScroller();
    var target = el || window;
    function scrollTo(y) {
      if (target === window) window.scrollTo(0, y); else el.scrollTop = y;
      // Окно чтения скрыто: событие прокрутки и пересечения элементов — вручную (assets/page_visibility.js).
      if (visibility && typeof visibility.afterScroll === 'function') visibility.afterScroll(target);
      else target.dispatchEvent(new Event('scroll'));
    }
    scrollTo(Math.min(200, target === window ? window.scrollY : el.scrollTop) || 200);
    setTimeout(function () { scrollTo(0); }, 60);
    return el ? 'scroll' : 'window';
  }

  function setInputValue(el, value) {
    var proto = el.tagName === 'TEXTAREA' ? window.HTMLTextAreaElement : window.HTMLInputElement;
    var desc = proto && Object.getOwnPropertyDescriptor(proto.prototype, 'value');
    if (desc && desc.set) desc.set.call(el, value); else el.value = value;
  }

  // ------------------------------------------------------------ сбор с повторами

  var MAX_ATTEMPTS = 25;
  var RETRY_MS = 350;

  function scrape(kind) {
    var attempt = 0;

    function attemptOnce() {
      attempt++;
      if (loggedOut()) { post({ type: 'unavailable', reason: 'login' }); return; }

      ingestPrefetch();
      if (kind === 'dialogs') {
        var dialogs = dialogsFromStore();
        if (!dialogs.length) dialogs = readDialogs();
        if (dialogs.length) { lastPayload = ''; postDialogs(dialogs); return; }
      } else if (kind === 'history') {
        var peer = currentPeer() || '';
        var fromApi = peer ? historyFromStore(+peer) : [];
        if (fromApi.length) { lastPayload = ''; postHistory(peer, fromApi); return; }
        var fromDom = readHistory();
        if (fromDom.length) {
          postHistory(peer, fromDom.map(function (m) { m.sender = ''; return m; }));
          return;
        }
      }

      if (attempt < MAX_ATTEMPTS) {
        setTimeout(attemptOnce, RETRY_MS);
      } else {
        post({ type: 'unavailable', reason: 'empty' });
      }
    }

    attemptOnce();
  }

  // ------------------------------------------------------------ переходы и обновление без перезагрузки (3.67)

  // Раньше окно чтения каждый раз грузило страницу заново: открыть переписку, вернуться к списку, автообновление раз в
  // минуту — документ мессенджера, скрипты и запросы VK (около 360 КБ) на каждое действие. Мессенджер VK — одностраничное
  // приложение: переход делает он сам (нажатие ссылки), а свежие данные приходят запросом с его же токеном.

  var GO_WAIT_MS = 100;
  var GO_WAIT_STEPS = 30;
  var goToken = 0;

  /** Путь окна чтения: «/mail» или «/mail/convo/N». */
  function readerPath(path) {
    var p = String(path || '');
    return /^\/mail(\/convo\/-?\d{1,12})?\/?$/.test(p) ? p.replace(/\/$/, '') : '';
  }

  /** Ссылка самой страницы на [path] (диалог в списке) — её нажатие передаёт VK всё как при обычном нажатии. */
  function pageLink(path) {
    var links = document.querySelectorAll('a[href*="/mail"]');
    for (var i = 0; i < links.length && i < 2000; i++) {
      var u;
      try { u = new URL(links[i].getAttribute('href'), location.href); } catch (e) { continue; }
      if (u.origin === location.origin && u.pathname.replace(/\/$/, '') === path && !u.search) return links[i];
    }
    return null;
  }

  /**
   * Перейти на [path] силами мессенджера. true — переход начат; данные новой страницы уйдут приложению сами (как после
   * загрузки), плюс свежие — запросом. false — перейти нельзя (не тот адрес, страница не мессенджер): нужна загрузка.
   */
  function go(path) {
    var target = readerPath(path);
    if (!target || loggedOut() || !/^\/mail(\/|$)/.test(location.pathname)) return false;
    var token = ++goToken;
    var kind = /convo/.test(target) ? 'history' : 'dialogs';
    if (location.pathname.replace(/\/$/, '') !== target) {
      try {
        var link = pageLink(target);
        if (!link) {
          link = document.createElement('a');
          link.href = target;
          link.style.display = 'none';
          (document.body || document.documentElement).appendChild(link);
          link.click();
          link.remove();
        } else {
          link.click();
        }
      } catch (e) { return false; }
    }
    var steps = 0;
    (function arrived() {
      if (token !== goToken) return;
      if (location.pathname.replace(/\/$/, '') !== target) {
        if (++steps < GO_WAIT_STEPS) setTimeout(arrived, GO_WAIT_MS);
        return; // не перешла — приложение увидит прежний адрес и загрузит страницу
      }
      lastPayload = '';
      scrape(kind);
      refresh();
    })();
    return true;
  }

  /** Свежие данные открытого (переписка или список) — запросом от имени страницы; false — данных входа нет. */
  function refresh() {
    if (!NetTap.apiReady || !NetTap.apiReady()) return false;
    var peer = currentPeer();
    var done = function () { lastPayload = ''; scheduleNotify(); };
    if (peer) NetTap.api('messages.getHistory', { peer_id: peer, count: 20, extended: 1, fields: 'photo_100,photo_50' }, done);
    else if (isDialogsPage()) NetTap.api('messages.getConversations', { count: 40, extended: 1, fields: 'photo_100,photo_50,online_info' }, done);
    else return false;
    return true;
  }

  // ------------------------------------------------------------ стикеры (3.73)

  // Недавние стикеры страница сама запрашивает при открытии мессенджера (messages.getRecentStickers — дамп 3.70: 32 штуки
  // с картинками). Приложение показывает их своей панелью и отправляет стикер запросом от имени страницы.
  var RECENT_STICKERS_URL = /\/method\/messages\.getRecentStickers(\?|$)/;
  var recentStickers = [];

  function stickerUrl(images) {
    var best = '';
    (Array.isArray(images) ? images : []).forEach(function (im) {
      if (im && typeof im.url === 'string' && NetTap.onHost(im.url, 'image') && (im.width || 0) <= 256) best = im.url;
    });
    return best;
  }

  function stickersFrom(response) {
    var items = response && Array.isArray(response.items) ? response.items : [];
    var out = [];
    items.forEach(function (it) {
      if (!it || !isNum(it.sticker_id) || it.is_allowed === false || out.length >= 60) return;
      var url = stickerUrl(it.images);
      if (url) out.push({ id: it.sticker_id, url: url });
    });
    return out;
  }

  function onRecentStickers(text) {
    try {
      var list = stickersFrom(JSON.parse(text).response);
      if (list.length) recentStickers = list;
    } catch (e) { /* не JSON */ }
  }

  /** Недавние стикеры приложению: {type:"stickers", items:[{id, url}]}; нет известных — запросом от имени страницы. */
  function stickers() {
    if (recentStickers.length) { post({ type: 'stickers', items: recentStickers }); return; }
    NetTap.api('messages.getRecentStickers', {}, function (error, r) {
      if (!error) recentStickers = stickersFrom(r);
      post({ type: 'stickers', items: recentStickers, reason: error ? String(error).slice(0, 120) : '' });
    });
  }

  // ---- подсказки стикеров по словам (3.92)
  // Открыв переписку, страница сама загружает словарь подсказок (store.getStickersKeywords, частями — дамп 3.91:
  // ~1445 групп слов в части, у каждой — свои стикеры). Набрали «привет», «спасибо», «ахаха» — над полем ввода стикеры,
  // как в VK. Сначала — свои недавние (точно доступны), потом остальные; стикер, который VK не дал отправить, больше
  // не предлагается.
  var STICKER_WORDS_URL = /\/method\/store\.getStickersKeywords(\?|$)/;
  var stickerWords = Object.create(null);
  var stickerWordCount = 0;
  var STICKER_WORDS_MAX = 80000;
  var STICKER_SUGGEST_MAX = 8;
  var stickerDenied = {};

  function wordKey(text) {
    return String(text || '').toLowerCase().replace(/ё/g, 'е').replace(/[\s!?.,…)(:;"'«»]+$/g, '').replace(/^[\s"'«»]+/, '').replace(/\s+/g, ' ');
  }

  function onStickerWords(text) {
    var r;
    try { r = JSON.parse(text).response; } catch (e) { return; }
    var dict = r && Array.isArray(r.dictionary) ? r.dictionary : [];
    dict.forEach(function (group) {
      if (!group || !Array.isArray(group.words)) return;
      var ids = [];
      ['user_stickers', 'stickers', 'promoted_stickers'].forEach(function (k) {
        (Array.isArray(group[k]) ? group[k] : []).forEach(function (st) {
          var id = st && (st.sticker_id || st.id);
          if (isNum(id) && id > 0 && ids.indexOf(id) === -1 && ids.length < 24) ids.push(id);
        });
      });
      if (!ids.length) return;
      group.words.forEach(function (w) {
        if (typeof w !== 'string' || stickerWordCount >= STICKER_WORDS_MAX) return;
        var key = wordKey(w);
        if (!key || key.length > 60) return;
        if (!stickerWords[key]) { stickerWords[key] = []; stickerWordCount++; }
        var list = stickerWords[key];
        ids.forEach(function (id) { if (list.indexOf(id) === -1 && list.length < 32) list.push(id); });
      });
    });
  }

  function suggestImage(id) {
    // Тот же адрес, что у недавних (https://vk.ru/sticker/1-<номер>-128): сайт открытой страницы без «m.».
    return 'https://' + location.hostname.replace(/^m\./, '') + '/sticker/1-' + id + '-128';
  }

  /** Подсказки для набранного [text]: {type:"stickersuggest", query, items:[{id, url}]} (пусто — нет). */
  function stickerSuggest(text) {
    var key = wordKey(text);
    var ids = key && key.length <= 60 ? (stickerWords[key] || []) : [];
    var recent = recentStickers.map(function (st) { return st.id; });
    var mine = ids.filter(function (id) { return recent.indexOf(id) !== -1; });
    var rest = ids.filter(function (id) { return recent.indexOf(id) === -1; });
    var items = mine.concat(rest).filter(function (id) { return !stickerDenied[id]; }).slice(0, STICKER_SUGGEST_MAX).map(function (id) {
      var known = recentStickers.filter(function (st) { return st.id === id; })[0];
      return { id: id, url: known ? known.url : suggestImage(id) };
    });
    post({ type: 'stickersuggest', query: String(text || '').slice(0, 80), items: items });
  }

  /** Отправить стикер [id] в открытую переписку; итог — {type:"stickersent", ok, reason}, затем свежая история. */
  function sendSticker(id) {
    var peer = currentPeer();
    var sid = +id;
    if (!peer || !isNum(sid) || sid <= 0) { post({ type: 'stickersent', ok: false, reason: 'нет переписки или стикера' }); return; }
    NetTap.api('messages.send', { peer_id: peer, random_id: Math.floor(Math.random() * 2147483647), sticker_id: sid }, function (error) {
      // Недоступный стикер (чужой платный набор) больше не подсказывается.
      if (error) stickerDenied[sid] = true;
      post({ type: 'stickersent', ok: !error, reason: error ? String(error).slice(0, 160) : '' });
      if (!error) refresh();
    });
  }

  // ------------------------------------------------------------ публичный API

  // ------------------------------------------------------------ музыка из сообщения: играет сама страница

  // Как в обычном VK: трек из сообщения включается прямо в переписке — нажатием на его вложение в вёрстке мессенджера
  // (её держит окно чтения), без перехода в раздел музыки. Звучащий элемент находится по вызову play() (страница
  // создаёт его сама, часто вне документа); его состояние уходит приложению сообщением type = "audio".
  var chatMedia = null;
  var chatAudioTitle = '';
  var chatAudioWantedAt = 0;
  var lastAudioPost = 0;
  /** Звук хоть раз заиграл: пауза человеком в первые секунды — не «не заиграл» (иначе уводило в раздел музыки). */
  var chatAudioStarted = false;

  function audioPost(state, detail) {
    var m = chatMedia;
    post({
      type: 'audio', state: state, title: chatAudioTitle,
      position: m ? Math.floor(m.currentTime || 0) : 0,
      duration: m && isFinite(m.duration) ? Math.floor(m.duration) : 0,
      detail: String(detail || '').slice(0, 200)
    });
  }

  function watchMedia(m) {
    if (chatMedia === m) return;
    chatMedia = m;
    ['playing', 'pause', 'ended', 'error'].forEach(function (name) {
      m.addEventListener(name, function () {
        if (chatMedia !== m) return;
        if (name === 'playing') chatAudioStarted = true;
        audioPost(name === 'playing' ? 'playing' : (name === 'pause' ? 'paused' : (name === 'ended' ? 'ended' : 'failed')),
          name === 'error' && m.error ? 'код ' + m.error.code : '');
      });
    });
    m.addEventListener('timeupdate', function () {
      var now = Date.now();
      if (chatMedia !== m || now - lastAudioPost < 1000) return;
      lastAudioPost = now;
      audioPost(m.paused ? 'paused' : 'playing');
    });
  }

  (function hookPlay() {
    var proto = window.HTMLMediaElement && window.HTMLMediaElement.prototype;
    if (!proto || typeof proto.play !== 'function' || proto.__vkReaderPlayHook) return;
    var original = proto.play;
    proto.play = function () {
      // Только звук, о котором попросили (звуки уведомлений мессенджера сюда не попадают), и не видеосообщения.
      if (this.tagName !== 'VIDEO' && Date.now() - chatAudioWantedAt < 10000) watchMedia(this);
      return original.apply(this, arguments);
    };
    try { Object.defineProperty(proto, '__vkReaderPlayHook', { value: true }); } catch (e) { /* без пометки */ }
  })();

  /** Вложение-трек в вёрстке: по номеру «владелец_номер» в data-id, кортеже data-audio, onclick или data-атрибутах. */
  function audioElement(id) {
    var safe = String(id).replace(/[^0-9_-]/g, '');
    if (!safe) return null;
    var el = document.querySelector('.audio_item[data-id="' + safe + '"]') || document.querySelector('.audio_item[data-id^="' + safe + '_"]');
    if (el) return el;
    var tuples = document.querySelectorAll('[data-audio]');
    for (var i = 0; i < tuples.length; i++) {
      try {
        var t = JSON.parse(tuples[i].getAttribute('data-audio'));
        if (Array.isArray(t) && t[1] + '_' + t[0] === safe) return tuples[i];
      } catch (e) { /* чужая разметка */ }
    }
    var exact = document.querySelector('[data-id="' + safe + '"],[data-audio-id="' + safe + '"],[data-content-id="' + safe + '"]');
    if (exact) return exact;
    // В onclick номер — подстрокой: «1_2» совпадало бы и с «11_23» (код ревью 3.59) — проверяем границы номера.
    var bounded = new RegExp('(^|[^0-9_-])' + safe.replace(/-/g, '\\-') + '(?![0-9])');
    var clicks = document.querySelectorAll('[onclick*="' + safe + '"]');
    for (var j = 0; j < clicks.length; j++) if (bounded.test(clicks[j].getAttribute('onclick') || '')) return clicks[j];
    return null;
  }

  /** Слова для сравнения названий: без регистра, знаков и «ё». */
  function titleWords(value) {
    return String(value || '').toLowerCase().replace(/ё/g, 'е').split(/[^0-9a-zа-я]+/i).filter(Boolean);
  }

  /**
   * Вложение-трек в новой вёрстке мессенджера (отчёт 3.51: класс AttachAudio, номера трека в разметке нет) — по названию:
   * то вложение, в тексте которого больше всего слов «Исполнитель — Название»; одно вложение-трек — оно и есть.
   */
  function attachAudioByTitle(title) {
    var nodes = Array.prototype.filter.call(document.querySelectorAll('[class*="AttachAudio"]'), function (el) {
      return String(el.className || '').split(/\s+/).indexOf('AttachAudio') !== -1;
    });
    if (!nodes.length) return null;
    var want = titleWords(title);
    var best = null;
    var bestScore = 0;
    nodes.forEach(function (el) {
      var have = titleWords(el.textContent);
      var score = want.filter(function (w) { return have.indexOf(w) !== -1; }).length;
      if (score > bestScore) { best = el; bestScore = score; }
    });
    // Совпало хотя бы больше половины слов — это оно; иначе только если вложение-трек на странице одно.
    if (best && bestScore * 2 > want.length) return best;
    return nodes.length === 1 ? nodes[0] : null;
  }

  /** Что нажимать во вложении: кнопку «играть» (или обложку, на которой она нарисована), иначе само вложение. */
  function playTarget(el) {
    return el.querySelector('[class*="AttachAudio__play"],[class*="play"],[class*="Play"],[class*="AttachAudio__album"],button,[role="button"]') || el;
  }

  /** Что похоже на музыку в вёрстке — для журнала, когда трек не нашёлся (без текстов и номеров). */
  function audioFacts() {
    var classes = {};
    var nodes = document.querySelectorAll('[class*="udio"]');
    for (var i = 0; i < nodes.length && Object.keys(classes).length < 4; i++) {
      var c = String(nodes[i].className || '').split(/\s+/).filter(function (x) { return /udio/.test(x); })[0];
      if (c) classes[c.replace(/\d+/g, '#').slice(0, 40)] = true;
    }
    return '.audio_item=' + document.querySelectorAll('.audio_item').length + ', [data-audio]=' + document.querySelectorAll('[data-audio]').length +
      ', классы=' + (Object.keys(classes).join('|') || 'нет') + ', плеер страницы=' + (window.audioplayer ? 'есть' : 'нет');
  }

  function playAudio(id, title) {
    chatAudioTitle = String(title || '').slice(0, 200);
    var byTitle = false;
    var el = audioElement(id);
    if (!el) { el = attachAudioByTitle(chatAudioTitle); byTitle = !!el; }
    if (!el) { audioPost('notfound', audioFacts()); return false; }
    chatAudioWantedAt = Date.now();
    chatAudioStarted = false;
    if (chatMedia) { try { chatMedia.pause(); } catch (e) { /* уже остановлен */ } chatMedia = null; }
    // Вложение новой вёрстки нажимается кнопкой «играть» (обложкой), старое (.audio_item) — само.
    (byTitle ? playTarget(el) : el).click();
    // Нажатие на сам элемент не включило — пробуем его кнопку «играть»; не вышло и так — сообщаем.
    setTimeout(function () {
      if (chatAudioStarted || (chatMedia && !chatMedia.paused)) return;
      var button = byTitle ? el : el.querySelector('[class*="play"],[class*="Play"],button,[role="button"]');
      if (button) button.click();
      setTimeout(function () {
        if (!chatAudioStarted && (!chatMedia || chatMedia.paused)) audioPost('failed', 'не заиграл после нажатия; ' + audioFacts());
      }, 2500);
    }, 1200);
    return true;
  }

  /** Пауза/продолжить и остановить — у звучащего элемента (кнопки приложения в переписке). */
  function audioCommand(action) {
    var m = chatMedia;
    if (!m) return false;
    try {
      if (action === 'toggle') { if (m.paused) m.play(); else m.pause(); }
      else if (action === 'stop') { m.pause(); audioPost('stopped'); chatMedia = null; }
    } catch (e) { return false; }
    return true;
  }

  Object.defineProperty(window, '__vkReader', {
    value: Object.freeze({ protocol: 1, contract: function () { return window.__vkCompat.describe('reader', window.__vkReader); }, scrape: scrape, beginTransfer: beginTransfer, cancelTransfer: cancelTransfer, send: sendMessage, sendApi: sendApi, reply: sendReply, loadOlder: loadOlder, stage: stageFile, attachAndSend: attachAndSend, sendVoice: sendVoice, reactionAssets: reactionAssets, react: react, playAudio: playAudio, audio: audioCommand, forward: forwardMessages, me: me, video: videoInfo, audioUrl: audioUrl, audioQueue: audioQueue, markRead: markRead, prefetch: prefetch, self: sendSelf, members: chatMembers, deleteMessage: deleteMessage, editMessage: editMessage, convAction: convAction, dialogs: refreshDialogs, go: go, refresh: refresh, stickers: stickers, sendSticker: sendSticker, stickerSuggest: stickerSuggest }),
    writable: false,
    configurable: false
  });
})();
