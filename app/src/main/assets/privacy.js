/*
 * VK Lite: защита от сбора сведений об устройстве (подключается вместе с остальными скриптами
 * в самом начале документа, до скриптов VK — web/PrivacyShield.kt).
 *
 * Сайты собирают «отпечаток» устройства: модель и мощность (ядра, память), размер экрана, режимы системы,
 * видеокарту, особенности отрисовки, датчики, тип сети. Здесь такие сведения заменяются одинаковыми для всех
 * общими значениями или скрываются. Нужное для работы страницы (размер окна, тема, язык, часовой пояс,
 * управление звуком) не трогается — иначе страница выглядела бы неверно.
 *
 * Каждая подмена независима и в try/catch: сбой одной не отключает остальные и не ломает страницу.
 * Повторный запуск ничего не делает.
 */
(function () {
  'use strict';
  if (window.__vkPrivacy) return;
  try { Object.defineProperty(window, '__vkPrivacy', { value: true }); } catch (e) { window.__vkPrivacy = true; }

  /** Подменяет свойство-получатель на прототипе (как у настоящих свойств Navigator, Screen). */
  function define(target, name, getter) {
    try {
      if (!target) return;
      Object.defineProperty(target, name, { get: getter, set: undefined, configurable: true, enumerable: true });
    } catch (e) { /* свойство не подменяется — пропускаем */ }
  }

  function guard(fn) { try { fn(); } catch (e) { /* подмена не удалась */ } }

  var nav = typeof Navigator !== 'undefined' ? Navigator.prototype : null;
  var scr = typeof Screen !== 'undefined' ? Screen.prototype : null;

  // ---- устройство: мощность, платформа, сеть, батарея
  guard(function () {
    define(nav, 'hardwareConcurrency', function () { return 4; });
    define(nav, 'deviceMemory', function () { return 4; });
    define(nav, 'platform', function () { return 'Linux armv81'; });
    define(nav, 'connection', function () { return undefined; });
    define(nav, 'mozConnection', function () { return undefined; });
    define(nav, 'webkitConnection', function () { return undefined; });
    define(nav, 'getBattery', function () { return undefined; });
    // Список языков — один основной: остальные выдают набор установленных раскладок.
    define(nav, 'languages', function () { return [nav && navigator.language ? navigator.language : 'ru']; });
  });

  // ---- подсказки о клиенте (замена User-Agent): без модели, версии Android и архитектуры
  guard(function () {
    var chrome = /Chrome\/(\d+)/.exec(navigator.userAgent);
    var major = chrome ? chrome[1] : '120';
    var brands = [{ brand: 'Chromium', version: major }, { brand: 'Not-A.Brand', version: '24' }];
    var data = {
      brands: brands,
      mobile: true,
      platform: 'Android',
      getHighEntropyValues: function () {
        return Promise.resolve({
          brands: brands, mobile: true, platform: 'Android', platformVersion: '10.0.0',
          architecture: '', bitness: '', model: '', uaFullVersion: major + '.0.0.0',
          fullVersionList: [{ brand: 'Chromium', version: major + '.0.0.0' }], wow64: false
        });
      },
      toJSON: function () { return { brands: brands, mobile: true, platform: 'Android' }; }
    };
    define(nav, 'userAgentData', function () { return data; });
  });

  // ---- экран: размеры физического экрана заменены размером окна (он нужен для вёрстки и так известен)
  guard(function () {
    define(scr, 'width', function () { return window.innerWidth; });
    define(scr, 'height', function () { return window.innerHeight; });
    define(scr, 'availWidth', function () { return window.innerWidth; });
    define(scr, 'availHeight', function () { return window.innerHeight; });
    define(scr, 'availLeft', function () { return 0; });
    define(scr, 'availTop', function () { return 0; });
    define(scr, 'colorDepth', function () { return 24; });
    define(scr, 'pixelDepth', function () { return 24; });
    define(window, 'outerWidth', function () { return window.innerWidth; });
    define(window, 'outerHeight', function () { return window.innerHeight; });
    define(window, 'screenX', function () { return 0; });
    define(window, 'screenY', function () { return 0; });
    define(window, 'screenLeft', function () { return 0; });
    define(window, 'screenTop', function () { return 0; });
  });

  // ---- плотность пикселей: целое число (2,625 → 3) — точное значение выдаёт модель экрана
  guard(function () {
    var own = Object.getOwnPropertyDescriptor(window, 'devicePixelRatio');
    var real;
    if (own && own.get) {
      real = function () { return own.get.call(window); };
    } else {
      // Не «получатель» (другой движок): значение запоминаем до подмены. Читать window.devicePixelRatio из
      // подменённого получателя нельзя — он вызывал бы сам себя бесконечно.
      var fixed = own && 'value' in own ? own.value : 1;
      real = function () { return fixed; };
    }
    define(window, 'devicePixelRatio', function () { return Math.min(3, Math.max(1, Math.round(real()))); });
  });

  // ---- режимы системы в медиа-запросах: сообщаем «обычные» значения; тема (prefers-color-scheme) не трогается
  guard(function () {
    if (typeof window.matchMedia !== 'function') return;
    var original = window.matchMedia;
    // Признаки, значение которых — личная настройка (доступность, режимы). «Обычное» значение — none / no-preference.
    var NEUTRAL = /\(\s*(forced-colors|prefers-reduced-motion|prefers-contrast|inverted-colors|prefers-reduced-data|prefers-reduced-transparency)\s*:\s*([\w-]+)\s*\)/;
    window.matchMedia = function (query) {
      var list = original.apply(this, arguments);
      var m = NEUTRAL.exec(String(query));
      if (m) {
        var asksNeutral = m[2] === 'none' || m[2] === 'no-preference';
        guard(function () { Object.defineProperty(list, 'matches', { get: function () { return asksNeutral; }, configurable: true }); });
      }
      return list;
    };
  });

  // ---- датчики: события движения и ориентации не доставляются, конструкторы датчиков недоступны
  guard(function () {
    var SENSOR_EVENTS = { devicemotion: 1, deviceorientation: 1, deviceorientationabsolute: 1 };
    var add = EventTarget.prototype.addEventListener;
    EventTarget.prototype.addEventListener = function (type) {
      if (SENSOR_EVENTS[String(type).toLowerCase()]) return undefined;
      return add.apply(this, arguments);
    };
    ['Accelerometer', 'Gyroscope', 'Magnetometer', 'AbsoluteOrientationSensor', 'RelativeOrientationSensor',
      'LinearAccelerationSensor', 'GravitySensor', 'AmbientLightSensor'].forEach(function (name) {
      define(window, name, function () { return undefined; });
    });
  });

  // ---- память и место: общие значения вместо реальных
  guard(function () {
    if (typeof Performance !== 'undefined') {
      define(Performance.prototype, 'memory', function () {
        return { jsHeapSizeLimit: 2172649472, totalJSHeapSize: 20000000, usedJSHeapSize: 15000000 };
      });
    }
    if (typeof StorageManager !== 'undefined' && StorageManager.prototype.estimate) {
      StorageManager.prototype.estimate = function () { return Promise.resolve({ quota: 2147483648, usage: 0 }); };
    }
  });

  // ---- видеокарта: производитель и модель из WebGL заменены общими
  guard(function () {
    var UNMASKED_VENDOR = 37445;
    var UNMASKED_RENDERER = 37446;
    [window.WebGLRenderingContext, window.WebGL2RenderingContext].forEach(function (Context) {
      if (!Context || !Context.prototype) return;
      var original = Context.prototype.getParameter;
      Context.prototype.getParameter = function (parameter) {
        if (parameter === UNMASKED_VENDOR) return 'Google Inc.';
        if (parameter === UNMASKED_RENDERER) return 'ANGLE (Generic, Generic GPU, OpenGL ES 3.0)';
        return original.apply(this, arguments);
      };
    });
  });

  // ---- отпечаток отрисовки (canvas): в маленькие изображения вносится невидимая глазу правка.
  // Отпечаток снимают с небольшого холста; большие (редактор фото, загрузка) не затрагиваются.
  // Правка вносится в КОПИЮ: исходный холст не меняется, поэтому повторный вызов даёт тот же результат.
  // (Правка прямо в холст при одинаковых позициях гасила бы сама себя через раз — и выдавала подмену.)
  guard(function () {
    if (typeof HTMLCanvasElement === 'undefined' || typeof CanvasRenderingContext2D === 'undefined') return;
    var MAX_AREA = 40000;
    var seed = Math.floor(Math.random() * 0x7fffffff) + 1;
    var context2d = CanvasRenderingContext2D.prototype;
    var originalGetImageData = context2d.getImageData;
    var originalPutImageData = context2d.putImageData;
    var originalToDataURL = HTMLCanvasElement.prototype.toDataURL;
    var originalToBlob = HTMLCanvasElement.prototype.toBlob;

    function perturbPixels(data) {
      // Меняется младший бит нескольких каналов; места определяет случайное число этой загрузки страницы.
      var state = seed;
      for (var i = 0; i < 24; i++) {
        state = (state * 1103515245 + 12345) & 0x7fffffff;
        var index = state % data.length;
        data[index] = data[index] ^ 1;
      }
    }

    function isSmall(canvas) {
      var area = canvas.width * canvas.height;
      return area > 0 && area <= MAX_AREA;
    }

    function noisyCopy(canvas) {
      var copy = document.createElement('canvas');
      copy.width = canvas.width;
      copy.height = canvas.height;
      var context = copy.getContext('2d');
      if (!context) return canvas;
      context.drawImage(canvas, 0, 0);
      var image = originalGetImageData.call(context, 0, 0, copy.width, copy.height);
      perturbPixels(image.data);
      originalPutImageData.call(context, image, 0, 0);
      return copy;
    }

    /** Что отдать на вывод: правленая копия маленького холста; при любой неудаче (чужой источник) — сам холст. */
    function exportable(canvas) {
      try { return isSmall(canvas) ? noisyCopy(canvas) : canvas; } catch (e) { return canvas; }
    }

    HTMLCanvasElement.prototype.toDataURL = function () { return originalToDataURL.apply(exportable(this), arguments); };
    HTMLCanvasElement.prototype.toBlob = function () { return originalToBlob.apply(exportable(this), arguments); };
    context2d.getImageData = function () {
      var image = originalGetImageData.apply(this, arguments);
      if (image && image.data && image.width * image.height <= MAX_AREA) guard(function () { perturbPixels(image.data); });
      return image;
    };
  });
})();
