package com.example.vkweb.settings

/**
 * Снимок настроек, чтобы понять, сменилось ли что-то за время экрана настроек (4.66, код ревью 4.64). Раньше сравнивалось
 * всё хранилище целиком, вместе с памятью приложения — меню уже открывали, срок жизни сессии VK, служебная отметка
 * PreferenceManager. Их запись (например, обновление сессии в главном окне) пересоздавала экран сообщений без причины:
 * терялись прокрутка и загруженная переписка. Без зависимостей от Android: проверяется тестами.
 */
object SettingsSnapshot {

    /** Не настройки, а память приложения: на вид экранов не влияют. */
    val STATE_KEYS: Set<String> = setOf(
        AppSettings.Keys.MENU_DISCOVERED,
        AppSettings.Keys.SESSION_LIFETIME,
        // Служебная отметка PreferenceManager.setDefaultValues.
        "_has_set_default_values",
    )

    fun of(all: Map<String, *>): String = all.filterKeys { it !in STATE_KEYS }.toSortedMap().toString()
}
