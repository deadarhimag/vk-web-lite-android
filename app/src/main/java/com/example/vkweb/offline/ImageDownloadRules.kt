package com.example.vkweb.offline

import com.example.vkweb.config.AppConfig

/**
 * Правила загрузки картинок (4.65, код ревью 4.64). Без зависимостей от Android: проверяются тестами.
 *
 *  - Перенаправления — только на https-серверы картинок VK ([AppConfig.isImageHost]) и не больше [MAX_REDIRECTS]: раньше
 *    HttpURLConnection шёл за ними сам, и список серверов проверялся лишь у исходного адреса.
 *  - Файл — не больше [MAX_BYTES]: раньше ответ любого размера сначала целиком ложился на диск.
 */
object ImageDownloadRules {
    const val MAX_REDIRECTS = 3
    const val MAX_BYTES = 25L * 1024 * 1024

    fun isRedirect(code: Int): Boolean = code == 301 || code == 302 || code == 303 || code == 307 || code == 308

    /** Куда идти по [location] со страницы [base]; null — адрес чужой, не https или битый. */
    fun redirectTarget(base: String, location: String?): String? {
        if (location.isNullOrBlank()) return null
        val target = try {
            java.net.URI(base).resolve(location.trim())
        } catch (e: Exception) {
            return null
        }
        if (!target.scheme.equals("https", ignoreCase = true)) return null
        if (!AppConfig.isImageHost(target.host)) return null
        return target.toString()
    }

    /** Заявленный размер ответа уже больше предела (-1 — не заявлен). */
    fun tooLarge(contentLength: Long): Boolean = contentLength > MAX_BYTES
}
