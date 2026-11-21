package com.example.vkweb.messages

/**
 * Мелочи голосовых сообщений. Без зависимостей от Android: проверяется обычными тестами.
 */
object VoiceFormat {

    /** «0:07», «1:15», «1:02:30» ([com.example.vkweb.ui.PlayerFormat], 4.65). */
    fun time(ms: Int): String = com.example.vkweb.ui.PlayerFormat.timeMs(ms.toLong())

    /** Доля проигранного от 0 до [scale]: полоса не выходит за края, даже если длительность неизвестна. */
    fun progress(positionMs: Int, durationMs: Int, scale: Int = 1000): Int =
        if (durationMs <= 0) 0 else (positionMs.toLong().coerceIn(0, durationMs.toLong()) * scale / durationMs).toInt()
}
