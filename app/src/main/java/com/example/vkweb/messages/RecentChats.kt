package com.example.vkweb.messages

import com.example.vkweb.config.VkIds
import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject

/**
 * Недавние переписки для ярлыков на значке приложения (4.57): последние открытые, новые первыми, без повторов.
 * Без зависимостей от Android: проверяется обычными тестами. Ярлыки строит ui/AppShortcuts.kt.
 */
object RecentChats {

    /** Сколько переписок на значке: остальные места — у разделов (их лаунчер показывает около четырёх всего). */
    const val MAX = 2

    data class Chat(val peerId: String, val title: String, val avatar: String = "")

    /** Переписку открыли: она — первой, повтор убирается, список — не длиннее [max]. */
    fun opened(list: List<Chat>, chat: Chat, max: Int = MAX): List<Chat> =
        if (chat.peerId.isEmpty()) list else (listOf(chat) + list.filter { it.peerId != chat.peerId }).take(max)

    /**
     * Что на значке по порядку: недавние переписки, затем разделы — всего не больше [slots]. [sections] — разделы
     * по порядку важности.
     */
    fun <S> plan(recent: List<Chat>, sections: List<S>, slots: Int): Pair<List<Chat>, List<S>> {
        val chats = recent.take(minOf(MAX, slots))
        return chats to sections.take((slots - chats.size).coerceAtLeast(0))
    }

    fun toJson(list: List<Chat>): String = JSONArray().apply {
        list.forEach { put(JSONObject().put("peer", it.peerId).put("title", it.title).put("avatar", it.avatar)) }
    }.toString()

    fun fromJson(raw: String?): List<Chat> {
        if (raw.isNullOrBlank()) return emptyList()
        return try {
            val a = JSONArray(raw)
            (0 until minOf(a.length(), MAX)).mapNotNull { i ->
                val o = a.optJSONObject(i) ?: return@mapNotNull null
                val peer = o.optString("peer")
                if (!VkIds.isPeer(peer)) null else Chat(peer, o.optString("title").take(200), o.optString("avatar").take(2000))
            }
        } catch (e: JSONException) {
            emptyList()
        }
    }
}
