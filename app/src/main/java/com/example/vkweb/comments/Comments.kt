package com.example.vkweb.comments

import com.example.vkweb.config.SafeUrls
import com.example.vkweb.config.VkIds
import org.json.JSONObject

/**
 * Комментарии к видео и клипам своим окном (4.45). Раньше «комментарии» в своём проигрывателе открывали страницу клипа
 * в главном окне: поверх неё снова вставал проигрыватель, а «Назад» уводил в переписку. Теперь список приходит от
 * страницы (assets/vk_lite/47_comments.js: video.getComments от её имени), показывает его [CommentsSheet].
 * Без зависимостей от Android: проверяется обычными тестами.
 */
data class VideoComment(
    val id: Long,
    val author: String,
    val photoUrl: String,
    val text: String,
    /** Время в секундах (unix); 0 — неизвестно. */
    val date: Long,
    val likes: Int,
    /** Тип первого вложения («sticker», «photo»…), если оно есть; пусто — только текст. */
    val attachment: String,
    val stickerUrl: String,
    /** Всего ответов на комментарий (у VK — ветка). */
    val replies: Int,
    /** Первые ответы, пришедшие вместе с комментарием (4.46); вся ветка — отдельным запросом. */
    val thread: List<VideoComment> = emptyList(),
)

/** Порция комментариев (сообщение «comments»). [ok] = false — не получены, [reason] — почему (для журнала). */
data class CommentsPage(
    val videoId: String,
    val offset: Int,
    val total: Int,
    val items: List<VideoComment>,
    /** С какого места просить следующую порцию (удалённые комментарии страница не присылает, но место они занимают). */
    val next: Int,
    val hasMore: Boolean,
    val ok: Boolean,
    val reason: String,
    /** Ветка ответов на этот комментарий (4.46); 0 — комментарии к самому ролику. */
    val parent: Long = 0,
) {
    companion object {
        const val TYPE = "comments"
        private const val MAX_ITEMS = 200
        private const val MAX_TEXT = 4000
        private const val MAX_NAME = 200
        private const val MAX_THREAD = 10

        /** Комментарии из массива; [nested] — ответы внутри комментария (их ветки глубже не разбираются). */
        private fun parseItems(array: org.json.JSONArray?, limit: Int, nested: Boolean): List<VideoComment> {
            if (array == null) return emptyList()
            val items = ArrayList<VideoComment>()
            for (i in 0 until minOf(array.length(), limit)) {
                val o = array.optJSONObject(i) ?: continue
                val cid = o.optLong("id")
                if (cid <= 0) continue
                items += VideoComment(
                    id = cid,
                    author = o.optString("author").trim().take(MAX_NAME),
                    photoUrl = SafeUrls.image(o.optString("photo")),
                    text = o.optString("text").take(MAX_TEXT),
                    date = o.optLong("date").coerceAtLeast(0),
                    likes = o.optInt("likes").coerceIn(0, 100_000_000),
                    attachment = o.optString("attach").filter { it.isLetter() || it == '_' }.take(20),
                    stickerUrl = SafeUrls.image(o.optString("sticker")),
                    replies = o.optInt("replies").coerceIn(0, 100_000_000),
                    thread = if (nested) emptyList() else parseItems(o.optJSONArray("thread"), MAX_THREAD, nested = true),
                )
            }
            return items
        }

        fun fromJson(json: JSONObject): CommentsPage? {
            val id = json.optString("id")
            if (!VkIds.isCommentTarget(id)) return null
            val items = parseItems(json.optJSONArray("items"), MAX_ITEMS, nested = false)
            return CommentsPage(
                videoId = id,
                offset = json.optInt("offset").coerceAtLeast(0),
                parent = json.optLong("parent").coerceAtLeast(0),
                total = json.optInt("total").coerceAtLeast(0),
                items = items,
                next = json.optInt("next").coerceAtLeast(0),
                hasMore = json.optBoolean("more"),
                ok = json.optBoolean("ok"),
                reason = json.optString("reason").take(MAX_NAME),
            )
        }
    }
}

/** Ответ на «написать комментарий» (сообщение «commentSent»). */
data class CommentSent(val videoId: String, val ok: Boolean, val reason: String, val replyTo: Long = 0) {
    companion object {
        const val TYPE = "commentSent"

        fun fromJson(json: JSONObject): CommentSent? {
            val id = json.optString("id")
            if (!VkIds.isCommentTarget(id)) return null
            return CommentSent(id, json.optBoolean("ok"), json.optString("reason").take(200), json.optLong("replyTo").coerceAtLeast(0))
        }
    }
}

/** Какое видео или клип показывает страница: «владелец_номер» из её пути. */
object CommentTarget {
    /** Комментарии к записи стены: номер записи с буквой «w» (так страница отличает её от ролика); неверный номер - null. */
    fun ofPost(postKey: String): String? = ("w$postKey").takeIf(VkIds::isCommentTarget)

    private val PATH = Regex("^/(?:video|clip)(-?\\d{1,12}_\\d{1,12})(?:[_/?#].*)?$")

    /** `/video-1_2`, `/clip-1_2?access_key=…` → «-1_2»; иное — null. */
    fun fromPagePath(path: String?): String? = path?.let { PATH.find(it)?.groupValues?.get(1) }?.takeIf(VkIds::isMediaId)
}

/**
 * Связь окон проигрывателя (видео, клипы) со страницей главного окна: запросы уходят странице, ответы приходят
 * подписчику. Ставит главное окно ([request], [send]); нет его — комментарии недоступны, и проигрыватель открывает
 * страницу, как раньше.
 */
object CommentsHub {
    /** Попросить порцию комментариев к «владелец_номер» с [offset]; [parent] ≠ 0 — ветку ответов на него (4.46). */
    var request: ((id: String, offset: Int, parent: Long) -> Unit)? = null

    /** Написать комментарий; [replyTo] ≠ 0 — ответ на этот комментарий (4.46). */
    var send: ((id: String, text: String, replyTo: Long) -> Unit)? = null

    /** Подписчик (открытый список комментариев): одна порция или ответ на отправку. */
    var listener: Listener? = null

    interface Listener {
        fun onPage(page: CommentsPage)
        fun onSent(result: CommentSent)
    }

    val isAvailable: Boolean get() = request != null

    fun deliver(page: CommentsPage) { listener?.onPage(page) }

    fun deliver(result: CommentSent) { listener?.onSent(result) }
}
