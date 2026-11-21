package com.example.vkweb.web

import android.annotation.SuppressLint
import android.content.pm.ApplicationInfo
import android.util.TypedValue
import android.webkit.CookieManager
import android.webkit.WebSettings
import android.webkit.WebView
import androidx.webkit.WebSettingsCompat
import androidx.webkit.WebViewFeature

/**
 * Базовые настройки WebView: безопасность, cookies, воспроизведение медиа, «всегда видимая» страница ([PageVisibility]),
 * скрытие сведений об устройстве.
 */
object WebViewSetup {

    /**
     * @param hideDeviceInfo не выдавать VK сведения об устройстве: обезличенный User-Agent, без заголовка
     * X-Requested-With (имя приложения) и с подменой того, по чему собирают отпечаток ([PrivacyShield]).
     */
    @SuppressLint("SetJavaScriptEnabled")
    fun configure(webView: WebView, hideDeviceInfo: Boolean = true) {
        with(webView.settings) {
            javaScriptEnabled = true
            domStorageEnabled = true
            allowFileAccess = false
            allowContentAccess = false
            mixedContentMode = WebSettings.MIXED_CONTENT_NEVER_ALLOW
            // Иначе play() из уведомления блокируется как «без жеста пользователя».
            mediaPlaybackRequiresUserGesture = false
            // Без маркера "wv" VK отдаёт обычную мобильную версию, а не заглушку «откройте приложение».
            userAgentString = if (hideDeviceInfo) UserAgent.generic(userAgentString) else UserAgent.withoutWebViewMarker(userAgentString)
            // Местоположение приложению не нужно, а сайту его знать незачем.
            setGeolocationEnabled(false)
        }
        // Первым из скриптов начала документа: страница не должна успеть увидеть настоящую видимость окна.
        PageVisibility.install(webView)
        if (hideDeviceInfo) hideDevice(webView)
        applyTheme(webView)
        // В debug-сборке страницу можно смотреть через chrome://inspect (консоль, ошибки загрузки).
        if (webView.context.applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE != 0) {
            WebView.setWebContentsDebuggingEnabled(true)
        }
        if (WebViewFeature.isFeatureSupported(WebViewFeature.SAFE_BROWSING_ENABLE)) {
            WebSettingsCompat.setSafeBrowsingEnabled(webView.settings, true)
        }
        CookieManager.getInstance().apply {
            setAcceptCookie(true)
            // Нужны для входа через id.vk.com.
            setAcceptThirdPartyCookies(webView, true)
        }
    }

    // В androidx.webkit 1.11 константа помечена @RestrictTo, хотя это публичная функция WebView; вызов защищён проверкой.
    @SuppressLint("RestrictedApi")
    private fun hideDevice(webView: WebView) {
        // WebView добавляет к запросам X-Requested-With с именем пакета приложения: пустой список источников его
        // отключает.
        if (WebViewFeature.isFeatureSupported(WebViewFeature.REQUESTED_WITH_HEADER_ALLOW_LIST)) {
            WebSettingsCompat.setRequestedWithHeaderOriginAllowList(webView.settings, emptySet())
        }
        PrivacyShield.install(webView)
    }

    /**
     * Тёмная тема страниц. С targetSdk 33+ WebView сам сообщает сайту prefers-color-scheme
     * по теме Activity; если сайт тёмной схемы не поддерживает, страница затемняется алгоритмически
     * (только когда тема приложения тёмная).
     */
    private fun applyTheme(webView: WebView) {
        if (WebViewFeature.isFeatureSupported(WebViewFeature.ALGORITHMIC_DARKENING)) {
            WebSettingsCompat.setAlgorithmicDarkeningAllowed(webView.settings, true)
        }
        // Фон под цвет темы: без него при загрузке мигает белый экран.
        val background = TypedValue()
        if (webView.context.theme.resolveAttribute(android.R.attr.colorBackground, background, true)) {
            webView.setBackgroundColor(background.data)
        }
    }
}
