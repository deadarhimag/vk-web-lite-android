package com.example.vkweb.sync

import com.example.vkweb.messages.DialogEntry
import org.json.JSONException
import org.json.JSONObject

/**
 * Что уже было показано в уведомлении, чтобы не повторяться. [seeded] — первый снимок сделан: до него ничего не
 * показываем, иначе после установки или очистки данных пришла бы лавина уведомлений о давно прочитанном.
 * [notified] — по каждому непрочитанному диалогу подпись последнего сообщения, о котором уже сообщали.
 */
data class SyncState(
    val seeded: Boolean = false,
    val notified: Map<String, String> = emptyMap(),
) {
    fun toJson(): String = JSONObject()
        .put("seeded", seeded)
        .put("notified", JSONObject(notified))
        .toString()

    companion object {
        private const val MAX_ENTRIES = 300

        /** Повреждённое состояние = пустое: хуже всего, что один раз не уведомим (первый снимок без показа). */
        fun fromJson(raw: String?): SyncState {
            if (raw.isNullOrBlank()) return SyncState()
            return try {
                val o = JSONObject(raw)
                val map = HashMap<String, String>()
                o.optJSONObject("notified")?.let { n ->
                    val keys = n.keys()
                    while (keys.hasNext() && map.size < MAX_ENTRIES) {
                        val key = keys.next()
                        map[key] = n.optString(key)
                    }
                }
                SyncState(o.optBoolean("seeded"), map)
            } catch (e: JSONException) {
                SyncState()
            }
        }
    }
}

/**
 * Какие сообщения новые. Без зависимостей от Android: проверяется обычными тестами.
 *
 * VK не присылает число непрочитанных, только «диалог непрочитан» и текст последнего сообщения. Поэтому новое —
 * это непрочитанный диалог, у которого последнее сообщение отличается от того, о котором уже сообщали.
 * Время в подпись не входит: «12:30» со временем превращается во «вчера», и старое сообщение выглядело бы новым.
 * Ограничение: два подряд одинаковых сообщения («ок», «ок») во втором диалоге без прочтения не повторят уведомление.
 */
object NewMessages {

    /** Подпись последнего сообщения диалога. */
    fun signature(dialog: DialogEntry): String = dialog.snippet.trim().hashCode().toString(16)

    /**
     * @param unread все сейчас непрочитанные диалоги — из них составляется уведомление;
     * @param fresh те из них, о которых ещё не сообщали — повод показать уведомление;
     * @param state что запомнить на следующий раз.
     */
    class Result(val unread: List<DialogEntry>, val fresh: List<DialogEntry>, val state: SyncState)

    fun detect(
        current: List<DialogEntry>,
        previous: SyncState,
        /** Свои настройки диалогов (4.47); без них — как в VK. */
        rules: Map<String, DialogNotify> = emptyMap(),
    ): Result {
        val allUnread = current.filter { it.unread }
        // Беседы с отключёнными уведомлениями — как в VK: о них не сообщаем (3.72); с 4.47 — и по своей настройке диалога.
        val unread = allUnread.filter { DialogNotifications.shows(it, rules[it.peerId]) }
        // Подписи — всех непрочитанных, и молчащих тоже: включили уведомления диалога — о давнем сообщении в нём не сообщим.
        val signatures = allUnread.associate { it.peerId to signature(it) }
        // Первый снимок: запоминаем, ничего не показываем.
        if (!previous.seeded) return Result(unread, emptyList(), SyncState(seeded = true, notified = signatures))
        val fresh = unread.filter { previous.notified[it.peerId] != signature(it) }
        // Прочитанные из памяти уходят: если в диалоге потом появится новое, сообщим снова.
        return Result(unread, fresh, SyncState(seeded = true, notified = signatures))
    }
}
