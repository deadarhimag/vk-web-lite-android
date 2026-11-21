package com.example.vkweb.messages

import com.example.vkweb.config.VkIds

/**
 * Кто в открытой переписке (4.31): нажатие на шапку открывает страницу собеседника, а в беседе — список участников на
 * выбор. Без зависимостей от Android: проверяется обычными тестами.
 *
 * Участники беседы — полный список от VK (4.32, messages.getConversationMembers — [merge]); пока его нет или VK его
 * не отдал — те, кто писал в загруженной части (номер автора есть у входящих, [ChatMessage.senderId]).
 */
object ChatPeople {

    data class Person(val id: Long, val name: String, val avatar: String = "") {
        /** Страница на сайте: человек — /id1, сообщество — /club1. */
        val path: String get() = pathOf(id) ?: ""
    }

    sealed interface Target {
        /** Личная переписка или сообщество: сразу его страница. */
        data class Page(val path: String) : Target

        /** Беседа: выбрать, чью страницу открыть (свежие авторы — первыми). */
        data class Choose(val people: List<Person>) : Target

        /** Беседа, где ещё никто не писал в загруженном, или непонятный номер. */
        data object Unknown : Target
    }

    /** Страница по номеру собеседника; беседы и 0 — null. */
    fun pathOf(id: Long): String? = when {
        VkIds.isUser(id) -> "/id$id"
        id < 0 -> "/club${-id}"
        else -> null
    }

    fun target(peer: String?, messages: List<ChatMessage>): Target {
        val id = peer?.toLongOrNull() ?: return Target.Unknown
        pathOf(id)?.let { return Target.Page(it) }
        if (!VkIds.isChat(id)) return Target.Unknown
        val people = members(messages)
        return if (people.isEmpty()) Target.Unknown else Target.Choose(people)
    }

    /** Авторы входящих по одному разу: последние писавшие — первыми; без имени — не показываются. */
    fun members(messages: List<ChatMessage>): List<Person> {
        val seen = LinkedHashMap<Long, String>()
        for (m in messages.asReversed()) {
            if (m.out || m.senderId == 0L || m.sender.isBlank() || pathOf(m.senderId) == null) continue
            if (m.senderId !in seen) seen[m.senderId] = m.sender.trim()
        }
        return seen.map { (id, name) -> Person(id, name) }
    }

    /**
     * Полный список участников [all] (от VK) и писавшие в загруженном [writers]: сначала писавшие (последние — первыми),
     * затем остальные по порядку VK. Полного списка нет — только писавшие.
     */
    fun merge(all: List<Person>?, writers: List<Person>): List<Person> {
        if (all.isNullOrEmpty()) return writers
        val known = all.associateBy { it.id }
        val first = writers.filter { it.id in known }.map { known.getValue(it.id) }
        val firstIds = first.map { it.id }.toSet()
        return first + all.filter { it.id !in firstIds }
    }

    /** Участники из сообщения страницы «members»: номер страницы и имя; без имени или с номером беседы — пропускаются. */
    fun parse(items: org.json.JSONArray?): List<Person> {
        if (items == null) return emptyList()
        val result = ArrayList<Person>()
        val seen = HashSet<Long>()
        for (i in 0 until minOf(items.length(), MAX_PEOPLE)) {
            val o = items.optJSONObject(i) ?: continue
            val id = o.optLong("id")
            val name = o.optString("name").trim().take(200)
            if (name.isEmpty() || pathOf(id) == null || !seen.add(id)) continue
            // Длиннее 1000 — не адрес аватарки: пусто, а не обрезанный адрес (4.65).
            val photo = com.example.vkweb.config.SafeUrls.https(o.optString("photo"), maxLength = 1000)
            result += Person(id, name, photo)
        }
        return result
    }

    private const val MAX_PEOPLE = 500

}
