package com.example.vkweb.owner

import com.example.vkweb.config.VkIds
import org.json.JSONArray
import org.json.JSONObject

/**
 * Фото или превью видео в посте. [open] — адрес на сайте (видео), иначе нажатие открывает просмотр [full].
 * [page] — страница самого фото на сайте (/photo-1_2): там его можно лайкнуть и прокомментировать.
 */
data class PostPhoto(
    val url: String, val width: Int, val height: Int, val full: String, val open: String, val page: String = "",
    /** Ключ доступа ролика ([open] — страница видео), 4.14: без него файлы закрытого ролика не отдаются. */
    val key: String = "",
) {
    fun toJson(): JSONObject = JSONObject()
        .put("url", url).put("width", width).put("height", height).put("full", full).put("open", open).put("page", page)
        .put("key", key)

    companion object {
        private val KEY = Regex("^[0-9a-zA-Z]{1,40}$")

        fun fromJson(o: JSONObject): PostPhoto? {
            // 4.65: адреса картинок — через SafeUrls (https и длина; серверы картинок VK проверяет ImageCache), а не своей проверкой.
            val url = com.example.vkweb.config.SafeUrls.https(o.optString("url")).ifEmpty { return null }
            return PostPhoto(
                url = url,
                width = o.optInt("width").coerceIn(0, 20_000),
                height = o.optInt("height").coerceIn(0, 20_000),
                full = com.example.vkweb.config.SafeUrls.https(o.optString("full")),
                open = safePath(o.optString("open")),
                page = safePath(o.optString("page")).takeIf { it.startsWith("/photo") }.orEmpty(),
                key = o.optString("key").takeIf { KEY.matches(it) }.orEmpty(),
            )
        }
    }
}

/** Вложение строкой: аудио, ссылка, документ, опрос, видео без превью. У аудио — номер трека и ключ (3.63: играет). */
data class PostExtra(val kind: String, val title: String, val url: String, val trackId: String = "", val trackKey: String = "") {
    fun toJson(): JSONObject = JSONObject().put("kind", kind).put("title", title).put("url", url).put("id", trackId).put("key", trackKey)

    companion object {
        fun fromJson(o: JSONObject): PostExtra? {
            val title = o.optString("title").trim().take(300)
            if (title.isEmpty()) return null
            val raw = o.optString("url")
            val url = if (raw.startsWith("https://") || raw.startsWith("http://")) raw.take(2000) else safePath(raw)
            val kind = o.optString("kind").take(20)
            val id = o.optString("id").takeIf { kind == "audio" && VkIds.isMediaId(it) }.orEmpty()
            val key = o.optString("key").takeIf { id.isNotEmpty() && TRACK_KEY.matches(it) }.orEmpty()
            return PostExtra(kind, title, url, id, key)
        }
    }
}

private val TRACK_KEY = Regex("^[0-9A-Za-z]{1,40}$")

/** Пост стены или пост, которым поделились ([WallPost.repost]). */
data class WallPost(
    val key: String,
    val url: String,
    val fromId: Long,
    val fromName: String,
    val fromPhoto: String,
    val date: Long,
    val text: String,
    val photos: List<PostPhoto>,
    val extras: List<PostExtra>,
    val pinned: Boolean = false,
    val repost: WallPost? = null,
    val likes: Int = 0,
    val liked: Boolean = false,
    val comments: Int = 0,
    val reposts: Int = 0,
    val views: Int = 0,
) {
    fun toJson(): JSONObject = JSONObject()
        .put("key", key).put("url", url).put("fromId", fromId).put("fromName", fromName).put("fromPhoto", fromPhoto)
        .put("date", date).put("text", text).put("pinned", pinned)
        .put("photos", JSONArray().apply { photos.forEach { put(it.toJson()) } })
        .put("extras", JSONArray().apply { extras.forEach { put(it.toJson()) } })
        .put("repost", repost?.toJson() ?: JSONObject.NULL)
        .put("likes", likes).put("liked", liked).put("comments", comments).put("reposts", reposts).put("views", views)

    companion object {
        private const val MAX_TEXT = 6000

        fun fromJson(o: JSONObject, nested: Boolean = false): WallPost? {
            val url = safePath(o.optString("url"))
            val key = o.optString("key").ifEmpty { url }
            if (key.isEmpty() && !nested) return null
            return WallPost(
                key = key.take(100),
                url = url,
                fromId = o.optLong("fromId"),
                fromName = o.optString("fromName").trim().take(300),
                fromPhoto = com.example.vkweb.config.SafeUrls.https(o.optString("fromPhoto")),
                date = o.optLong("date").coerceIn(0, com.example.vkweb.people.PeopleFormat.MAX_EPOCH_SEC),
                text = o.optString("text").take(MAX_TEXT),
                photos = list(o.optJSONArray("photos"), PostPhoto::fromJson),
                extras = list(o.optJSONArray("extras"), PostExtra::fromJson),
                pinned = o.optBoolean("pinned"),
                repost = if (nested) null else o.optJSONObject("repost")?.let { fromJson(it, nested = true) },
                likes = o.optInt("likes").coerceAtLeast(0),
                liked = o.optBoolean("liked"),
                comments = o.optInt("comments").coerceAtLeast(0),
                reposts = o.optInt("reposts").coerceAtLeast(0),
                views = o.optInt("views").coerceAtLeast(0),
            )
        }
    }
}

/** Шапка страницы: человек ([isUser]) или сообщество. Поля — те, что присылает страница. */
data class OwnerHeader(
    val isUser: Boolean,
    /** id без знака: у человека — id пользователя, у сообщества — id сообщества. */
    val id: Long,
    val name: String,
    val photo: String,
    val photoFull: String,
    val status: String,
    val verified: Boolean,
    val deactivated: String,
    val canWrite: Boolean,
    // человек
    val sex: Int = 0,
    val online: Boolean = false,
    val mobile: Boolean = false,
    val lastSeen: Long = 0,
    val seen: String = "",
    val city: String = "",
    val occupation: String = "",
    /** Закрытый профиль, к которому нет доступа. */
    val closedProfile: Boolean = false,
    val friends: Long = 0,
    val followers: Long = 0,
    val photos: Long = 0,
    val videos: Long = 0,
    val groups: Long = 0,
    // сообщество
    val cover: String = "",
    val description: String = "",
    val activity: String = "",
    val members: Long = 0,
    val membersText: String = "",
    val groupType: String = "",
    val closedGroup: Int = 0,
    val isMember: Boolean = false,
) {
    /** id владельца в API: у сообществ отрицательный. */
    val ownerId: Long get() = if (isUser) id else -id

    fun toJson(): JSONObject = JSONObject()
        .put("kind", if (isUser) "user" else "group").put("id", id).put("name", name)
        .put("photo", photo).put("photoFull", photoFull).put("status", status).put("verified", verified)
        .put("deactivated", deactivated).put("canWrite", canWrite)
        .put("sex", sex).put("online", online).put("mobile", mobile).put("lastSeen", lastSeen).put("seen", seen)
        .put("city", city).put("occupation", occupation).put("closed", if (isUser) closedProfile else closedGroup)
        .put("counters", JSONObject().put("friends", friends).put("followers", followers).put("photos", photos)
            .put("videos", videos).put("groups", groups))
        .put("cover", cover).put("description", description).put("activity", activity)
        .put("members", members).put("membersText", membersText).put("groupType", groupType).put("isMember", isMember)

    companion object {
        fun fromJson(o: JSONObject): OwnerHeader? {
            val id = o.optLong("id")
            if (id <= 0) return null
            val user = o.optString("kind") == "user"
            val c = o.optJSONObject("counters") ?: JSONObject()
            fun https(key: String) = com.example.vkweb.config.SafeUrls.https(o.optString(key))
            return OwnerHeader(
                isUser = user,
                id = id,
                name = o.optString("name").trim().take(300),
                photo = https("photo"),
                photoFull = https("photoFull"),
                status = o.optString("status").trim().take(500),
                verified = o.optBoolean("verified"),
                deactivated = o.optString("deactivated").take(40),
                canWrite = o.optBoolean("canWrite"),
                sex = o.optInt("sex"),
                online = o.optBoolean("online"),
                mobile = o.optBoolean("mobile"),
                lastSeen = o.optLong("lastSeen").coerceIn(0, com.example.vkweb.people.PeopleFormat.MAX_EPOCH_SEC),
                seen = o.optString("seen").take(40),
                city = o.optString("city").take(200),
                occupation = o.optString("occupation").take(200),
                closedProfile = user && o.optBoolean("closed"),
                friends = c.optLong("friends").coerceAtLeast(0),
                followers = c.optLong("followers").coerceAtLeast(0),
                photos = c.optLong("photos").coerceAtLeast(0),
                videos = c.optLong("videos").coerceAtLeast(0),
                groups = c.optLong("groups").coerceAtLeast(0),
                cover = https("cover"),
                description = o.optString("description").trim().take(4000),
                activity = o.optString("activity").trim().take(200),
                members = o.optLong("members").coerceAtLeast(0),
                membersText = o.optString("membersText").take(200),
                groupType = o.optString("groupType").take(20),
                closedGroup = if (user) 0 else o.optInt("closed"),
                isMember = o.optBoolean("isMember"),
            )
        }
    }
}

/**
 * Страница человека или сообщества (сообщение type = "owner"). [key] — путь страницы (/durov, /id1):
 * по нему приложение понимает, что данные относятся к открытой странице.
 * [wallSeen] — стена уже пришла (пустой список постов тогда значит «записей нет»).
 */
data class OwnerPayload(
    val key: String,
    val ownerId: Long,
    val header: OwnerHeader?,
    val posts: List<WallPost>,
    val hasMore: Boolean,
    val wallSeen: Boolean,
    val snapshot: Boolean = false,
    val needsWallHead: Boolean = false,
) {
    /**
     * Картинки страницы для заблаговременной загрузки: фото владельца, обложка, а у первых записей — авторы и
     * первое фото. Столько же записей сохраняется без сети ([toJson]), так что без сети они выглядят целиком.
     */
    fun imageUrls(maxPosts: Int = PREFETCH_POSTS): List<String> {
        val result = ArrayList<String>()
        header?.let { result += listOf(it.photo, it.cover) }
        for (post in posts.take(maxPosts)) {
            result += post.fromPhoto
            post.photos.firstOrNull()?.let { result += it.url }
            post.repost?.let { repost ->
                result += repost.fromPhoto
                repost.photos.firstOrNull()?.let { result += it.url }
            }
        }
        return result.filter { it.isNotEmpty() }
    }

    fun toJson(maxPosts: Int = Int.MAX_VALUE): JSONObject = JSONObject()
        .put("type", TYPE).put("key", key).put("ownerId", ownerId)
        .put("header", header?.toJson() ?: JSONObject.NULL)
        .put("posts", JSONArray().apply { posts.take(maxPosts).forEach { put(it.toJson()) } })
        .put("hasMore", hasMore).put("wallSeen", wallSeen)

    companion object {
        /** Тип сообщения страницы (поле type). */
        const val TYPE = "owner"
        private const val MAX_POSTS = 300
        private const val PREFETCH_POSTS = 20

        fun fromJson(json: JSONObject): OwnerPayload? {
            val ownerId = json.optLong("ownerId")
            if (ownerId == 0L) return null
            val key = json.optString("key").take(200)
            if (!key.startsWith("/")) return null
            val posts = ArrayList<WallPost>()
            val seen = HashSet<String>()
            val array = json.optJSONArray("posts")
            if (array != null) {
                for (i in 0 until minOf(array.length(), MAX_POSTS)) {
                    val post = array.optJSONObject(i)?.let { WallPost.fromJson(it) } ?: continue
                    if (seen.add(post.key)) posts += post
                }
            }
            return OwnerPayload(
                snapshot = json.optBoolean("snapshot"),
                needsWallHead = ownerId < 0 && json.optBoolean("wallSeen") && json.optBoolean("needsWallHead"),
                key = key,
                ownerId = ownerId,
                header = json.optJSONObject("header")?.let(OwnerHeader::fromJson),
                posts = posts,
                hasMore = json.optBoolean("hasMore"),
                wallSeen = json.optBoolean("wallSeen"),
            )
        }
    }
}

internal data class OwnerRefreshResult(val operation: String, val key: String, val reason: String, val payload: OwnerPayload?) {
    companion object {
        const val TYPE = "ownerRefresh"

        fun fromJson(json: JSONObject): OwnerRefreshResult? {
            val operation = json.optString("operation")
            val key = safePath(json.optString("key"))
            if (!operation.matches(Regex("[0-9]{1,16}")) || key.isEmpty()) return null
            val payload = json.optJSONObject("payload")?.let(OwnerPayload::fromJson)
                ?.takeIf { it.wallSeen && !it.snapshot && !it.needsWallHead && OwnerPath.sameKey(key, it.key) }
            return OwnerRefreshResult(operation, key, json.optString("reason"), payload)
        }
    }
}

/**
 * Итог лайка записи (сообщение type = "like"): [ok] — сервер принял, [liked] и [likes] — новое состояние;
 * [reason] — причина отказа для журнала.
 */
data class LikeResult(val key: String, val ok: Boolean, val liked: Boolean, val likes: Int, val reason: String) {
    companion object {
        const val TYPE = "like"

        fun fromJson(o: JSONObject): LikeResult? {
            val key = o.optString("key").takeIf { VkIds.isMediaId(it) } ?: return null
            return LikeResult(key, o.optBoolean("ok"), o.optBoolean("liked"), o.optInt("likes").coerceAtLeast(0), o.optString("reason").take(200))
        }
    }
}

/** Адреса потоков треков записи (ответ страницы на «играть трек записи»): нажатый — [wanted]. */
data class PostAudio(val wanted: String, val items: List<Item>, val reason: String) {
    data class Item(val id: String, val url: String, val title: String, val artist: String, val durationSec: Int)

    companion object {
        const val TYPE = "postaudio"

        fun fromJson(o: JSONObject): PostAudio? {
            val items = list(o.optJSONArray("items")) { i ->
                val url = com.example.vkweb.messages.ReaderPayload.audioUrlOrEmpty(i.optString("url"))
                val id = i.optString("id").takeIf { VkIds.isMediaId(it) }
                if (url.isEmpty() || id == null) null
                else Item(id, url, i.optString("title").trim().take(200), i.optString("artist").trim().take(200), i.optInt("duration").coerceIn(0, 86_400))
            }.take(100)
            return PostAudio(o.optString("wanted").take(40), items, o.optString("reason").take(200))
        }
    }
}

/** Адрес на сайте из данных страницы: только путь вида /wall1_2 (без схемы и хоста). */
internal fun safePath(raw: String): String =
    raw.takeIf { it.startsWith("/") && !it.startsWith("//") && it.length <= 300 && it.none { c -> c.isWhitespace() } }.orEmpty()

private fun <T> list(array: JSONArray?, parse: (JSONObject) -> T?): List<T> {
    if (array == null) return emptyList()
    val result = ArrayList<T>()
    for (i in 0 until minOf(array.length(), 20)) array.optJSONObject(i)?.let(parse)?.let { result += it }
    return result
}
