package com.example.vkweb.web

import android.webkit.WebView

/**
 * Усыпление WebView в фоне. pauseTimers() глобален для процесса и останавливает JS,
 * поэтому вызывается только когда ничего не играет.
 */
class WebLifecycle(private val webView: WebView) {

    private var paused = false

    fun pause() {
        if (paused) return
        webView.onPause()
        webView.pauseTimers()
        paused = true
    }

    /**
     * Включить обратно в любом случае. Таймеры общие для процесса: их мог остановить другой экран, и тогда
     * собственный флаг [paused] ничего не знает — без этого окно осталось бы замороженным.
     */
    fun forceResume() {
        webView.resumeTimers()
        webView.onResume()
        paused = false
    }

    fun resume() {
        if (!paused) return
        webView.resumeTimers()
        webView.onResume()
        paused = false
    }
}
