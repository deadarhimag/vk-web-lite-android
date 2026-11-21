package com.example.vkweb.messages

import com.example.vkweb.config.SafeUrls

/**
 * Страница входа VK (4.0): после выхода из аккаунта мессенджер уводит на вход VK ID (id.vk.ru/auth), где данных
 * сообщений нет. Раньше это распознавалось только по сроку ожидания — 10 секунд и «Не удалось прочитать сообщения»;
 * теперь сразу — «Нужно войти». Без зависимостей от Android: проверяется обычными тестами.
 */
object LoginPages {

    private val HOSTS = setOf("id.vk.ru", "id.vk.com", "login.vk.ru", "login.vk.com", "oauth.vk.ru", "oauth.vk.com")
    private val PATHS = listOf("/auth", "/login", "/join")

    /**
     * Страница VK, но не вход (4.19): после входа VK уводит на m.vk.com/… — значит, вход выполнен, и показывать саму
     * страницу VK больше незачем.
     */
    fun isAfterLogin(url: String?): Boolean {
        if (url.isNullOrEmpty() || isLogin(url)) return false
        val host = SafeUrls.hostOf(url)?.lowercase() ?: return false
        return host == "vk.com" || host == "vk.ru" || host.endsWith(".vk.com") || host.endsWith(".vk.ru")
    }

    fun isLogin(url: String?): Boolean {
        if (url.isNullOrEmpty()) return false
        val uri = try {
            java.net.URI(url)
        } catch (e: java.net.URISyntaxException) {
            return false
        }
        val host = uri.host?.lowercase() ?: return false
        if (host in HOSTS) return true
        if (!host.endsWith("vk.ru") && !host.endsWith("vk.com")) return false
        val path = uri.path.orEmpty().lowercase()
        return PATHS.any { path == it || path.startsWith("$it/") || path.startsWith("$it.") }
    }
}
