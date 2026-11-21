package com.example.vkweb.media

import com.example.vkweb.config.SafeUrls
import android.content.Intent
import com.example.vkweb.config.AppConfig
import org.json.JSONException
import org.json.JSONObject

/** Команда плееру на странице. [js] — имя команды в assets/vk_lite/00_core.js. */
enum class PlayerCommand(val js: String) {
    PLAY("play"),
    PAUSE("pause"),
    NEXT("next"),
    PREVIOUS("prev"),
}

/** Что сейчас играет на странице. */
data class MediaState(
    val playing: Boolean,
    val title: String,
    val artist: String,
    /** Трек доиграл до конца и следом ничего не началось (в отличие от паузы). */
    val ended: Boolean = false,
    /** Позиция на момент сообщения и длина трека, секунды; 0 — неизвестно. */
    val position: Double = 0.0,
    val duration: Double = 0.0,
    /** Обложка трека (https; пусто — нет): на экране блокировки, в шторке, на часах и в машине (4.0). */
    val cover: String = "",
    /** Играет музыка или голосовое из переписки: нажатие на уведомление открывает переписку, а не главное окно. */
    val fromChat: Boolean = false,
    /** Играющий трек «владелец_номер» (4.46; пусто — неизвестен или это не музыка страницы). */
    val trackId: String = "",
    /** Трек уже у человека в музыке; null — неизвестно (кнопки «добавить» нет). */
    val trackAdded: Boolean? = null,
    /** URL текущего аудиопотока; используется только для сохранения в локальный кэш. */
    val audioUrl: String = "",
) {
    /** В плеере можно предложить «добавить к себе»: трек известен и его нет в музыке человека. */
    val canAddTrack: Boolean get() = trackId.isNotEmpty() && trackAdded == false

    fun writeTo(intent: Intent): Intent = intent
        .putExtra(EXTRA_PLAYING, playing)
        .putExtra(EXTRA_TITLE, title)
        .putExtra(EXTRA_ARTIST, artist)
        .putExtra(EXTRA_ENDED, ended)
        .putExtra(EXTRA_POSITION, position)
        .putExtra(EXTRA_DURATION, duration)
        .putExtra(EXTRA_COVER, cover)
        .putExtra(EXTRA_FROM_CHAT, fromChat)

    companion object {
        /** Тип сообщения страницы (поле type). */
        const val TYPE = "state"

        /** Секунды из сообщения: не число или отрицательное — «неизвестно». */
        private fun seconds(value: Double): Double = if (value.isFinite() && value > 0) value else 0.0
        val IDLE = MediaState(playing = false, title = "", artist = "")

        private const val EXTRA_PLAYING = "media.playing"
        private const val EXTRA_TITLE = "media.title"
        private const val EXTRA_ARTIST = "media.artist"
        private const val EXTRA_ENDED = "media.ended"
        private const val EXTRA_POSITION = "media.position"
        private const val EXTRA_DURATION = "media.duration"
        private const val EXTRA_COVER = "media.cover"
        private const val EXTRA_FROM_CHAT = "media.from_chat"

        /** Адрес обложки: только https и разумной длины (данные со страницы чужие). */
        fun coverOrEmpty(value: String?): String =
            SafeUrls.https(value, MAX_COVER_URL)

        private const val MAX_COVER_URL = 2000

        fun readFrom(intent: Intent) = MediaState(
            playing = intent.getBooleanExtra(EXTRA_PLAYING, false),
            title = intent.getStringExtra(EXTRA_TITLE).orEmpty(),
            artist = intent.getStringExtra(EXTRA_ARTIST).orEmpty(),
            ended = intent.getBooleanExtra(EXTRA_ENDED, false),
            position = seconds(intent.getDoubleExtra(EXTRA_POSITION, 0.0)),
            duration = seconds(intent.getDoubleExtra(EXTRA_DURATION, 0.0)),
            cover = coverOrEmpty(intent.getStringExtra(EXTRA_COVER)),
            fromChat = intent.getBooleanExtra(EXTRA_FROM_CHAT, false),
        )

        /** Сообщение от страницы. Данные чужие, поэтому проверяем формат и режем длину. */
        fun fromJson(raw: String?): MediaState? {
            if (raw.isNullOrEmpty()) return null
            return try {
                val json = JSONObject(raw)
                if (json.optString("type") != TYPE) return null
                MediaState(
                    playing = json.optBoolean("playing"),
                    title = json.optString("title").trim().take(AppConfig.MAX_METADATA_LENGTH),
                    artist = json.optString("artist").trim().take(AppConfig.MAX_METADATA_LENGTH),
                    ended = json.optBoolean("ended"),
                    position = seconds(json.optDouble("position", 0.0)),
                    duration = seconds(json.optDouble("duration", 0.0)),
                    cover = coverOrEmpty(json.optString("cover")),
                    trackId = json.optString("trackId").takeIf(com.example.vkweb.config.VkIds::isMediaId).orEmpty(),
                    trackAdded = if (json.has("trackAdded") && !json.isNull("trackAdded")) json.optBoolean("trackAdded") else null,
                    audioUrl = SafeUrls.audio(json.optString("audioUrl")),
                )
            } catch (e: JSONException) {
                null
            }
        }
    }
}
