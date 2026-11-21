// Часть assets/vk_lite/: Окна «Откройте приложение», подсказки и баннеры: закрываются и скрываются по тексту.
// Выполняется внутри общей функции из 00_core.js (см. web/PageScripts.kt), отдельно не запускается.

  // ------------------------------------------------- навязчивые окна и баннеры

  // Классы VK меняются при каждой сборке, поэтому окна и баннеры ищем по тексту.
  //  - promoClicks: нажать кнопку с таким текстом («Остаться в браузере»), чтобы VK запомнил отказ;
  //  - promoHides: спрятать блок с такой фразой (окно «Откройте приложение», подсказки, баннеры).
  // MutationObserver срабатывает сразу после вставки узла и ДО первой отрисовки, поэтому окно не мелькает.
  var promoClicks = normList(CONFIG.promoClicks);
  var promoHides = normList(CONFIG.promoHides);
  var promoIgnore = CONFIG.promoIgnoreSelector || '';
  var HIDDEN_ATTR = 'data-vklite-hidden';
  var SIBLING_TEXT_LIMIT = 12; // сколько «чужого» текста допустимо рядом (кнопка «Скачать», крестик)
  var HIDE_MAX_LEVELS = 10;
  var OVERLAY_MAX_CHARS = 1200;
  var CLICK_RETRY_MS = 350;
  var MAX_NODE_TEXT = 3000;

  function norm(text) {
    return (text || '').replace(/[\u00a0\u2009\u202f]/g, ' ').replace(/\s+/g, ' ').trim().toLowerCase();
  }

  function normList(list) {
    return (Array.isArray(list) ? list : []).map(norm).filter(Boolean);
  }

  function isProtected(el) {
    return !el || el === document.body || el === document.documentElement ||
      el.id === 'spa_global_root' || el.id === 'bff_global_root' || el.id === 'reforged-root';
  }

  /** Тексты чатов, комментариев и постов не трогаем: в них человек может написать что угодно. */
  function isUserContent(el) {
    return !!(promoIgnore && el && el.closest && el.closest(promoIgnore));
  }

  function findTextNode(root, phrase, exact) {
    var walker = document.createTreeWalker(root, NodeFilter.SHOW_TEXT, null);
    var node;
    while ((node = walker.nextNode())) {
      var value = norm(node.nodeValue);
      if (exact ? value === phrase : value.indexOf(phrase) !== -1) return node;
    }
    return null;
  }

  /**
   * Находит блок, который нужно спрятать целиком. Сначала ищем «плавающее» окно (position: fixed) —
   * так закрывается и подложка, и карточка. Если такого нет (баннер в ленте) — поднимаемся вверх,
   * пока родитель почти не содержит другого текста.
   */
  function findBlock(anchor, phrase) {
    var overlay = null;
    var el = anchor;
    for (var i = 0; el && !isProtected(el) && i < HIDE_MAX_LEVELS; i++, el = el.parentElement) {
      if (getComputedStyle(el).position === 'fixed' && norm(el.textContent).length <= OVERLAY_MAX_CHARS) overlay = el;
    }
    if (overlay) return overlay;

    var block = anchor;
    for (var j = 0; j < HIDE_MAX_LEVELS; j++) {
      var parent = block.parentElement;
      if (!parent || isProtected(parent)) break;
      // Поднимаемся, пока рядом только мелочь: иначе заденем соседние диалоги и посты.
      var extra = norm(parent.textContent).length - norm(block.textContent).length;
      if (extra > SIBLING_TEXT_LIMIT) break;
      block = parent;
    }
    return isProtected(block) ? null : block;
  }

  function hideBlock(block) {
    block.style.setProperty('display', 'none', 'important');
    block.setAttribute(HIDDEN_ATTR, '1');
  }

  function clickButton(button) {
    var tries = button.__vkLiteClicks || 0;
    if (tries >= 2) return;
    button.__vkLiteClicks = tries + 1;
    button.click();
    // React мог ещё не навесить обработчик — повторяем нажатие, если окно не закрылось.
    if (tries === 0) setTimeout(function () { if (button.isConnected) clickButton(button); }, CLICK_RETRY_MS);
  }

  function processNode(node) {
    if (!node || node.nodeType !== 1 || node.closest('[' + HIDDEN_ATTR + ']')) return;
    processAdLabels(node);
    processClipLinks(node);
    var raw = node.textContent;
    if (!raw || raw.length > MAX_NODE_TEXT) return;
    var text = norm(raw);

    promoClicks.forEach(function (phrase) {
      if (text.indexOf(phrase) === -1) return;
      var textNode = findTextNode(node, phrase, true);
      if (!textNode || isUserContent(textNode.parentElement)) return;
      var button = textNode.parentElement.closest('button,[role="button"],a') || textNode.parentElement;
      clickButton(button);
    });

    promoHides.forEach(function (phrase) {
      if (text.indexOf(phrase) === -1) return;
      var textNode = findTextNode(node, phrase, false);
      var anchor = textNode ? textNode.parentElement : node;
      if (!anchor || isUserContent(anchor) || anchor.closest('[' + HIDDEN_ATTR + ']')) return;
      var block = findBlock(anchor, phrase);
      if (block) hideBlock(block);
    });
  }

  /** Может ли изменённый текст быть рекламой, окном «Откройте приложение» или кнопкой, которую нажимаем. */
  function textMayMatter(value) {
    if (!value) return false;
    var text = norm(value);
    if (!text || text.length > MAX_NODE_TEXT) return false;
    if (blockAdData && isAdLabel(text)) return true;
    for (var i = 0; i < promoHides.length; i++) if (text.indexOf(promoHides[i]) !== -1) return true;
    for (var j = 0; j < promoClicks.length; j++) if (text.indexOf(promoClicks[j]) !== -1) return true;
    return false;
  }

  function scanAll() {
    if (document.body && (promoClicks.length || promoHides.length || blockAdData || blockClips)) processNode(document.body);
  }

  if (typeof MutationObserver === 'function') {
    // Наблюдаем за document, а не за documentElement: при запуске скрипта до разбора страницы его ещё нет.
    new MutationObserver(function (mutations) {
      if (!promoClicks.length && !promoHides.length && !blockAdData && !blockClips) return;
      for (var i = 0; i < mutations.length; i++) {
        var m = mutations[i];
        if (m.type === 'characterData') {
          // Смена текста — это и время трека, и счётчики, и «печатает…»: раз в секунду, в том числе в фоне.
          // Узел целиком разбирается, только если в новом тексте есть то, что ищем.
          var parent = m.target.parentElement;
          // Фраза может быть разбита на соседние текстовые узлы: тогда смотрим и короткий текст родителя.
          var parentText = parent && parent.childNodes.length > 1 ? parent.textContent : '';
          if (textMayMatter(m.target.nodeValue) || (parentText.length <= 300 && textMayMatter(parentText))) {
            processNode(parent);
          }
          continue;
        }
        for (var j = 0; j < m.addedNodes.length; j++) {
          var added = m.addedNodes[j];
          // Текст, дописанный React в уже существующий узел, приходит как текстовый узел.
          processNode(added.nodeType === 3 ? added.parentElement : added);
        }
      }
    }).observe(document, { childList: true, subtree: true, characterData: true });
  }

  window.addEventListener('load', function () {
    scanAll();
    setTimeout(scanAll, 3000);
  });

  /** Смена списка скрываемых блоков без перезагрузки (настройка «Скрывать рекламу»). */
  function setHides(list) {
    promoHides = normList(list);
    scanAll();
  }
