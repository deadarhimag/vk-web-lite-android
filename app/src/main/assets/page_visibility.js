/*
 * VK Web: страница всегда «на экране» (web/PageVisibility.kt; подключается первым из скриптов, в самом начале
 * документа, во все окна VK — главное, окно чтения сообщений и фоновую проверку).
 *
 * Когда экран блокируется, приложение уходит в фон или страница закрыта своим экраном, WebView сообщает странице
 * «скрыта»: document.hidden = true, событие visibilitychange, blur окна. Скрипты VK на это реагируют — останавливают
 * плеер, переходят в «спящий» режим, перестают дорисовывать переписку. Здесь страница всегда видит «показана и в
 * фокусе», а события смены видимости до её скриптов не доходят.
 *
 * Свои скрипты приложения настоящую видимость знают: window.__vkWebVisibility.hidden() и .onChange(fn) —
 * чтобы не тратить батарею на свою работу, пока окно действительно не видно.
 *
 * Пока окно скрыто (страница закрыта своим экраном — WebView не рисует её), здесь же эмулируется то, что в скрытой
 * странице не работает: кадры, пересечения и прокрутка (см. ниже). Размер окна при этом обычный.
 *
 * Каждая подмена в try/catch: сбой одной не ломает остальные и страницу. Повторный запуск ничего не делает.
 */
(function () {
  'use strict';
  if (window.__vkWebVisibility) return;

  var docProto = typeof Document !== 'undefined' ? Document.prototype : null;

  function nativeGetter(name) {
    try {
      var d = docProto && Object.getOwnPropertyDescriptor(docProto, name);
      return d && typeof d.get === 'function' ? d.get : null;
    } catch (e) { return null; }
  }

  var realHiddenGet = nativeGetter('hidden');
  var realStateGet = nativeGetter('visibilityState');

  /** Настоящая видимость окна (для своих скриптов). */
  function realHidden() {
    try {
      if (realHiddenGet) return realHiddenGet.call(document) === true;
      if (realStateGet) return realStateGet.call(document) !== 'visible';
    } catch (e) { /* документ ещё не готов */ }
    return false;
  }

  var listeners = [];

  function notifyOwn() {
    var hidden = realHidden();
    for (var i = 0; i < listeners.length; i++) {
      try { listeners[i](hidden); } catch (e) { /* чужая ошибка не мешает остальным */ }
    }
  }

  function define(target, name, getter) {
    try {
      if (!target) return;
      Object.defineProperty(target, name, { get: getter, set: undefined, configurable: true, enumerable: true });
    } catch (e) { /* свойство не подменяется — пропускаем */ }
  }

  // ---- свойства: всегда «показана»
  define(docProto, 'hidden', function () { return false; });
  define(docProto, 'webkitHidden', function () { return false; });
  define(docProto, 'visibilityState', function () { return 'visible'; });
  define(docProto, 'webkitVisibilityState', function () { return 'visible'; });
  try {
    if (docProto && typeof docProto.hasFocus === 'function') {
      docProto.hasFocus = function () { return true; };
    }
  } catch (e) { /* без подмены фокуса */ }

  // ---- события: смена видимости, потеря фокуса окном, «заморозка» страницы.
  // Слушатель в фазе перехвата на window ставится раньше всех скриптов страницы, поэтому срабатывает первым
  // и останавливает событие до её обработчиков (и до свойств-обработчиков вида document.onvisibilitychange).
  function swallow(event) {
    try { event.stopImmediatePropagation(); } catch (e) { /* старый движок */ }
  }

  try {
    var onVisibility = function (event) {
      notifyOwn();
      swallow(event);
    };
    window.addEventListener('visibilitychange', onVisibility, true);
    window.addEventListener('webkitvisibilitychange', onVisibility, true);
    // Потеря фокуса самим окном (не полем ввода): так страница узнаёт о блокировке экрана.
    window.addEventListener('blur', function (event) { if (event.target === window) swallow(event); }, true);
    // Page Lifecycle: страница «заморожена» в фоне.
    window.addEventListener('freeze', swallow, true);
  } catch (e) { /* без перехвата событий: подмена свойств всё равно работает */ }

  // ======================================================================================================
  // Скрытое окно без отрисовки. Пока страница закрыта своим экраном приложения (или окно чтения сообщений), WebView
  // скрыт: страница не рисуется — это экономит батарею. Размер окна при этом сохраняется (вёрстка считается как
  // обычно), но Chromium в скрытой странице не даёт кадров (requestAnimationFrame), не сообщает о пересечениях
  // (IntersectionObserver) и не шлёт событий прокрутки. На них VK держит догрузку лент и отрисовку списков, поэтому
  // здесь, только пока окно действительно скрыто, это делается вручную: кадры — редким таймером, пересечения —
  // по расчёту положения элементов, прокрутка — по команде приложения ([afterScroll]).
  // ======================================================================================================

  /** Задача «как можно скорее» без таймера: таймеры скрытой страницы Chromium выравнивает до секунды. */
  var soon = (function () {
    var queue = [];
    var port = null;
    try {
      var channel = new MessageChannel();
      channel.port1.onmessage = function () {
        var tasks = queue;
        queue = [];
        for (var i = 0; i < tasks.length; i++) { try { tasks[i](); } catch (e) { /* чужая ошибка */ } }
      };
      port = channel.port2;
    } catch (e) { /* без MessageChannel — обычный таймер */ }
    return function (fn) {
      if (!port) { setTimeout(fn, 0); return; }
      queue.push(fn);
      if (queue.length === 1) port.postMessage(0);
    };
  })();

  // ---- кадры: в скрытой странице — редким таймером (цикл анимации не крутит процессор, но и не встаёт)
  var HIDDEN_FRAME_MS = 100;
  try {
    var nativeRaf = window.requestAnimationFrame;
    var nativeCancelRaf = window.cancelAnimationFrame;
    if (typeof nativeRaf === 'function' && typeof nativeCancelRaf === 'function') {
      var shimFrames = {};
      var shimFrameId = 0;
      window.requestAnimationFrame = function (callback) {
        if (!realHidden()) return nativeRaf.call(window, callback);
        var id = --shimFrameId; // отрицательные — свои, не пересекаются с настоящими
        shimFrames[id] = setTimeout(function () {
          delete shimFrames[id];
          callback(typeof performance !== 'undefined' ? performance.now() : Date.now());
        }, HIDDEN_FRAME_MS);
        return id;
      };
      window.cancelAnimationFrame = function (id) {
        if (id < 0) {
          clearTimeout(shimFrames[id]);
          delete shimFrames[id];
          return;
        }
        return nativeCancelRaf.call(window, id);
      };
    }
  } catch (e) { /* без подмены кадров */ }

  // ---- пересечения: свой расчёт, пока окно скрыто (настоящий наблюдатель в это время молчит)
  // Наблюдатели хранятся слабыми ссылками: страница VK часто создаёт их и бросает без disconnect(), и сильная ссылка
  // здесь держала бы и наблюдателя, и все его элементы вечно (утечка в долгоживущей странице). Пока наблюдатель
  // следит за элементами, его держит сам браузер — слабая ссылка остаётся рабочей.
  var observers = new Set();
  var HAS_WEAKREF = typeof WeakRef === 'function';
  var NativeIO = window.IntersectionObserver;

  function eachObserver(fn) {
    observers.forEach(function (holder) {
      var observer = HAS_WEAKREF ? holder.deref() : holder;
      if (!observer) { observers.delete(holder); return; }
      fn(observer);
    });
  }

  function parseMargin(value, rootWidth, rootHeight) {
    var parts = String(value || '0px').trim().split(/\s+/).slice(0, 4);
    var px = parts.map(function (part, i) {
      var n = parseFloat(part) || 0;
      if (/%$/.test(part)) n = n / 100 * (i % 2 === 0 ? rootHeight : rootWidth);
      return n;
    });
    while (px.length < 4) px.push(px.length === 1 ? px[0] : px.length === 2 ? px[0] : px[1]);
    return { top: px[0], right: px[1], bottom: px[2], left: px[3] };
  }

  function rootRect(observer) {
    var root = observer.root;
    if (root && root.nodeType === 1 && typeof root.getBoundingClientRect === 'function') return root.getBoundingClientRect();
    var w = window.innerWidth || (document.documentElement && document.documentElement.clientWidth) || 0;
    var h = window.innerHeight || (document.documentElement && document.documentElement.clientHeight) || 0;
    return { top: 0, left: 0, right: w, bottom: h, width: w, height: h };
  }

  function computeEntry(observer, target, now) {
    var bounds = rootRect(observer);
    var m = parseMargin(observer.rootMargin, bounds.width, bounds.height);
    var root = {
      top: bounds.top - m.top, left: bounds.left - m.left,
      bottom: bounds.bottom + m.bottom, right: bounds.right + m.right
    };
    root.width = root.right - root.left;
    root.height = root.bottom - root.top;
    var box = target.getBoundingClientRect();
    var top = Math.max(box.top, root.top), left = Math.max(box.left, root.left);
    var bottom = Math.min(box.bottom, root.bottom), right = Math.min(box.right, root.right);
    var w = Math.max(0, right - left), h = Math.max(0, bottom - top);
    var connected = target.isConnected !== false;
    var intersecting = connected && bottom >= top && right >= left && (w > 0 || h > 0 || box.width === 0 || box.height === 0);
    var area = box.width * box.height;
    var ratio = !intersecting ? 0 : area > 0 ? Math.min(1, (w * h) / area) : 1;
    return {
      target: target, time: now, isIntersecting: intersecting, intersectionRatio: ratio,
      boundingClientRect: box, rootBounds: root,
      intersectionRect: { top: top, left: left, bottom: top + h, right: left + w, width: w, height: h, x: left, y: top }
    };
  }

  /** Номер порога, который пройден: смена номера — повод сообщить, как это сделал бы настоящий наблюдатель. */
  function thresholdIndex(observer, entry) {
    if (!entry.isIntersecting) return -1;
    var list = observer.thresholds && observer.thresholds.length ? observer.thresholds : [0];
    var index = 0;
    for (var i = 0; i < list.length; i++) if (entry.intersectionRatio >= list[i]) index = i + 1;
    return index;
  }

  /**
   * Сверить всех наблюдателей с текущим положением элементов (только когда окно скрыто).
   * force — сообщить и о неизменившихся видимых элементах: после прокрутки «до конца» лента должна догрузиться,
   * даже если метка конца была видна и до неё (настоящий наблюдатель сообщил бы о ней при прокрутке туда-обратно).
   */
  function checkObservers(force) {
    if (!realHidden() || !observers.size) return;
    var now = typeof performance !== 'undefined' ? performance.now() : Date.now();
    eachObserver(function (observer) {
      var state = observer.__vkWebState;
      if (!state) return;
      var entries = [];
      state.forEach(function (last, target) {
        var entry;
        try { entry = computeEntry(observer, target, now); } catch (e) { return; }
        var index = thresholdIndex(observer, entry);
        if (index !== last || (force && entry.isIntersecting)) {
          state.set(target, index);
          entries.push(entry);
        }
      });
      if (entries.length) {
        try { observer.__vkWebCallback.call(observer, entries, observer); } catch (e) { /* ошибка обработчика VK */ }
      }
    });
  }

  var checkQueued = false;
  function queueCheck() {
    if (checkQueued || !realHidden()) return;
    checkQueued = true;
    soon(function () { checkQueued = false; checkObservers(false); });
  }

  try {
    if (typeof NativeIO === 'function') {
      var ShimIO = function IntersectionObserver(callback, options) {
        var state = new Map();
        // Настоящий наблюдатель продолжает работать, когда окно видно; его сообщения обновляют и своё состояние,
        // чтобы после возврата в скрытое окно не повторять уже известное.
        var native = new NativeIO(function (entries, obs) {
          entries.forEach(function (e) { if (state.has(e.target)) state.set(e.target, thresholdIndex(obs, e)); });
          callback.call(obs, entries, obs);
        }, options);
        Object.defineProperty(native, '__vkWebState', { value: state });
        Object.defineProperty(native, '__vkWebCallback', { value: callback });
        var holder = HAS_WEAKREF ? new WeakRef(native) : native;
        observers.add(holder);
        var observe = native.observe, unobserve = native.unobserve, disconnect = native.disconnect;
        Object.defineProperty(native, 'observe', { value: function (target) {
          observe.call(native, target);
          if (target && !state.has(target)) state.set(target, null);
          // Настоящий наблюдатель сразу сообщает о новом элементе; в скрытом окне это делаем сами.
          queueCheck();
        } });
        Object.defineProperty(native, 'unobserve', { value: function (target) {
          state.delete(target);
          return unobserve.call(native, target);
        } });
        Object.defineProperty(native, 'disconnect', { value: function () {
          state.clear();
          observers.delete(holder);
          return disconnect.call(native);
        } });
        return native;
      };
      ShimIO.prototype = NativeIO.prototype;
      window.IntersectionObserver = ShimIO;
    }
  } catch (e) { /* без подмены наблюдателя: догрузка — кнопкой «Показать ещё» и событием прокрутки */ }

  /**
   * Приложение прокрутило страницу (или элемент) — догрузка списка, старые сообщения. В скрытом окне Chromium не шлёт
   * события прокрутки и не сообщает о пересечениях: делаем это сами. В видимом окне — только событие (как раньше).
   */
  function afterScroll(target) {
    var el = target && target !== window && target !== document && target.nodeType === 1 ? target : null;
    try { (el || window).dispatchEvent(new Event('scroll')); } catch (e) { /* старый движок */ }
    if (!el) { try { document.dispatchEvent(new Event('scroll')); } catch (e) { /* старый движок */ } }
    if (realHidden()) soon(function () { checkObservers(true); });
  }

  try {
    Object.defineProperty(window, '__vkWebVisibility', {
      value: Object.freeze({
        hidden: realHidden,
        onChange: function (fn) { if (typeof fn === 'function') listeners.push(fn); },
        soon: soon,
        afterScroll: afterScroll
      }),
      enumerable: false,
      configurable: false,
      writable: false
    });
  } catch (e) { /* без доступа своих скриптов к настоящей видимости */ }
})();
