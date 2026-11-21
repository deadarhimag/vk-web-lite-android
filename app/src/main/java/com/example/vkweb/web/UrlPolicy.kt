package com.example.vkweb.web

import com.example.vkweb.config.AppConfig
import com.example.vkweb.config.HiddenSection

/** Что делать с переходом по ссылке. */
enum class Navigation {
    /** Открыть внутри приложения. */
    ALLOW,

    /** Раздел скрыт: отправить на домашнюю страницу. */
    REDIRECT_HOME,

    /** Ссылка на VK по http: открыть ту же страницу по https. */
    UPGRADE_HTTPS,

    /** Чужой сайт или схема: передать другому приложению. */
    OPEN_EXTERNAL,

    /** Опасная или неизвестная схема (javascript:, file: …): ничего не делать. */
    IGNORE,
}

/**
 * Правила навигации. Без зависимостей от Android: принимает уже разобранные части URL,
 * поэтому покрыта unit-тестами (UrlPolicyTest).
 */
class UrlPolicy(
    private val appDomains: Set<String> = AppConfig.VK_DOMAINS + AppConfig.AUTH_DOMAINS,
    private val mainSiteHosts: Set<String> = AppConfig.MAIN_SITE_HOSTS,
    private val videoSiteHosts: Set<String> = AppConfig.VIDEO_SITE_HOSTS,
    private val clipSections: List<Regex> = AppConfig.CLIP_SECTIONS,
    private val adDomains: Set<String> = AppConfig.AD_DOMAINS,
    private val trackerDomains: Set<String> = AppConfig.TRACKER_DOMAINS,
    private val trackerPathsOnVk: List<Regex> = AppConfig.TRACKER_PATHS_ON_VK,
    private val adPathsOnVk: List<Regex> = AppConfig.AD_PATHS_ON_VK,
    private val vkDomains: Set<String> = AppConfig.VK_DOMAINS,
    private val externalSchemes: Set<String> = AppConfig.EXTERNAL_SCHEMES,
    private val scriptDomains: Set<String> = AppConfig.SCRIPT_DOMAINS,
) {

    /** Разделы, которые сейчас скрыты (настройка «Содержимое» → «Скрытые разделы»). По умолчанию — все. */
    var hiddenSections: Set<HiddenSection> = HiddenSection.DEFAULT

    /** Скрывать короткие видео — клипы (выключенный пункт «Клипы» в меню). По умолчанию включено. */
    var hideClips: Boolean = true

    /**
     * Клип открыт по ссылке (из переписки, ленты, группы): пока человек смотрит его, страницы клипов не закрыты, даже
     * если раздел «Клипы» выключен. Иначе сам просмотрщик VK, перейдя на /clips, был бы отправлен на главную страницу.
     */
    var clipSession: Boolean = false

    /** Блокировать рекламные сети (настройка «Конфиденциальность»). По умолчанию включено. */
    var blockAds: Boolean = true

    /** Блокировать счётчики и аналитику (настройка «Конфиденциальность»). По умолчанию включено. */
    var blockTrackers: Boolean = true

    fun decide(scheme: String?, host: String?, path: String?): Navigation {
        val s = scheme?.lowercase() ?: return Navigation.IGNORE
        return when {
            s == "about" -> Navigation.ALLOW
            s == "https" && isAppHost(host) ->
                if (isBlockedSection(host, path)) Navigation.REDIRECT_HOME else Navigation.ALLOW
            s == "http" && isAppHost(host) -> Navigation.UPGRADE_HTTPS
            s in externalSchemes -> Navigation.OPEN_EXTERNAL
            else -> Navigation.IGNORE
        }
    }

    /**
     * Хост, страницы которого показываются в окне приложения: сайт VK, VK Видео, короткие ссылки и вход (OK, Mail).
     * Серверы файлов (userapi.com, vkuser.net, vk-cdn.net, vkuserphoto.ru) — только для картинок и скачивания.
     */
    fun isPageHost(host: String?): Boolean =
        matchesDomain(host, scriptDomains + setOf("vk.me") + AppConfig.AUTH_DOMAINS)

    fun isBlockedSection(host: String?, path: String?): Boolean {
        if (hiddenSections.isEmpty() && !hideClips) return false
        val h = normalizeHost(host) ?: return false
        val p = path.orEmpty().ifEmpty { "/" }
        val isClip = hideClips && !clipSession && clipSections.any { it.matches(p) }
        return when (h) {
            in mainSiteHosts -> isClip || hiddenSections.any { it.matches(p) }
            in videoSiteHosts -> isClip
            else -> false
        }
    }

    /** Запрос страницы (картинка, скрипт, beacon, iframe…), который не нужно загружать. */
    fun isBlockedResource(host: String?, path: String? = null, pagePath: String? = null): Boolean = when {
        blockAds && matchesDomain(host, adDomains) -> true
        blockAds && matchesDomain(host, vkDomains) && adPathsOnVk.any { it.matches(path.orEmpty()) } -> true
        blockTrackers && matchesDomain(host, trackerDomains) -> true
        blockTrackers && matchesDomain(host, vkDomains) ->
            trackerPathsOnVk.any { it.matches(path.orEmpty()) } && !isViewStatsAllowed(path, pagePath)
        else -> false
    }

    /**
     * События просмотра на страницах людей и сообществ (и записи) — не блокируются: по ним VK считает записи
     * просмотренными и уменьшает счётчик «новых записей» сообщества. Блокировка statEvents сводила на нет и обычный
     * просмотр, и вывод увиденных записей на страницу (3.58; дамп 3.63: на страницах сообществ страница шлёт только эти
     * события и open_group). На остальных страницах (музыка, лента…) статистика по-прежнему блокируется.
     */
    fun isViewStatsAllowed(path: String?, pagePath: String?): Boolean {
        val p = path.orEmpty()
        if (pagePath == null) return false
        // 3.70: и stats.trackEvents — им страница сообщества шлёт open_group (отчёт 3.69: «заблокирован запрос
        // stats.trackEvents страница=/club#»). Раньше этот запрос получал подставной «успех», и в дампе казалось, что
        // отметка открытия доходит до VK, — а счётчик новых записей сообщества не сбрасывался.
        if (!p.startsWith("/method/statEvents.") && !p.startsWith("/method/stats.trackEvents")) return false
        return com.example.vkweb.owner.OwnerPath.isCandidate(pagePath) || WALL_POST.matches(pagePath) ||
            GROUPS_LIST.matches(pagePath)
    }

    /** Страница VK Видео (для подсветки раздела «Видео» в меню). */
    fun isVideoSite(host: String?): Boolean = SiteDomains.isVideoSite(host, videoSiteHosts)

    fun isAppHost(host: String?): Boolean = matchesDomain(host, appDomains)

    /** Origin, которому доверяем: встраиваем скрипт, выдаём микрофон/камеру, отправляем cookie при загрузке. */
    fun isTrustedOrigin(scheme: String?, host: String?): Boolean =
        scheme.equals("https", ignoreCase = true) && matchesDomain(host, scriptDomains)

    companion object {
        private val WALL_POST = Regex("^/wall-?\\d+_\\d+/?$")
        /** Список «Мои сообщества»: нажатие сообщества в нём VK отмечает stats.trackEvents (с track_code). */
        private val GROUPS_LIST = Regex("^/groups/?$")
        /** true, если host совпадает с доменом или является его поддоменом ("evilvk.com" ≠ "vk.com"). */
        fun matchesDomain(host: String?, domains: Set<String>): Boolean {
            val h = normalizeHost(host) ?: return false
            return domains.any { h == it || h.endsWith(".$it") }
        }

        /**
         * Адрес целиком — на доверенном сайте ([AppConfig.SCRIPT_DOMAINS], только https): туда встраивается свой скрипт
         * ([ScriptInjection]). Битый адрес или null — нет.
         */
        fun isTrustedUrl(url: String?, domains: Set<String> = AppConfig.SCRIPT_DOMAINS): Boolean {
            val uri = try {
                java.net.URI(url ?: return false)
            } catch (e: Exception) {
                return false
            }
            return uri.scheme.equals("https", ignoreCase = true) && matchesDomain(uri.host, domains)
        }

        /** Хост адреса для журнала; битый — null. */
        fun hostOf(url: String?): String? = try {
            url?.let { java.net.URI(it).host }
        } catch (e: Exception) {
            null
        }

        private fun normalizeHost(host: String?): String? =
            host?.lowercase()?.trimEnd('.')?.takeIf { it.isNotEmpty() }
    }
}
