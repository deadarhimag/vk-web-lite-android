// Часть assets/vk_lite/: Пустой отступ под мессенджером VK.
// Выполняется внутри общей функции из 00_core.js (см. web/PageScripts.kt), отдельно не запускается.

  // ------------------------------------------------- пустой отступ под мессенджером

  // Раньше высота проверялась таймером раз в 1,5 с на каждой странице и всё время, пока она открыта (в том числе
  // в фоне во время музыки): каждая проверка — пересчёт вёрстки. Теперь проверка идёт только в мессенджере (/mail),
  // пока контейнер не найден, — не дольше SHELL_SEARCH_MS; дальше — только при изменении его размеров или окна.
  var SHELL_ID = 'reforged-root';
  var SHELL_MAX_GAP_PX = 220;
  var SHELL_MIN_GAP_PX = 4;
  var SHELL_CHECK_MS = 1500;
  var SHELL_SEARCH_MS = 30000;
  var shellTimer = 0;
  var shellSearchUntil = 0;
  var shellObserved = null;
  var shellResize = typeof ResizeObserver === 'function' ? new ResizeObserver(function () { fitShell(); }) : null;

  function isMessengerPage() { return /^\/mail(\/|$)/.test(location.pathname); }

  /** VK оставляет внизу место под свою панель. Если контейнер мессенджера не доходит до низа экрана — растягиваем. */
  function fitShell() {
    var shell = document.getElementById(SHELL_ID);
    if (!shell) return false;
    observeShell(shell);
    var rect = shell.getBoundingClientRect();
    var gap = window.innerHeight - rect.bottom;
    if (gap < SHELL_MIN_GAP_PX || gap > SHELL_MAX_GAP_PX || rect.height <= 0) return true;
    var height = window.innerHeight - rect.top;
    shell.style.setProperty('height', height + 'px', 'important');
    shell.style.setProperty('max-height', height + 'px', 'important');
    return true;
  }

  /** Следить за размером найденного контейнера (VK пересоздаёт его — тогда следим за новым). */
  function observeShell(shell) {
    if (!shellResize || shellObserved === shell) return;
    if (shellObserved) shellResize.unobserve(shellObserved);
    shellObserved = shell;
    shellResize.observe(shell);
  }

  /** Поиск контейнера: только в мессенджере, только пока окно действительно видно и не дольше SHELL_SEARCH_MS. */
  function searchShell() {
    clearTimeout(shellTimer);
    shellTimer = 0;
    if (!isMessengerPage() || realPageHidden()) return;
    if (fitShell() && shellResize) return;
    if (Date.now() < shellSearchUntil) shellTimer = setTimeout(searchShell, SHELL_CHECK_MS);
  }

  function restartShellSearch() {
    shellSearchUntil = Date.now() + SHELL_SEARCH_MS;
    searchShell();
  }

  window.addEventListener('resize', fitShell);
  window.addEventListener('orientationchange', fitShell);
  onRouteChange(restartShellSearch);
  onRealVisibility(function (hidden) { if (!hidden) restartShellSearch(); });
  restartShellSearch();

  // ------------------------------------------------- признак раздела на <html>

  // Первый сегмент пути («feed», «discover», «market»…): стили отличают разделы друг от друга (см. vk_lite.css).
  function syncPageAttribute() {
    try {
      var root = document.documentElement;
      if (!root) return;
      var segment = (location.pathname.split('/')[1] || 'root').replace(/[^A-Za-z_]/g, '').toLowerCase() || 'other';
      if (root.getAttribute('data-vklite-page') !== segment) root.setAttribute('data-vklite-page', segment);
    } catch (e) { /* страница без доступа к адресу */ }
  }

  onRouteChange(syncPageAttribute);
  syncPageAttribute();
