package com.example.vkweb.web

import com.example.vkweb.config.SafeUrls
import java.net.URI
import java.net.URISyntaxException

/**
 * Адрес для перехода «силами страницы» (как нажатие ссылки): путь и запрос на том же сайте. Пока играет музыка, любая
 * полная загрузка уничтожает документ вместе с плеером, а переход самой страницы (VK — одностраничное приложение)
 * плеер не трогает. Без зависимостей от Android: проверяется обычными тестами.
 *
 * Адрес на другом домене относительным не делается: ссылка на него в странице открыла бы чужой сайт, а не переход внутри VK.
 */
object InPageNavigation {

    /** Путь с запросом (без фрагмента) или null, если адрес чужой или не разбирается. */
    fun relative(url: String, isTrusted: (scheme: String?, host: String?) -> Boolean): String? {
        if (url.startsWith("/") && !url.startsWith("//")) return url.substringBefore('#')
        return try {
            val uri = URI(url)
            if (!isTrusted(uri.scheme, uri.host)) return null
            val path = uri.rawPath?.takeIf { it.isNotEmpty() } ?: "/"
            path + (uri.rawQuery?.let { "?$it" } ?: "")
        } catch (e: URISyntaxException) {
            null
        }
    }

    /**
     * Можно ли перейти силами открытой страницы: тот же сайт — VK (vk.com, vk.ru и их m.) или VK Видео (vkvideo.ru).
     * Отчёт 3.73: с открытого m.vkvideo.ru «Музыка» и «Лента» превращались в m.vkvideo.ru/audio и /feed — сайт видео
     * уводил на свою главную, свой экран музыки оставался пустым. Относительный [url] — на сайте открытой страницы.
     */
    fun sameSite(currentHost: String?, url: String): Boolean {
        if (url.startsWith("/") && !url.startsWith("//")) return true
        val target = SafeUrls.hostOf(url) ?: return false
        val a = siteOf(currentHost) ?: return false
        return a == siteOf(target)
    }

    private fun siteOf(host: String?): String? {
        val h = host?.lowercase()?.trimEnd('.') ?: return null
        return when {
            h == "vkvideo.ru" || h.endsWith(".vkvideo.ru") -> "vkvideo"
            h == "vk.com" || h.endsWith(".vk.com") || h == "vk.ru" || h.endsWith(".vk.ru") -> "vk"
            else -> null
        }
    }

    /** Только путь (без запроса): по нему сравнивается с тем, что уже открыто. */
    fun pathOf(relative: String): String = relative.substringBefore('?')
}
