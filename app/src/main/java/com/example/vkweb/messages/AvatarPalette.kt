package com.example.vkweb.messages

/**
 * Цвета и буквы кружка-заглушки вместо аватара ([AvatarBadge]). Без зависимостей от Android: проверяется тестами.
 *
 * 3.94: цвета приглушены. Прежние (светло-зелёный, бирюзовый, оранжевый) с белыми буквами давали контраст около 2:1 —
 * буквы терялись, особенно в светлой теме. Нынешние дают не меньше 4.5:1 с белым (норма WCAG для текста) и спокойно
 * смотрятся на тёмном фоне.
 */
object AvatarPalette {

    val COLORS = intArrayOf(
        0xFF3B66C4.toInt(), // синий
        0xFF2E7D3E.toInt(), // зелёный
        0xFFAD5414.toInt(), // оранжевый
        0xFF8445A8.toInt(), // фиолетовый
        0xFF19788A.toInt(), // бирюзовый
        0xFFAD3F64.toInt(), // малиновый
    )

    /**
     * Имя автора над сообщениями беседы (4.53) — тем же цветом, что его кружок ([COLORS], тот же номер по имени), но
     * подобранным под фон входящего пузыря: на светлом — темнее, на тёмном и чёрном — светлее. Контраст с фоном пузыря
     * всех тем — не меньше 4,5:1 (AvatarPaletteTest).
     */
    val NAME_COLORS_LIGHT = intArrayOf(
        0xFF3B66C4.toInt(), // синий
        0xFF2A7239.toInt(), // зелёный
        0xFF9E4C12.toInt(), // оранжевый
        0xFF8445A8.toInt(), // фиолетовый
        0xFF166C7C.toInt(), // бирюзовый
        0xFFAD3F64.toInt(), // малиновый
    )
    val NAME_COLORS_DARK = intArrayOf(
        0xFF8FB0F5.toInt(),
        0xFF7CC98A.toInt(),
        0xFFF0A066.toInt(),
        0xFFC99BE3.toInt(),
        0xFF6CC3D3.toInt(),
        0xFFEE8FAE.toInt(),
    )

    /** Цвет стабилен для одного собеседника: по имени. */
    fun colorFor(title: String): Int = COLORS[indexOf(title)]

    /** Цвет имени автора: [darkBackground] — пузырь тёмной или чёрной темы. */
    fun nameColorFor(name: String, darkBackground: Boolean): Int =
        (if (darkBackground) NAME_COLORS_DARK else NAME_COLORS_LIGHT)[indexOf(name)]

    /** Тёмный ли фон (по яркости): так видно тему, в том числе с цветами обоев. */
    fun isDark(color: Int): Boolean = luminance(color) < 0.18

    private fun indexOf(title: String): Int = (title.ifEmpty { "?" }.hashCode() and Int.MAX_VALUE) % COLORS.size

    /** Одна-две заглавные буквы: «Иван Петров» → «ИП», «Беседа» → «Б», пусто → «?». */
    fun initials(title: String): String {
        val parts = title.trim().split(Regex("\\s+")).filter { it.isNotEmpty() }
        return when {
            parts.isEmpty() -> "?"
            parts.size == 1 -> parts[0].take(1).uppercase()
            else -> (parts[0].take(1) + parts[1].take(1)).uppercase()
        }
    }

    /** Контраст двух цветов по WCAG 2 (1…21). */
    fun contrast(a: Int, b: Int): Double {
        val la = luminance(a)
        val lb = luminance(b)
        return (maxOf(la, lb) + 0.05) / (minOf(la, lb) + 0.05)
    }

    private fun luminance(color: Int): Double {
        fun channel(shift: Int): Double {
            val c = ((color shr shift) and 0xFF) / 255.0
            return if (c <= 0.03928) c / 12.92 else Math.pow((c + 0.055) / 1.055, 2.4)
        }
        return 0.2126 * channel(16) + 0.7152 * channel(8) + 0.0722 * channel(0)
    }
}
