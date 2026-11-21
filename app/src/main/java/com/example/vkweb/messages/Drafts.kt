package com.example.vkweb.messages

import android.content.Context
import org.json.JSONObject

/**
 * Черновики переписок (3.94): недописанный текст остаётся у своей переписки. Раньше поле ввода было одно на все —
 * набранное в одной переписке оказывалось в следующей открытой. Хранится до отправки или стирания, переживает
 * перезапуск; в списке диалогов — «Черновик: …». Стирается при выходе из аккаунта.
 */
class Drafts(context: Context) {

    private val store = com.example.vkweb.offline.PrefsValue(context, PREFS, KEY, DraftMap::decode, DraftMap::encode)

    val all: Map<String, String> get() = store.value

    fun get(peer: String): String = all[peer].orEmpty()

    /** @return true, если черновики изменились (список диалогов стоит перерисовать). */
    fun put(peer: String, text: String): Boolean = store.update(DraftMap.put(all, peer, text))

    companion object {
        private const val PREFS = "messages_drafts"
        private const val KEY = "drafts"

        fun clear(context: Context) = com.example.vkweb.offline.PrefsValue.clear(context, PREFS)
    }
}

/** Правила черновиков без Android: проверяются тестами. */
object DraftMap {
    const val MAX_DRAFTS = 50
    const val MAX_CHARS = 4000

    /** Пустой (из пробелов) — черновика нет; новый или изменённый — последним; самые старые сверх [MAX_DRAFTS] уходят. */
    fun put(map: Map<String, String>, peer: String, text: String): Map<String, String> {
        if (peer.isEmpty()) return map
        val clean = text.take(MAX_CHARS)
        if (clean.isBlank()) return if (peer in map) map - peer else map
        if (map[peer] == clean) return map
        val next = LinkedHashMap(map)
        next.remove(peer)
        next[peer] = clean
        while (next.size > MAX_DRAFTS) next.remove(next.keys.first())
        return next
    }

    fun encode(map: Map<String, String>): String = JSONObject().apply { map.forEach { (k, v) -> put(k, v) } }.toString()

    fun decode(raw: String?): Map<String, String> {
        if (raw.isNullOrEmpty()) return emptyMap()
        return try {
            val o = JSONObject(raw)
            val out = LinkedHashMap<String, String>()
            o.keys().forEach { k -> o.optString(k).takeIf { it.isNotBlank() }?.let { out[k] = it.take(MAX_CHARS) } }
            out
        } catch (e: org.json.JSONException) {
            emptyMap()
        }
    }

    /** Строка списка диалогов: одна строка текста черновика. */
    fun preview(text: String): String = text.replace(Regex("\\s+"), " ").trim()
}
