// Часть assets/vk_lite/: Сбор обезличенных ответов страниц для разработки (модуль Dump).
// Выполняется внутри общей функции из 00_core.js (см. web/PageScripts.kt), отдельно не запускается.

  // ------------------------------------------------- сбор данных для разработки (настройка)

  // То же, что vk_dump.js, но изнутри приложения: включается в настройках («Для разработки»),
  // каждая запись сразу уходит приложению (dev/DumpRecorder.kt) и переживает перезагрузку страницы.
  // Из ответов убирается всё личное: токены, ключи и подписи — XXX, имена людей, тексты и контакты — «•••(длина)»,
  // у ссылок — параметры. Названия сообществ, плейлистов и треков остаются: по ним видно, что где лежит.
  // Длинные списки — первые MAX_ITEMS элементов: для структуры этого хватает.
  var Dump = (function () {
    var enabled = CONFIG.collectDump === true;
    var core = window.__vkDumpCore;

    function onText(url, body, text) {
      if (!core) return;
      var bridge = window[CONFIG.bridge];
      if (!bridge || typeof bridge.postMessage !== 'function') return;
      var rec = core.record(url, body, text, 'fetch');
      if (rec) { rec.compatibility = window.__vkCompat.describe('lite', window.__vkLite); bridge.postMessage(JSON.stringify(rec)); }
    }

    ResponseTap.listen(function () { return enabled; }, onText);

    return {
      /** Включить/выключить сбор (настройка меняется без перезагрузки страницы). */
      set: function (on) { enabled = on === true; MusicDiagnostics.set(on); }
    };
  })();
