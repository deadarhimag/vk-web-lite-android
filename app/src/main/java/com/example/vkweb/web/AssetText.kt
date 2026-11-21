package com.example.vkweb.web

import android.content.Context
import java.util.concurrent.ConcurrentHashMap

/**
 * Тексты из assets (скрипты и стили страниц), прочитанные один раз на процесс.
 *
 * Скрипты нужны уже при создании окна (onCreate), то есть в главном потоке. Чтение сотни килобайт из APK там —
 * лишние миллисекунды до первого кадра, поэтому [prewarm] читает их заранее в фоновом потоке при запуске процесса.
 * Если окно создаётся раньше, чем прогрев закончился, текст дочитывается на месте (результат тот же).
 */
object AssetText {

    private val cache = ConcurrentHashMap<String, String>()

    /** Текст файла assets/[name]. */
    fun read(context: Context, name: String): String =
        cache[name] ?: context.applicationContext.assets.open(name).bufferedReader(Charsets.UTF_8)
            .use { it.readText() }
            .also { cache.putIfAbsent(name, it) }

    /** Имена файлов в папке assets/[dir]. */
    fun list(context: Context, dir: String): List<String> =
        context.applicationContext.assets.list(dir).orEmpty().toList()

    /** Прочитать заранее в фоновом потоке (вызывается при запуске процесса). Ошибки не важны: прочтётся при надобности. */
    fun prewarm(context: Context, names: (Context) -> List<String>) {
        val app = context.applicationContext
        Thread({
            runCatching { names(app) }.getOrDefault(emptyList()).forEach { name -> runCatching { read(app, name) } }
        }, "asset-prewarm").apply {
            isDaemon = true
            priority = Thread.NORM_PRIORITY - 1
        }.start()
    }
}
