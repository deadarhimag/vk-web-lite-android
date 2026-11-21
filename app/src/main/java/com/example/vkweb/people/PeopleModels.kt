package com.example.vkweb.people

import com.example.vkweb.config.AppConfig
import com.example.vkweb.config.Tab
import org.json.JSONArray
import org.json.JSONObject

/** Какой список показывает свой экран: друзья или сообщества. [wire] — имя в сообщениях страницы. */
enum class PeopleKind(val wire: String, val tab: Tab) {
    FRIENDS("friends", Tab.FRIENDS),
    GROUPS("groups", Tab.GROUPS);

    companion object {
        fun fromWire(value: String?): PeopleKind? = entries.firstOrNull { it.wire == value }

        /** Раздел по пути страницы (без query): /friends… или /groups…; иначе null. */
        fun fromPath(path: String?): PeopleKind? = path?.let { p -> entries.firstOrNull { it.tab.matches(p) } }
    }
}

/**
 * Человек или сообщество в своём списке. Поля — те, что присылает страница (assets/vk_lite/60_people.js,
 * раздел «друзья и сообщества»); у друга заполнены одни, у сообщества — другие.
 */
data class PeopleEntry(
    val id: Long,
    val kind: PeopleKind,
    val name: String,
    val photo: String = "",
    // друзья
    /** 1 — женский, 2 — мужской, 0 — неизвестно (для «был/была в сети»). */
    val sex: Int = 0,
    val online: Boolean = false,
    val mobile: Boolean = false,
    /** Последний вход, секунды Unix; 0 — скрыт. */
    val lastSeen: Long = 0,
    /** Скрытое время входа: recently, last_week, last_month, long_ago. */
    val seen: String = "",
    val city: String = "",
    val mutual: Int = 0,
    val canWrite: Boolean = false,
    /** deleted / banned — страница удалена или заблокирована. */
    val deactivated: String = "",
    /** Заявка в друзья (входящая). */
    val request: Boolean = false,
    /** «Возможно, вы знакомы» (friends.getRecommendations, 3.83). */
    val suggested: Boolean = false,
    // сообщества
    /** Готовая строка VK: «42 783 подписчика». */
    val members: String = "",
    val membersCount: Int = 0,
    /** Тематика: «Музыка», «Аниме». */
    val activity: String = "",
    /** group / page / event. */
    val groupType: String = "",
    val verified: Boolean = false,
    /** 0 — открытое, 1 — закрытое, 2 — частное. */
    val closed: Int = 0,
    val admin: Boolean = false,
    /** Новые записи (счётчик VK у подписки). */
    val counter: Int = 0,
) {
    /** Страница человека или сообщества на сайте. */
    val pageUrl: String get() = AppConfig.SITE_ORIGIN + if (kind == PeopleKind.FRIENDS) "/id$id" else "/club$id"

    /** Собеседник в мессенджере: у сообществ id отрицательный. */
    val peerId: String get() = if (kind == PeopleKind.FRIENDS) id.toString() else "-$id"

    val isDeactivated: Boolean get() = deactivated.isNotEmpty()

    fun toJson(): JSONObject = JSONObject()
        .put("id", id).put("name", name).put("photo", photo)
        .put("sex", sex).put("online", online).put("mobile", mobile).put("lastSeen", lastSeen).put("seen", seen)
        .put("city", city).put("mutual", mutual).put("canWrite", canWrite).put("deactivated", deactivated)
        .put("members", members).put("membersCount", membersCount).put("activity", activity)
        .put("groupType", groupType).put("verified", verified).put("closed", closed).put("admin", admin)
        .put("counter", counter)

    companion object {
        private const val MAX_TEXT = 300

        fun fromJson(o: JSONObject, kind: PeopleKind, request: Boolean = false, suggested: Boolean = false): PeopleEntry? {
            val id = o.optLong("id")
            if (id <= 0) return null
            return PeopleEntry(
                id = id,
                kind = kind,
                name = o.optString("name").trim().take(MAX_TEXT),
                photo = com.example.vkweb.config.SafeUrls.https(o.optString("photo")),
                sex = o.optInt("sex"),
                online = o.optBoolean("online"),
                mobile = o.optBoolean("mobile"),
                lastSeen = o.optLong("lastSeen").coerceIn(0, PeopleFormat.MAX_EPOCH_SEC),
                seen = o.optString("seen").take(40),
                city = o.optString("city").take(MAX_TEXT),
                mutual = o.optInt("mutual").coerceAtLeast(0),
                canWrite = o.optBoolean("canWrite"),
                deactivated = o.optString("deactivated").take(40),
                request = request,
                suggested = suggested,
                members = o.optString("members").take(MAX_TEXT),
                membersCount = o.optInt("membersCount").coerceAtLeast(0),
                activity = o.optString("activity").take(MAX_TEXT),
                groupType = o.optString("groupType").take(20),
                verified = o.optBoolean("verified"),
                closed = o.optInt("closed"),
                admin = o.optBoolean("admin"),
                counter = o.optInt("counter").coerceAtLeast(0),
            )
        }
    }
}

/**
 * Список друзей или сообществ открытой страницы (сообщение type = "people").
 * [own] — свой список (а не друзья другого человека): только он сохраняется для работы без сети.
 * [key] — какая это страница: обновлять на месте можно только тот же список.
 * [listSeen] — сам список уже приходил; до этого сообщение может нести одни заявки (они приходят раньше),
 * и пустой [items] не значит «друзей нет».
 */
data class PeoplePayload(
    val kind: PeopleKind,
    val key: String,
    val own: Boolean,
    val hasMore: Boolean,
    /** Всего в списке по данным VK (0 — неизвестно). */
    val total: Int,
    val items: List<PeopleEntry>,
    /** Входящие заявки в друзья. */
    val requests: List<PeopleEntry> = emptyList(),
    val listSeen: Boolean = true,
    /** «Возможно, вы знакомы» — в своём списке друзей. */
    val suggestions: List<PeopleEntry> = emptyList(),
) {
    val isEmpty: Boolean get() = items.isEmpty() && requests.isEmpty()

    /** Аватарки списка и заявок — для заблаговременной загрузки, чтобы они были и без сети. */
    fun imageUrls(): List<String> = (items + requests).map { it.photo }.filter { it.isNotEmpty() }

    /** Пришёл весь список: лишнее из сохранённого можно убрать. */
    val isComplete: Boolean get() = listSeen && !hasMore

    fun toJson(): JSONObject = JSONObject()
        .put("type", TYPE).put("kind", kind.wire).put("key", key).put("own", own)
        .put("hasMore", hasMore).put("total", total).put("listSeen", listSeen)
        .put("items", JSONArray().apply { items.forEach { put(it.toJson()) } })
        .put("requests", JSONArray().apply { requests.forEach { put(it.toJson()) } })
        .put("suggestions", JSONArray().apply { suggestions.forEach { put(it.toJson()) } })

    companion object {
        /** Тип сообщения страницы (поле type). */
        const val TYPE = "people"
        private const val MAX_ITEMS = 10_000
        private const val MAX_REQUESTS = 500
        private const val MAX_SUGGESTIONS = 50

        /** null — сообщение не о друзьях/сообществах. */
        fun fromJson(json: JSONObject): PeoplePayload? {
            val kind = PeopleKind.fromWire(json.optString("kind")) ?: return null
            return PeoplePayload(
                kind = kind,
                key = json.optString("key").ifEmpty { kind.tab.path }.take(500),
                own = json.optBoolean("own", true),
                hasMore = json.optBoolean("hasMore"),
                total = json.optInt("total").coerceAtLeast(0),
                items = parse(json.optJSONArray("items"), kind, false, MAX_ITEMS),
                requests = parse(json.optJSONArray("requests"), kind, true, MAX_REQUESTS),
                listSeen = json.optBoolean("listSeen", true),
                suggestions = if (kind == PeopleKind.FRIENDS) parse(json.optJSONArray("suggestions"), kind, false, MAX_SUGGESTIONS, suggested = true) else emptyList(),
            )
        }

        private fun parse(array: JSONArray?, kind: PeopleKind, request: Boolean, limit: Int, suggested: Boolean = false): List<PeopleEntry> {
            if (array == null) return emptyList()
            val seen = HashSet<Long>()
            val result = ArrayList<PeopleEntry>()
            for (i in 0 until minOf(array.length(), limit)) {
                val entry = array.optJSONObject(i)?.let { PeopleEntry.fromJson(it, kind, request, suggested) } ?: continue
                if (seen.add(entry.id)) result += entry
            }
            return result
        }
    }
}
