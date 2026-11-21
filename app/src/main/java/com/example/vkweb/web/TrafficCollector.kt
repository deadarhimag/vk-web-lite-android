package com.example.vkweb.web

import android.webkit.WebView
import com.example.vkweb.diag.TrafficSummary

/**
 * Забрать у страницы сводку сетевого трафика за период и записать в журнал ([TrafficSummary.write]).
 * Страница считает каждый запрос в общем модуле перехвата (assets/shared/net_tap.js); здесь — только вопрос и ответ,
 * одинаковые для главного окна, окна чтения сообщений и фоновой проверки. Ответ приходит в колбэке, поэтому окно
 * можно закрывать сразу после него ([onDone]).
 */
object TrafficCollector {

    private const val SCRIPT =
        "(function(){var t=window.__vkNetTap&&window.__vkNetTap.trafficMessage();return t?JSON.stringify(t):''})()"

    fun collect(webView: WebView, onDone: () -> Unit = {}) {
        webView.evaluateJavascript(SCRIPT) { result ->
            TrafficSummary.fromEvaluateResult(result)?.let(TrafficSummary::write)
            onDone()
        }
    }
}
