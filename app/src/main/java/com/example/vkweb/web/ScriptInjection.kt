package com.example.vkweb.web

import android.webkit.WebView
import com.example.vkweb.diag.AppLog
import com.example.vkweb.diag.LogTag

/**
 * Запасной путь встраивания своего скрипта после загрузки страницы — общий для главного окна ([PageScripts]) и окон
 * чтения сообщений (messages/ReaderScript.kt) (4.65, код ревью 4.64: было написано дважды, и у окна чтения не было
 * проверки, на каком сайте окно сейчас).
 *
 * Скрипт встраивается только на страницы доверенных сайтов ([UrlPolicy.isTrustedUrl]). Если он уже встал на старте
 * документа, весь его текст заново не передаётся: сначала короткая проверка [presentCheck].
 */
object ScriptInjection {
    private val requests = java.util.WeakHashMap<WebView, Long>()
    private var sequence = 0L

    /**
     * [onReady] — в главном потоке, когда скрипт точно на странице: команды ему — только после этого (вызовы
     * evaluateJavascript выполняются по порядку). Страница чужого сайта — ничего не делается, [onReady] не вызывается.
     */
    fun ensure(webView: WebView, url: String?, source: String, presentCheck: String, onReady: () -> Unit = {}) {
        if (!UrlPolicy.isTrustedUrl(url)) {
            AppLog.w(LogTag.PAGE, "скрипт не встроен: страница не на сайте VK", "хост" to UrlPolicy.hostOf(url))
            return
        }
        val documentUrl = webView.url ?: return
        val request = ++sequence
        requests[webView] = request
        fun current(): Boolean = requests[webView] == request && webView.url == documentUrl && UrlPolicy.isTrustedUrl(webView.url)
        fun verify() {
            if (!current()) return
            webView.evaluateJavascript(presentCheck) { ready ->
                if (!current()) return@evaluateJavascript
                if (ready == "true") onReady()
                else AppLog.w(LogTag.PAGE, "скрипт не готов: контракт не подтверждён")
            }
        }
        if (!current()) return
        webView.evaluateJavascript(presentCheck) { result ->
            if (!current()) return@evaluateJavascript
            if (result == "true") onReady()
            else webView.evaluateJavascript(source) { verify() }
        }
    }
}
