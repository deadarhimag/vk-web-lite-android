// Часть assets/vk_lite/: повторная загрузка превью, которые не загрузились.
// Выполняется внутри общей функции из 00_core.js (см. web/PageScripts.kt), отдельно не запускается.
//
// Серверы картинок VK (sun9-NN.vkuserphoto.ru и др.) иногда отвечают ошибкой (в отчёте: net::ERR_FAILED), и тогда у
// видео и клипа в ленте остаётся пустая плитка без превью. Такая картинка запрашивается ещё раз с задержкой.

  var IMG_RETRY_MAX = 2;
  var IMG_RETRY_DELAY_MS = 1500;

  function retryImage(img) {
    var src = img.currentSrc || img.src || '';
    if (!src || src.indexOf('https://') !== 0) return;
    var tries = Number(img.getAttribute('data-vklite-retry') || 0);
    if (tries >= IMG_RETRY_MAX) return;
    // Только картинки серверов VK — общий список (shared/net_tap.js, 4.65).
    if (!NetTap.onHost(src, 'image')) return;
    img.setAttribute('data-vklite-retry', String(tries + 1));
    setTimeout(function () {
      if (!img.isConnected) return;
      // Тот же адрес, но заново: сброс src заставляет браузер повторить запрос.
      img.removeAttribute('srcset');
      img.src = '';
      img.src = src;
    }, IMG_RETRY_DELAY_MS * (tries + 1));
  }

  // Ошибки загрузки картинок не всплывают, поэтому слушаем на этапе перехвата.
  document.addEventListener('error', function (event) {
    var target = event && event.target;
    if (target && target.tagName === 'IMG') retryImage(target);
  }, true);
