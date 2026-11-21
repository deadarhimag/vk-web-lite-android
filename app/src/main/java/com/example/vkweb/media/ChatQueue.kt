package com.example.vkweb.media

/**
 * Очередь музыки из переписки: треки сообщений по порядку (сверху вниз), какой играет, что дальше. Без зависимостей
 * от Android: проверяется обычными тестами.
 */
class ChatQueue(tracks: List<Track>, startIndex: Int) {

    /** Трек с адресом потока (https на серверах звука VK) и тем, что показать: название, исполнитель, обложка. */
    data class Track(
        val id: String,
        val title: String,
        val artist: String,
        val url: String,
        val cover: String = "",
        val durationSec: Int = 0,
        /** Голосовое (4.0): у него своя скорость воспроизведения ([PlaybackSpeed]). */
        val voice: Boolean = false,
    )

    var tracks: List<Track> = tracks
        private set

    var index: Int = startIndex.coerceIn(0, (tracks.size - 1).coerceAtLeast(0))
        private set

    val current: Track? get() = tracks.getOrNull(index)
    val hasNext: Boolean get() = index + 1 < tracks.size
    val hasPrevious: Boolean get() = index > 0

    /** Следующий трек; в конце очереди — null (музыка заканчивается, как плейлист без повтора). */
    fun next(): Track? {
        if (!hasNext) return null
        index++
        return current
    }

    /** Предыдущий трек; в начале — null. */
    fun previous(): Track? {
        if (!hasPrevious) return null
        index--
        return current
    }

    /** «Назад» как в плеерах: дальше [RESTART_AFTER_MS] от начала — к началу этого же трека, иначе — предыдущий. */
    fun previousRestarts(positionMs: Int): Boolean = positionMs > RESTART_AFTER_MS || !hasPrevious

    /** Свежий адрес трека [id] (прежний не проигрался): остальное без изменений. */
    fun replaceUrl(id: String, url: String): Boolean {
        val i = tracks.indexOfFirst { it.id == id }
        if (i < 0 || url.isEmpty()) return false
        tracks = tracks.toMutableList().also { it[i] = it[i].copy(url = url) }
        return true
    }

    /** Трек не играет и не заменить: убрать из очереди; текущим становится следующий (или null — очередь кончилась). */
    fun dropCurrent(): Track? {
        if (tracks.isEmpty()) return null
        tracks = tracks.toMutableList().also { it.removeAt(index) }
        if (index >= tracks.size) {
            index = (tracks.size - 1).coerceAtLeast(0)
            return null
        }
        return current
    }

    companion object {
        const val RESTART_AFTER_MS = 3_000

        /** «Исполнитель — Название» карточки → (исполнитель, название); без разделителя — всё название. */
        fun splitTitle(full: String): Pair<String, String> {
            val i = full.indexOf(" — ")
            return if (i <= 0) "" to full.trim() else full.substring(0, i).trim() to full.substring(i + 3).trim()
        }
    }
}
