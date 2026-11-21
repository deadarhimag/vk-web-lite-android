package com.example.vkweb.web

import android.webkit.WebView
import androidx.webkit.WebViewCompat
import androidx.webkit.WebViewFeature
import com.example.vkweb.config.AppConfig

/**
 * Приём сообщений страницы через addWebMessageListener (4.67, код ревью 4.64): общее у главного окна ([PageBridge]) и
 * окон чтения сообщений (messages/ReaderChannel.kt). Объект моста виден только страницам доверенных сайтов
 * ([AppConfig.SCRIPT_ORIGIN_RULES]) и не виден рекламным iframe: принимаются только сообщения главного фрейма. Разбор —
 * не в главном потоке, а в потоке [inbox].
 */
object BridgeListener {

    /** @return false — WebView слишком старый, мост не установлен. */
    fun install(webView: WebView, name: String, inbox: MessageInbox, receive: (String) -> Unit): Boolean {
        if (!WebViewFeature.isFeatureSupported(WebViewFeature.WEB_MESSAGE_LISTENER)) return false
        WebViewCompat.addWebMessageListener(webView, name, AppConfig.SCRIPT_ORIGIN_RULES) { _, message, _, isMainFrame, _ ->
            val raw = message.data
            if (isMainFrame && !raw.isNullOrEmpty()) inbox.post { receive(raw) }
        }
        return true
    }
}
