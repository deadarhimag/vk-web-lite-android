package com.example.vkweb.people

import android.content.Context
import org.json.JSONObject

/**
 * Прочитанные новые записи сообществ (4.0). Счётчик у подписки — «новых записей с последнего посещения» — VK
 * сбрасывает, только когда страницу сообщества открывают на его сайте; свой экран сообщества читает записи иначе, и
 * счётчик так и оставался (жалоба с телефона). Теперь открыли сообщество — его текущий счётчик запоминается и
 * вычитается: показываются только записи, вышедшие после этого.
 */
class GroupSeen(context: Context) {

    private val store = com.example.vkweb.offline.PrefsValue(context, PREFS, KEY, GroupCounters::decode, GroupCounters::encode)

    /** Сколько показать у сообщества [id] при счётчике VK [counter]. */
    fun shown(id: Long, counter: Int): Int = GroupCounters.shown(counter, store.value[id] ?: 0)

    /** Сообщество открыли — всё, что сейчас в счётчике, прочитано. */
    fun markSeen(id: Long, counter: Int) {
        store.update(GroupCounters.remember(store.value, id, counter))
    }

    companion object {
        private const val PREFS = "groups_seen"
        private const val KEY = "seen"

        fun clear(context: Context) = com.example.vkweb.offline.PrefsValue.clear(context, PREFS)
    }
}

/** Правила счётчиков без Android: проверяются тестами. */
object GroupCounters {
    const val MAX_ENTRIES = 500

    /**
     * Показать: счётчик VK минус уже прочитанное. Счётчик стал меньше запомненного — VK сам его сбросил (сообщество
     * открывали на сайте): тогда он показывается как есть.
     */
    fun shown(counter: Int, seen: Int): Int = when {
        counter <= 0 -> 0
        counter < seen -> counter
        else -> counter - seen
    }

    fun remember(map: Map<Long, Int>, id: Long, counter: Int): Map<Long, Int> {
        if (id <= 0 || counter < 0) return map
        if (map[id] == counter) return map
        val next = LinkedHashMap(map)
        next.remove(id)
        if (counter > 0) next[id] = counter
        while (next.size > MAX_ENTRIES) next.remove(next.keys.first())
        return next
    }

    fun encode(map: Map<Long, Int>): String = JSONObject().apply { map.forEach { (k, v) -> put(k.toString(), v) } }.toString()

    fun decode(raw: String?): Map<Long, Int> {
        if (raw.isNullOrEmpty()) return emptyMap()
        return try {
            val o = JSONObject(raw)
            val out = LinkedHashMap<Long, Int>()
            o.keys().forEach { k -> val id = k.toLongOrNull(); val v = o.optInt(k); if (id != null && id > 0 && v > 0) out[id] = v }
            out
        } catch (e: org.json.JSONException) {
            emptyMap()
        }
    }
}
