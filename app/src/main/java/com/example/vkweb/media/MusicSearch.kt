package com.example.vkweb.media

import java.net.URLDecoder
import java.net.URLEncoder

/**
 * Поиск музыки (4.79): по запросу из своего экрана музыки и по исполнителю — из долгого нажатия на трек, из плеера и из
 * вложения-трека в переписке. Ищет сама страница музыки VK (адрес поиска — [PATH]), приложение показывает её результаты
 * своим списком. Без зависимостей от Android: проверяется обычными тестами.
 *
 * Поиск по исполнителю — тот же поиск по имени исполнителя, но в списке остаются только его треки ([byArtist]):
 * VK по имени находит и чужие треки с этим словом в названии.
 */
object MusicSearch {

    /** Страница поиска музыки на m.vk (отчёт 3.51: туда ведёт поле поиска самой страницы). */
    const val PATH = "/audio?section=general&q="

    const val MAX_QUERY = 200

    /** Сколько имён исполнителей предлагать в меню трека («A, B feat. C» — три). */
    const val MAX_ARTISTS = 4

    private val SPACES = Regex("\\s+")
    private val NOT_WORD = Regex("[^\\p{L}\\p{N}]+")
    /** Разметка внутри имени (выделение совпадений в поиске VK — <em>…</em>), 4.88. */
    private val TAGS = Regex("</?[a-zA-Z][^<>]*>")
    private val QUERY_PARAM = Regex("""(?:^|[?&])q=([^&#]*)""")

    // Разделители соисполнителей: запятая, «&», «feat.», «ft.», «featuring», «при участии», « x » (как пишет VK).
    private val ARTIST_SEPARATORS = Regex(
        """\s*(?:,|;|&|\s/\s|\s(?:feat\.?|ft\.?|featuring|при\s+участии|x|×|vs\.?)\s)\s*""",
        RegexOption.IGNORE_CASE,
    )

    /** Запрос в том виде, в каком уходит странице: без лишних пробелов, не длиннее [MAX_QUERY]; пусто — искать нечего. */
    fun clean(query: String?): String = query.orEmpty().replace(SPACES, " ").trim().take(MAX_QUERY)

    /** Путь страницы поиска по [query] (null — пустой запрос). */
    fun pathFor(query: String?): String? {
        val q = clean(query)
        if (q.isEmpty()) return null
        return PATH + URLEncoder.encode(q, "UTF-8").replace("+", "%20")
    }

    /**
     * Запрос страницы поиска музыки из пути [path] и строки запроса [query] адреса; null — это не поиск (другая страница
     * музыки, плейлист, пустой запрос).
     */
    fun queryOf(path: String?, query: String?): String? {
        if (path != "/audio") return null
        val q = query.orEmpty()
        if (q.contains("act=audio_playlist")) return null
        val raw = QUERY_PARAM.find(q)?.groupValues?.get(1) ?: return null
        val decoded = runCatching { URLDecoder.decode(raw, "UTF-8") }.getOrDefault(raw)
        return clean(decoded).ifEmpty { null }
    }

    /** Для сравнения: без разметки, строчные, «ё» как «е», знаки — пробелы. */
    fun normalize(value: String): String =
        value.replace(TAGS, " ").lowercase().replace('ё', 'е').replace(NOT_WORD, " ").trim()

    /** Тот же запрос (страница могла переписать регистр, пробелы, знаки). */
    fun sameQuery(a: String, b: String): Boolean = normalize(a) == normalize(b)

    /** Имена исполнителей трека: «A, B feat. C» → [A, B, C]; без повторов, не больше [MAX_ARTISTS]. */
    fun artistsOf(artist: String): List<String> {
        val whole = clean(artist)
        if (whole.isEmpty()) return emptyList()
        val seen = HashSet<String>()
        val names = whole.split(ARTIST_SEPARATORS).map { it.trim() }.filter { it.isNotEmpty() && seen.add(normalize(it)) }
        return names.filter { normalize(it).isNotEmpty() }.take(MAX_ARTISTS).ifEmpty { listOf(whole) }
    }

    /**
     * Трек с исполнителем [trackArtist] — исполнителя [wanted]: имя целиком, отдельными словами («A & B» — и A, и B).
     * 4.88: и то же имя без пробелов и знаков — у исполнителя целиком или у одного из соисполнителей («Би-2» и «Би 2»,
     * «AC/DC» и «ACDC»).
     */
    fun isBy(trackArtist: String, wanted: String): Boolean {
        val w = normalize(wanted)
        if (w.isEmpty()) return true
        val artist = normalize(trackArtist)
        if (" $artist ".contains(" $w ")) return true
        val compact = w.replace(" ", "")
        return artist.replace(" ", "") == compact ||
            artistsOf(trackArtist).any { normalize(it).replace(" ", "") == compact }
    }

    /** Только треки исполнителя [artist]. */
    fun byArtist(tracks: List<MusicTrack>, artist: String): List<MusicTrack> = tracks.filter { isBy(it.artist, artist) }
}
