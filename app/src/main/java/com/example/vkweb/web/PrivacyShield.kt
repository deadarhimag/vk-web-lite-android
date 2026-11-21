package com.example.vkweb.web

import android.webkit.WebView

/**
 * Защита от сбора сведений об устройстве: скрипт assets/privacy.js встраивается в страницы VK в самом начале
 * документа (до скриптов VK) и заменяет то, по чему сайт узнаёт телефон — ядра и память, размер экрана, режимы
 * системы, видеокарту, датчики, тип сети, отпечаток отрисовки. Дополняют её обезличенный User-Agent
 * ([UserAgent]) и отключённый заголовок X-Requested-With ([WebViewSetup]).
 */
object PrivacyShield {

    const val ASSET = "privacy.js"

    fun install(webView: WebView) = DocumentStartScript.install(webView, ASSET, "WebView слишком старый: скрытие сведений об устройстве в скриптах страницы недоступно")
}
