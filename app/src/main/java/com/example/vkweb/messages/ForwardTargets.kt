package com.example.vkweb.messages

import com.example.vkweb.config.VkIds

/**
 * Куда переслать: переписки из списка диалогов, и первой всегда — своя («Избранное», номер своей страницы), даже если
 * её нет в загруженном списке. Без зависимостей от Android: проверяется обычными тестами.
 */
object ForwardTargets {

    /** Кто получатель (3.91): от этого — подпись под именем в списке пересылки. */
    enum class Kind { SELF, USER, CHAT, GROUP }

    data class Target(
        val peerId: String,
        val title: String,
        val isSelf: Boolean,
        /** Фото (https VK) или пусто — кружок с инициалами. */
        val avatar: String = "",
        val online: Boolean = false,
        val kind: Kind = if (isSelf) Kind.SELF else kindOf(peerId),
    )

    /** Беседа — номер от 2 000 000 000, сообщество — отрицательный, иначе человек. */
    fun kindOf(peerId: String): Kind {
        val n = peerId.toLongOrNull() ?: return Kind.USER
        return when {
            VkIds.isChat(n) -> Kind.CHAT
            n < 0 -> Kind.GROUP
            else -> Kind.USER
        }
    }

    /** Поиск по имени: без учёта регистра и «ё»; пустой запрос — все. */
    fun filter(targets: List<Target>, query: String): List<Target> {
        val q = normalize(query)
        if (q.isEmpty()) return targets
        return targets.filter { normalize(it.title).contains(q) }
    }

    private fun normalize(s: String) = s.trim().lowercase().replace('ё', 'е')

    /**
     * Что пересылается — одной строкой для шапки выбора (3.91): текст, иначе что во вложениях («Фото · 3»,
     * «Голосовое сообщение»…). [labels] — подписи вложений (из ресурсов).
     */
    fun preview(m: ChatMessage, labels: Labels): String {
        val text = m.text.trim().replace(Regex("\\s+"), " ")
        if (text.isNotEmpty()) return text.take(PREVIEW_MAX)
        return when {
            m.photos.isNotEmpty() -> labels.photo + if (m.photos.size > 1) " · ${m.photos.size}" else ""
            m.videos.isNotEmpty() -> labels.video + if (m.videos.size > 1) " · ${m.videos.size}" else ""
            m.voices.isNotEmpty() -> labels.voice
            m.cards.isNotEmpty() -> m.cards.first().title.ifEmpty { labels.attachment }
            m.forwards.isNotEmpty() -> labels.forwards
            else -> labels.attachment
        }
    }

    data class Labels(val photo: String, val video: String, val voice: String, val attachment: String, val forwards: String)

    private const val PREVIEW_MAX = 300

    private val USER_ID = Regex("^[1-9]\\d{0,11}$")

    /** Номер своей страницы из ответа страницы («30804332», "\"30804332\"", "null") — или пусто. */
    fun selfId(raw: String?): String = raw?.trim()?.trim('"')?.takeIf { USER_ID.matches(it) }.orEmpty()

    /**
     * [dialogs] — по порядку списка; [self] — свой номер (пусто — неизвестен: тогда своей переписки сверху нет);
     * [selfTitle] — подпись своей. Повторы убираются, переписок — не больше [max].
     */
    fun build(dialogs: List<DialogEntry>, self: String, selfTitle: String, max: Int = MAX): List<Target> {
        val me = selfId(self)
        val rest = dialogs.asSequence()
            .filter { it.peerId.isNotEmpty() && it.peerId != me }
            .distinctBy { it.peerId }
            .take(max)
            .map { Target(it.peerId, it.title, false, avatar = it.avatar, online = it.online) }
            .toList()
        return if (me.isEmpty()) rest else listOf(Target(me, selfTitle, true)) + rest
    }

    const val MAX = 300
}
