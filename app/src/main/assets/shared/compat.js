// Local, read-only compatibility strategies. No downloads, eval, DOM mutations or write retries.
(function () {
  'use strict';
  if (window.__vkCompat) return;
  var MAX_JSON = 8 * 1024 * 1024, MAX_BATCH = 100;
  var page = '', generation = 0, health = Object.create(null);
  var documentId = Date.now().toString(36) + '-' + Math.random().toString(36).slice(2, 8);
  var apiStats = { standard: 0, alternate: 0, errors: 0, unknown: 0 };
  function own(value, key) { return value && Object.prototype.hasOwnProperty.call(value, key); }
  function object(value) { return value && typeof value === 'object' && !Array.isArray(value); }
  function syncPage() {
    var key = location.pathname + location.search;
    if (key !== page) { page = key; generation++; health = Object.create(null); }
  }
  function parse(text) {
    if (typeof text !== 'string' || text.length > MAX_JSON) return null;
    text = text.replace(/^[\s\uFEFF]+/, '');
    if (text[0] !== '{' && text[0] !== '[') return null;
    try { return JSON.parse(text); } catch (e) { return null; }
  }
  // Only known envelopes are unwrapped. Business objects with arbitrary nested ids/urls are never searched.
  function api(value) {
    if (typeof value === 'string') value = parse(value);
    for (var depth = 0; depth < 4 && object(value); depth++) {
      if (own(value, 'error') && !!value.error || value.success === false || value.status === 'error') { apiStats.errors++; return null; }
      if (own(value, 'response')) { apiStats[depth ? 'alternate' : 'standard']++; return { response: value.response }; }
      var keys = Object.keys(value), next = null;
      if (keys.every(function (k) { return ['data', 'result', 'success', 'version', 'request_id', 'status'].indexOf(k) !== -1; }) &&
          value.success !== false && value.status !== 'error') {
        if (own(value, 'result') && value.result != null) next = value.result;
        else if (own(value, 'data') && value.data != null) next = value.data;
      }
      if (!next) { apiStats.unknown++; return null; }
      if (object(next) && (own(next, 'error') && !!next.error || next.success === false || next.status === 'error')) {
        apiStats.errors++; return null;
      }
      if (!object(next) || !own(next, 'response') && !own(next, 'result') && !own(next, 'data')) {
        apiStats.alternate++; return { response: next };
      }
      value = next;
    }
    apiStats.unknown++; return null;
  }
  function batch(body, data) {
    var request = parse(body);
    if (!request) { try { request = parse(new URLSearchParams(body).get('requests') || ''); } catch (e) {} }
    var requests = Array.isArray(request) ? request : request && request.requests;
    if (!Array.isArray(requests) || requests.length > MAX_BATCH) return [];
    var byId = Object.create(null), duplicate = Object.create(null);
    requests.forEach(function (q) {
      if (!q || q.id == null || !/^[A-Za-z][\w.]{0,99}$/.test(String(q.method || '')) || q.params != null && !object(q.params)) return;
      var key = String(q.id);
      if (own(byId, key)) duplicate[key] = true;
      byId[key] = q;
    });
    var canonical = api(data), r = canonical && canonical.response;
    var parts = data && data.responses || r && r.responses;
    if (!Array.isArray(parts) || parts.length > MAX_BATCH) return [];
    var seen = Object.create(null), repeated = Object.create(null);
    parts.forEach(function (part) {
      if (!part) return;
      var key = String(part.id != null ? part.id : part.request_id);
      if (own(seen, key)) repeated[key] = true;
      seen[key] = part;
    });
    return Object.keys(seen).map(function (key) {
      var part = seen[key], q = byId[key];
      if (!q || duplicate[key] || repeated[key]) return null;
      var raw = own(part, 'body') ? part.body : own(part, 'response') ? { response: part.response } : null;
      var value = api(raw);
      return value ? { method: String(q.method), params: q.params || {}, body: value } : null;
    }).filter(Boolean);
  }
  /** Readers return candidates only. A failed validation never becomes an action retry. */
  function choose(section, strategies, validate) {
    syncPage();
    if (!/^[a-z][a-z0-9.-]{0,40}$/.test(section) || strategies.length > 8) return null;
    if (!own(health, section) && Object.keys(health).length >= 24) return null;
    var group = health[section] || (health[section] = Object.create(null));
    for (var i = 0; i < strategies.length; i++) {
      var strategy = strategies[i], key = String(strategy.id);
      if (!/^[a-z][a-z0-9.-]{0,40}$/.test(key)) continue;
      if (!own(group, key) && Object.keys(group).length >= 8) continue;
      var state = group[key] || (group[key] = { failures: 0, until: 0, accepted: 0, misses: 0 });
      if (state.until > Date.now()) continue;
      try {
        var value = strategy.read();
        if (value == null) { state.misses++; continue; }
        if (!validate(value)) throw new Error('invalid_candidate');
        state.failures = 0; state.until = 0; state.accepted++;
        return value;
      } catch (e) {
        state.failures++;
        if (state.failures >= 3) state.until = Date.now() + 30000;
      }
    }
    return null;
  }
  function describe(kind, apiObject) {
    syncPage();
    var functions = Object.keys(apiObject || {}).filter(function (key) { return typeof apiObject[key] === 'function'; }).slice(0, 80);
    var strategies = [];
    Object.keys(health).forEach(function (section) {
      Object.keys(health[section]).forEach(function (id) {
        var state = health[section][id];
        strategies.push({ section: section, id: id, failures: state.failures, accepted: state.accepted,
          misses: state.misses, coolingDown: state.until > Date.now() });
      });
    });
    return { protocol: 1, engine: 1, kind: kind, documentId: documentId, generation: generation,
      functions: functions, strategies: strategies, api: Object.assign({}, apiStats) };
  }
  function validReadList(items, validate) {
    if (!Array.isArray(items) || items.length > 5000) return false;
    try { return items.every(validate); } catch (e) { return false; }
  }
  Object.defineProperty(window, '__vkCompat', { value: Object.freeze({ protocol: 1, parse: parse, api: api,
    batch: batch, choose: choose, describe: describe, validReadList: validReadList,
    generation: function () { syncPage(); return generation; } }), writable: false, configurable: false });
  // Observe real navigation calls; never initiate one or change its return value/arguments.
  ['pushState', 'replaceState'].forEach(function (name) {
    try {
      var history = window.history, original = history && history[name];
      if (typeof original !== 'function') return;
      history[name] = function () { var result = original.apply(this, arguments); syncPage(); return result; };
    } catch (e) { /* page-generation checks still work at request/response boundaries */ }
  });
  if (typeof window.addEventListener === 'function') window.addEventListener('popstate', syncPage);
})();
