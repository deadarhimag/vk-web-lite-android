package com.example.vkweb.web

import android.annotation.SuppressLint
import android.content.Context
import android.net.Uri
import android.webkit.WebView
import androidx.webkit.ScriptHandler
import androidx.webkit.WebViewCompat
import androidx.webkit.WebViewFeature
import com.example.vkweb.config.AppConfig
import org.json.JSONArray
import org.json.JSONObject

/**
 * Встраивает скрипт assets/vk_lite/ (со стилями из assets/vk_lite.css) в страницы VK.
 * Скрипт хранится частями по ответственности; здесь они склеиваются по порядку имён в один.
 * Основной путь — скрипт на старте документа; запасной — после загрузки страницы.
 * Скрытие рекламы и клипов можно переключать на лету: [setOptions].
 */
class PageScripts(context: Context, private val policy: UrlPolicy) {

    /** Настройки, от которых зависит скрипт страницы. Новая настройка — поле здесь и в [buildSource]. */
    data class Options(
        val hideAds: Boolean = true,
        val hideClips: Boolean = true,
        /** Собирать обезличенные ответы страниц для разработки (assets/vk_lite/80_dump.js). */
        val collectDump: Boolean = false,
        /** Окно кэша музыки (media/MusicCacheWorker.kt, 4.99.7): звук страницы всегда выключен. */
        val cacheWorker: Boolean = false,
    )

    private val appContext = context.applicationContext
    private val template: String by lazy { readScriptParts() }
    private val adsCss: String by lazy { readAsset(CSS_ASSET) }
    private val layoutCss: String by lazy { readAsset(LAYOUT_CSS_ASSET) }
    private val clipsCss: String by lazy { readAsset(CLIPS_CSS_ASSET) }

    private var options = Options()
    private val adsHidden: Boolean get() = options.hideAds
    private val clipsHidden: Boolean get() = options.hideClips
    private var source: String = ""
    private var handler: ScriptHandler? = null

    fun install(webView: WebView, options: Options) {
        this.options = options
        source = buildSource()
        registerDocumentStartScript(webView)
    }

    /** Применяет настройки к будущим страницам и к уже открытой (без перезагрузки). */
    fun setOptions(webView: WebView, options: Options) {
        if (options == this.options) return
        this.options = options
        source = buildSource()
        registerDocumentStartScript(webView)
        call(webView, "setCss(${JSONObject.quote(currentCss())})")
        call(webView, "setHides(${JSONArray(currentPromoHides())})")
        call(webView, "setAdData($adsHidden)")
        call(webView, "setClips($clipsHidden)")
        call(webView, "setDump(${options.collectDump})")
    }

    private fun call(webView: WebView, expression: String) {
        val method = expression.substringBefore('(')
        webView.evaluateJavascript("window.__vkLite && window.__vkLite.protocol===1 && typeof window.__vkLite.$method==='function' && window.__vkLite.$expression", null)
    }

    /**
     * Запасной путь: скрипт на старте документа мог не встать (старый WebView, страница загрузилась до установки).
     * Раньше весь скрипт (больше 100 КБ вместе со стилями) заново передавался и разбирался при каждой загрузке;
     * теперь сначала короткая проверка — есть ли он уже на странице, и целиком он передаётся, только если его нет.
     */
    fun onPageFinished(webView: WebView, url: String?) {
        // Чужие страницы молча пропускаются, как раньше: журнал о них — только у окон чтения (там их быть не должно).
        val uri = url?.let(Uri::parse) ?: return
        if (!policy.isTrustedOrigin(uri.scheme, uri.host)) return
        ScriptInjection.ensure(webView, url, source, PRESENT_CHECK)
    }

    // В части версий androidx.webkit метод помечен @RestrictTo; вызов защищён проверкой фичи.
    @SuppressLint("RestrictedApi")
    private fun registerDocumentStartScript(webView: WebView) {
        if (!WebViewFeature.isFeatureSupported(WebViewFeature.DOCUMENT_START_SCRIPT)) return
        handler?.remove()
        handler = WebViewCompat.addDocumentStartJavaScript(webView, source, AppConfig.SCRIPT_ORIGIN_RULES)
    }

    private fun currentPromoHides(): List<String> = if (adsHidden) AppConfig.PROMO_HIDE_TEXTS else emptyList()

    private fun currentCss() = buildString {
        append(layoutCss)
        if (adsHidden) append('\n').append(adsCss)
        if (clipsHidden) append('\n').append(clipsCss)
    }

    private fun buildSource(): String {
        val config = JSONObject()
            .put("css", currentCss())
            .put("nextSelector", AppConfig.NEXT_TRACK_SELECTOR)
            .put("prevSelector", AppConfig.PREV_TRACK_SELECTOR)
            .put("bridge", AppConfig.JS_BRIDGE_NAME)
            .put("promoClicks", JSONArray(AppConfig.PROMO_CLICK_TEXTS))
            .put("promoHides", JSONArray(currentPromoHides()))
            .put("promoIgnoreSelector", AppConfig.PROMO_IGNORE_SELECTOR)
            .put("blockAdData", adsHidden)
            .put("blockClips", clipsHidden)
            .put("collectDump", options.collectDump)
            .put("cacheWorker", options.cacheWorker)
        check(PLACEHOLDER in template) { "В $JS_DIR нет плейсхолдера $PLACEHOLDER" }
        return template.replace(PLACEHOLDER, config.toString())
    }

    /** Части скрипта по порядку имён (00_core.js … 90_api.js). */
    private fun readScriptParts(): String {
        val parts = scriptParts(appContext)
        check(parts.isNotEmpty()) { "Нет частей скрипта в assets/$JS_DIR" }
        // Общий перехват ответов — первым: части подписываются на него, а хуки он ставит позже, после скрипта рекламы.
        return NetTapScript.source(appContext) + "\n" + parts.joinToString("\n") { readAsset(it) }
    }

    private fun readAsset(name: String): String = AssetText.read(appContext, name)

    companion object {
        private const val JS_DIR = "vk_lite"
        private const val CSS_ASSET = "vk_lite.css"
        private const val LAYOUT_CSS_ASSET = "vk_layout.css"
        private const val CLIPS_CSS_ASSET = "vk_clips.css"
        private const val PLACEHOLDER = "/*CONFIG*/null"

        /** Скрипт уже на странице: восстановить стили (VK мог их убрать) и ничего больше не делать. */
        private const val PRESENT_CHECK =
            "(function(){if(!window.__vkLite||window.__vkLite.protocol!==1||typeof window.__vkLite.contract!=='function'||typeof window.__vkLite.ensureCss!=='function')return false;try{var c=window.__vkLite.contract();if(!c||c.protocol!==1||c.engine!==1||c.kind!=='lite'||typeof c.documentId!=='string'||!c.documentId)return false;window.__vkLite.ensureCss();return true}catch(e){return false}})()"

        /** Части скрипта по порядку имён, полными путями в assets. */
        private fun scriptParts(context: Context): List<String> =
            AssetText.list(context, JS_DIR).filter { it.endsWith(".js") }.sorted().map { "$JS_DIR/$it" }

        /** Всё, что понадобится окну при создании: читается заранее в фоне (см. [AssetText.prewarm]). */
        fun assetsToPrewarm(context: Context): List<String> =
            scriptParts(context) + listOf(NetTapScript.COMPAT_ASSET, NetTapScript.ASSET, NetTapScript.DUMP_ASSET, CSS_ASSET, LAYOUT_CSS_ASSET, CLIPS_CSS_ASSET)
    }
}
