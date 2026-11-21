package com.example.vkweb.sync

import com.example.vkweb.messages.DialogEntry
import org.json.JSONException
import org.json.JSONObject

/**
 * Уведомления отдельного диалога на этом телефоне (4.47). Настройка VK («выключить уведомления» беседы, [DialogEntry.muted])
 * меняет аккаунт и нужна сеть; эта — только приложение, работает без сети и не видна на других устройствах.
 */
enum class NotifyMode(val key: String) {
    /** Как в VK: сообщаем со звуком, если беседа в VK не заглушена. */
    DEFAULT("default"),
    /** Всегда со звуком — даже если в VK уведомления беседы выключены. */
    ALWAYS("always"),
    /** В шторке, но без звука и вибрации (отдельный тихий канал). */
    SILENT("silent"),
    /** Не сообщать совсем. */
    OFF("off");

    companion object {
        fun of(key: String?): NotifyMode = entries.firstOrNull { it.key == key } ?: DEFAULT
    }
}

/** Настройка диалога. [title] — чтобы показать список настроенных в настройках приложения (хранится только локально). */
data class DialogNotify(
    val mode: NotifyMode = NotifyMode.DEFAULT,
    /** Не показывать текст сообщения в уведомлении — только имя. */
    val hideText: Boolean = false,
    val title: String = "",
) {
    /** Ничем не отличается от поведения по умолчанию — хранить незачем. */
    val isDefault: Boolean get() = mode == NotifyMode.DEFAULT && !hideText
}

/** Правила уведомлений по диалогам. Без зависимостей от Android: проверяется обычными тестами. */
object DialogNotifications {

    private const val MAX_ENTRIES = 500
    private const val MAX_TITLE = 200

    /** Попадает ли непрочитанный диалог в уведомление. */
    fun shows(dialog: DialogEntry, rule: DialogNotify?): Boolean = when (rule?.mode ?: NotifyMode.DEFAULT) {
        NotifyMode.OFF -> false
        NotifyMode.ALWAYS, NotifyMode.SILENT -> true
        NotifyMode.DEFAULT -> !dialog.muted
    }

    /** Новое в этом диалоге звучит (громкий канал), а не появляется молча. */
    fun sounds(dialog: DialogEntry, rule: DialogNotify?): Boolean = when (rule?.mode ?: NotifyMode.DEFAULT) {
        NotifyMode.ALWAYS -> true
        NotifyMode.DEFAULT -> !dialog.muted
        NotifyMode.SILENT, NotifyMode.OFF -> false
    }

    /** Диалог «не зовёт»: в списке у него приглушённый счётчик. */
    fun quiet(dialog: DialogEntry, rule: DialogNotify?): Boolean = !sounds(dialog, rule)

    /** Уведомление звучит, если звучит хотя бы один из диалогов, где появилось новое. */
    fun alerts(fresh: List<DialogEntry>, rules: Map<String, DialogNotify>): Boolean =
        fresh.any { sounds(it, rules[it.peerId]) }

    /** Показывать ли текст сообщения диалога: общая настройка [preview] и своя у диалога. */
    fun showsText(peer: String, rules: Map<String, DialogNotify>, preview: Boolean): Boolean =
        preview && rules[peer]?.hideText != true

    /**
     * Число на «Сообщениях» нижней панели (4.50): непрочитанные диалоги, о которых сообщают уведомления (без выключенных
     * в VK и своей настройкой «Не уведомлять»; «Без звука» — считается: в шторке он есть).
     */
    fun badgeCount(dialogs: List<DialogEntry>, rules: Map<String, DialogNotify>): Int =
        dialogs.count { it.unread && shows(it, rules[it.peerId]) }

    /** Правила после изменения одного диалога: настройка по умолчанию не хранится. */
    fun with(rules: Map<String, DialogNotify>, peer: String, rule: DialogNotify): Map<String, DialogNotify> {
        if (peer.isEmpty()) return rules
        if (rule.isDefault) return rules - peer
        if (peer !in rules && rules.size >= MAX_ENTRIES) return rules
        return rules + (peer to rule.copy(title = rule.title.take(MAX_TITLE)))
    }

    fun toJson(rules: Map<String, DialogNotify>): String {
        val o = JSONObject()
        rules.forEach { (peer, r) ->
            o.put(peer, JSONObject().put("m", r.mode.key).put("h", r.hideText).put("t", r.title))
        }
        return o.toString()
    }

    /** Повреждённое = пустое: хуже всего, что диалоги вернутся к «как в VK». */
    fun fromJson(raw: String?): Map<String, DialogNotify> {
        if (raw.isNullOrBlank()) return emptyMap()
        return try {
            val o = JSONObject(raw)
            val out = LinkedHashMap<String, DialogNotify>()
            val keys = o.keys()
            while (keys.hasNext() && out.size < MAX_ENTRIES) {
                val peer = keys.next()
                val r = o.optJSONObject(peer) ?: continue
                val rule = DialogNotify(NotifyMode.of(r.optString("m")), r.optBoolean("h"), r.optString("t").take(MAX_TITLE))
                if (peer.isNotEmpty() && !rule.isDefault) out[peer] = rule
            }
            out
        } catch (e: JSONException) {
            emptyMap()
        }
    }
}
