package com.example.vkweb.media

import com.example.vkweb.config.VkIds

/**
 * Просьба из переписки «включить этот трек». Адреса звука в сообщении нет, а плеер VK включает только трек из вёрстки,
 * поэтому всё делает страница музыки (`playFromChat` в assets/vk_lite/50_music.js): трек есть на странице — нажимает его;
 * нет — ищет по [title] поиском самой страницы и нажимает в результатах. Без номера трека — только поиск (выберет человек).
 * Без зависимостей от Android: проверяется обычными тестами.
 */
data class ChatTrackRequest(
    /** Номер трека «владелец_номер»; пусто — только поиск. */
    val trackId: String,
    /** «Исполнитель — Название» для поиска. */
    val title: String,
) {
    /** Команда странице уже отдана: страница музыки сообщает о загрузке дважды, а включать нужно один раз. */
    var started: Boolean = false

    /** Страница [path] — страница музыки, на которой выполняется просьба. */
    fun isMusicPage(path: String?): Boolean = path == MUSIC_PATH

    companion object {
        const val MUSIC_PATH = "/audio"
        private const val MAX_TITLE = 200

        /** Данные приходят из намерения: номер — только «владелец_номер», без номера нужен хотя бы заголовок. */
        fun from(trackId: String?, title: String?): ChatTrackRequest? {
            val id = trackId.orEmpty()
            if (id.isNotEmpty() && !VkIds.isMediaId(id)) return null
            val query = title.orEmpty().replace(Regex("\\s+"), " ").trim().take(MAX_TITLE)
            if (id.isEmpty() && query.isEmpty()) return null
            return ChatTrackRequest(id, query)
        }
    }
}
