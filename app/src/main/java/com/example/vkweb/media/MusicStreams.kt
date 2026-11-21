package com.example.vkweb.media

import com.example.vkweb.config.SafeUrls
import com.example.vkweb.config.VkIds
import org.json.JSONObject

/**
 * Потоки треков для «Кэшировать весь список» (сообщение «musicStreams», 4.97): адрес для каждого найденного трека и
 * номера, для которых страница потока не нашла. Только серверы звука VK ([SafeUrls.audio]).
 */
data class MusicStreams(
    val urls: Map<String, String>,
    val missing: List<String>,
    /** Ответ очереди «открыть в плеере страницы» (4.99.5), а не порции audio.getById. */
    val byPlay: Boolean = false,
    val operation: String = "",
) {
    companion object {
        const val TYPE = "musicStreams"
        /** Сколько треков страница разбирает за один раз (`STREAMS_BATCH` в 50_music.js). */
        const val BATCH = 50

        fun fromJson(json: JSONObject): MusicStreams {
            val urls = LinkedHashMap<String, String>()
            val missing = ArrayList<String>()
            json.optJSONArray("items")?.let { items ->
                for (i in 0 until minOf(items.length(), BATCH)) {
                    val o = items.optJSONObject(i) ?: continue
                    val id = o.optString("id").takeIf(VkIds::isMediaId) ?: continue
                    val url = SafeUrls.audio(o.optString("url"))
                    if (url.isEmpty()) missing += id else urls[id] = url
                }
            }
            json.optJSONArray("missing")?.let { list ->
                for (i in 0 until minOf(list.length(), BATCH)) list.optString(i).takeIf(VkIds::isMediaId)?.let { missing += it }
            }
            return MusicStreams(urls, missing.distinct(), json.optBoolean("byPlay", false), json.optString("operation").take(80))
        }
    }
}
