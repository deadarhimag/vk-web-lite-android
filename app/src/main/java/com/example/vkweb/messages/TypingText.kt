package com.example.vkweb.messages

/**
 * Подпись «печатает…» в шапке переписки (4.60). Без зависимостей от Android: проверяется обычными тестами; текст —
 * из ресурсов по [Kind].
 */
object TypingText {

    enum class Kind {
        /** Личная переписка: «печатает…». */
        PRIVATE,
        /** Беседа, один известный: «Анна печатает…». */
        ONE,
        /** Беседа, двое: «Анна и Борис печатают…». */
        TWO,
        /** Беседа, трое и больше или неизвестно кто: «Печатают…». */
        SOMEONE,
    }

    data class Label(val kind: Kind, val names: List<String>, val voice: Boolean)

    /** Сколько держать подпись после события: VK шлёт его раз в несколько секунд, пока человек печатает. */
    const val SHOW_MS = 6_000L

    fun of(isChat: Boolean, names: List<String>, voice: Boolean): Label {
        val known = names.map { it.trim() }.filter { it.isNotEmpty() }.map(::firstName).distinct()
        val kind = when {
            !isChat -> Kind.PRIVATE
            known.size == 1 -> Kind.ONE
            known.size == 2 -> Kind.TWO
            else -> Kind.SOMEONE
        }
        return Label(kind, if (kind == Kind.ONE || kind == Kind.TWO) known else emptyList(), voice)
    }

    /** В шапке узко: «Анна Каренина» → «Анна». */
    private fun firstName(name: String): String = name.split(Regex("\\s+")).first()
}
