package com.example.vkweb.diag

/** Значения настройки «Журнал событий» и их соответствие уровням. Без зависимостей от Android. */
object LogSetting {
    const val OFF = "off"
    const val INFO = "info"
    const val DEBUG = "debug"

    /** В порядке показа в настройках. */
    val VALUES: List<String> = listOf(OFF, INFO, DEBUG)

    const val DEFAULT = INFO

    /** Минимальный уровень записей; null — журнал выключен. Неизвестное значение — как по умолчанию. */
    fun level(value: String?): LogLevel? = when (value) {
        OFF -> null
        DEBUG -> LogLevel.DEBUG
        else -> LogLevel.INFO
    }
}
