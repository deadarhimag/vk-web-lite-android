package com.example.vkweb.web

import com.example.vkweb.config.AppConfig

/**
 * Ссылки VK из других приложений (3.94): браузер, мессенджер, почта открывают vk.com/… здесь. Принимается только
 * сайт VK ([AppConfig.MAIN_SITE_HOSTS]) по https/http, и только путь с параметрами — он открывается на нашем
 * [AppConfig.SITE_ORIGIN]. Без зависимостей от Android: проверяется обычными тестами.
 */
object VkLinks {

    /** Путь с параметрами («/id1?w=wall1_2») или null, если ссылка не на сайт VK. */
    fun sitePath(scheme: String?, host: String?, encodedPath: String?, encodedQuery: String?): String? {
        if (scheme?.lowercase() !in setOf("https", "http")) return null
        if (host?.lowercase() !in AppConfig.MAIN_SITE_HOSTS) return null
        val path = encodedPath.orEmpty().ifEmpty { "/" }
        // Только обычный путь сайта: без «//» (это был бы другой хост) и без обратных косых.
        if (!path.startsWith("/") || path.startsWith("//") || '\\' in path) return null
        val query = encodedQuery?.takeIf { it.isNotEmpty() }?.let { "?$it" }.orEmpty()
        return path + query
    }

    /** Полный адрес на сайте приложения или null. */
    fun siteUrl(scheme: String?, host: String?, encodedPath: String?, encodedQuery: String?): String? =
        sitePath(scheme, host, encodedPath, encodedQuery)?.let { AppConfig.SITE_ORIGIN + it }
}
