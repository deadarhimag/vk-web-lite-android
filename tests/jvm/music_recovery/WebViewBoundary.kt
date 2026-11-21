package android.webkit

class WebView(var parent: Any? = null) {
    var destroyed = false
    fun destroy() { destroyed = true }
}
class RenderProcessGoneDetail { fun didCrash() = true }
