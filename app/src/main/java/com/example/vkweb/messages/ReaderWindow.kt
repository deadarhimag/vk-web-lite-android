package com.example.vkweb.messages

import android.webkit.WebView
import com.example.vkweb.settings.AppSettings
import com.example.vkweb.web.WebViewSetup

/**
 * Окно, которое читает мессенджер VK (4.66, код ревью 4.64): одна настройка для экрана сообщений и фоновой работы
 * (sync/HeadlessSync.kt). Раньше она была написана в обоих местах — общие настройки WebView, скрипт чтения, сбор данных
 * для разработки, канал связи и правила переходов — и могла разойтись.
 */
object ReaderWindow {

    /**
     * Настроить [view] для чтения через [channel]. [dumpLabel] — подпись записей сбора данных для разработки (собираются,
     * только если он включён). Остальное — как у [ReaderPageClient].
     * @return скрипт чтения окна (для повторного встраивания и команд).
     */
    fun setUp(
        view: WebView,
        channel: ReaderChannel,
        settings: AppSettings,
        dumpLabel: String,
        isActive: () -> Boolean,
        onStarted: (String?) -> Unit = {},
        onFinished: (String?) -> Unit = {},
        onRendererGone: () -> Unit,
    ): ReaderScript {
        WebViewSetup.configure(view, settings.hideDeviceInfo)
        val script = ReaderScript(view.context)
        script.install(view)
        if (settings.collectDump) {
            val dump = com.example.vkweb.dev.DumpRecorder.get(view.context)
            channel.onDump = { dump.appendRaw(it, dumpLabel) }
        }
        channel.install(view)
        view.webViewClient = ReaderPageClient(
            script, channel, ReaderPageClient.policyFor(settings), isActive = isActive,
            onStarted = onStarted, onFinished = onFinished,
            onRendererGone = onRendererGone,
        )
        return script
    }
}
