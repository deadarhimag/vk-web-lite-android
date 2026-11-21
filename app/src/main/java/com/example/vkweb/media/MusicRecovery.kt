package com.example.vkweb.media

import java.net.URI

/** Bounded recovery of a read/download operation; never retries a user write action. */
object MusicRecovery {
    const val DEFAULT_PAGE = "https://m.vk.ru/audio"
    class Expired : IllegalStateException("Audio URL expired in queue")
    class HttpFailure(val status: Int, val retryAfter: String? = null) : IllegalStateException("Media request failed: $status")
    fun shouldRefresh(error: Throwable, attempt: Int): Boolean = attempt == 0 &&
        (error is Expired || error is HttpFailure && error.status in setOf(401, 403, 404, 410))
    fun page(value: String): String = runCatching {
        val uri = URI(value)
        val host = uri.host.orEmpty().lowercase()
        if (value.length <= 4000 && uri.scheme == "https" && uri.rawUserInfo == null && uri.rawFragment == null &&
            (host == "vk.ru" || host.endsWith(".vk.ru") || host == "vk.com" || host.endsWith(".vk.com"))) value
        else DEFAULT_PAGE
    }.getOrDefault(DEFAULT_PAGE)
    fun isCurrent(cache: Long, cancellation: Long, currentCache: Long, currentCancellation: Long): Boolean =
        cache == currentCache && cancellation == currentCancellation
}
