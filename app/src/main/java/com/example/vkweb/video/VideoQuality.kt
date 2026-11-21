package com.example.vkweb.video

import org.json.JSONObject

/**
 * Качество видео (3.86): файлы mp4 разной высоты (144…2160) из ответа VK и выбор по настройке. Настройка — потолок:
 * берётся самое высокое качество, не выше выбранного; если все выше — самое низкое из есть. «Авто» — поток HLS (он сам
 * подстраивается под сеть), без потока — [DEFAULT]. Без зависимостей от Android: проверяется обычными тестами.
 */
object VideoQuality {
    /** «Авто»: поток, подстраивающийся под сеть. */
    const val AUTO = 0

    /** По умолчанию — как было до 3.86: файл до 720p. */
    const val DEFAULT = 720

    /** Значения настройки (высота кадра). */
    val OPTIONS = listOf(144, 240, 360, 480, 720, 1080, 1440, 2160)

    private const val MIN_HEIGHT = 100
    private const val MAX_HEIGHT = 4320

    /** Ближайшее к [preferred] сверху вниз: самое высокое не выше; все выше — самое низкое. null — файлов нет. */
    fun pick(available: Collection<Int>, preferred: Int): Int? {
        if (available.isEmpty()) return null
        val ceiling = if (preferred <= 0) DEFAULT else preferred
        return available.filter { it <= ceiling }.maxOrNull() ?: available.minOrNull()
    }

    /**
     * Что можно выбрать у ролика (4.8; было в VideoPlayerActivity): «Авто», если есть поток [hls], и файлы — от высокого
     * к низкому.
     */
    fun choices(files: Map<Int, String>, hls: String): List<Int> =
        (if (hls.isNotEmpty()) listOf(AUTO) else emptyList()) + files.keys.sortedDescending()

    /** С чего начать по настройке [preferred]: «Авто» — поток, если он есть; иначе файл по [pick]; ничего — -1. */
    fun initial(files: Map<Int, String>, hls: String, preferred: Int): Int =
        if (preferred == AUTO && hls.isNotEmpty()) AUTO
        else pick(files.keys, preferred) ?: if (hls.isNotEmpty()) AUTO else -1

    /** Адрес для качества [q]: «Авто» — поток, высота — файл; нет такого — null. */
    fun urlFor(q: Int, files: Map<Int, String>, hls: String): String? = when {
        q == AUTO -> hls.ifEmpty { null }
        q > 0 -> files[q]
        else -> null
    }

    /** «720p», «Авто». */
    fun label(height: Int): String = if (height == AUTO) "Авто" else "${height}p"

    /** Файлы по высоте из {"720": url, …}; адрес проверяет [valid] (пусто — не годится). */
    fun parse(o: JSONObject?, valid: (String) -> String): Map<Int, String> {
        if (o == null) return emptyMap()
        val result = sortedMapOf<Int, String>()
        val keys = o.keys()
        while (keys.hasNext()) {
            val k = keys.next()
            val h = k.toIntOrNull()?.takeIf { it in MIN_HEIGHT..MAX_HEIGHT } ?: continue
            val url = valid(o.optString(k))
            if (url.isNotEmpty()) result[h] = url
        }
        return result
    }

    /** Для передачи в Intent: «720 https://…». */
    fun encode(files: Map<Int, String>): ArrayList<String> = ArrayList(files.map { (h, url) -> "$h $url" })

    fun decode(list: List<String>?, valid: (String) -> String): Map<Int, String> {
        if (list == null) return emptyMap()
        val result = sortedMapOf<Int, String>()
        list.forEach { line ->
            val h = line.substringBefore(' ').toIntOrNull()?.takeIf { it in MIN_HEIGHT..MAX_HEIGHT } ?: return@forEach
            val url = valid(line.substringAfter(' ', ""))
            if (url.isNotEmpty()) result[h] = url
        }
        return result
    }
}
