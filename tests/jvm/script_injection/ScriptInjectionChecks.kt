import android.webkit.WebView
import com.example.vkweb.web.ScriptInjection

fun main() {
    var tests = 0
    fun test(name: String, run: () -> Unit) { run(); tests++; println("PASS $name") }
    test("existing verified script becomes ready without reinjection") {
        val view = WebView(); var ready = 0
        ScriptInjection.ensure(view, view.url, "source", "check") { ready++ }
        view.reply("true"); check(ready == 1 && view.scripts == listOf("check"))
    }
    test("injection waits for verification before reporting readiness") {
        val view = WebView(); var ready = 0
        ScriptInjection.ensure(view, view.url, "source", "check") { ready++ }
        view.reply("false"); check(ready == 0 && view.scripts.last() == "source")
        view.reply("null"); check(ready == 0 && view.scripts.last() == "check")
        view.reply("true"); check(ready == 1)
    }
    test("failed or incompatible script never reports readiness") {
        val view = WebView(); var ready = 0
        ScriptInjection.ensure(view, view.url, "source", "check") { ready++ }
        view.reply("false"); view.reply("null"); view.reply("false"); check(ready == 0)
    }
    test("navigation during preflight prevents late injection") {
        val view = WebView(); var ready = 0
        ScriptInjection.ensure(view, view.url, "source", "check") { ready++ }
        view.url = "https://m.vk.ru/mail"; view.reply("false")
        check(ready == 0 && view.scripts.size == 1)
    }
    test("newer readiness request supersedes the previous callbacks") {
        val view = WebView(); var old = 0; var current = 0
        ScriptInjection.ensure(view, view.url, "old", "check") { old++ }
        ScriptInjection.ensure(view, view.url, "new", "check") { current++ }
        view.reply("false"); view.reply("true"); check(old == 0 && current == 1 && view.scripts.size == 2)
    }
    test("navigation after injection discards stale ready callback") {
        val view = WebView(); var ready = 0
        ScriptInjection.ensure(view, view.url, "source", "check") { ready++ }
        view.reply("false"); view.url = "https://m.vk.ru/mail"; view.reply("null")
        check(ready == 0 && view.scripts.size == 2)
    }
    test("untrusted supplied URL is rejected") {
        val view = WebView(); ScriptInjection.ensure(view, "https://evil.example/", "source", "check")
        check(view.scripts.isEmpty())
    }
    test("current WebView origin is checked independently of supplied URL") {
        val view = WebView("https://evil.example/")
        ScriptInjection.ensure(view, "https://m.vk.ru/audio", "source", "check")
        check(view.scripts.isEmpty())
    }
    println("PASS $tests script injection lifecycle checks")
}
