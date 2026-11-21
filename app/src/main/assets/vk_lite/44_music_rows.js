// Read adapters for music rows. Selection never clicks or changes the page.
  var MusicRows = (function () {
    var last = '', LIMIT = 2000;
    function mediaId(value, suffix) {
      var match = (suffix ? /^(-?\d{1,12}_\d{1,12})(?:_[\w-]{1,80})?$/ : /^(-?\d{1,12}_\d{1,12})$/).exec(String(value || ''));
      return match ? match[1] : '';
    }
    function tuple(el) {
      try {
        var raw = el.getAttribute('data-audio');
        if (!raw || raw.length > 65536) return null;
        var value = JSON.parse(raw);
        return isTrackTuple(value) ? value : null;
      } catch (e) { return null; }
    }
    function identity(el) {
      if (!el || typeof el.getAttribute !== 'function') return '';
      var t = tuple(el), raw = el.getAttribute('data-audio');
      if (raw && !t) return '';
      var id = t ? mediaId(t[1] + '_' + t[0]) : '';
      var names = ['data-audio-id', 'data-full-id', 'data-id'];
      for (var i = 0; i < names.length; i++) {
        var candidate = mediaId(el.getAttribute(names[i]), names[i] === 'data-id');
        if (!candidate) continue;
        if (id && id !== candidate) return '';
        id = candidate;
      }
      var classes = String(el.getAttribute('class') || '');
      var marker = t || mediaId(el.getAttribute('data-audio-id')) || /audio[_-]?(?:item|row)/i.test(classes) ||
        /audio[_-]?(?:item|row|track)/i.test(String(el.getAttribute('data-testid') || ''));
      return marker ? id : '';
    }
    function nodes(root, selector) {
      if (!root || typeof root.querySelectorAll !== 'function') return [];
      return Array.prototype.slice.call(root.querySelectorAll(selector), 0, LIMIT);
    }
    function unique(list, id) {
      var found = null;
      for (var i = 0; i < list.length; i++) {
        var el = list[i];
        if (identity(el) !== id) continue;
        if (found === el) continue;
        if (found && typeof found.contains === 'function' && found.contains(el)) continue;
        if (found && typeof el.contains === 'function' && el.contains(found)) { found = el; continue; }
        if (found) return null;
        found = el;
      }
      return found;
    }
    function rows(root) {
      var result = nodes(root, '[data-audio]').filter(function (el) { return !!tuple(el); });
      if (root && typeof root.getAttribute === 'function' && tuple(root) && result.indexOf(root) === -1) result.unshift(root);
      return result.slice(0, LIMIT);
    }
    function find(value) {
      var id = mediaId(value); last = '';
      if (!id) return null;
      function strategy(name, read) { return { id: name, read: function () {
        var value = read(); if (value) last = name; return value;
      } }; }
      var found = window.__vkCompat.choose('music.row', [
        strategy('legacy', function () { return unique(nodes(document, '.audio_item[data-id="' + id + '"],.audio_item[data-id^="' + id + '_"]'), id); }),
        strategy('tuple', function () { return unique(rows(document), id); }),
        strategy('semantic', function () { return unique(nodes(document,
          '[data-audio-id="' + id + '"],[data-full-id="' + id + '"],[data-id="' + id + '"],[data-id^="' + id + '_"]'), id); })
      ], function (el) { return identity(el) === id; });
      if (!found) last = '';
      return found;
    }
    function current() {
      var candidates = nodes(document, '.audio_item[class*="playing"],.audio_item[class*="_current"],.audio_item[class*="--current"],' +
        '[data-audio][aria-current="true"],[data-audio-id][aria-current="true"],[data-audio][aria-selected="true"],' +
        '[data-audio-id][data-state="playing"],[data-full-id][data-state="playing"]');
      var id = '';
      for (var i = 0; i < candidates.length; i++) {
        var candidate = identity(candidates[i]);
        if (!candidate) continue;
        if (id && id !== candidate) return '';
        id = candidate;
      }
      return id;
    }
    return { find: find, rows: rows, identity: identity, current: current, last: function () { return last; } };
  })();
