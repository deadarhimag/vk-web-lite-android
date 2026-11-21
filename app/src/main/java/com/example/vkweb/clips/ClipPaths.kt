package com.example.vkweb.clips

/**
 * Адреса раздела «Клипы» на сайте VK (4.39) — в одном месте. Без зависимостей от Android: проверяется тестами.
 *
 * Отчёт 4.38: вкладки ленты клипов — не только /clips. «Тренды» — /clips_trends, «Подписки» — /clips/subscriptions, и
 * ролик открывается под ними: /clips_trends/clip-1_2, /clips/subscriptions/clip-1_2. Раньше разделом считался только
 * /clips, а клипом — только /clip-1_2: на «Трендах» и «Подписках» свой экран раздела уходил, под ним открывалась сама
 * страница VK со своим проигрывателем («другой интерфейс»), её ролики начинали играть, «Назад» вело не туда, и касание
 * этой страницы открывало свой проигрыватель («самопроизвольные запуски»).
 */
object ClipPaths {

    /** Лента раздела и её вкладки: /clips, /clips/subscriptions, /clips_trends. */
    fun isFeed(path: String?): Boolean = FEED.matches(clean(path))

    /** Отдельный клип — сам по себе или под вкладкой ленты: /clip-1_2, /clips_trends/clip-1_2. */
    fun isClip(path: String?): Boolean = CLIP.matches(clean(path))

    /** Номер клипа из адреса («-1_2»); null — не клип. */
    fun clipId(path: String?): String? = CLIP.matchEntire(clean(path))?.groupValues?.get(2)

    private fun clean(path: String?) = path.orEmpty().substringBefore('?').substringBefore('#')

    private const val FEED_PART = "clips(?:/[a-z_]+)?|clips_[a-z_]+"
    private val FEED = Regex("^/(?:$FEED_PART)/?$", RegexOption.IGNORE_CASE)
    private val CLIP = Regex("^(/(?:$FEED_PART))?/clip(-?\\d+_\\d+)(?:[/?#].*)?$", RegexOption.IGNORE_CASE)
}
