package com.example.vkweb.config

/** Режим темы. Чистая логика без Android — покрыта тестами. */
enum class ThemeMode {
    DARK, LIGHT, SYSTEM,

    /** Тёмная с чисто чёрным фоном (4.0): на OLED-экранах чёрные точки не светятся — меньше расход батареи. */
    BLACK;

    /** Итоговая тема с учётом системной настройки. */
    fun isDark(systemIsDark: Boolean): Boolean = when (this) {
        DARK, BLACK -> true
        LIGHT -> false
        SYSTEM -> systemIsDark
    }

    /** Чёрный фон поверх тёмной темы. */
    val isBlack: Boolean get() = this == BLACK

    companion object {
        /** По умолчанию — тёмная. */
        val DEFAULT = DARK

        fun fromName(name: String?): ThemeMode = entries.firstOrNull { it.name == name } ?: DEFAULT
    }
}
