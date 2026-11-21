package com.example.vkweb.messages

import android.annotation.SuppressLint
import android.content.Context
import android.webkit.WebView
import androidx.webkit.WebViewCompat
import androidx.webkit.WebViewFeature
import com.example.vkweb.config.AppConfig
import com.example.vkweb.config.ReaderConfig
import com.example.vkweb.web.AssetText
import com.example.vkweb.web.NetTapScript
import org.json.JSONObject

/** Встраивает assets/vk_reader.js в страницы сообщений. */
class ReaderScript(context: Context) {

    private val source: String by lazy { build(context.applicationContext) }

    @SuppressLint("RestrictedApi")
    fun install(webView: WebView) {
        if (WebViewFeature.isFeatureSupported(WebViewFeature.DOCUMENT_START_SCRIPT)) {
            WebViewCompat.addDocumentStartJavaScript(webView, source, AppConfig.SCRIPT_ORIGIN_RULES)
        }
    }

    /**
     * Запасной путь после загрузки страницы ([com.example.vkweb.web.ScriptInjection]): только на сайте VK (4.65 — раньше
     * без проверки сайта), без повторной передачи уже вставшего скрипта. [onReady] — когда скрипт точно на странице.
     */
    fun inject(webView: WebView, url: String?, onReady: () -> Unit = {}) =
        com.example.vkweb.web.ScriptInjection.ensure(webView, url, source, PRESENT_CHECK, onReady)

    private fun build(context: Context): String {
        val config = JSONObject()
            .put("bridge", ReaderChannel.BRIDGE_NAME)
            .put("loginMarker", ReaderConfig.LOGIN_MARKER)
            // Дамп разработчика и из окна чтения (3.68) — по той же настройке, что у главного окна.
            .put("collectDump", com.example.vkweb.settings.AppSettings(context).collectDump)
            .put("dialogs", JSONObject()
                .put("row", ReaderConfig.Dialogs.ROW)
                .put("link", ReaderConfig.Dialogs.LINK)
                .put("title", ReaderConfig.Dialogs.TITLE)
                .put("snippet", ReaderConfig.Dialogs.SNIPPET)
                .put("time", ReaderConfig.Dialogs.TIME)
                .put("unread", ReaderConfig.Dialogs.UNREAD)
                .put("online", ReaderConfig.Dialogs.ONLINE))
            .put("history", JSONObject()
                .put("row", ReaderConfig.History.ROW)
                .put("text", ReaderConfig.History.TEXT)
                .put("outMarker", ReaderConfig.History.OUT_MARKER)
                .put("time", ReaderConfig.History.TIME)
                .put("title", ReaderConfig.History.TITLE))
            .put("compose", JSONObject()
                .put("input", ReaderConfig.Compose.INPUT)
                .put("send", ReaderConfig.Compose.SEND))
        val template = AssetText.read(context, ASSET)
        check(PLACEHOLDER in template) { "В $ASSET нет плейсхолдера $PLACEHOLDER" }
        // Общий перехват ответов (fetch, XHR, WebSocket) — первым: читатель только подписывается на него.
        return NetTapScript.source(context) + "\n" + template.replace(PLACEHOLDER, config.toString())
    }

    companion object {
        const val ASSET = "vk_reader.js"
        private const val PLACEHOLDER = "/*CONFIG*/null"
        private const val PRESENT_CHECK = "(function(){if(!window.__vkReader||window.__vkReader.protocol!==1||typeof window.__vkReader.contract!=='function'||typeof window.__vkReader.scrape!=='function')return false;try{var c=window.__vkReader.contract();return !!(c&&c.protocol===1&&c.engine===1&&c.kind==='reader'&&typeof c.documentId==='string'&&c.documentId)}catch(e){return false}})()"
    }
}
