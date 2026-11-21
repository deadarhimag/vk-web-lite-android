package com.example.vkweb.media

/**
 * Очередь своего проигрывателя из списка на экране (4.95, код ревью: трек из кэша играл один, и после него музыка
 * останавливалась). В очередь входят соседние треки, которые приложение может сыграть само — из кэша или по адресу
 * потока; на первом, которого сыграть нельзя, очередь кончается, и дальше трек включает страница. Без Android.
 */
object InAppQueue {

    /** Очередь [tracks] и номер нажатого в ней. */
    data class Built(val tracks: List<ChatQueue.Track>, val start: Int)

    /**
     * Очередь вокруг трека [tappedId] из [list]: [urlFor] — адрес, по которому приложение сыграет трек ('' — не может).
     * null — нажатый трек приложение само не сыграет.
     */
    fun build(list: List<MusicTrack>, tappedId: String, urlFor: (MusicTrack) -> String): Built? {
        val at = list.indexOfFirst { it.id == tappedId }
        if (at < 0) return null
        val urls = HashMap<Int, String>()
        fun url(i: Int) = urls.getOrPut(i) { urlFor(list[i]) }
        if (url(at).isEmpty()) return null
        var first = at
        while (first > 0 && url(first - 1).isNotEmpty()) first--
        var last = at
        while (last + 1 < list.size && url(last + 1).isNotEmpty()) last++
        val tracks = (first..last).map { i ->
            val t = list[i]
            ChatQueue.Track(id = t.id, title = t.title, artist = t.artist, url = url(i), cover = t.coverUrl, durationSec = t.durationSec)
        }
        return Built(tracks, at - first)
    }

    /**
     * Очередь, где треки из кэша играют из файла ([uriFor] — адрес файла или null), а голосовые — как есть (4.95, вынесено
     * из ChatMusic в 4.98 для тестов).
     */
    fun withCached(tracks: List<ChatQueue.Track>, uriFor: (String) -> String?): List<ChatQueue.Track> =
        tracks.map { t -> if (t.voice) t else uriFor(t.id)?.let { t.copy(url = it) } ?: t }

    /** Трек после [id] в [list] (для продолжения страницей, когда своя очередь доиграла); null — его нет. */
    fun nextAfter(list: List<MusicTrack>, id: String): MusicTrack? {
        val at = list.indexOfFirst { it.id == id }
        return if (at < 0) null else list.getOrNull(at + 1)
    }
}
