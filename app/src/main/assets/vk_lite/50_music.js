// Часть assets/vk_lite/: Свой экран музыки («Мои треки» и плейлисты).
// Выполняется внутри общей функции из 00_core.js (см. web/PageScripts.kt), отдельно не запускается.

  // ------------------------------------------------- музыка: «Мои треки» для своего экрана

  // Трек в ответах VK — массив: [0] id, [1] владелец, [3] название, [4] исполнитель (с HTML-кодами),
  // [5] длительность в секундах, [11] контекст («general:my_audios_block» у «Моих треков»), [14] обложка.
  // Списки приходят ответами /audio?act=… (ResponseTap) и в атрибутах data-audio у .audio_item.
  // Ссылки на звук закрыты VK — их не трогаем: играет плеер страницы, мы только нажимаем трек.
  var MY_AUDIOS = 'my_audios';
  var MUSIC_PATH = /^\/audio/;
  var MUSIC_NOTIFY_MS = 200;
  var musicTracks = {};
  var musicOrder = [];
  var musicHasMore = false;
  // Сервер явно сообщил, есть ли продолжение «Моих треков» (ответ с hasMore). Только тогда список можно считать полным:
  // первая порция приходит в вёрстке без такого ответа, а кнопки «Показать ещё» в этот момент может ещё не быть —
  // раньше это принималось за «весь список», и приложение выбрасывало сохранённые треки (список «сжимался»).
  var musicHasMoreKnown = false;
  var musicListKey = '';

  // Главная «Моей музыки» показывает лишь несколько верхних треков (превью), а полный список — на отдельной странице
  // (/audio?act=block&block=…). Раньше экран показывал сохранённый полный список, но включить трек можно было только
  // из тех нескольких, что есть на главной, а плейлист VK после них кончался. Теперь трек включается со страницы полного
  // списка: туда страница переходит сама, а нужный трек догружается, если его ещё нет.
  var MY_BLOCK_KEY = 'vkweb_my_audios_block';
  var PENDING_PLAY_KEY = 'vkweb_pending_play';
  var PENDING_MAX_MORE = 60;
  // Отложенное включение действует недолго: если трек не нашёлся, а список перестал догружаться, запрос не должен
  // «выстрелить» через час, когда человек снова откроет полный список (внезапно заиграл бы старый трек).
  var PENDING_TTL_MS = 60000;
  var pendingMoreCount = 0;
  var pendingTimer = 0;
  /** Трек, который включили по команде приложения: его название — пока страница не сообщит своё. */
  var musicHint = null;
  /** Скачивание трека, которому нужен поток со страницы (4.95: одно за раз — второе получает отказ «занято»). */
  var pendingMusicDownload = null;
  /** Адреса потоков трека из ответов API (mp3 и HLS), по номеру трека: для кэша и скачивания (4.95). */
  var streamById = {};
  var streamIds = [];
  var STREAM_MEMORY = 400;

  // 4.45: продолжение после конца загруженного списка и «добавить к себе». Объявлены здесь, а не в своём разделе ниже:
  // разбор ответов (collectTracks) может прийти раньше, чем выполнится конец файла.
  var AFTER_PREFIX = 'after:';
  /** «владелец_номер» плейлиста → добавлен ли к себе (по ответам страницы, поле isFollowed). */
  var playlistFollowed = {};
  var queueLastId = '';
  /** Порядок списка, из которого играет трек (строки страницы, пока список был на ней). */
  var queueOrder = [];
  var musicCommandAt = 0;
  var continueTimer = 0;

  function store(key, value) {
    try { if (value == null) sessionStorage.removeItem(key); else sessionStorage.setItem(key, value); } catch (e) { /* без памяти вкладки */ }
  }

  function stored(key) {
    try { return sessionStorage.getItem(key); } catch (e) { return null; }
  }

  function blockParam(href) {
    var m = /[?&]block=([^&#]+)/.exec(href || '');
    return m ? m[1] : '';
  }

  /** Страница полного списка «Моей музыки» (опознаётся по ссылке, по которой на неё перешли с главной). */
  function isMyAudiosBlock() {
    if (location.pathname !== '/audio' || !/(^|[?&])act=block/.test(location.search)) return false;
    var mine = stored(MY_BLOCK_KEY);
    return !!mine && blockParam(location.search) === mine;
  }

  /** «Мои треки» — главная или страница полного списка: для приложения это один список. */
  function isMyList() { return isMyAudiosPage() || isMyAudiosBlock(); }

  /**
   * Ссылка на полный список «Моих треков» на главной: из ссылок на страницы-блоки — та, что ближе всех к трекам
   * с меткой my_audios (у рекомендаций рядом свои ссылки, но до «моих» треков от них дальше).
   */
  function myAudiosBlockLink() {
    var links = document.querySelectorAll('a[href*="act=block"]');
    var best = null;
    var bestDepth = 99;
    for (var i = 0; i < links.length; i++) {
      var el = links[i];
      for (var depth = 1; depth <= 8 && el; depth++) {
        el = el.parentElement;
        if (el && el.querySelector('.audio_item[data-audio*="' + MY_AUDIOS + '"]')) {
          if (depth < bestDepth) { best = links[i]; bestDepth = depth; }
          break;
        }
      }
    }
    return best;
  }

  /** Перейти на страницу полного списка; [pendingId] — включить там этот трек. */
  function goToMyBlock(pendingId) {
    var link = myAudiosBlockLink();
    if (!link) return false;
    store(MY_BLOCK_KEY, blockParam(link.getAttribute('href')));
    if (pendingId) setPending(pendingId);
    pendingMoreCount = 0;
    Diag.debug('переход на полный список «Моих треков»' + (pendingId ? ' для включения трека' : ''));
    link.click(); // переход силами страницы (без перезагрузки, если она так умеет)
    return true;
  }

  /** Запрос «включить трек» с отметкой времени. */
  function setPending(id) { store(PENDING_PLAY_KEY, String(id) + '|' + Date.now()); }

  /** Действующий запрос (не старше PENDING_TTL_MS) или null; устаревший удаляется. */
  function activePending() {
    var raw = stored(PENDING_PLAY_KEY);
    if (!raw) return null;
    var cut = raw.lastIndexOf('|');
    var at = cut > 0 ? Number(raw.slice(cut + 1)) : 0;
    if (!at || Date.now() - at > PENDING_TTL_MS) { store(PENDING_PLAY_KEY, null); return null; }
    return raw.slice(0, cut);
  }

  /** Каким способом нашёлся последний искомый трек (для журнала). */
  var lastLookup = 'нет';

  function trackElement(id) {
    var el = MusicRows.find(id);
    lastLookup = MusicRows.last() || 'нет';
    return el;
  }

  /**
   * Первый трек вёрстки, кортеж которого (data-audio) подходит под [match]; null — нет. Один обход для поиска по номеру
   * и по названию (код ревью 4.43: был переписан дважды).
   */
  function findTrackItem(match) {
    var items = MusicRows.rows(document);
    for (var i = 0; i < items.length; i++) {
      try {
        var t = JSON.parse(items[i].getAttribute('data-audio'));
        if (isTrackTuple(t) && match(t)) return items[i];
      } catch (e) { /* чужая разметка */ }
    }
    return null;
  }

  // ---- журнал включения треков (для разбора «не нажимается и не играет»)

  var playSeq = 0;
  var playbackGeneration = 0;

  function traceMusic(message) { Diag.event('info', 'музыка [' + (CONFIG.cacheWorker ? 'кэш' : 'главное') + ']: ' + message); }

  /** Сведения о странице музыки: что это за страница и сколько в ней треков (без текстов и адресов). */
  function pageFacts() {
    var act = (location.search.match(/[?&]act=([^&]*)/) || [])[1] || '-';
    var items = document.querySelectorAll('.audio_item').length;
    return 'страница={путь=' + location.pathname + ', act=' + String(act).replace(/-?\d+/g, '#') + ', главная=' + isMyAudiosPage() +
      ', мои=' + isMyList() + ', плейлист=' + isPlaylistPage() + ', треков в вёрстке=' + items + '}';
  }

  function mediaSource(m) {
    var src = m.currentSrc || m.src || '';
    if (!src) return 'нет';
    if (src.indexOf('blob:') === 0) return 'blob';
    try { return new URL(src).hostname; } catch (e) { return 'непонятный'; }
  }

  function mediaBrief() {
    var m = currentMedia();
    if (!m) return 'медиа-элемента нет';
    return 'paused=' + m.paused + ', позиция=' + Math.round(m.currentTime) + ', готовность=' + m.readyState + ', сеть=' + m.networkState +
      ', ошибка=' + (m.error ? m.error.code : 0) + ', источник=' + mediaSource(m);
  }

  /** Через 1,5 и 4 с после команды: началось ли воспроизведение; плюс события элемента за это время. */
  function verifyPlay(seq) {
    var generation = playbackGeneration;
    var events = [];
    var listener = function (e) { if (events.length < 12) events.push(e.type); };
    var names = ['play', 'playing', 'pause', 'waiting', 'stalled', 'error', 'emptied', 'loadstart', 'canplay'];
    names.forEach(function (n) { document.addEventListener(n, listener, true); });
    [1500, 4000].forEach(function (ms) {
      setTimeout(function () {
        if (generation !== playbackGeneration) { names.forEach(function (n) { document.removeEventListener(n, listener, true); }); return; }
        MusicDiagnostics.capture('verify-' + ms);
        traceMusic('включение №' + seq + ' через ' + ms + ' мс: ' + mediaBrief() + ', события=' + (events.join(',') || 'нет'));
        if (ms === 1500) retryClickIfSilent(seq);
        if (ms === 4000) names.forEach(function (n) { document.removeEventListener(n, listener, true); });
      }, ms);
    });
  }

  /** Что есть в вёрстке, когда трек не найден: количество, образцы data-id, ссылка на страницу (для отчёта; без текстов). */
  function trackLookupFacts() {
    var items = document.querySelectorAll('.audio_item');
    var sample = [];
    for (var i = 0; i < items.length && sample.length < 3; i++) sample.push(String(items[i].getAttribute('data-id') || '-'));
    return 'в вёрстке треков=' + items.length + ', с data-audio=' + document.querySelectorAll('.audio_item[data-audio]').length +
      ', образцы data-id=' + sample.join('|') + ', в списке приложения=' + Object.keys(musicTracks).length;
  }

  var lastClicked = null;

  function playElement(el, id) {
    playbackGeneration++;
    if (!playResolve || playResolve.current !== id) MusicDiagnostics.begin(id);
    MusicDiagnostics.capture('row-click', el);
    musicHint = musicTracks[id] || musicHint;
    // Нажали на тот трек, что уже звучит, — это пауза по желанию человека: повторять нажатие нельзя.
    var md = null;
    try { md = navigator.mediaSession && navigator.mediaSession.metadata; } catch (e) { md = null; }
    var info = musicTracks[id];
    var cur = currentMedia();
    var same = !!(md && info && cur && !cur.paused && normTitle(md.title) === normTitle(info.title));
    lastClicked = { el: el, at: Date.now(), retried: same };
    el.click();
  }

  /**
   * Нажатие не включило трек (отчёт 3.58: после смены плейлиста нажатие на трек ставило на паузу прежний, новый не
   * начинался; сразу после открытия полного списка — «медиа-элемента нет»). Один повтор — кнопкой «играть» внутри строки
   * трека (иначе самой строкой).
   */
  function retryClickIfSilent(seq) {
    var c = lastClicked;
    if (!c || c.retried || Date.now() - c.at > 3000 || !c.el.isConnected) return;
    var m = currentMedia();
    if (m && !m.paused) return;
    c.retried = true;
    var button = c.el.querySelector('.ai_play,[class*="play_btn"],[class*="PlayButton"],[class*="ai_play"],[role="button"],button');
    traceMusic('включение №' + seq + ': звука нет — повторное нажатие (' + (button ? 'кнопка «играть»' : 'строка трека') + ')');
    MusicDiagnostics.capture('retry-click', button || c.el);
    (button || c.el).click();
  }

  /** Отложенное включение после перехода: трек есть — включаем, нет — догружаем список (ограниченно). */
  function tryPendingPlay() {
    var raw = activePending();
    if (!raw || !isMyAudiosBlock()) return;
    // «after:владелец_номер» (4.45): включить трек, следующий за этим, — продолжение после конца загруженного списка.
    var after = raw.indexOf(AFTER_PREFIX) === 0;
    var id = after ? raw.slice(AFTER_PREFIX.length) : raw;
    var el = trackElement(id);
    if (el && after) {
      var nextRow = rowAfter(el);
      if (nextRow) {
        store(PENDING_PLAY_KEY, null);
        pendingMoreCount = 0;
        traceMusic('продолжение: список открыт заново, включается трек после последнего сыгранного');
        noteMusicCommand();
        playElement(nextRow, nextRow.getAttribute('data-id'));
        return;
      }
      el = null; // следующего ещё нет в вёрстке — догрузка ниже
    }
    if (el) {
      store(PENDING_PLAY_KEY, null);
      pendingMoreCount = 0;
      traceMusic('отложенное включение: трек найден способом «' + lastLookup + '», нажимаем');
      playElement(el, id);
      verifyPlay(0);
      return;
    }
    if (pendingMoreCount >= PENDING_MAX_MORE || (musicHasMoreKnown && !musicHasMore) || PageList.isBlocked()) {
      Diag.event('warn', 'музыка: трек для включения не найден в полном списке, догрузок=' + pendingMoreCount + ', ' + trackLookupFacts());
      store(PENDING_PLAY_KEY, null);
      return;
    }
    if (pendingTimer) return;
    pendingTimer = setTimeout(function () {
      pendingTimer = 0;
      pendingMoreCount++;
      loadMoreTracks();
    }, 300);
  }

  // ---- «Предыдущий трек»
  // «Следующий» работает и без помощи страницы: трек перематывается в конец, и VK сам включает следующий. Для «назад»
  // такого приёма нет — запасной путь лишь начинал текущий трек заново, и вернуться к предыдущему было нельзя. Поэтому
  // предыдущий трек берётся из списка на странице: строка перед играющей включается нажатием, как обычный трек.

  /** Номер играющего трека: по выделенной строке списка, иначе по названию и исполнителю из Media Session, иначе по последнему включённому из приложения. */
  function currentTrackId() {
    return observedTrackId() || (musicHint && musicHint.id ? String(musicHint.id) : '');
  }

  /** Only page observations, without the last requested ID as a fallback. */
  function observedTrackId() {
    if (typeof MusicRows !== 'undefined') {
      var selected = MusicRows.current();
      if (selected) return selected;
    }
    var row = typeof MusicRows === 'undefined' ? document.querySelector('.audio_item[class*="playing"],.audio_item[class*="_current"],.audio_item[class*="--current"]') : null;
    if (row && row.getAttribute('data-id')) return row.getAttribute('data-id');
    var meta = navigator.mediaSession && navigator.mediaSession.metadata;
    if (meta && meta.title) {
      var title = norm(meta.title);
      var artist = norm(meta.artist || '');
      var found = '';
      for (var id in musicTracks) {
        var t = musicTracks[id];
        if (norm(t.title) === title && (!artist || norm(t.artist) === artist)) {
          if (found) return '';
          found = id;
        }
      }
      return found;
    }
    return '';
  }

  /** Строка списка рядом с треком [id]: [step] = -1 — предыдущая, 1 — следующая. */
  function adjacentTrackRow(id, step) {
    var rows = document.querySelectorAll('.audio_item[data-id]');
    for (var i = 0; i < rows.length; i++) {
      if (rows[i].getAttribute('data-id') === id) return rows[i + step] || null;
    }
    return null;
  }

  /** Включить трек перед играющим. false — его нет в списке на странице (первый трек или список другой страницы). */
  function previousTrack() {
    var id = currentTrackId();
    var row = id ? adjacentTrackRow(id, -1) : null;
    if (!row) {
      Diag.debug('музыка: предыдущего трека в списке нет' + (id ? '' : ' (играющий не определён)'));
      return false;
    }
    musicHint = musicTracks[row.getAttribute('data-id')] || null; // название в уведомлении, пока страница не сообщит своё
    row.click();
    return true;
  }

  /** Что играет сейчас (для уведомления и экрана блокировки), если страница не сообщает этого сама. */
  function musicNowPlaying() {
    var el = document.querySelector('.audio_item[class*="playing"],.audio_item[class*="_current"],.audio_item[class*="--current"]');
    if (el) {
      try {
        var t = JSON.parse(el.getAttribute('data-audio'));
        if (isTrackTuple(t)) return { title: plainText(t[3]), artist: plainText(t[4]) };
      } catch (e) { /* чужая разметка */ }
    }
    return musicHint;
  }

  /** Трек кончился: подсказка больше не про звучащий. */
  function musicTrackEnded() { musicHint = null; }

  function isMusicPage() { return MUSIC_PATH.test(location.pathname); }

  /**
   * Главная страница музыки («Мои треки»): там на странице есть и рекомендации, поэтому берём
   * только треки с меткой my_audios. На остальных страницах музыки (плейлист, альбом, поиск)
   * берём все треки страницы.
   */
  function isMyAudiosPage() { return location.pathname === '/audio' && !/(^|[?&])act=/.test(location.search) && !isSearchPage(); }

  // 4.79: поиск музыки для своего экрана. Страница поиска — /audio?section=general&q=… (MUSIC_SEARCH_PATH ниже).
  var SEARCH_QUERY = /(^|[?&])q=([^&#]*)/;

  /** Запрос открытой страницы поиска музыки; '' — это не поиск. */
  function searchQueryHere() {
    if (location.pathname !== '/audio' || /(^|[?&])act=audio_playlist/.test(location.search)) return '';
    var m = SEARCH_QUERY.exec(location.search);
    if (!m) return '';
    var q = m[2].replace(/\+/g, ' ');
    try { q = decodeURIComponent(q); } catch (e) { /* как есть */ }
    return q.replace(/\s+/g, ' ').trim().slice(0, 200);
  }

  function isSearchPage() { return !!searchQueryHere(); }

  /** Ключ текущего списка: сменилась страница музыки — список начинается заново. */
  function currentListKey() {
    // Полный список «Моих треков» — тот же список, что и главная: сохранённое сливается, а не заменяется.
    if (isMyAudiosBlock()) return '/audio';
    return location.pathname + location.search.replace(/[?&](offset|from)=[^&]*/g, '');
  }

  // Переход между списками силами страницы: адрес уже новый, а в вёрстке ещё треки прежнего списка. Раньше они
  // попадали в новый список (плейлист из 10 треков показывался с 25 чужими сверху), а нажатие на такой трек не
  // находило его на странице. Каждый прочитанный элемент помнит, для какого списка его прочитали: элемент,
  // прочитанный для другого списка и с тем же data-audio, — остаток прежней вёрстки, он не читается.
  var SCANNED_PROP = '__vkLiteScanned';

  function isStaleItem(el) {
    var mark = el[SCANNED_PROP];
    return !!mark && mark.key !== musicListKey && mark.audio === el.getAttribute('data-audio');
  }

  function markScanned(el) { el[SCANNED_PROP] = { key: musicListKey, audio: el.getAttribute('data-audio') }; }

  // Треки прежнего списка (после перехода между списками) и треки, пришедшие в ответах сервера для нового. Отчёт 3.48:
  // первая порция только что открытого плейлиста повторяла прежний байт в байт (8 треков _2 → «_6», 10 треков _3 → «_1») —
  // вёрстка прежнего плейлиста перерисовывалась уже под новым адресом новыми элементами, и пометка элементов не помогала.
  // Теперь трек прежнего списка из вёрстки принимается, только если он есть и в ответе сервера для нового списка.
  var previousIds = {};
  var previousOrder = [];
  var responseIds = {};
  var collectingResponse = false;
  var staleLoggedFor = '';

  function ensureListKey() {
    var key = currentListKey();
    if (key === musicListKey) return;
    previousIds = musicListKey ? musicTracks : {};
    previousOrder = musicListKey ? musicOrder : [];
    leftoverCheckedAt = 0;
    responseIds = {};
    musicListKey = key;
    musicTracks = {};
    musicOrder = [];
    musicHasMore = false;
    musicHasMoreKnown = false;
    musicChannel.reset();
    // Результаты поиска пришли ответом API раньше, чем страница сменила адрес на поиск (ввод в её поле): список нового
    // адреса начинается с них.
    seedApiSearch();
  }

  /**
   * Название или исполнитель трека как текст (4.88): HTML-коды раскрыты, разметка убрана. В результатах поиска VK
   * может выделять совпавшие слова тегами (<em>…</em>) — с ними исполнитель «Король и Шут» не совпадал с собственным
   * именем, и «Только исполнитель» отбрасывал все найденные треки.
   */
  function plainText(value) {
    return decodeEntities(value).replace(/<\/?[a-z][^<>]*>/gi, '').replace(/\s+/g, ' ').trim();
  }

  function isTrackTuple(a) {
    return Array.isArray(a) && a.length >= 15 && typeof a[0] === 'number' && typeof a[1] === 'number' &&
      typeof a[3] === 'string' && typeof a[4] === 'string' && typeof a[5] === 'number';
  }

  /** В данных трека ([12]) VK пишет {"like":true}, если трек уже добавлен к себе. */
  function trackLiked(t) {
    try { var extra = typeof t[12] === 'string' ? JSON.parse(t[12]) : t[12]; return !!(extra && extra.like === true); } catch (e) { return false; }
  }

  function coverOf(raw) {
    if (typeof raw !== 'string' || !raw) return '';
    // Несколько размеров через запятую, от меньшего к большему — берём самый большой.
    var urls = raw.split(/,(?=https?:)/);
    return urls[urls.length - 1];
  }

  /** Добавляет трек «Моих треков» или обновляет уже известный. Возвращает true, если что-то изменилось. */
  // ---- адрес трека из списка (4.99.4)
  //
  // В списке трека (кортеж, t[2]) адрес звука замаскирован: https://m.vk.ru/mp3/audio_api_unavailable.mp3?extra=A#B.
  // Плеер страницы раскрывает его сам: A — адрес в base64 со своим алфавитом (без w и W), B — цепочка преобразований
  // (переворот, сдвиг, xor, перестановка, перестановка с номером человека). Тот же алгоритм здесь: с ним кэш получает
  // поток любого трека «Моей музыки» и плейлиста, не включая плеер. Раскрытое принимается, только если это https-адрес
  // на серверах звука VK (NetTap.onHost) — иначе (алгоритм у VK сменился) трек кэшируется прежними путями.
  var UNMASK_ABC = 'abcdefghijklmnopqrstuvxyzABCDEFGHIJKLMNOPQRSTUVXYZ0123456789+/=';

  function unmaskBase64(text) {
    if (!text || text.length % 4 === 1) return '';
    var out = '';
    var acc = 0;
    var n = 0;
    for (var k = 0; k < text.length; k++) {
      var v = UNMASK_ABC.indexOf(text.charAt(k));
      if (v === -1) continue;
      acc = n % 4 ? 64 * acc + v : v;
      if (n++ % 4) out += String.fromCharCode(255 & acc >> (-2 * n & 6));
    }
    return out;
  }

  function unmaskShuffleOrder(length, seed) {
    var order = [];
    var e = seed;
    for (var a = length - 1; a >= 0; a--) {
      e = (length * (a + 1) ^ e + a) % length;
      order[a] = e;
    }
    return order;
  }

  function unmaskShuffle(text, seed) {
    var length = text.length;
    if (!length) return text;
    var order = unmaskShuffleOrder(length, seed);
    var chars = text.split('');
    for (var a = 1; a < length; a++) {
      var removed = chars.splice(order[length - 1 - a], 1, chars[a])[0];
      chars[a] = removed;
    }
    return chars.join('');
  }

  function unmaskOp(text, op, vkId) {
    var parts = op.split('\x0b');
    var cmd = parts[0];
    var arg = parts[1];
    if (cmd === 'v') return text.split('').reverse().join('');
    if (cmd === 'r') {
      var abc2 = UNMASK_ABC + UNMASK_ABC;
      var shift = parseInt(arg, 10);
      return text.split('').map(function (c) {
        var at = abc2.indexOf(c);
        if (at === -1) return c;
        var to = at - shift;
        return abc2.charAt(to < 0 ? to + abc2.length : to);
      }).join('');
    }
    if (cmd === 'x') {
      var code = String(arg).charCodeAt(0);
      return text.split('').map(function (c) { return String.fromCharCode(c.charCodeAt(0) ^ code); }).join('');
    }
    if (cmd === 's') return unmaskShuffle(text, parseInt(arg, 10));
    if (cmd === 'i') return unmaskShuffle(text, parseInt(arg, 10) ^ vkId);
    return null;
  }

  /** Раскрыть замаскированный адрес; '' — не маска, не раскрылось или не сервер звука VK. */
  function unmaskAudioUrl(masked, vkId) {
    try {
      if (typeof masked !== 'string' || masked.indexOf('audio_api_unavailable') === -1) return '';
      var extra = masked.split('?extra=')[1];
      if (!extra) return '';
      var halves = extra.split('#');
      var text = unmaskBase64(halves[0]);
      var opsText = halves[1] ? unmaskBase64(halves[1]) : '';
      if (opsText) {
        var ops = opsText.split('\t').reverse();
        for (var k = 0; k < ops.length; k++) {
          text = unmaskOp(text, ops[k], vkId);
          if (text === null) return '';
        }
      }
      return text.indexOf('https://') === 0 && NetTap.onHost(text, 'audio') ? text : '';
    } catch (e) {
      return '';
    }
  }

  /** Номер человека для раскрытия: из данных трека (поле vk_id), иначе страницы. */
  function vkIdOf(t) {
    for (var k = 0; k < t.length; k++) {
      var o = t[k];
      if (o && typeof o === 'object' && !Array.isArray(o) && o.vk_id) return apiNumber(o.vk_id);
    }
    return window.vk && window.vk.id ? apiNumber(window.vk.id) : 0;
  }

  function rememberTupleStream(id, t) {
    var url = unmaskAudioUrl(t[2], vkIdOf(t));
    if (!url) return;
    rememberStreams(id, /\.m3u8(\?|$)/i.test(url) ? { mp3: '', hls: url } : { mp3: url, hls: '' });
  }

  function rememberTrack(t) {
    // Список поиска собран из ответа API: кортежи в вёрстке этой страницы — её подборки, а не найденное.
    if (apiSearchKey && apiSearchKey === musicListKey) return false;
    var id = t[1] + '_' + t[0];
    if (collectingResponse) responseIds[id] = true;
    var known = musicTracks[id];
    var mine = typeof t[11] === 'string' && t[11].indexOf(MY_AUDIOS) !== -1;
    // На главной — только «Мои треки» (без рекомендаций); на странице плейлиста — все его треки.
    if (!known && !mine && isMyAudiosPage()) return false;
    rememberTupleStream(id, t);
    musicTracks[id] = {
      id: id,
      title: plainText(t[3]),
      artist: plainText(t[4]),
      album: Array.isArray(t[17]) && t[17][0] ? plainText(t[17][0].name || '') : (known ? known.album || '' : ''),
      duration: t[5],
      cover: coverOf(t[14]) || (known ? known.cover : ''),
      // 4.95: обложка для тегов скачанного файла (в вёрстке и так самая большая из размеров).
      coverLarge: coverOf(t[14]) || (known ? known.coverLarge || known.cover : ''),
      // 4.45: трек уже у человека в музыке — «Мои треки» или отметка like в данных трека (у VK это одно и то же).
      added: mine || trackLiked(t) || !!(known && known.added),
      key: typeof t[24] === 'string' ? t[24].replace(/[^0-9a-zA-Z]/g, '').slice(0, 40) : (known ? known.key : '')
    };
    if (!known) musicOrder.push(id);
    return !known;
  }

  function collectTracks(node, depth) {
    if (!node || typeof node !== 'object' || depth > 30) return false;
    var changed = false;
    if (Array.isArray(node)) {
      if (isTrackTuple(node)) return rememberTrack(node);
      for (var i = 0; i < node.length; i++) changed = collectTracks(node[i], depth + 1) || changed;
      return changed;
    }
    // Плейлист в ответе: добавлен ли он к себе (4.45).
    if (node.type === 'playlist' && typeof node.isFollowed === 'boolean' && (typeof node.ownerId === 'number' || typeof node.owner_id === 'number')) {
      playlistFollowed[String(typeof node.ownerId === 'number' ? node.ownerId : node.owner_id) + '_' + String(node.id)] = node.isFollowed;
    }
    // Плейлист «Моих треков» (или открытый плейлист): по нему узнаём, есть ли ещё страницы.
    if (Array.isArray(node.list) && typeof node.hasMore === 'boolean') {
      var first = node.list[0];
      var ofMine = isTrackTuple(first) && String(first[11] || '').indexOf(MY_AUDIOS) !== -1;
      // На странице плейлиста приходят только его треки: ответ с «есть ли ещё» относится к нему.
      if (ofMine || (isTrackTuple(first) && !isMyList())) {
        musicHasMore = node.hasMore;
        musicHasMoreKnown = true;
      }
    }
    for (var key in node) {
      if (Object.prototype.hasOwnProperty.call(node, key)) changed = collectTracks(node[key], depth + 1) || changed;
    }
    return changed;
  }

  /** Служебный ответ при включении трека (act=reload_audio…): новых треков в список не добавляет. */
  function isReloadResponse(data) {
    return data && Array.isArray(data.data) && data.data.length === 1 && Array.isArray(data.data[0]) &&
      isTrackTuple(data.data[0][0]);
  }

  function onMusicResponse(url, body, text) {
    var first = text.charAt(0);
    if (first !== '{' && first !== '[') return;
    if (isMyAudiosPage() && text.indexOf(MY_AUDIOS) === -1) return;
    ensureListKey();
    var data;
    try { data = JSON.parse(text); } catch (e) { return; }
    if (isReloadResponse(data)) {
      // Только обновляем уже известные треки (например, обложку), не расширяя список.
      data.data[0].forEach(function (t) { if (isTrackTuple(t) && musicTracks[t[1] + '_' + t[0]]) rememberTrack(t); });
    } else {
      collectingResponse = true;
      try {
        collectTracks(data, 0);
        // Страница при переходе получает вёрстку нового списка строкой (поле html): треки в ней — точно нового списка.
        collectHtmlTracks(data, 0);
      } finally { collectingResponse = false; }
    }
    scheduleMusicNotify();
    tryPendingPlay();
  }

  /** Кортежи треков из вёрстки, пришедшей строкой в ответе (data-audio="[…]"). */
  function collectHtmlTracks(node, depth) {
    if (!node || depth > 6) return;
    if (typeof node === 'string') {
      if (node.indexOf('data-audio') === -1) return;
      var re = /data-audio=(["'])(.*?)\1/g;
      var m;
      var box = document.createElement('textarea');
      while ((m = re.exec(node))) {
        box.innerHTML = m[2]; // HTML-коды (&quot;) → текст
        try { var t = JSON.parse(box.value); if (isTrackTuple(t)) rememberTrack(t); } catch (e) { /* чужая разметка */ }
      }
      return;
    }
    if (typeof node !== 'object') return;
    for (var k in node) if (Object.prototype.hasOwnProperty.call(node, k)) collectHtmlTracks(node[k], depth + 1);
  }

  // Вёрстка повторяет прежний список (те же треки в том же порядке с начала) — это его перерисовка, а не новый список.
  // Код ревью 3.51: раньше отсекался любой трек прежнего списка, которого не было в ответе. Плейлисты обычно собраны из
  // «Моих треков», и при переходе «Мои треки» → плейлист, если ответ с вёрсткой не перехвачен, плейлист оказался бы
  // почти пустым. Теперь отсекается только вёрстка, совпадающая с прежним списком.
  var LEFTOVER_MIN_MATCH = 3;
  var leftoverCheckedAt = 0;
  var leftoverDom = false;

  function domRepeatsPrevious() {
    var now = Date.now();
    if (now - leftoverCheckedAt < 300) return leftoverDom;
    leftoverCheckedAt = now;
    var items = document.querySelectorAll('.audio_item[data-audio]');
    var ids = [];
    for (var i = 0; i < items.length && ids.length < previousOrder.length; i++) {
      try { var t = JSON.parse(items[i].getAttribute('data-audio')); if (isTrackTuple(t)) ids.push(t[1] + '_' + t[0]); } catch (e) { /* чужая разметка */ }
    }
    var same = ids.length >= Math.min(LEFTOVER_MIN_MATCH, previousOrder.length) && ids.length > 0;
    for (var j = 0; same && j < ids.length; j++) same = ids[j] === previousOrder[j];
    leftoverDom = same;
    return same;
  }

  /** Трек из вёрстки — остаток прежнего списка: был в нём, не пришёл в ответе для нового, и вёрстка — прежний список. */
  function isLeftover(t) {
    var id = t[1] + '_' + t[0];
    return !!previousIds[id] && !responseIds[id] && domRepeatsPrevious();
  }

  function scanMusicDom(root) {
    if (!root || !root.querySelectorAll) return;
    ensureListKey();
    var items = MusicRows.rows(root);
    var fresh = 0;
    var staleSeen = 0;
    for (var i = 0; i < items.length; i++) {
      if (isStaleItem(items[i])) { staleSeen++; continue; }
      var t = null;
      try { t = JSON.parse(items[i].getAttribute('data-audio')); } catch (e) { /* чужая разметка */ }
      if (!isTrackTuple(t)) continue;
      if (isLeftover(t)) { staleSeen++; continue; }
      markScanned(items[i]);
      fresh++;
      rememberTrack(t);
    }
    if (staleSeen && staleLoggedFor !== musicListKey) {
      staleLoggedFor = musicListKey;
      Diag.debug('музыка: пропущены треки прежнего списка в вёрстке: ' + staleSeen);
    }
    if (fresh) tryChatPending();
    if (fresh) {
      scheduleMusicNotify();
      tryPendingPlay();
      continuePendingFromRoot();
    }
    retryDiscoveryOnRoot();
  }

  var pendingNavAt = 0;

  /**
   * Трек «Моих треков» просили включить, пока страница была на другой странице музыки (плейлисте): страница вернулась
   * на главную — дальше обычный путь, через полный список (goToMyBlock). Не чаще раза в 1,5 с: переход ещё идёт.
   */
  function continuePendingFromRoot() {
    var id = activePending();
    if (!id || !isMyAudiosPage() || Date.now() - pendingNavAt < 1500) return;
    pendingNavAt = Date.now();
    goToMyBlock(id);
  }

  function scheduleMusicNotify() {
    if (isMusicPage()) musicChannel.schedule();
  }

  function buildMusicPayload() {
    if (!isMusicPage()) return null;
    ensureListKey();
    var landing = isMyAudiosPage();
    var my = isMyList();
    // 4.88: список поиска — из ответа API целиком (продолжения у страницы нет): показанное сокращается до присланного.
    var apiSearchOwnsList = !!apiSearchKey && apiSearchKey === musicListKey;
    var moreButton = !!document.querySelector(landing ? '[class*="showMore--' + MY_AUDIOS + '"]' : '.show_more') ||
      // На главной превью: продолжение — на странице полного списка.
      (landing && !!myAudiosBlockLink());
    return {
      type: 'music',
      kind: my ? 'my' : 'list',
      // Какая это страница: приложение показывает «Мои треки» и треки открытого плейлиста раздельно.
      page: my ? 'my' : (isPlaylistPage() ? 'playlist' : (isSearchPage() ? 'search' : 'other')),
      // 4.79: запрос страницы поиска — приложение показывает результаты только того поиска, который ждёт.
      query: searchQueryHere(),
      title: my ? '' : String(document.title || '').replace(/\s+/g, ' ').trim().slice(0, 120),
      key: musicListKey,
      // «Есть ещё» приходит в ответе сервера (musicHasMore) — но первая порция лежит прямо в вёрстке, без такого
      // ответа, поэтому смотрим и на кнопку «Показать ещё»: у «Моих треков» — на свою (у рекомендаций рядом
      // есть другие), у плейлистов — на общую.
      // Пока сервер не ответил, продолжение считается возможным: догрузка попробует, а сохранённое не пропадёт.
      hasMore: !apiSearchOwnsList && (musicHasMore || moreButton || !musicHasMoreKnown) && !PageList.isBlocked(),
      // Весь список — только по явному ответу сервера, а не по отсутствию кнопки.
      complete: musicHasMoreKnown && !musicHasMore && (!moreButton || apiSearchOwnsList),
      // 4.45: открытый плейлист — чей он и добавлен ли к себе (кнопка «Добавить к себе» в приложении).
      playlist: isPlaylistPage() ? openedPlaylistFacts() : null,
      items: musicOrder.map(publicMusicTrack)
    };
  }

  var musicChannel = createChannel(MUSIC_NOTIFY_MS, buildMusicPayload);
  ResponseTap.listen(isMusicPage, onMusicResponse);

  // ---- результаты поиска из ответа API (4.88)
  // Страница поиска музыки m.vk получает найденное ответом catalog.getAudioSearch (объекты {id, owner_id, artist, title,
  // duration, url, …}), а не вёрсткой с data-audio. Скрипт читал только вёрстку — поэтому поиск показывал пустой список
  // (отчёт 4.86: «найдено треков=0», хотя VK вернул 21 трек), а на странице поиска, открытой переходом, — её подборки
  // («показано=1» из 24 чужих). Теперь список страницы поиска — из ответа API для её запроса; кортежи в вёрстке этой
  // страницы не подмешиваются. У найденных есть адрес звука (url): их играет приложение (вёрстки, на которую можно
  // нажать, у них нет).
  var apiSearch = null; // { query: нормализованный запрос, list: [объекты API] }
  var apiSearchKey = '';
  var API_SEARCH_MAX = 500;

  function apiNumber(value) {
    if (typeof value === 'number' && isFinite(value)) return value;
    if (typeof value === 'string' && /^-?\d+$/.test(value)) return Number(value);
    return 0;
  }

  // VK меняет типы полей и обёртки API между мобильными версиями: принимаем числовые строки и
  // распространённые варианты списков, но всё равно отбрасываем элементы без валидных id.
  // 4.95 (код ревью): «id + owner_id + title» есть и у плейлистов, и у альбомов — они попадали в найденное как треки.
  // Теперь у трека обязателен признак звука: длительность, исполнитель или адрес потока.
  function isApiAudio(o) {
    if (!o || typeof o !== 'object' || Array.isArray(o)) return false;
    if (apiNumber(o.id) === 0 || apiNumber(o.owner_id != null ? o.owner_id : o.ownerId) === 0) return false;
    if (typeof o.title !== 'string' && typeof o.name !== 'string') return false;
    if (o.type === 'playlist' || o.type === 'album' || o.playlist_type != null || o.count != null && o.duration == null) return false;
    var artist = o.artist != null ? o.artist : o.artists;
    var hasArtist = typeof artist === 'string' || Array.isArray(artist) && artist.length > 0;
    var hasDuration = apiNumber(o.duration || o.duration_sec) > 0;
    var hasStream = typeof o.url === 'string' || o.audio_streams != null || o.audioStreams != null;
    return hasArtist || hasDuration || hasStream;
  }

  function apiAudioList(value, depth) {
    if (!value || depth > 5) return null;
    if (Array.isArray(value)) {
      var direct = value.filter(isApiAudio);
      if (direct.length) return direct;
      var nestedEmpty = null;
      for (var j = 0; j < value.length; j++) {
        var nested = apiAudioList(value[j], depth + 1);
        if (nested && nested.length) return nested;
        if (nested !== null) nestedEmpty = nested;
      }
      return nestedEmpty || direct;
    }
    if (typeof value !== 'object') return null;
    var keys = ['audios', 'audio', 'items', 'tracks', 'list', 'records', 'catalog', 'payload', 'data', 'response', 'result'];
    var empty = null;
    for (var i = 0; i < keys.length; i++) {
      if (value[keys[i]] == null) continue;
      var list = apiAudioList(value[keys[i]], depth + 1);
      if (list && list.length) return list;
      if (list !== null) empty = list;
    }
    return empty;
  }

  function apiText(value) {
    if (typeof value === 'string') return plainText(value);
    if (Array.isArray(value)) return value.map(apiText).filter(Boolean).join(', ');
    if (value && typeof value === 'object') return apiText(value.name || value.title || value.value || '');
    return '';
  }

  /** Обложка из ответа API: [large] — самая большая (теги файла), иначе ~300 px для списка (как до 4.91). */
  function apiCover(o, large) {
    var album = o.album && typeof o.album === 'object' ? o.album : {};
    var thumb = album.thumb || album.cover || {};
    if (typeof thumb === 'string') return thumb;
    if (Array.isArray(thumb)) {
      var urls = thumb.map(function (t) { return t && (t.url || t.src); })
        .filter(function (u) { return typeof u === 'string' && u.indexOf('https://') === 0; });
      if (!urls.length) return '';
      // Размеры по возрастанию: для списка — средний, для тегов — последний.
      return large ? urls[urls.length - 1] : urls[Math.min(urls.length - 1, 1)];
    }
    if (!thumb || typeof thumb !== 'object') return '';
    var url = large
      ? thumb.photo_1200 || thumb.photo_600 || thumb.photo_300 || thumb.photo_270 || thumb.photo_135 || thumb.photo_68 || thumb.url || thumb.src || ''
      : thumb.photo_300 || thumb.photo_270 || thumb.photo_600 || thumb.photo_135 || thumb.photo_68 || thumb.url || thumb.src || '';
    return typeof url === 'string' ? url : '';
  }

  /**
   * Потоки трека из ответа API: mp3 (его играет и приложение) и HLS (4.95: годится для кэша и скачивания — приложение
   * само собирает из него mp3). Только https на серверах звука.
   */
  function apiStreams(o) {
    var ok = function (u) { return typeof u === 'string' && NetTap.onHost(u, 'audio') && !/audio_api_unavailable/.test(u) ? u : ''; };
    var result = { mp3: '', hls: '', dash: '' };
    var streams = o.audio_streams || o.audioStreams || o.streams || [];
    if (typeof streams === 'string') { try { streams = JSON.parse(streams); } catch (e) { streams = []; } }
    if (!Array.isArray(streams) && streams && typeof streams === 'object') streams = Object.keys(streams).map(function (k) { return streams[k]; });
    if (Array.isArray(streams)) {
      for (var i = 0; i < streams.length; i++) {
        var st = streams[i];
        if (!st || typeof st !== 'object') continue;
        var kind = String(st.type || st.codec || st.format || '').toLowerCase();
        var u = ok(st.url) || ok(st.fallback_url) || ok(st.fallbackUrl) || ok(st.src);
        if (!u) continue;
        if (!result.mp3 && kind.indexOf('mp3') !== -1) result.mp3 = u;
        else if (!result.hls && (kind.indexOf('hls') !== -1 || /\.m3u8(\?|$)/i.test(u))) result.hls = u;
        else if (!result.dash && (kind.indexOf('dash') !== -1 || /\.mpd(\?|$)/i.test(u))) result.dash = u;
      }
    }
    if (!result.mp3) {
      var legacy = ok(o.url);
      if (legacy && /\.m3u8(\?|$)/i.test(legacy)) { if (!result.hls) result.hls = legacy; }
      else if (legacy && /\.mpd(\?|$)/i.test(legacy)) { if (!result.dash) result.dash = legacy; }
      else result.mp3 = legacy;
    }
    return result;
  }

  /** Запомнить потоки трека [id] (последние [STREAM_MEMORY] треков). */
  function streamDeadline(url) {
    var now = Date.now(), until = now + 300000;
    try {
      var u = new URL(url), expires = u.searchParams.get('expires') || u.searchParams.get('expires_at') || '';
      if (/^\d{10}$/.test(expires)) until = Math.min(until, Number(expires) * 1000);
      else if (/^\d{13}$/.test(expires)) until = Math.min(until, Number(expires));
    } catch (e) { return 0; }
    return until;
  }

  function rememberStreams(id, streams, fresh) {
    if (!id || !streams || !streams.mp3 && !streams.hls && !streams.dash) return;
    var known = streamById[id];
    if (!known) {
      streamIds.push(id);
      if (streamIds.length > STREAM_MEMORY) delete streamById[streamIds.shift()];
    }
    var next = { until: {} };
    ['mp3', 'hls', 'dash'].forEach(function (kind) {
      var url = streams[kind] || (known && known[kind]) || '';
      if (!url || url.length > 4000 || !NetTap.onHost(url, 'audio')) return;
      next[kind] = url;
      next.until[kind] = known && known[kind] === url && !(fresh === true && streams[kind]) && known.until ? known.until[kind] : streamDeadline(url);
    });
    streamById[id] = next;
  }

  /** Signed URLs are transient; local MP3 files have an independent lifetime. */
  function musicStreamFor(id) {
    var known = streamById[id];
    if (!known || !known.until) return '';
    var kinds = ['mp3', 'hls', 'dash'];
    for (var i = 0; i < kinds.length; i++) {
      var kind = kinds[i];
      if (known[kind] && known.until[kind] > Date.now()) return known[kind];
    }
    return '';
  }

  function publicMusicTrack(id) {
    var value = Object.assign({}, musicTracks[id]);
    var known = streamById[id];
    value.url = known && known.until && known.until.mp3 > Date.now() ? known.mp3 || '' : '';
    value.streamExpiresAt = value.url ? known.until.mp3 : 0;
    return value;
  }

  function rememberApiAudio(o) {
    var ownerId = apiNumber(o.owner_id != null ? o.owner_id : o.ownerId);
    var id = ownerId + '_' + apiNumber(o.id);
    var known = musicTracks[id];
    var streams = apiStreams(o);
    musicTracks[id] = {
      id: id,
      title: apiText(o.title || o.name),
      artist: apiText(o.artist != null ? o.artist : o.artists),
      album: apiText(o.album && typeof o.album === 'object' ? (o.album.title || o.album.name || o.album) : (o.album || o.album_title || (known && known.album) || '')),
      duration: apiNumber(o.duration || o.duration_sec),
      cover: apiCover(o, false) || (known ? known.cover : ''),
      coverLarge: apiCover(o, true) || (known ? known.coverLarge || known.cover : ''),
      added: o.like === true || !!(known && known.added),
      key: typeof o.access_key === 'string' ? o.access_key.replace(/[^0-9a-zA-Z]/g, '').slice(0, 40) : (known ? known.key : ''),
      // Адрес звука: mp3 из audio_streams (так играет и сама страница), иначе старое поле url — только https на серверах
      // звука; сервер проверяет и приложение (SafeUrls.audio).
      url: streams.mp3,
      // 4.95: HLS-поток — для кэша и скачивания (приложение собирает mp3 само); играть его приложение не берётся.
      hls: streams.hls
    };
    rememberStreams(id, streams, true);
    if (!known) musicOrder.push(id);
    return !known;
  }

  /** Открыта страница поиска того запроса, на который пришёл ответ API. */
  function apiSearchMatchesPage() {
    return !!apiSearch && isSearchPage() && normTitle(searchQueryHere()) === apiSearch.query;
  }

  /** Список текущего адреса — найденное из ответа API (вызывается из ensureListKey после сброса списка). */
  function seedApiSearch() {
    if (!apiSearchMatchesPage()) return false;
    if (apiSearchKey !== musicListKey) {
      musicTracks = {};
      musicOrder = [];
      apiSearchKey = musicListKey;
    }
    apiSearch.list.forEach(rememberApiAudio);
    musicHasMore = false;
    musicHasMoreKnown = true;
    return true;
  }

  function onSearchApi(method, params, body) {
    var r = body && (body.response != null ? body.response : (body.data != null ? body.data : body));
    var found = apiAudioList(r, 0);
    if (found === null) return false;
    // 4.95 (код ревью): пустой список где-то в обёртках — не ответ «ничего не найдено». Пустым он считается только у
    // самого поиска и только если VK прислал поле со списком треков.
    if (!found.length && !(r && typeof r === 'object' && (Array.isArray(r.audios) || Array.isArray(r.items)))) return false;
    if (method === 'catalog.getAudioSearch') {
      var query = normTitle(params && (params.query || params.q || params.search_query));
      // Заготовка экрана поиска без запроса (need_blocks) — это подборки, не поиск.
      if (!query) return false;
      apiSearch = { query: query, list: found.slice(0, API_SEARCH_MAX) };
      apiSearchKey = '';
      traceMusic('поиск: ответ API, треков=' + found.length + ', страница поиска этого запроса=' + apiSearchMatchesPage());
    } else {
      // Продолжение списка (раздел или блок каталога) — только на странице поиска, у которой уже есть ответ API.
      if (!apiSearchMatchesPage()) return false;
      var seen = {};
      apiSearch.list.forEach(function (o) { seen[o.owner_id + '_' + o.id] = true; });
      found.forEach(function (o) { if (!seen[o.owner_id + '_' + o.id] && apiSearch.list.length < API_SEARCH_MAX) apiSearch.list.push(o); });
    }
    if (!apiSearchMatchesPage()) return false; // адрес ещё прежний: список начнётся с них при смене адреса
    ensureListKey();
    seedApiSearch();
    musicChannel.reset();
    return true;
  }

  /** Все треки в ответе API (на любой глубине): запомнить их потоки — для кэша и скачивания (4.95). */
  function collectApiStreams(node, depth) {
    if (!node || typeof node !== 'object' || depth > 12) return;
    if (!Array.isArray(node) && isApiAudio(node)) {
      var id = String(apiNumber(node.owner_id != null ? node.owner_id : node.ownerId)) + '_' + String(apiNumber(node.id));
      rememberStreams(id, apiStreams(node), true);
      return;
    }
    if (Array.isArray(node)) {
      for (var i = 0; i < node.length; i++) collectApiStreams(node[i], depth + 1);
    } else {
      var keys = Object.keys(node);
      for (var k = 0; k < keys.length; k++) collectApiStreams(node[keys[k]], depth + 1);
    }
  }

  /** Ответы API со звуком: потоки запоминаются; ждущее скачивание получает адрес сразу. Список экрана не меняется. */
  function onAudioApi(method, params, body) {
    MusicDiagnostics.network('audioApiResponses');
    collectApiStreams(body, 0);
    var job = pendingMusicDownload;
    if (job) {
      var url = musicStreamFor(job.id);
      if (url) musicDownloadResult(job, url);
    }
    return false;
  }

  /**
   * Плейлист HLS, который загружает плеер страницы (4.99.2, 4.99.6). Мобильная VK играет трек через MSE (у элемента —
   * blob:), адрес плейлиста виден только в запросе плеера (cs*.vkuseraudio.ru/s/v1/ac/…).
   *
   * 4.99.6 (отчёт 22.10 23:33): в 4.99.2 трек брался в момент запроса — а плеер запрашивает плейлист раньше, чем страница
   * отмечает новый трек, и адрес не запоминался ни разу. Теперь: плейлисты из одной папки адреса (основной и вариант)
   * — одна загрузка; её адрес — первый. Загрузка достаётся треку, который играет через [HLS_SETTLE_MS] после неё и всё
   * ещё тот же ещё через [HLS_SETTLE_MS]; если за это время началась другая загрузка (переключили трек) — эта
   * отбрасывается: чужой трек в кэше хуже, чем никакого.
   */
  var hlsRequests = {};
  var hlsRequestOrder = [];
  var HLS_REQUESTS_MAX = 64;
  function hlsDir(url) { return String(url).split('?')[0].replace(/\/[^\/]*$/, ''); }
  var HLS_SETTLE_MS = 1500;
  var HLS_SETTLE_TRIES = 8;
  var lastHlsLoad = null;

  NetTap.onRequest(function (url) {
    if (!NetTap.onHost(url, 'audio') || hlsRequests[url] !== undefined) return;
    MusicDiagnostics.network('audioRequests');
    hlsRequests[url] = { at: Date.now(), generation: playbackGeneration };
    hlsRequestOrder.push(url);
    if (hlsRequestOrder.length > HLS_REQUESTS_MAX) delete hlsRequests[hlsRequestOrder.shift()];
  });

  NetTap.listen({
    playlist: true,
    wants: function (url) { return hlsRequests[url] !== undefined; },
    onText: function (url, body, text) {
      var request = hlsRequests[url];
      var at = request && request.at;
      delete hlsRequests[url];
      if (!request || request.generation !== playbackGeneration) return;
      var format = /^\s*#EXTM3U/.test(text) ? 'hls' : /^(?:\s*<\?xml[^>]*>)?\s*<(?:[A-Za-z_][\w.-]*:)?MPD[\s>]/i.test(text) ? 'dash' : '';
      if (!at || !format) return;
      MusicDiagnostics.network(format === 'dash' ? 'dashResponses' : 'hlsResponses');
      MusicDiagnostics.capture(format + '-response');
      // Вариант той же загрузки (основной плейлист → плейлист качества, перезапрос при перемотке) — в той же папке
      // адреса: запомнен уже основной. Другая папка — другой трек, даже если пришёл через секунду.
      if (lastHlsLoad && Date.now() - lastHlsLoad.at < 10000 && lastHlsLoad.format === format &&
          (format === 'hls' ? hlsDir(url) === hlsDir(lastHlsLoad.url) : url === lastHlsLoad.url)) return;
      var load = { url: url, at: at, seenId: '', tries: 0, format: format, generation: request.generation };
      lastHlsLoad = load;
      setTimeout(function () { attributeHlsLoad(load); }, HLS_SETTLE_MS);
    }
  });

  NetTap.onResponse(function (url, transport, info) {
    if (NetTap.onHost(url, 'audio')) MusicDiagnostics.response(url, transport, info);
  });

  function attributeHlsLoad(load) {
    if (load.generation !== playbackGeneration) return;
    var format = load.format || 'hls';
    MusicDiagnostics.capture(format + '-attribute-' + load.tries);
    if (lastHlsLoad !== load) { Diag.debug('музыка: манифест ' + format + ' — трек переключили, адрес не запомнен'); return; }
    var id = format === 'dash' ? observedTrackId() : currentTrackId();
    if (!id || id !== load.seenId) {
      load.seenId = id;
      if (++load.tries < HLS_SETTLE_TRIES) setTimeout(function () { attributeHlsLoad(load); }, HLS_SETTLE_MS);
      else { MusicDiagnostics.capture(format + '-unattributed'); Diag.debug('музыка: манифест ' + format + ' — трек не определился, адрес не запомнен'); }
      return;
    }
    MusicDiagnostics.capture(format + '-attributed');
    var known = streamById[id];
    if (!(known && known[format] === load.url && known.until && known.until[format] > Date.now())) {
      var streams = {}; streams[format] = load.url;
      rememberStreams(id, streams, true);
      Diag.debug('музыка: поток ' + format + ' трека запомнен (для кэша)');
      // Приложение узнаёт адрес с очередным состоянием, не дожидаясь события плеера.
      try { report(); } catch (e) { /* состояние уйдёт со следующим событием */ }
    }
    var job = pendingMusicDownload;
    if (job && job.id === id) musicDownloadResult(job, load.url);
  }

  // Плеер страницы получает адрес трека ответом API и на других страницах (музыка играет и вне раздела): слушаем везде.
  ResponseTap.listenApi(function () { return true; }, /audio/i, onAudioApi, function () {});

  ResponseTap.listenApi(isMusicPage, /^catalog\.(getAudioSearch|getSection|getBlockItems)$/, onSearchApi, scheduleMusicNotify);

  if (typeof MutationObserver === 'function') {
    new MutationObserver(function (mutations) {
      if (!isMusicPage()) return;
      // Список, в который добавились треки, разбираем один раз на пачку, а не на каждый трек: при догрузке 50 строк в
      // список из 500 это были тысячи разборов (код ревью 3.59).
      var scannedParents = [];
      for (var i = 0; i < mutations.length; i++) {
        var added = mutations[i].addedNodes;
        for (var j = 0; j < added.length; j++) {
          var n = added[j];
          if (n.nodeType !== 1) continue;
          if (n.matches && n.matches('.audio_item[data-audio]')) {
            var parent = n.parentElement || n;
            if (scannedParents.indexOf(parent) === -1) { scannedParents.push(parent); scanMusicDom(parent); }
          } else scanMusicDom(n);
          // Кнопка «Показать ещё» появилась позже треков: приложению нужно узнать, что есть продолжение.
          if (isShowMore(n)) scheduleMusicNotify();
          onPlaylistNode(n);
        }
      }
    }).observe(document, { childList: true, subtree: true });
  }

  function loadMoreTracks() {
    return PageList.loadMore(['[class*="showMore--' + MY_AUDIOS + '"]', '.show_more']);
  }

  function isShowMore(n) {
    var selector = '[class*="showMore--' + MY_AUDIOS + '"],.show_more';
    try { return (n.matches && n.matches(selector)) || (n.querySelector && !!n.querySelector(selector)); } catch (e) { return false; }
  }

  // ---- музыка из переписки: включить трек, которого может не быть в списках страницы

  // Прямого адреса звука в сообщении нет, а плеер страницы включает только трек из вёрстки. Поэтому: трек есть на
  // странице — нажимаем; нет — ищем его по названию поиском самой страницы музыки и нажимаем в результатах.
  // Ожидание переживает полную загрузку (поиск может сменить страницу): запрос лежит в памяти вкладки.
  var CHAT_PLAY_KEY = 'vkweb_chat_play';
  var CHAT_PLAY_TTL_MS = 20000;
  // Запрос поиска и адрес, на котором его уже ввели: страница поиска может открыться не сразу (переход), поле — появиться
  // позже; на каждой новой странице запрос вводится один раз.
  var CHAT_QUERY_KEY = 'vkweb_chat_query';
  var NO_TRACK = '-';
  var CHAT_TYPED_KEY = 'vkweb_chat_typed';
  /** Страница поиска музыки. Отчёт 3.48: на главной музыки m.vk.ru поля поиска нет (24 поля ввода, ни одно не поиск). */
  // Отчёт 3.51: поле поиска на странице музыки ведёт на /audio?section=general&q=… — это и есть адрес поиска.
  var MUSIC_SEARCH_PATH = '/audio?section=general&q=';
  var chatSeq = 0;
  var chatTimer = 0;

  /** Адрес для журнала: путь и параметры без номеров и без текста запроса. */
  function maskedLocation() {
    return (location.pathname + location.search).replace(/([?&](q|query|c%5Bq%5D|c\[q\])=)[^&]*/gi, '$1…').replace(/-?\d{3,}/g, '#').slice(0, 160);
  }

  /** Поле поиска на странице музыки: по типу, имени, подсказке или по обёртке с «search» в классе. */
  function findSearchInput() {
    var inputs = document.querySelectorAll('input');
    var fallback = null;
    for (var i = 0; i < inputs.length; i++) {
      var el = inputs[i];
      var type = (el.getAttribute('type') || 'text').toLowerCase();
      if (type !== 'search' && type !== 'text') continue;
      var hint = ((el.getAttribute('name') || '') + ' ' + (el.getAttribute('placeholder') || '') + ' ' + (el.getAttribute('aria-label') || '')).toLowerCase();
      if (type === 'search' || /(^|\s)q(\s|$)|search|поиск|найти/.test(hint)) return el;
      if (!fallback && el.closest && el.closest('[class*="earch"]')) fallback = el;
    }
    return fallback;
  }

  function describeInput(el) {
    return 'type=' + (el.getAttribute('type') || '-') + ', name=' + (el.getAttribute('name') || '-') + ', в форме=' + !!el.form;
  }

  /** Ввести запрос так, как это сделал бы человек: React-поля следят за событиями ввода, а не за value. */
  function submitSearch(input, query) {
    try { input.focus(); } catch (e) { /* без фокуса */ }
    var setter = Object.getOwnPropertyDescriptor(HTMLInputElement.prototype, 'value');
    if (setter && setter.set) setter.set.call(input, query); else input.value = query;
    input.dispatchEvent(new Event('input', { bubbles: true }));
    input.dispatchEvent(new Event('change', { bubbles: true }));
    ['keydown', 'keypress', 'keyup'].forEach(function (type) {
      input.dispatchEvent(new KeyboardEvent(type, { key: 'Enter', code: 'Enter', keyCode: 13, which: 13, bubbles: true }));
    });
  }

  function chatPending() {
    var raw = stored(CHAT_PLAY_KEY);
    if (!raw) return null;
    var cut = raw.lastIndexOf('|');
    var at = cut > 0 ? Number(raw.slice(cut + 1)) : 0;
    if (!at || Date.now() - at > CHAT_PLAY_TTL_MS) {
      store(CHAT_PLAY_KEY, null);
      if (at && raw.slice(0, cut) !== NO_TRACK) {
        Diag.event('warn', 'музыка: из переписки: трек не найден ни на странице, ни в поиске, адрес=' + maskedLocation() + ', ' + trackLookupFacts() + ', ' + inputsFacts());
      }
      return null;
    }
    return raw.slice(0, cut);
  }

  /** Ожидаемый трек появился в вёрстке (результаты поиска) — нажимаем. */
  function normTitle(value) {
    return decodeEntities(String(value || '')).toLowerCase().replace(/ё/g, 'е').replace(/[^0-9a-zа-я]+/gi, ' ').trim();
  }

  /**
   * Трек по названию «Исполнитель — Название»: в поиске и в «Моих треках» тот же трек часто лежит под другим номером
   * (копия у другого владельца). Отчёт 3.51: поиск нашёл 24 трека, но номер из сообщения среди них не совпал.
   */
  function trackByTitle(query) {
    var parts = String(query || '').split(' — ');
    if (parts.length < 2) return null;
    var artist = normTitle(parts[0]);
    var title = normTitle(parts.slice(1).join(' — '));
    if (!artist || !title) return null;
    var el = findTrackItem(function (t) { return normTitle(t[4]) === artist && normTitle(t[3]) === title; });
    if (el) lastLookup = 'название';
    return el;
  }

  function tryChatPending() {
    var id = chatPending();
    if (!id || id === NO_TRACK) return false;
    var el = trackElement(id) || trackByTitle(stored(CHAT_QUERY_KEY));
    if (!el) return false;
    store(CHAT_PLAY_KEY, null);
    store(CHAT_QUERY_KEY, null);
    store(CHAT_TYPED_KEY, null);
    clearTimeout(chatTimer);
    traceMusic('из переписки: трек найден способом «' + lastLookup + '», адрес=' + maskedLocation() + ', нажимаем');
    playElement(el, id);
    verifyPlay(0);
    return true;
  }

  /** Результаты поиска могут прийти без новых элементов-треков в наблюдаемых узлах: проверяем и по таймеру. */
  /** Что за поля ввода на странице (для журнала, когда поиска среди них нет): тип, имя, подсказка — первые шесть. */
  function inputsFacts() {
    var inputs = document.querySelectorAll('input');
    var list = [];
    for (var i = 0; i < inputs.length && list.length < 6; i++) {
      var el = inputs[i];
      var type = el.getAttribute('type') || 'text';
      if (type === 'hidden' || type === 'checkbox' || type === 'radio') continue;
      list.push(type + ':' + (el.getAttribute('name') || '-') + ':' + String(el.getAttribute('placeholder') || '').slice(0, 16));
    }
    return 'полей ввода=' + inputs.length + ', видимые=' + (list.join(' | ') || 'нет');
  }

  /** На открывшейся странице есть поле поиска, а запрос в нём ещё не набирали — набираем. */
  function typeChatQueryHere() {
    var query = stored(CHAT_QUERY_KEY);
    // Только на странице музыки: иначе название трека вводилось бы в поиск друзей, групп или сообщений (код ревью 3.59).
    if (!query || !isMusicPage()) return;
    var here = location.pathname + location.search;
    if (stored(CHAT_TYPED_KEY) === here) return;
    // Страница уже показывает поиск этого запроса (4.79): ввод повторил бы тот же поиск ещё раз.
    if (normTitle(searchQueryHere()) === normTitle(query) && document.querySelector('.audio_item')) { store(CHAT_TYPED_KEY, here); return; }
    var input = findSearchInput();
    if (!input) return;
    store(CHAT_TYPED_KEY, here);
    submitSearch(input, query);
    traceMusic('из переписки: запрос введён в поле поиска, поле={' + describeInput(input) + '}, адрес=' + maskedLocation());
  }

  function pollChatPending() {
    clearTimeout(chatTimer);
    if (!chatPending()) { store(CHAT_QUERY_KEY, null); store(CHAT_TYPED_KEY, null); return; }
    if (tryChatPending()) { store(CHAT_QUERY_KEY, null); store(CHAT_TYPED_KEY, null); return; }
    typeChatQueryHere();
    chatTimer = setTimeout(pollChatPending, 500);
  }

  function playFromChat(id, query) {
    var seq = ++chatSeq;
    traceMusic('из переписки №' + seq + ': трек=' + (id || '-') + ', ' + pageFacts() + ', плеер страницы=' + (window.audioplayer ? 'есть' : 'нет'));
    store(CHAT_PLAY_KEY, null);
    if (id) {
      var el = trackElement(id) || trackByTitle(query);
      if (el) {
        traceMusic('из переписки №' + seq + ': трек есть на странице (способ «' + lastLookup + '»), нажимаем');
        playElement(el, id);
        verifyPlay(seq);
        return true;
      }
    }
    if (!query) return false;
    return searchOnPage(id, query, 'из переписки №' + seq);
  }

  /**
   * Поиск самой страницы музыки: запрос вводится в её поле поиска; поля нет — переход на страницу поиска с запросом
   * (не подхватит его из адреса — запрос введётся в её поле). [id] — трек, который включить в результатах ('' — только
   * показать результаты). Общее для музыки из переписки и поиска своего экрана (4.79).
   */
  function searchOnPage(id, query, label) {
    store(CHAT_QUERY_KEY, query);
    store(CHAT_TYPED_KEY, null);
    // Без номера трека («-») включать нечего, но ожидание нужно: запрос вводится на открывшейся странице поиска.
    store(CHAT_PLAY_KEY, (id || NO_TRACK) + '|' + Date.now());
    var input = findSearchInput();
    if (input) {
      store(CHAT_TYPED_KEY, location.pathname + location.search);
      submitSearch(input, query);
      traceMusic(label + ': поиск запущен, поле={' + describeInput(input) + '}, адрес=' + maskedLocation());
    } else {
      // Поиска на этой странице нет — открываем страницу поиска музыки с запросом; если она запрос из адреса не
      // подхватит, он будет введён в её поле (typeChatQueryHere).
      Diag.event(id ? 'warn' : 'info', 'музыка: ' + label + ': поля поиска здесь нет, переход на поиск музыки; ' + inputsFacts() + ', адрес=' + maskedLocation());
      navigateInPage(MUSIC_SEARCH_PATH + encodeURIComponent(query));
    }
    pollChatPending();
    return true;
  }

  var SEARCH_RESYNC_MS = [2500, 6000];
  var searchSeq = 0;
  var searchTimers = [];

  /** Ожидание поиска снято: прежний запрос не вводится ни в чьё поле (человек вернулся к «Моим трекам»). */
  function cancelPageSearch() {
    store(CHAT_PLAY_KEY, null);
    store(CHAT_QUERY_KEY, null);
    store(CHAT_TYPED_KEY, null);
    clearTimeout(chatTimer);
    searchTimers.forEach(clearTimeout);
    searchTimers = [];
  }

  /**
   * Поиск своего экрана музыки (4.79): результаты приходят обычными данными музыки (page='search', query). Уже на этой
   * странице поиска — только прислать их заново. Ничего не нашлось — новых треков нет и сообщения не было бы: данные
   * отправляются ещё раз через SEARCH_RESYNC_MS, чтобы экран показал «ничего не найдено», а не ждал вечно.
   */
  function searchMusic(query) {
    var q = String(query || '').replace(/\s+/g, ' ').trim().slice(0, 200);
    if (!q) return false;
    cancelPageSearch();
    var seq = ++searchSeq;
    if (normTitle(searchQueryHere()) === normTitle(q)) {
      traceMusic('поиск №' + seq + ': страница уже на этом поиске');
      resyncMusic();
    } else {
      traceMusic('поиск №' + seq + ': ' + pageFacts());
      searchOnPage('', q, 'поиск №' + seq);
    }
    SEARCH_RESYNC_MS.forEach(function (ms) {
      searchTimers.push(setTimeout(function () { if (seq === searchSeq && isSearchPage()) resyncMusic(); }, ms));
    });
    return true;
  }

  /** Разобрать страницу заново и прислать данные, даже если они не менялись. */
  function resyncMusic() {
    scanMusicDom(document);
    musicChannel.reset();
    scheduleMusicNotify();
  }

  function postMusicDownload(job, url, reason) {
    postToApp({
      type: 'musicDownload', id: job.id, url: url || '', title: job.title || '', artist: job.artist || '',
      pageUrl: location.href, album: job.album || '', cover: job.cover || '', coverLarge: job.coverLarge || '', ok: !!url, reason: reason || ''
    });
  }

  /**
   * Поток получен (или не получен за [DOWNLOAD_WAIT_MS]): адрес — приложению, затем плеер страницы возвращается к тому,
   * что играло до скачивания: тот же трек, место и громкость. Звук включается только после перемотки — иначе было
   * слышно начало трека.
   */
  function musicDownloadResult(job, url) {
    if (!job.restoreOnly) {
      if (pendingMusicDownload !== job) return;
      pendingMusicDownload = null;
      postMusicDownload(job, url, url ? '' : 'timeout');
    }
    if (!job.switched) return;
    var media = currentMedia();
    if (job.previousId && job.previousId !== job.id) {
      music('play', job.previousId, job.previousFrom);
      var tries = 0;
      var restore = function () {
        var restored = currentMedia();
        if (!restored || currentTrackId() !== job.previousId) {
          if (++tries < 30) { setTimeout(restore, 100); return; }
          if (restored) restored.muted = job.previousMuted;
          return;
        }
        restored.muted = true;
        try { if (job.previousTime > 0) restored.currentTime = job.previousTime; } catch (e) { /* поток ещё не готов */ }
        if (job.previousPaused) restored.pause();
        setTimeout(function () { restored.muted = job.previousMuted; }, 250);
      };
      setTimeout(restore, 100);
    } else if (media) {
      // Раньше ничего не играло: скачиваемый трек останавливается, звук — как был.
      media.pause();
      media.muted = job.previousMuted;
    }
  }

  var DOWNLOAD_WAIT_MS = 18000;

  // ---- потоки списка через плеер страницы (4.99.5)
  //
  // «Кэшировать весь список»: треки, адрес которых не раскрылся и не пришёл от API, по очереди «открываются» в плеере
  // страницы без звука — плеер грузит плейлист HLS, адрес запоминается (onRequest выше) и уходит приложению
  // (musicStreams с byPlay). Звук глушится на всё время; в конце плеер возвращается к тому, что играло до этого: трек,
  // место, пауза, громкость. VK при этом считает «открытые» треки прослушанными.
  var RESOLVE_WAIT_MS = 15000;

  /**
   * Окно кэша музыки (MusicCacheWorker, 4.99.7): своя невидимая страница, звук которой не нужен никогда. Каждый
   * медиа-элемент глушится до начала воспроизведения (play() — уже беззвучный, иначе система могла бы отдать окну
   * звуковой фокус и остановить музыку человека), а найденный поток сразу ставится на паузу: плееру незачем качать
   * трек дальше — его скачает кэш.
   */
  var cacheWorker = !!(CONFIG && CONFIG.cacheWorker);
  if (cacheWorker) {
    try {
      (function (playBeforeCache) {
        HTMLMediaElement.prototype.play = function () {
          try { this.muted = true; this.volume = 0; } catch (e) { /* без громкости */ }
          return playBeforeCache.apply(this, arguments);
        };
      })(HTMLMediaElement.prototype.play);
      document.addEventListener('play', function (e) {
        try { if (e.target && e.target.tagName) { e.target.muted = true; e.target.volume = 0; } } catch (x) { /* без громкости */ }
      }, true);
    } catch (e) { /* без глушения окно кэша всё равно беззвучно для человека: оно невидимо */ }
  }
  var RESOLVE_STEP_MS = 150;
  var playResolve = null;

  function postResolved(id, url, job) {
    traceMusic('результат поиска потока: id=' + id + ', найден=' + !!url);
    MusicDiagnostics.capture(url ? 'resolve-found' : 'resolve-missing');
    postToApp({ type: 'musicStreams', byPlay: true, operation: job && job.operation || '', items: url ? [{ id: id, url: url }] : [], missing: url ? [] : [id] });
  }

  function resolveByPlaying(csv, extra) {
    var options = {};
    if (extra) {
      try { options = JSON.parse(String(extra)); } catch (e) { return false; }
      if (!options || typeof options !== 'object') return false;
    }
    var operation = String(options.operation || '');
    if (operation && !/^[A-Za-z0-9._-]{1,80}$/.test(operation)) return false;
    var ids = String(csv).split(',').filter(function (id) { return /^-?\d{1,12}_\d{1,12}$/.test(id); });
    if (!ids.length) return true;
    if (playResolve && playResolve.operation !== operation) stopResolveByPlaying();
    if (playResolve) {
      ids.forEach(function (id) { if (playResolve.ids.indexOf(id) === -1) playResolve.ids.push(id); });
      return true;
    }
    if (pendingMusicDownload) { ids.forEach(function (id) { postResolved(id, '', { operation: operation }); }); return true; }
    playbackGeneration++;
    lastHlsLoad = null;
    var force = Array.isArray(options.force) ? options.force.slice(0, 50) : [];
    force.forEach(function (id) {
      id = String(id);
      if (ids.indexOf(id) !== -1) { delete streamById[id]; streamIds = streamIds.filter(function (old) { return old !== id; }); }
    });
    var media = currentMedia();
    playResolve = {
      operation: operation,
      ids: ids,
      at: 0,
      current: '',
      startedAt: 0,
      previousId: currentTrackId(),
      previousTime: media ? Number(media.currentTime) || 0 : 0,
      previousPaused: media ? !!media.paused : true,
      previousMuted: media ? !!media.muted : false,
      previousFrom: isPlaylistPage() ? 'playlist' : isSearchPage() ? 'search' : 'my'
    };
    traceMusic('потоки через плеер: треков ' + ids.length);
    stepResolve(playResolve);
    return true;
  }

  function stepResolve(expected) {
    var job = playResolve;
    if (!job || expected && expected !== job) return;
    if (job.at >= job.ids.length) { finishResolve(job); return; }
    var id = job.ids[job.at];
    var known = musicStreamFor(id);
    if (known) {
      postResolved(id, known, job);
      job.at++;
      setTimeout(function () { stepResolve(job); }, 0);
      return;
    }
    job.current = id;
    job.startedAt = Date.now();
    job.diagnosed = false;
    MusicDiagnostics.begin(id);
    MusicDiagnostics.capture('resolve-start');
    var media = currentMedia();
    if (media) media.muted = true;
    var row = trackElement(id);
    if (row) playElement(row, id); else music('play', id, job.previousFrom);
    setTimeout(function () { pollResolve(job, id); }, RESOLVE_STEP_MS);
  }

  function pollResolve(job, id) {
    if (playResolve !== job || job.current !== id) return;
    var media = currentMedia();
    if (media) media.muted = true;
    var url = musicStreamFor(id);
    if (!url && media && currentTrackId() === id) {
      var src = String(media.currentSrc || media.src || '');
      if (src.indexOf('https://') === 0 && NetTap.onHost(src, 'audio')) url = src;
    }
    if (!job.diagnosed && Date.now() - job.startedAt >= 5000) {
      job.diagnosed = true;
      MusicDiagnostics.capture('resolve-wait-5s');
    }
    if (url || Date.now() - job.startedAt >= RESOLVE_WAIT_MS) {
      MusicDiagnostics.capture(url ? 'resolve-stream' : 'resolve-timeout');
      if (cacheWorker && media) { try { media.pause(); } catch (e) { /* уже остановлен */ } }
      postResolved(id, url, job);
      job.current = '';
      job.at++;
      setTimeout(function () { stepResolve(job); }, RESOLVE_STEP_MS);
      return;
    }
    setTimeout(function () { pollResolve(job, id); }, RESOLVE_STEP_MS);
  }

  /** Очередь кончилась (или её остановили): плеер — к тому, что играло до неё. */
  function finishResolve(job) {
    if (playResolve !== job) return;
    playResolve = null;
    traceMusic('потоки через плеер: готово');
    // Окну кэша возвращать нечего: оно только ищет потоки.
    if (cacheWorker) {
      var media = currentMedia();
      if (media) { try { media.pause(); } catch (e) { /* уже остановлен */ } }
      return;
    }
    musicDownloadResult({
      id: job.ids[job.ids.length - 1] || '', switched: true, previousId: job.previousId, previousTime: job.previousTime,
      previousPaused: job.previousPaused, previousMuted: job.previousMuted, previousFrom: job.previousFrom, restoreOnly: true
    }, '');
  }

  function stopResolveByPlaying() {
    playbackGeneration++;
    lastHlsLoad = null;
    var job = playResolve;
    if (!job) return;
    job.ids = job.ids.slice(0, job.at);
    job.current = '';
    finishResolve(job);
  }

  function pollMusicDownload(job) {
    if (pendingMusicDownload !== job) return;
    var known = musicStreamFor(job.id);
    if (known) { musicDownloadResult(job, known); return; }
    var media = currentMedia();
    var src = media && (media.currentSrc || media.src || '');
    if (media && currentTrackId() === job.id) {
      media.muted = true;
      // 4.95: годится любой поток на серверах звука — mp3 или HLS (приложение само соберёт mp3).
      if (src.indexOf('https://') === 0 && NetTap.onHost(src, 'audio')) { musicDownloadResult(job, src); return; }
    }
    if (Date.now() - job.startedAt >= DOWNLOAD_WAIT_MS) { musicDownloadResult(job, ''); return; }
    setTimeout(function () { pollMusicDownload(job); }, 100);
  }

  function requestMusicDownload(id) {
    var known = musicTracks[id] || {};
    var job = {
      id: id,
      title: known.title || '',
      artist: known.artist || '',
      album: known.album || '',
      cover: known.cover || '',
      coverLarge: known.coverLarge || '',
      switched: false
    };
    // Адрес уже известен (ответ API) — плеер страницы не трогаем.
    var stream = musicStreamFor(id);
    if (stream) { postMusicDownload(job, stream); return true; }
    var media = currentMedia();
    var src = media && (media.currentSrc || media.src || '');
    // Скачивают то, что играет: адрес — у плеера, звук не глушится.
    if (media && currentTrackId() === id && src.indexOf('https://') === 0 && NetTap.onHost(src, 'audio')) {
      postMusicDownload(job, src);
      return true;
    }
    // Одно скачивание через плеер за раз: второе сбило бы восстановление первого.
    if (pendingMusicDownload || playResolve) { postMusicDownload(job, '', 'busy'); return true; }
    var row = trackElement(id);
    var previousId = currentTrackId();
    job.previousId = previousId;
    job.previousTime = media ? Number(media.currentTime) || 0 : 0;
    job.previousPaused = media ? !!media.paused : true;
    job.previousMuted = media ? !!media.muted : false;
    job.previousFrom = isPlaylistPage() ? 'playlist' : isSearchPage() ? 'search' : 'my';
    job.startedAt = Date.now();
    job.switched = previousId !== id;
    pendingMusicDownload = job;
    if (media) media.muted = true;
    if (job.switched) {
      if (row) playElement(row, id);
      else music('play', id, job.previousFrom);
    }
    pollMusicDownload(job);
    return true;
  }

  /**
   * Потоки многих треков без включения плеера (4.97: «Кэшировать весь список»): известные — сразу, остальные — одним
   * запросом audio.getById от имени страницы (по 50; ключ доступа трека — из его данных). Ответ — сообщение
   * musicStreams: [{id, url}] и номера, для которых потока нет.
   */
  var STREAMS_BATCH = 50;

  function resolveStreams(csv) {
    var ids = String(csv).split(',').filter(function (id) { return /^-?\d{1,12}_\d{1,12}$/.test(id); }).slice(0, STREAMS_BATCH);
    var reply = function () {
      var items = [];
      var missing = [];
      ids.forEach(function (id) {
        var url = musicStreamFor(id);
        if (url) items.push({ id: id, url: url }); else missing.push(id);
      });
      postToApp({ type: 'musicStreams', items: items, missing: missing });
    };
    var unknown = ids.filter(function (id) { return !musicStreamFor(id); });
    if (!unknown.length || !VkApi.ready()) {
      if (unknown.length) Diag.warn('музыка: потоки для кэша — нет данных входа');
      reply();
      return true;
    }
    var audios = unknown.map(function (id) {
      var key = musicTracks[id] && musicTracks[id].key;
      return key ? id + '_' + key : id;
    }).join(',');
    VkApi.call('audio.getById', { audios: audios }, function (error, r) {
      if (error) Diag.warn('музыка: потоки для кэша не получены: ' + String(error).slice(0, 100));
      else collectApiStreams(r, 0);
      reply();
    });
    return true;
  }

  // Поиск мог полностью загрузить новую страницу: ожидание продолжается на ней.
  if (stored(CHAT_PLAY_KEY)) setTimeout(pollChatPending, 500);

  /** Команды своего экрана музыки: 'play' + id трека, 'more' — следующая порция, 'sync' — прислать список. */
  function music(action, id, extra) {
    if (action === 'diagnostics') { MusicDiagnostics.capture('native-' + String(id || 'snapshot')); return true; }
    if (action === 'download' && id) return requestMusicDownload(String(id));
    if (action === 'streams' && id) return resolveStreams(String(id));
    if (action === 'resolve' && id) return resolveByPlaying(String(id), extra);
    if (action === 'resolveStop') { stopResolveByPlaying(); return true; }
    if (action === 'chat') return playFromChat(String(id || ''), String(extra || ''));
    if (action === 'playlists') return discoverPlaylists();
    if (action === 'playlist' && id) return openPlaylist(String(id), extra);
    if (action === 'home') { cancelPageSearch(); return goHome(); }
    if (action === 'search') return searchMusic(extra);
    if (action === 'add' && id) return addTrack(String(id));
    if (action === 'follow' && id) return followPlaylist(String(id), String(extra || ''));
    if (action === 'play' && id) {
      playbackGeneration++;
      if (!playResolve || playResolve.current !== id) MusicDiagnostics.begin(id);
      MusicDiagnostics.capture('play-command');
      noteMusicCommand();
      var seq = ++playSeq;
      var done = function (route, ok) {
        traceMusic('включение №' + seq + ': путь=' + route + ', результат=' + (ok ? 'команда выполнена' : 'не выполнено'));
        if (ok) verifyPlay(seq);
        return ok;
      };
      traceMusic('включение №' + seq + ': трек=' + id + ', откуда=' + (extra || '-') + ', ' + pageFacts() +
        ', в списке приложения=' + (musicTracks[id] ? 'да' : 'нет') + ', плеер страницы=' + (window.audioplayer ? 'есть' : 'нет'));
      musicHint = musicTracks[id] || null;
      // Трек «Моих треков» просят включить со страницы плейлиста: сначала на главную музыки, там он есть. Включение
      // продолжится само (continuePendingFromRoot), плейлист VK при этом остаётся целым — переход делает страница.
      if (extra === 'my' && !isMyList() && !trackElement(id)) {
        setPending(id);
        pendingNavAt = Date.now();
        navigateInPage('/audio');
        return done('на главную музыки и там включить', true);
      }
      // С главной — всегда через полный список: там VK знает весь плейлист, и после трека играют следующие
      // (на главной плейлист — те несколько треков превью).
      if (isMyAudiosPage() && goToMyBlock(id)) return done('через полный список «Моих треков»', true);
      var el = trackElement(id);
      if (el) { traceMusic('включение №' + seq + ': элемент найден способом «' + lastLookup + '»'); playElement(el, id); return done('нажатие на элемент', true); }
      // Плейлист мог перерисоваться: пересканируем страницу и ищем трек ещё раз.
      if (extra === 'playlist') {
        scanMusicDom(document);
        el = trackElement(id);
        if (el) { traceMusic('включение №' + seq + ': после пересканирования найден способом «' + lastLookup + '»'); playElement(el, id); return done('нажатие на элемент после пересканирования', true); }
        Diag.event('warn', 'музыка: включение №' + seq + ': трек плейлиста не найден на странице: ' + id + ', ' + trackLookupFacts());
      }
      if (isMyAudiosBlock()) {
        setPending(id);
        pendingMoreCount = 0;
        tryPendingPlay();
        return done('отложенное включение в полном списке', true);
      }
      // Главная музыки только что открылась и ещё не нарисовала ни треков, ни ссылки на полный список (отчёт 3.58:
      // «треков в вёрстке=0», плеер страницы бросал исключение): ждём вёрстку — включение продолжится само
      // (continuePendingFromRoot при разборе пришедших треков).
      if (isMyAudiosPage() && extra === 'my' && !document.querySelector('.audio_item')) {
        setPending(id);
        pendingNavAt = 0;
        return done('ждём вёрстку главной музыки, затем полный список', true);
      }
      if (window.audioplayer && typeof window.audioplayer.playPause === 'function') {
        Diag.event('warn', 'музыка: включение №' + seq + ': запасной путь плеера страницы, трек=' + id);
        // Трека нет в вёрстке — плеер страницы его не знает и бросает исключение: раньше оно уходило наверх, команда
        // возвращала null, и в журнале не было ни результата, ни причины.
        try {
          window.audioplayer.playPause(new MouseEvent('click'), String(id));
        } catch (e) {
          Diag.event('warn', 'музыка: включение №' + seq + ': плеер страницы не знает трек: ' + String(e && e.message || e).slice(0, 120));
          return done('запасной путь плеера страницы: ошибка', false);
        }
        return done('запасной путь плеера страницы', true);
      }
      Diag.event('warn', 'музыка: включение №' + seq + ': ни элемента, ни плеера страницы, трек=' + id);
      return done('нет пути', false);
    }
    if (action === 'more') {
      if (id === 'playlists') return loadMorePlaylists();
      // Просят догрузить «Мои треки», а страница на плейлисте: догрузка шла бы не в тот список — сначала на главную.
      // Результаты поиска (4.79) догружаются на месте.
      if (id !== 'playlist' && id !== 'search' && !isMyList() && !isPlaylistsPage()) { navigateInPage('/audio'); return true; }
      // На главной превью: продолжение — на странице полного списка.
      if (isMyAudiosPage() && !document.querySelector('[class*="showMore--' + MY_AUDIOS + '"]') && goToMyBlock(null)) return true;
      return loadMoreTracks();
    }
    if (action === 'sync') {
      resyncMusic();
      return true;
    }
    return false;
  }

  // ---- непрерывное воспроизведение (4.45)
  // Плеер VK играет только то, что загружено в список (на странице «Моих треков» — 50 треков), а после последнего
  // начинает его сначала (отчёт 4.44: «музыка дала круг»). Поэтому: заранее, за QUEUE_AHEAD треков до конца, страница
  // догружает продолжение; а если плеер всё же перескочил с конца на первый трек сам (не по выбору человека), включается
  // трек, следующий за последним сыгранным, — догружается, если его ещё нет.
  var QUEUE_AHEAD = 5;
  var QUEUE_WRAP_MIN = 3;
  var COMMAND_QUIET_MS = 5000;
  var CONTINUE_MAX_TRIES = 30;
  var CONTINUE_STEP_MS = 700;

  /** Человек выбрал трек или «назад»: переход на первый трек после этого — его выбор, а не круг плеера. */
  function noteMusicCommand() { musicCommandAt = Date.now(); }

  function rowIds() {
    var rows = document.querySelectorAll('.audio_item[data-id]');
    var ids = [];
    for (var i = 0; i < rows.length; i++) ids.push(rows[i].getAttribute('data-id'));
    return ids;
  }

  /** Строка списка после [el] (или null). */
  function rowAfter(el) {
    var rows = document.querySelectorAll('.audio_item');
    for (var i = 0; i < rows.length; i++) if (rows[i] === el) return rows[i + 1] || null;
    return null;
  }

  function moreTracksLikely() {
    return (musicHasMore || !musicHasMoreKnown || !!document.querySelector('[class*="showMore--' + MY_AUDIOS + '"],.show_more')) && !PageList.isBlocked();
  }

  /** Состояние плеера сменилось (00_core.js, report): заиграл другой трек — проверить, не конец ли списка. */
  function onPlaybackTick() {
    var id = currentTrackId();
    if (!id || id === queueLastId) return;
    var ids = isMusicPage() ? rowIds() : [];
    if (ids.indexOf(id) !== -1) queueOrder = ids;
    var order = queueOrder.length ? queueOrder : musicOrder;
    var prev = queueLastId;
    queueLastId = id;
    var idx = order.indexOf(id);
    var prevIdx = prev ? order.indexOf(prev) : -1;
    if (idx >= 0 && ids.length && idx >= ids.length - QUEUE_AHEAD && moreTracksLikely()) {
      Diag.debug('музыка: до конца загруженного списка треков: ' + (ids.length - idx - 1) + ' — догружается продолжение');
      loadMoreTracks();
    }
    if (prev && idx === 0 && prevIdx >= QUEUE_WRAP_MIN && Date.now() - musicCommandAt > COMMAND_QUIET_MS) {
      traceMusic('список начался заново после трека №' + (prevIdx + 1) + ' — включается следующий за ним');
      continueAfter(prev);
    }
  }

  /** Включить трек после [prevId]: со страницы списка, догружая его; списка на странице нет — через «Мои треки». */
  function continueAfter(prevId) {
    clearTimeout(continueTimer);
    var tries = 0;
    (function step() {
      continueTimer = 0;
      var el = isMusicPage() ? trackElement(prevId) : null;
      if (!el) {
        // Человек в другом разделе (страница ушла со списка). Пока экран не виден, вернуть страницу к «Моим трекам»
        // можно; на виду — нельзя: раздел, который он смотрит, сменился бы сам.
        if (musicListKey === '/audio' && musicTracks[prevId] && realPageHidden()) {
          traceMusic('продолжение: списка на странице нет, экран не виден — открываются «Мои треки»');
          setPending(AFTER_PREFIX + prevId);
          pendingNavAt = 0;
          navigateInPage('/audio');
        } else {
          Diag.event('warn', 'музыка: список кончился, а его самого на странице нет — плеер VK играет его сначала');
        }
        return;
      }
      var next = rowAfter(el);
      if (next) {
        noteMusicCommand();
        traceMusic('продолжение: включён трек после конца загруженного списка');
        playElement(next, next.getAttribute('data-id') || '');
        return;
      }
      if (!moreTracksLikely() || ++tries > CONTINUE_MAX_TRIES) {
        traceMusic('продолжение: дальше треков нет — список играет сначала');
        return;
      }
      loadMoreTracks();
      continueTimer = setTimeout(step, CONTINUE_STEP_MS);
    })();
  }

  // ---- «добавить к себе» (4.45): трек и плейлист — от имени страницы, итог — сообщение «musicAction»

  var ADD_BUTTONS = '.ai_add,[class*="add_btn"],[class*="AddButton"],[class*="__add"],[data-action="add"]';

  function addTrack(id) {
    var m = /^(-?\d{1,12})_(\d{1,12})$/.exec(id);
    if (!m) return false;
    var reply = function (ok, reason) {
      if (ok && musicTracks[id]) { musicTracks[id].added = true; scheduleMusicNotify(); }
      if (ok) scheduleReport(); // плеер приложения прячет «+» у играющего трека
      postToApp({ type: 'musicAction', action: 'add', id: id, ok: ok, reason: reason || '' });
    };
    // Запасной путь — кнопка «добавить» в строке трека самой страницы (если API отказал).
    var viaPage = function (why) {
      var row = trackElement(id);
      var button = row && row.querySelector(ADD_BUTTONS);
      if (button) {
        button.click();
        traceMusic('трек добавлен кнопкой страницы (API: ' + String(why).slice(0, 60) + ')');
        reply(true, '');
        return;
      }
      reply(false, why);
    };
    if (!VkApi.ready()) { viaPage('нет данных входа'); return true; }
    var params = { owner_id: m[1], audio_id: m[2] };
    var key = musicTracks[id] && musicTracks[id].key;
    if (key) params.access_key = key;
    VkApi.call('audio.add', params, function (error) {
      if (error) {
        Diag.warn('музыка: трек не добавлен через API: ' + String(error).slice(0, 100));
        viaPage(String(error).slice(0, 120));
        return;
      }
      traceMusic('трек добавлен к себе');
      reply(true, '');
    });
    return true;
  }

  /** Плейлист «владелец_номер»; ключ доступа — из его адреса [url] (или из адреса открытой страницы плейлиста). */
  function followPlaylist(id, url) {
    var m = /^(-?\d{1,12})_(\d{1,12})$/.exec(id);
    if (!m) return false;
    var link = url ? playlistLink(url) : null;
    var key = link && link[3] ? link[3] : '';
    if (!key && isPlaylistPage()) {
      var here = playlistLink(location.pathname + location.search);
      if (here && here[1] === m[1] && here[2] === m[2]) key = here[3] || '';
    }
    var reply = function (ok, reason) {
      if (ok) {
        playlistFollowed[id] = true;
        if (library) library[id] = true;
        libraryAskedAt = 0; // медиатека перечитается при следующем открытии списка плейлистов
        scheduleMusicNotify();
      }
      postToApp({ type: 'musicAction', action: 'follow', id: id, ok: ok, reason: reason || '' });
    };
    if (!VkApi.ready()) { reply(false, 'нет данных входа'); return true; }
    var params = { owner_id: m[1], playlist_id: m[2] };
    if (key) params.access_key = key;
    VkApi.call('audio.followPlaylist', params, function (error) {
      if (error) {
        Diag.warn('музыка: плейлист не добавлен: ' + String(error).slice(0, 100));
        reply(false, String(error).slice(0, 120));
        return;
      }
      traceMusic('плейлист добавлен к себе');
      reply(true, '');
    });
    return true;
  }

  /** Открытый плейлист: номер, свой ли (null — свой номер страница не сообщила) и добавлен ли к себе. */
  function openedPlaylistFacts() {
    var m = PLAYLIST_PAGE.exec(location.search);
    if (!m) return null;
    var id = m[2] + '_' + m[3];
    var me = myOwnerId();
    return { id: id, own: me ? m[2] === me : null, followed: playlistFollowed[id] === true || !!(library && library[id]) };
  }

  /**
   * Номер играющего трека и добавлен ли он к себе (4.46: «+» в плеере приложения). Только для звука (у видео номера
   * трека нет); added = null — неизвестно (трека нет ни в списках, ни в вёрстке), тогда кнопки нет.
   */
  function playingTrackFacts(media) {
    if (!media || media.tagName !== 'AUDIO') return { id: '', added: null };
    var id = currentTrackId();
    if (!id || !/^-?\d{1,12}_\d{1,12}$/.test(id)) return { id: '', added: null };
    var known = musicTracks[id];
    if (known) return { id: id, added: !!known.added };
    var row = trackElement(id);
    if (row) {
      try { var t = JSON.parse(row.getAttribute('data-audio')); if (isTrackTuple(t)) return { id: id, added: trackLiked(t) || String(t[11] || '').indexOf(MY_AUDIOS) !== -1 }; } catch (e) { /* чужая разметка */ }
    }
    return { id: id, added: null };
  }

