package com.example.vkweb.media

import com.example.vkweb.config.SafeUrls
import org.json.JSONObject

/**
 * Трек для кэша или скачивания: адрес потока (MP3, HLS или DASH — приложение сохраняет MP3) и теги. [ok] = false —
 * страница адреса не нашла; [reason] — почему ([REASON_BUSY]: идёт другое скачивание через плеер страницы).
 */
data class MusicDownload(
    val id: String,
    val url: String,
    val title: String,
    val artist: String,
    val album: String = "",
    /** Обложка для тегов (по возможности самая большая). */
    val coverUrl: String = "",
    val ok: Boolean = true,
    val reason: String = "",
    val pageUrl: String = MusicRecovery.DEFAULT_PAGE,
    val streamExpiresAt: Long = 0L,
    val refreshAttempt: Int = 0,
    val cacheGeneration: Long? = null,
    val cancellationGeneration: Long? = null,
    val manualRequest: Boolean? = null,
    val recoveryToken: Long = 0L,
) {
    companion object {
        const val TYPE = "musicDownload"
        const val REASON_BUSY = "busy"

        /** Трек из списка экрана музыки. */
        fun of(track: MusicTrack, url: String = track.currentStreamUrl(), pageUrl: String = MusicRecovery.DEFAULT_PAGE) =
            MusicDownload(track.id, url, track.title, track.artist, track.album, track.tagCoverUrl,
                pageUrl = MusicRecovery.page(pageUrl), streamExpiresAt = if (url == track.streamUrl) track.streamExpiresAt
                else StreamLifetime.limit(Long.MAX_VALUE, System.currentTimeMillis()))

        fun fromJson(json: JSONObject): MusicDownload? {
            val id = json.optString("id").takeIf { com.example.vkweb.config.VkIds.isMediaId(it) } ?: return null
            val ok = json.optBoolean("ok", true)
            val url = SafeUrls.audio(json.optString("url"))
            if (ok && url.isEmpty()) return null
            return MusicDownload(
                id = id,
                url = url,
                title = json.optString("title").take(300),
                artist = json.optString("artist").take(300),
                album = json.optString("album").take(300),
                coverUrl = SafeUrls.image(json.optString("coverLarge")).ifEmpty { SafeUrls.image(json.optString("cover")) },
                ok = ok,
                reason = json.optString("reason").take(20),
                pageUrl = MusicRecovery.page(json.optString("pageUrl")),
                streamExpiresAt = StreamLifetime.limit(Long.MAX_VALUE, System.currentTimeMillis()),
            )
        }
    }
}
