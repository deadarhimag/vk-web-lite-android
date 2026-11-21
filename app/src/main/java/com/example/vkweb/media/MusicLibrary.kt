package com.example.vkweb.media

import com.example.vkweb.config.VkIds
import org.json.JSONObject

/** Трек из «Моих треков». [id] — «владелец_id», как его ждёт плеер страницы. */
data class MusicTrack(
    val id: String,
    val title: String,
    val artist: String,
    val durationSec: Int,
    val coverUrl: String,
    /** Трек уже у человека в музыке (4.45): у такого нет кнопки «добавить к себе». Нет сведений — считается добавленным. */
    val added: Boolean = true,
    /**
     * Адрес звука (4.88): есть у найденного поиском — его страница получает ответом API, а не вёрсткой, на которую можно
     * нажать. Такой трек играет приложение. Только серверы звука VK ([com.example.vkweb.config.SafeUrls.audio]).
     */
    val streamUrl: String = "",
    /** Альбом, если VK прислал его вместе с треком. */
    val album: String = "",
    /** Самая большая обложка (4.95): для тегов скачанного файла; в списке — [coverUrl] поменьше. */
    val coverLargeUrl: String = "",
    /** Expiry of the transient network URL; never applies to local audio files. */
    val streamExpiresAt: Long = 0L,
) {
    fun currentStreamUrl(now: Long = System.currentTimeMillis()): String =
        if (StreamLifetime.isFresh(streamExpiresAt, now)) streamUrl else ""

    /** Обложка для тегов файла: большая, иначе та, что в списке. */
    val tagCoverUrl: String get() = coverLargeUrl.ifEmpty { coverUrl }

    /** «3:52» или «1:02:05». */
    val durationText: String get() = com.example.vkweb.ui.PlayerFormat.timeSec(durationSec.toLong())
}

/**
 * Треки открытой страницы музыки (сообщение type = "music").
 * [isMyTracks] — это «Мои треки» (главная страница музыки), а не плейлист или альбом:
 * для работы без сети сохраняются только они.
 */
data class MusicPayload(
    val tracks: List<MusicTrack>,
    val hasMore: Boolean,
    val isMyTracks: Boolean = true,
    /** Какой это список (страница музыки): обновлять на месте можно только тот же список. */
    val listKey: String = MY_TRACKS_KEY,
    /**
     * Страница прислала список целиком (сервер явно ответил, что продолжения нет). Только тогда показанное можно
     * сократить до присланного; иначе свежая порция встаёт сверху, а сохранённое остаётся ниже.
     */
    val complete: Boolean = false,
    /** Какая это страница: «my» (главная), «playlist» (плейлист), «search» (поиск, 4.79) или «other» (рекомендации). */
    val page: String = "my",
    /** Запрос страницы поиска (4.79); пусто — это не поиск. */
    val query: String = "",
    /** Название страницы (для плейлиста, открытого не из списка приложения). */
    val title: String = "",
    /** Открытый плейлист: чей он и добавлен ли к себе (4.45); null — это не страница плейлиста. */
    val playlist: OpenedPlaylist? = null,
) {
    companion object {
        /** Тип сообщения страницы (поле type). */
        const val TYPE = "music"
        const val MY_TRACKS_KEY = "/audio"
        private const val MAX_TRACKS = 5000
        private const val MAX_TEXT = 300

        fun fromJson(json: JSONObject): MusicPayload {
            val array = json.optJSONArray("items")
            val tracks = ArrayList<MusicTrack>()
            if (array != null) {
                for (i in 0 until minOf(array.length(), MAX_TRACKS)) {
                    val o = array.optJSONObject(i) ?: continue
                    val id = o.optString("id")
                    if (!VkIds.isMediaId(id)) continue
                    val cover = com.example.vkweb.config.SafeUrls.https(o.optString("cover"))
                    tracks += MusicTrack(
                        id = id,
                        title = o.optString("title").trim().take(MAX_TEXT),
                        artist = o.optString("artist").trim().take(MAX_TEXT),
                        durationSec = o.optInt("duration").coerceIn(0, 24 * 3600),
                        coverUrl = cover,
                        added = o.optBoolean("added", true),
                        streamUrl = com.example.vkweb.config.SafeUrls.audio(o.optString("url")),
                        streamExpiresAt = StreamLifetime.limit(o.optLong("streamExpiresAt"), System.currentTimeMillis()),
                        album = o.optString("album").trim().take(MAX_TEXT),
                        coverLargeUrl = com.example.vkweb.config.SafeUrls.https(o.optString("coverLarge")),
                    )
                }
            }
            return MusicPayload(
                tracks = tracks,
                hasMore = json.optBoolean("hasMore"),
                isMyTracks = json.optString("kind", "my") == "my",
                listKey = json.optString("key").ifEmpty { MY_TRACKS_KEY }.take(500),
                // Нет поля (старый скрипт, сохранённое) — не полный: ничего не выбрасываем.
                complete = json.optBoolean("complete", false),
                // Нет поля (старый скрипт, сохранённое): по виду списка.
                page = json.optString("page").ifEmpty { if (json.optString("kind", "my") == "my") "my" else "other" },
                title = json.optString("title").trim().take(MAX_TEXT),
                playlist = json.optJSONObject("playlist")?.let(OpenedPlaylist::fromJson),
                query = MusicSearch.clean(json.optString("query")),
            )
        }
    }
}

/**
 * Открытый плейлист (4.45): [own] — свой (null — страница не знает своего номера), [followed] — уже добавлен к себе.
 * Кнопка «Добавить к себе» — только у чужого и не добавленного ([canFollow]).
 */
data class OpenedPlaylist(val id: String, val own: Boolean?, val followed: Boolean) {
    val canFollow: Boolean get() = own == false && !followed

    companion object {
        fun fromJson(json: JSONObject): OpenedPlaylist? {
            val id = json.optString("id")
            if (!VkIds.isMediaId(id)) return null
            val own = if (json.isNull("own") || !json.has("own")) null else json.optBoolean("own")
            return OpenedPlaylist(id, own, json.optBoolean("followed"))
        }
    }
}

/** Итог «добавить к себе» (сообщение «musicAction», 4.45): [action] — «add» (трек) или «follow» (плейлист). */
data class MusicAction(val action: String, val id: String, val ok: Boolean, val reason: String) {
    companion object {
        const val TYPE = "musicAction"
        const val ADD = "add"
        const val FOLLOW = "follow"

        fun fromJson(json: JSONObject): MusicAction? {
            val action = json.optString("action").takeIf { it == ADD || it == FOLLOW } ?: return null
            val id = json.optString("id")
            if (!VkIds.isMediaId(id)) return null
            return MusicAction(action, id, json.optBoolean("ok"), json.optString("reason").take(200))
        }
    }
}
