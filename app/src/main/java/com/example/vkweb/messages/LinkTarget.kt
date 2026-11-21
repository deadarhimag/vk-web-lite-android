package com.example.vkweb.messages

import com.example.vkweb.web.InPageNavigation

/**
 * Куда ведёт нажатие в переписке: на ссылку в тексте, на карточку вложения, на видео. Решение ([Target]) — чистая функция
 * без Android, проверяется обычными тестами; запуск ([MessageLinks]) — тонкая оболочка. Раньше это жило в экране переписки,
 * который перерос тысячу строк.
 */
object LinkTarget {

    sealed interface Target {
        /** Страница сайта VK (путь с запросом): открывается в главном окне, где вход уже выполнен. */
        data class Site(val path: String) : Target

        /** Внешний адрес: браузер или другое приложение. */
        data class External(val url: String) : Target

        /** Музыка из сообщения: включить трек [trackId] (пусто — только поиск) на странице музыки; [title] — для поиска. */
        data class Track(val trackId: String, val title: String) : Target
    }

    /** Ссылка из текста или вложения: страницы VK по https — в главном окне, остальное — наружу. */
    fun forUrl(url: String, isTrusted: (scheme: String?, host: String?) -> Boolean): Target {
        if (url.startsWith("/") && !url.startsWith("//")) return Target.Site(url.substringBefore('#'))
        val relative = InPageNavigation.relative(url) { scheme, host -> scheme == "https" && isTrusted(scheme, host) }
        return if (relative != null) Target.Site(relative) else Target.External(url)
    }

    /** Карточка вложения: страница сайта, включение музыки (адреса звука нет) или внешний адрес. */
    fun forCard(card: ChatCard, isTrusted: (scheme: String?, host: String?) -> Boolean): Target = when {
        card.path.isNotEmpty() -> Target.Site(card.path)
        card.kind == "audio" -> Target.Track(card.trackId, card.title)
        else -> forUrl(card.url, isTrusted)
    }
}
