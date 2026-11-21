package com.example.vkweb.media

import com.example.vkweb.config.VkIds
import org.json.JSONArray
import org.json.JSONObject

/** Плейлист из вкладки «Плейлисты». [id] — «владелец_номер»; [url] — адрес страницы плейлиста на сайте (путь и запрос). */
data class PlaylistEntry(
    val id: String,
    val title: String,
    val subtitle: String,
    val coverUrl: String,
    /** Число треков; 0 — неизвестно. */
    val count: Int,
    val url: String,
) {
    fun toJson(): JSONObject = JSONObject()
        .put("id", id).put("title", title).put("subtitle", subtitle)
        .put("cover", coverUrl).put("count", count).put("url", url)
}

/**
 * Плейлисты, которые нашла страница (сообщение type = "playlists"). [none] — страница искала и не нашла:
 * экран скажет об этом, а не будет вечно показывать индикатор. [complete] — страница плейлистов целиком,
 * без «Показать ещё»: только тогда показанное можно сократить до присланного.
 */
data class PlaylistsPayload(
    val items: List<PlaylistEntry>,
    val hasMore: Boolean = false,
    val complete: Boolean = false,
    val none: Boolean = false,
) {
    companion object {
        const val TYPE = "playlists"
        private const val MAX_PLAYLISTS = 500
        private const val MAX_TEXT = 200

        fun fromJson(json: JSONObject): PlaylistsPayload = PlaylistsPayload(
            items = parseItems(json.optJSONArray("items")),
            hasMore = json.optBoolean("hasMore"),
            complete = json.optBoolean("complete"),
            none = json.optBoolean("none"),
        )

        fun parseItems(array: JSONArray?): List<PlaylistEntry> {
            if (array == null) return emptyList()
            val result = ArrayList<PlaylistEntry>()
            val seen = HashSet<String>()
            for (i in 0 until minOf(array.length(), MAX_PLAYLISTS)) {
                val o = array.optJSONObject(i) ?: continue
                val id = o.optString("id")
                if (!VkIds.isMediaId(id) || !seen.add(id)) continue
                // Адрес — только путь на сайте: чужие адреса и «javascript:» сюда не попадают.
                val url = o.optString("url").takeIf { it.startsWith("/") && !it.startsWith("//") }.orEmpty().take(500)
                result += PlaylistEntry(
                    id = id,
                    title = o.optString("title").trim().take(MAX_TEXT),
                    subtitle = o.optString("subtitle").trim().take(MAX_TEXT),
                    coverUrl = com.example.vkweb.config.SafeUrls.https(o.optString("cover")),
                    count = o.optInt("count").coerceIn(0, 1_000_000),
                    url = url,
                )
            }
            return result
        }
    }
}

/** Что показывает экран музыки: свои треки или плейлисты. */
enum class MusicSection { MUSIC, PLAYLISTS }

/**
 * Куда идут треки, присланные страницей. Без зависимостей от Android: проверяется обычными тестами.
 *
 * Страница музыки одна, а в приложении два списка: «Мои треки» и треки открытого плейлиста. Страница сообщает, что
 * сейчас на ней (`page`): главная («my»), плейлист («playlist») или другая страница музыки («other»), и свой адрес
 * (`key`), по которому узнаётся, какой именно плейлист.
 */
object MusicRouting {

    enum class Target {
        /** «Мои треки». */
        MY,
        /** Треки открытого плейлиста. */
        OPENED,
        /** Плейлист открылся на странице сам (не из списка приложения): открыть его и показать. */
        OPEN_NEW,
        /** Результаты поиска (4.79): экран сейчас в поиске, и это тот же запрос. */
        SEARCH,
        /** Показывать нечего (рекомендации, поиск, которого экран не ждёт; плейлист, пока человек в «Музыке»). */
        IGNORE,
    }

    private val PLAYLIST_IN_KEY = Regex("""[?&]act=audio_playlist(-?\d+_\d+)""")

    /** Номер плейлиста из адреса страницы («…act=audio_playlist7_1&…» → «7_1»). */
    fun playlistIdOf(key: String): String? = PLAYLIST_IN_KEY.find(key)?.groupValues?.get(1)

    /**
     * [searching] — запрос, результаты которого ждёт экран (null — экран не в поиске), [query] — запрос страницы поиска.
     * Страница с другим запросом — остаток прежнего поиска: в список не попадает.
     */
    fun target(
        page: String, section: MusicSection, openedId: String?, key: String,
        searching: String? = null, query: String = "",
    ): Target = when {
        page == "search" && searching != null ->
            if (query.isEmpty() || MusicSearch.sameQuery(query, searching)) Target.SEARCH else Target.IGNORE
        else -> targetOfList(if (page == "search") "other" else page, section, openedId, key)
    }

    private fun targetOfList(page: String, section: MusicSection, openedId: String?, key: String): Target = when (page) {
        "my" -> Target.MY
        "playlist" -> {
            val id = playlistIdOf(key)
            when {
                // Страница сменила плейлист сама (id другой): не подмешиваем его треки в открытый.
                openedId != null && (id == null || id == openedId) -> Target.OPENED
                section == MusicSection.PLAYLISTS -> Target.OPEN_NEW
                else -> Target.IGNORE
            }
        }
        else -> if (openedId != null && section == MusicSection.PLAYLISTS) Target.OPENED else Target.IGNORE
    }
}
