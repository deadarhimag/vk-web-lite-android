package com.example.vkweb.outbox

import com.example.vkweb.config.VkIds
import org.json.JSONArray
import org.json.JSONObject

/**
 * Где неотправленное сообщение на пути к собеседнику.
 *  - [QUEUED]: точно не отправляли (не было сети или страница не готова) — безопасно отправить сами;
 *  - [SENDING]: кнопка «Отправить» нажата, ждём, что сообщение появится в переписке;
 *  - [UNCONFIRMED]: нажали, но подтверждения нет. Могло уйти, а могло нет — сами повторять нельзя (будет дубль),
 *    решает человек; если сообщение появится в переписке, оно снимется само;
 *  - [FAILED]: отправить не получилось несколько раз подряд (страница не принимает ввод).
 */
enum class OutboxState { QUEUED, SENDING, UNCONFIRMED, FAILED }

/**
 * Сообщение, которое ещё не доставлено. Хранится на диске: переживает закрытие приложения.
  * [attempts] — сколько раз подряд страница не приняла ввод; [sentAt] — когда нажали «Отправить» (0 — не нажимали);
  * [retryAt] — не раньше этого момента пробовать снова;
 * [baseline] — номера уже существовавших своих сообщений с таким же текстом в момент нажатия: новое сообщение
 * с тем же текстом, которого в этом списке нет, и есть доставленное (так одинаковые «ок» не путаются).
 */
data class OutboxItem(
    val id: String,
    val peerId: String,
    val text: String,
    val createdAt: Long,
    val state: OutboxState = OutboxState.QUEUED,
    val attempts: Int = 0,
    val sentAt: Long = 0,
    val retryAt: Long = 0,
    val baseline: List<String> = emptyList(),
    /** Отложенная отправка (4.17): не раньше этого момента (мс); 0 — сразу. */
    val sendAt: Long = 0,
    /** Название переписки — для уведомления об отложенном (4.17). */
    val title: String = "",
    /** Серверные номера, уже использованные для подтверждения такого текста, повторно не считаются. */
    val confirmedThrough: Long = 0,
    /** Неоднозначные старые отправки требуют решения человека, а не угадывания по тексту. */
    val confirmationBlocked: Boolean = false,
) {
    /** Отложенное, время которого ещё не пришло. */
    fun isScheduled(now: Long): Boolean = sendAt > now
    fun toJson(): JSONObject = JSONObject()
        .put("id", id).put("peerId", peerId).put("text", text).put("createdAt", createdAt)
        .put("state", state.name).put("attempts", attempts).put("sentAt", sentAt).put("retryAt", retryAt)
        .put("baseline", JSONArray(baseline)).put("sendAt", sendAt).put("title", title)
        .put("confirmedThrough", confirmedThrough).put("confirmationBlocked", confirmationBlocked)

    companion object {
        private const val MAX_TEXT = 4000
        private const val MAX_BASELINE = 50
        private const val MAX_TITLE = 200

        /** null — запись повреждена или пуста: пропускается, остальные читаются. */
        fun fromJson(o: JSONObject): OutboxItem? {
            val id = o.optString("id").take(64)
            val peer = o.optString("peerId")
            val text = o.optString("text").take(MAX_TEXT)
            if (id.isEmpty() || !VkIds.isPeer(peer) || text.isBlank()) return null
            val baseline = ArrayList<String>()
            o.optJSONArray("baseline")?.let { array ->
                for (i in 0 until minOf(array.length(), MAX_BASELINE)) array.optString(i).takeIf { it.isNotEmpty() }?.let(baseline::add)
            }
            return OutboxItem(
                id = id,
                peerId = peer,
                text = text,
                createdAt = o.optLong("createdAt"),
                state = OutboxState.entries.firstOrNull { it.name == o.optString("state") } ?: OutboxState.QUEUED,
                attempts = o.optInt("attempts").coerceAtLeast(0),
                sentAt = o.optLong("sentAt").coerceAtLeast(0),
                retryAt = o.optLong("retryAt").coerceAtLeast(0),
                baseline = baseline,
                sendAt = o.optLong("sendAt").coerceAtLeast(0),
                title = o.optString("title").take(MAX_TITLE),
                confirmedThrough = o.optLong("confirmedThrough").coerceAtLeast(0),
                confirmationBlocked = o.optBoolean("confirmationBlocked"),
            )
        }

        fun listToJson(items: List<OutboxItem>): String =
            JSONArray().apply { items.forEach { put(it.toJson()) } }.toString()

        fun listFromJson(raw: String): List<OutboxItem> {
            val array = JSONArray(raw)
            val result = ArrayList<OutboxItem>(array.length())
            for (i in 0 until array.length()) array.optJSONObject(i)?.let(::fromJson)?.let(result::add)
            return result
        }
    }
}
