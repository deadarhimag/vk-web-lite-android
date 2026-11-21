package com.example.vkweb.web

/**
 * Обезличенная строка User-Agent. Без зависимостей от Android: проверяется обычными тестами.
 *
 * Штатная строка WebView выдаёт модель телефона, сборку, версию Android и точную версию Chrome — всё это
 * уходит сайту с каждым запросом и в `navigator.userAgent`. Так же поступает сам Chrome («сокращение User-Agent»):
 * `Android 10; K`, версия Chrome без дробной части. Сайт при этом по-прежнему видит мобильный Chrome и отдаёт
 * обычную мобильную версию. Маркер WebView (`; wv`, `Version/4.0`) убирается всегда: по нему VK подсовывает
 * заглушку «откройте приложение».
 */
object UserAgent {

    private val ANDROID_BLOCK = Regex("\\(Linux; Android [^)]*\\)")
    private val WEBVIEW_VERSION = Regex("Version/\\d+\\.\\d+ ")
    private val CHROME_VERSION = Regex("Chrome/(\\d+)\\.\\d+\\.\\d+\\.\\d+")

    /** Только убрать маркер WebView (поведение без защиты). */
    fun withoutWebViewMarker(ua: String): String = ua.replace("; wv", "")

    /** Убрать маркер WebView и сведения об устройстве. */
    fun generic(ua: String): String = withoutWebViewMarker(ua)
        .replace(ANDROID_BLOCK, "(Linux; Android 10; K)")
        .replace(WEBVIEW_VERSION, "")
        .replace(CHROME_VERSION) { "Chrome/${it.groupValues[1]}.0.0.0" }
}
