package com.example.vkweb.web

import android.annotation.SuppressLint
import android.webkit.WebView
import androidx.webkit.WebViewCompat
import androidx.webkit.WebViewFeature
import com.example.vkweb.config.AppConfig
import com.example.vkweb.diag.AppLog
import com.example.vkweb.diag.LogTag

/**
 * Скрипт из assets, который выполняется на страницах VK до их собственных скриптов. Общая установка для
 * [PageVisibility] и [PrivacyShield]: на старом WebView такой скрипт запустился бы слишком поздно и не помог бы,
 * поэтому тогда он не ставится, а в журнал пишется [unsupportedWarning].
 */
object DocumentStartScript {

    @SuppressLint("RestrictedApi")
    fun install(webView: WebView, asset: String, unsupportedWarning: String) {
        // Вызов — внутри проверки (lint RequiresFeature узнаёт только такую форму, не ранний выход).
        if (WebViewFeature.isFeatureSupported(WebViewFeature.DOCUMENT_START_SCRIPT)) {
            WebViewCompat.addDocumentStartJavaScript(webView, AssetText.read(webView.context, asset), AppConfig.SCRIPT_ORIGIN_RULES)
        } else {
            AppLog.w(LogTag.PAGE, unsupportedWarning)
        }
    }
}
