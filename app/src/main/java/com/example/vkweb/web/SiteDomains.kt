package com.example.vkweb.web

import com.example.vkweb.clips.ClipPaths
import com.example.vkweb.config.AppConfig

/**
 * На каком сайте живёт раздел (4.31). VK Видео — отдельный сайт (m.vkvideo.ru), остальное — основной (m.vk.com).
 * С основного на видео VK переводит сам (m.vk.com/video перенаправляет на m.vkvideo.ru), а обратно — нет: на
 * m.vkvideo.ru пути /friends, /club1, /clip-1_2 не существуют, сайт видео уводит на свою главную, и свой экран раздела
 * не запускается. Поэтому путь основного сайта, открытый на сайте видео, переводится на основной сайт — так же, как
 * VK переводит в обратную сторону. Без зависимостей от Android: проверяется обычными тестами.
 */
object SiteDomains {

    /** Хост сайта VK Видео ([AppConfig.VIDEO_SITE_HOSTS]; одно правило и для [UrlPolicy.isVideoSite]). */
    fun isVideoSite(host: String?, hosts: Set<String> = AppConfig.VIDEO_SITE_HOSTS): Boolean =
        host?.lowercase()?.trimEnd('.')?.let { it in hosts } == true

    /**
     * Путь основного сайта VK: разделы меню (кроме видео), сообщения, люди и сообщества по номеру, записи, фото,
     * клипы и лента клипов. Видео (/video-1_2), каналы (/@name), плейлисты, подборки, поиск и настройки (у VK Видео
     * свои /search и /settings) — пути сайта видео.
     */
    fun isMainSitePath(path: String?): Boolean =
        MAIN.matches(path.orEmpty().substringBefore('?')) || ClipPaths.isFeed(path) || ClipPaths.isClip(path)

    /** Главная или подборка каталога VK Видео: «/», «/trends», «/politics/media» (как VideoOverlay и 48_vkvideo.js). */
    fun isVideoCatalog(path: String?): Boolean {
        val p = path.orEmpty().substringBefore('?')
        return p.isEmpty() || (CATALOG.matches(p) && !NOT_CATALOG.matches(p))
    }

    /** Адрес на основном сайте для пути основного сайта, открытого на сайте видео; null — переводить не нужно. */
    fun mainSiteUrl(host: String?, path: String?, query: String?): String? {
        if (!isVideoSite(host) || !isMainSitePath(path)) return null
        return AppConfig.SITE_ORIGIN + path + (query?.takeIf { it.isNotEmpty() }?.let { "?$it" } ?: "")
    }

    private val MAIN = Regex(
        "^/(feed|friends|groups|mail|im|audio|audios-?\\d+|music(/.*)?|" +
            "id\\d+|club\\d+|public\\d+|event\\d+|wall-?\\d+(_\\d+)?|photo-?\\d+_\\d+|album-?\\d+_\\d+|" +
            "fave|bookmarks|market(/.*)?|apps|games|services|menu|discover|recommendations)/?$",
        RegexOption.IGNORE_CASE,
    )
    /** Главная и подборки: одно-два слова в пути или ничего (то же правило — в 48_vkvideo.js). */
    private val CATALOG = Regex("^/([A-Za-z_]{2,40}(/[A-Za-z_]{2,40})?/?)?$")
    private val NOT_CATALOG = Regex("^/(video|clip|clips|playlist|playlists|live|search|settings|audio)(/.*)?$", RegexOption.IGNORE_CASE)
}
