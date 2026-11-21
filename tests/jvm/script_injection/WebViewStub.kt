package android.webkit

/** Controlled asynchronous callback boundary; not an Android WebView emulator. */
class WebView(var url: String? = "https://m.vk.ru/audio") {
    val scripts = ArrayList<String>()
    private val callbacks = ArrayList<((String?) -> Unit)?>()
    fun evaluateJavascript(script: String, callback: ((String?) -> Unit)?) {
        scripts += script; callbacks += callback
    }
    fun reply(value: String?) { callbacks.removeAt(0)?.invoke(value) }
}
