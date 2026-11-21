// Структурная диагностика музыки. Только при включённом сборе дампа; без HTML, текстов и адресов потоков.
  var MusicDiagnostics = (function () {
    var enabled = CONFIG.collectDump === true;
    var documentId = Date.now().toString(36) + '-' + Math.random().toString(36).slice(2, 8);
    var expected = '', operation = 0, events = [], minute = 0, count = 0, dropped = 0;
    var network = { audioRequests: 0, hlsResponses: 0, dashResponses: 0, audioApiResponses: 0 };
    var lastFailure = '', lastFailureAt = 0, suppressedFailures = 0;
    var responses = [], payloads = { hls: 0, dash: 0, mp4: 0, other: 0, limit: 0, unreadable: 0, timeout: 0 };
    function id(value) { return /^-?\d{1,12}_\d{1,12}$/.test(String(value || '')) ? String(value) : ''; }
    function word(value) { return String(value || '').replace(/[^a-zA-Z0-9_.:-]/g, '_').slice(0, 80); }
    function source(value) {
      if (!value) return 'none';
      if (/^blob:/.test(value)) return 'blob';
      try { var u = new URL(value); return u.protocol === 'https:' ? 'https://' + u.hostname : word(u.protocol); }
      catch (e) { return 'unknown'; }
    }
    function node(el) {
      if (!el) return null;
      return { tag: word(el.tagName), classes: String(el.getAttribute('class') || '').split(/\s+/).slice(0, 6).map(word),
        id: id(el.getAttribute('data-id')), audioData: el.hasAttribute('data-audio'),
        onclick: el.hasAttribute('onclick'), role: word(el.getAttribute('role')) };
    }
    function media(el) {
      return { tag: word(el.tagName), paused: !!el.paused, ended: !!el.ended, muted: !!el.muted,
        volume: Number(el.volume), ready: el.readyState, network: el.networkState,
        time: Math.round(Number(el.currentTime) || 0), error: el.error ? el.error.code : 0,
        source: source(el.currentSrc || el.src) };
    }
    // Descriptors only: inspecting a player must never execute its getters or methods.
    function methods() {
      var result = [], player = window.audioplayer;
      for (var depth = 0; player && depth < 3 && result.length < 40; depth++, player = Object.getPrototypeOf(player)) {
        if (player === Object.prototype) break;
        Object.getOwnPropertyNames(player).slice(0, 100).forEach(function (key) {
          var d = Object.getOwnPropertyDescriptor(player, key);
          if (result.length < 40 && d && typeof d.value === 'function') result.push(word(key));
        });
      }
      return result;
    }
    function capture(stage, target) {
      if (!enabled) return;
      try {
        var now = Date.now(), bucket = Math.floor(now / 60000);
        if (minute !== bucket) { minute = bucket; count = 0; }
        if (count >= 120) { dropped++; return; }
        var bridge = window[CONFIG.bridge];
        if (!bridge || typeof bridge.postMessage !== 'function') return;
        var rows = document.querySelectorAll('.audio_item'), samples = [];
        for (var i = 0; i < rows.length && samples.length < 8; i++) samples.push(node(rows[i]));
        var candidates = document.querySelectorAll('[data-audio],[data-id][class*="audio"],[class*="AudioRow"],[class*="AudioItem"]');
        var alternative = [];
        for (var k = 0; k < candidates.length && alternative.length < 4; k++) alternative.push(node(candidates[k]));
        var all = document.querySelectorAll('audio,video'), states = [];
        for (var j = 0; j < all.length && states.length < 6; j++) states.push(media(all[j]));
        // VK also uses new Audio() without attaching it to the DOM (00_core tracks it).
        var active = typeof currentMedia === 'function' ? currentMedia() : null;
        var playing = document.querySelector('.audio_item.playing,.audio_item.current,.audio_item.audio_item_current');
        var path = /^\/(audio|audios|login|mail|feed)(?:\/|$)/.exec(location.pathname);
        var rec = { type: 'dump', kind: 'musicDiagnostic', schema: 1, page: path ? '/' + path[1] : '/other',
          at: new Date(now).toISOString(), method: 'music.diagnostic', stage: word(stage),
          documentId: documentId, operation: operation, expectedId: expected,
          lookupOperation: typeof playResolve !== 'undefined' && playResolve ? playResolve.operation : '',
          playbackGeneration: typeof playbackGeneration === 'number' ? playbackGeneration : 0,
          compatibility: window.__vkCompat ? window.__vkCompat.describe('lite', window.__vkLite) : null,
          observedId: typeof observedTrackId === 'function' ? id(observedTrackId()) : '',
          hasMediaMetadata: !!(navigator.mediaSession && navigator.mediaSession.metadata),
          worker: !!CONFIG.cacheWorker, visibility: word(document.visibilityState),
          actualHidden: window.__vkWebVisibility && typeof window.__vkWebVisibility.hidden === 'function' ? !!window.__vkWebVisibility.hidden() : null,
          userActivation: !!(navigator.userActivation && navigator.userActivation.isActive),
          rows: rows.length, sample: samples, alternatives: alternative, target: node(target), playing: node(playing),
          media: states, activeMedia: active ? media(active) : null,
          playerPresent: !!window.audioplayer, playerMethods: methods(),
          events: events.slice(-12), network: network, responses: responses.slice(-16), payloads: payloads,
          dropped: dropped, suppressedFailures: suppressedFailures };
        var raw = JSON.stringify(rec);
        if (raw.length <= 18000) { count++; bridge.postMessage(raw); }
      } catch (e) { /* Диагностика не должна мешать командам плеера. */ }
    }
    function note(type, el, error) {
      if (!enabled) return;
      try {
        if (type === 'error' || type === 'play-rejected' || type === 'play-threw') {
          var key = operation + ':' + type + ':' + word(error), now = Date.now();
          if (key === lastFailure && now - lastFailureAt < 1000) { suppressedFailures++; return; }
          lastFailure = key; lastFailureAt = now;
        }
        events.push({ event: word(type), at: Date.now(), media: media(el), error: word(error) });
        if (events.length > 12) events.shift();
        if (type === 'error' || type === 'play-rejected' || type === 'play-threw') capture(type);
      } catch (e) { /* повреждённый элемент */ }
    }
    ['play', 'playing', 'pause', 'ended', 'waiting', 'stalled', 'error', 'emptied', 'loadstart', 'canplay'].forEach(function (name) {
      document.addEventListener(name, function (e) {
        if (e.target && /^(AUDIO|VIDEO)$/.test(e.target.tagName)) note(name, e.target);
      }, true);
    });
    // Observe the original promise; return it unchanged and never issue an extra play().
    try {
      var originalPlay = HTMLMediaElement.prototype.play;
      HTMLMediaElement.prototype.play = function () {
        var result, el = this;
        note('play-call', el);
        try { result = originalPlay.apply(el, arguments); }
        catch (e) { note('play-threw', el, e && e.name); throw e; }
        if (enabled && result && typeof result.then === 'function') {
          try { result.then(function () { note('play-resolved', el); }, function (e) { note('play-rejected', el, e && e.name); }); }
          catch (e) { /* нестандартный thenable */ }
        }
        return result;
      };
    } catch (e) { /* медиа API недоступен */ }
    return {
      set: function (on) { enabled = on === true; if (!enabled) { events = []; expected = ''; } },
      begin: function (trackId) { if (enabled) { expected = id(trackId); operation++; } },
      network: function (kind) { if (enabled && Object.prototype.hasOwnProperty.call(network, kind)) network[kind]++; },
      response: function (url, transport, info) {
        if (!enabled) return;
        try {
          var u = new URL(url), mime = /^[-\w.+]+\/[-\w.+]+/.exec(String(info.mime || ''));
          var value = { host: u.hostname, transport: word(transport), at: Date.now() };
          if (info.payload) {
            value.payload = word(info.payload);
            if (Object.prototype.hasOwnProperty.call(payloads, info.payload)) payloads[info.payload]++;
          } else {
            value.status = Number(info.status) || 0; value.mime = mime ? mime[0].slice(0, 80) : '';
            value.mode = word(info.mode); value.bytes = isFinite(Number(info.size)) ? Math.max(0, Number(info.size)) : 0;
          }
          responses.push(value); if (responses.length > 16) responses.shift();
        } catch (e) { /* Не сохранять адрес или тело ответа даже при ошибке разбора. */ }
      },
      capture: capture
    };
  })();
