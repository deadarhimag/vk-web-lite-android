package com.example.vkweb.config

/**
 * Разделы сайта VK сверх основных ([Tab]). Раздел с [menuPath] — пункт бокового меню: по умолчанию включён, выключается
 * в «Пунктах бокового меню» ([MenuItems]); выключенный закрыт и для переходов. Раздел без [menuPath] закрыт всегда.
 *
 * [patterns] — пути (без query), которые относятся к разделу. Названия для экрана — в ui/SectionLabels.kt.
 */
enum class HiddenSection(val menuPath: String?, vararg patterns: String) {
    FEED("/feed", "^/$", "^/(feed|news)(/.*)?$"),
    STORIES(null, "^/stories(/.*)?$"),
    DISCOVER("/discover", "^/(discover|discover_search)(/.*)?$"),
    FAVE("/bookmarks", "^/(fave|bookmarks)(/.*)?$"),
    MARKET("/market", "^/market(/.*)?$"),
    APPS("/services", "^/(apps|services)(/.*)?$", "^/app\\d+.*$"),
    GAMES("/games", "^/games(/.*)?$"),
    PAYMENTS(null, "^/(vkpay|donut)(/.*)?$"),
    VK_MENU("/menu", "^/menu(/.*)?$"),
    COMMUNITIES_CATALOG(null, "^/communities(/.*)?$"),
    AD_SETTINGS(null, "^/settings/ads.*$");

    val regexes: List<Regex> = patterns.map(::Regex)

    fun matches(path: String): Boolean = regexes.any { it.matches(path) }

    companion object {
        /** Закрыто всё — значение [com.example.vkweb.web.UrlPolicy], пока настройки не применены. */
        val DEFAULT: Set<HiddenSection> = entries.toSet()
    }
}
