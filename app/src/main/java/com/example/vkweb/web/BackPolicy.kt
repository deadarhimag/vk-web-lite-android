package com.example.vkweb.web

import com.example.vkweb.clips.ClipPaths

/**
 * Куда ведёт «Назад» на главном экране. Без зависимостей от Android: проверяется обычными тестами.
 *
 * История страницы VK — это её внутренние переходы (она одностраничная и сама добавляет записи, перенаправляет),
 * а не то, что делал человек. Поэтому история страницы для «Назад» не используется вовсе (4.31):
 * - с главной страницы раздела («Друзья», «Клипы», каталог VK Видео…) «Назад» закрывает раздел;
 * - с клипа — в раздел «Клипы», с видео — в раздел «Видео», с сообщества, человека, записи — на главную страницу
 *   раздела, из которого их открыли. Следующее «Назад» оттуда — закрытие, а не шаг дальше по истории.
 * До 4.31 «Назад» из клипа вело на последнюю страницу «не проигрывателя» — ею могла оказаться страница из другого
 * раздела (отчёт 4.29: из клипа — на сообщество, из следующего клипа — в VK Видео, и так по кругу).
 */
object BackPolicy {

    /** Страница — главная страница раздела: её путь совпадает с путём раздела, а не глубже. */
    fun isSectionRoot(path: String?, sectionPaths: Set<String>): Boolean {
        val clean = path.orEmpty().substringBefore('?').trimEnd('/')
        return clean.isEmpty() || clean in sectionPaths
    }

    /** Страница проигрывателя: клип, лента клипов, отдельное видео (на VK или VK Видео). «Назад» закрывает её. */
    fun isPlayerPage(path: String?): Boolean = ClipPaths.isFeed(path) || isClip(path) || isVideo(path)

    /** Отдельный клип (и под вкладкой ленты — [ClipPaths]) или «шорт». */
    private fun isClip(path: String?): Boolean = ClipPaths.isClip(path) || SHORTS.matches(path.orEmpty().substringBefore('?'))

    /** Отдельное видео: /video-1_2. */
    private fun isVideo(path: String?): Boolean = VIDEO.matches(path.orEmpty().substringBefore('?'))

    /** Что делает «Назад» (4.31). */
    sealed interface Step {
        /** Закрыть раздел: главная страница раздела (или свой экран раздела) уже на экране. */
        data object Close : Step

        /** На главную страницу раздела [url]. */
        data class ToSection(val url: String) : Step
    }

    /**
     * «Назад» с [path] страницы на [host].
     * [sectionScreenShown] — показан свой экран раздела (список клипов, каталог видео, музыка, друзья, сообщества):
     * это и есть главная раздела, даже если адрес страницы под ним глубже (под списком клипов — страница клипа).
     * [clipsRoot] — раздел «Клипы» (null — клипы выключены в меню), [videoRoot] — раздел «Видео»,
     * [lastSectionRoot] — главная страница раздела, где человек был последним, [inVideoSection] — человек в разделе
     * «Видео» (клип оттуда — обратно в «Видео»).
     */
    fun decide(
        host: String?,
        path: String?,
        sectionPaths: Set<String>,
        sectionScreenShown: Boolean,
        clipsRoot: String?,
        videoRoot: String,
        lastSectionRoot: String?,
        inVideoSection: Boolean = false,
    ): Step {
        if (sectionScreenShown) return Step.Close
        val p = path.orEmpty().substringBefore('?')
        // Клип, открытый в разделе «Видео» (из VK Видео или страницей видео сам) — обратно в «Видео», а не в «Клипы»:
        // иначе «Назад» гоняло между разделами (4.38).
        val clip = isClip(p)
        if (clip && inVideoSection) return Step.ToSection(videoRoot)
        if (clip) return (clipsRoot ?: lastSectionRoot)?.let(Step::ToSection) ?: Step.Close
        if (isVideo(p)) return Step.ToSection(videoRoot)
        if (SiteDomains.isVideoSite(host)) {
            // На VK Видео весь сайт — раздел «Видео»: главная и подборки — его главные страницы, канал и плейлист — нет.
            return if (SiteDomains.isVideoCatalog(p)) Step.Close else Step.ToSection(videoRoot)
        }
        if (isSectionRoot(p, sectionPaths) || ClipPaths.isFeed(p)) return Step.Close
        return lastSectionRoot?.let(Step::ToSection) ?: Step.Close
    }

    private val SHORTS = Regex("^/shorts?/.*$")
    private val VIDEO = Regex("^/video-?\\d+_\\d+.*$")
}
