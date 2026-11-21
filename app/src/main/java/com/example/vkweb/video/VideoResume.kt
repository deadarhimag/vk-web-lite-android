package com.example.vkweb.video

import android.content.Context
import org.json.JSONObject

/**
 * Место в видео (4.0): закрыли длинный ролик посередине — при следующем открытии он продолжается оттуда же, а не с
 * начала. Хранится для последних [VideoResumeRules.MAX_ENTRIES] роликов; досмотренное забывается.
 */
class VideoResume(context: Context) {

    private val store = com.example.vkweb.offline.PrefsValue(context, PREFS, KEY, VideoResumeRules::decode, VideoResumeRules::encode)

    fun get(key: String): Long = if (key.isEmpty()) 0L else store.value[key] ?: 0L

    /** Запомнить (или забыть — если начало, конец или ролик короткий). */
    fun put(key: String, positionMs: Long, durationMs: Long) {
        if (key.isEmpty()) return
        store.update(VideoResumeRules.put(store.value, key, VideoResumeRules.toSave(positionMs, durationMs)))
    }

    companion object {
        private const val PREFS = "video_resume"
        private const val KEY = "positions"

        fun clear(context: Context) = com.example.vkweb.offline.PrefsValue.clear(context, PREFS)
    }
}

/** Правила без Android: проверяются тестами. */
object VideoResumeRules {
    const val MAX_ENTRIES = 100
    /** Короче минуты — смотрят целиком, продолжать незачем. */
    const val MIN_DURATION_MS = 60_000L
    /** У самого начала и самого конца — не запоминаем (конец — значит досмотрел). */
    const val EDGE_MS = 15_000L

    /** Что запомнить: место или null (забыть). */
    fun toSave(positionMs: Long, durationMs: Long): Long? = when {
        durationMs < MIN_DURATION_MS -> null
        positionMs < EDGE_MS -> null
        positionMs > durationMs - EDGE_MS -> null
        else -> positionMs
    }

    /** Ключ ролика: страница видео («/video-1_2»), иначе адрес файла без параметров (в них — срок и подпись). */
    fun keyOf(page: String, url: String): String = page.ifEmpty { url.substringBefore('?') }.take(300)

    fun put(map: Map<String, Long>, key: String, value: Long?): Map<String, Long> {
        if (value == null) return if (key in map) map - key else map
        if (map[key] == value) return map
        val next = LinkedHashMap(map)
        next.remove(key)
        next[key] = value
        while (next.size > MAX_ENTRIES) next.remove(next.keys.first())
        return next
    }

    fun encode(map: Map<String, Long>): String = JSONObject().apply { map.forEach { (k, v) -> put(k, v) } }.toString()

    fun decode(raw: String?): Map<String, Long> {
        if (raw.isNullOrEmpty()) return emptyMap()
        return try {
            val o = JSONObject(raw)
            val out = LinkedHashMap<String, Long>()
            o.keys().forEach { k -> val v = o.optLong(k); if (k.isNotEmpty() && v > 0) out[k] = v }
            out
        } catch (e: org.json.JSONException) {
            emptyMap()
        }
    }
}
