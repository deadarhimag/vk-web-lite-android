package com.example.vkweb.ui

import java.util.Locale

/**
 * Время и позиция для плеера. Без зависимостей от Android: проверяется обычными тестами.
 *
 * 4.65 (код ревью 4.64): единственное место, где время пишется как «м:сс» / «ч:мм:сс». Раньше таких функций было пять
 * (плеер, голосовые, видео, треки, карточки в переписке) и три «позиция / длина»; часть без Locale.ROOT — на арабской
 * или хинди-локали цифры выходили не латинские.
 */
object PlayerFormat {

    /** «0:07», «3:07», «1:02:03»; отрицательное и неизвестное — «0:00». */
    fun time(seconds: Double): String = timeSec(if (seconds.isFinite() && seconds > 0) seconds.toLong() else 0L)

    /** То же для целых секунд. */
    fun timeSec(seconds: Long): String {
        val total = seconds.coerceAtLeast(0)
        val hours = total / 3600
        val minutes = total % 3600 / 60
        val secs = total % 60
        return if (hours > 0) String.format(Locale.ROOT, "%d:%02d:%02d", hours, minutes, secs)
        else String.format(Locale.ROOT, "%d:%02d", minutes, secs)
    }

    /** То же для миллисекунд (доли секунды отбрасываются). */
    fun timeMs(ms: Long): String = timeSec(ms.coerceAtLeast(0) / 1000)

    /** «1:15 / 3:40» — позиция и длина. */
    fun clockMs(positionMs: Long, totalMs: Long): String = timeMs(positionMs) + " / " + timeMs(totalMs)

    /**
     * Позиция сейчас. Страница сообщает её только при событиях (запуск, пауза, перемотка); пока трек играет,
     * она досчитывается по прошедшему времени — так полоса движется, а страница не шлёт сообщения каждую секунду.
     */
    fun position(playing: Boolean, position: Double, duration: Double, receivedAtMs: Long, nowMs: Long): Double {
        if (!playing) return position
        val current = position + (nowMs - receivedAtMs).coerceAtLeast(0) / 1000.0
        return if (duration > 0) minOf(current, duration) else current
    }
}
