package com.example.vkweb.owner

/**
 * Какие адреса — страницы людей и сообществ. Без зависимостей от Android (проверяется тестами).
 *  - /id1, /club1, /public1, /event1 — точно страница владельца, id известен сразу;
 *  - /durov, /animenation1 — короткое имя: может быть и разделом сайта, поэтому свой экран
 *    показывается, только когда страница прислала данные владельца.
 * У сообществ id отрицательный, как в API VK.
 */
object OwnerPath {

    private val EXPLICIT = Regex("^/(id|club|public|event)(\\d{1,12})/?$")
    private val SCREEN_NAME = Regex("^/([A-Za-z0-9_.]{2,64})/?$")

    /** Разделы и служебные страницы VK: само слово или слово с продолжением (wall1_2, photo1_2, albums1). */
    private val RESERVED = Regex(
        "^(id|club|public|event|wall|photo|photos|video|videos|audio|audios|album|albums|doc|docs|topic|board|" +
            "page|pages|note|notes|app|apps|clip|clips|story|stories|poll|market|product|gifts?|tag|podcasts?|" +
            "playlist|artist|music|search|feed|news|im|mail|friends|groups|communities|settings|edit|login|join|" +
            "restore|support|help|terms|privacy|blog|dev|ads|menu|services|games|discover|discover_search|" +
            "bookmarks|fave|vkpay|donut|notifications|feedback|away|al_\\w*|\\w*\\.php|stats|wiki|reg|verify|" +
            "about|jobs|legal|security|data_protection|catalog|shorts|live|channels?|call|calls|dating|" +
            "mini_apps|apps_catalog|links|checkout|pay|coupons?|bugs|rules|press|cookies)(\\d|_|-|\\.|$)",
        RegexOption.IGNORE_CASE,
    )

    /**
     * Лента новостей (3.77) показывается тем же своим экраном, что стена: у неё нет владельца — условный номер
     * [FEED_ID] (как в скрипте страницы, 70_owner.js), шапки нет.
     */
    const val FEED_ID = 1_000_000_000_000_000L

    fun isFeed(path: String?): Boolean = path != null && FEED.matches(path)

    private val FEED = Regex("^/feed/?$")

    /** id владельца по адресу (/id1 → 1, /club1 → −1) или null. */
    fun ownerIdOf(path: String?): Long? {
        val m = EXPLICIT.matchEntire(path ?: return null) ?: return null
        val id = m.groupValues[2].toLongOrNull()?.takeIf { it > 0 } ?: return null
        return if (m.groupValues[1].equals("id", ignoreCase = true)) id else -id
    }

    /** Может быть страницей человека или сообщества (короткое имя, не раздел сайта). */
    fun isCandidate(path: String?): Boolean {
        if (path == null) return false
        if (ownerIdOf(path) != null) return true
        val m = SCREEN_NAME.matchEntire(path) ?: return false
        return !RESERVED.containsMatchIn(m.groupValues[1])
    }

    /** Ключ страницы: путь без завершающего «/», как его считает скрипт страницы. */
    fun keyOf(path: String?): String = path.orEmpty().trimEnd('/').ifEmpty { "/" }

    fun sameKey(a: String?, b: String?): Boolean = keyOf(a).equals(keyOf(b), ignoreCase = true)
}
