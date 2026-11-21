package com.example.vkweb.ui

import com.example.vkweb.config.Tab
import kotlin.math.abs

/**
 * Горизонтальный свайп по своему экрану: с 4.19 — к соседней вкладке внутреннего меню экрана (вкладки клипов,
 * разделы VK Видео, «Музыка / Плейлисты», папки сообщений — [TabSwipe]); в 4.13 был переход между разделами меню
 * ([target]). Влево — следующая, вправо — предыдущая. Здесь только решения, без Android: проверяется [SectionSwipeTest]; касания — [SectionSwipeDetector].
 */
object SectionSwipe {

    enum class Direction { NEXT, PREVIOUS }

    /** Во сколько раз движение по горизонтали должно быть больше вертикального: иначе это прокрутка списка. */
    private const val HORIZONTAL_RATIO = 2f

    /**
     * Свайп ли это: палец ушёл на [dx]/[dy] за [durationMs]. Не короче [minDistancePx], заметно горизонтальный и быстрый
     * (дольше [maxDurationMs] — человек что-то тянет или читает, а не листает разделы).
     */
    fun direction(dx: Float, dy: Float, durationMs: Long, minDistancePx: Float, maxDurationMs: Long = MAX_DURATION_MS): Direction? {
        if (abs(dx) < minDistancePx || abs(dx) < abs(dy) * HORIZONTAL_RATIO || durationMs > maxDurationMs) return null
        return if (dx < 0) Direction.NEXT else Direction.PREVIOUS
    }

    /** Соседний раздел [current] в порядке меню [order]; за краем — нет (по кругу не листается). */
    fun target(current: Tab?, direction: Direction, order: List<Tab> = Tab.entries): Tab? {
        val index = order.indexOf(current ?: return null)
        if (index < 0) return null
        return order.getOrNull(if (direction == Direction.NEXT) index + 1 else index - 1)
    }

    /**
     * Соседняя вкладка полосы из [count] вкладок (4.19), отмечена [current] (-1 — никакая: тогда первая). За краем — нет.
     */
    fun neighbor(current: Int, count: Int, direction: Direction): Int? {
        if (count < 2) return null
        if (current !in 0 until count) return 0
        val next = if (direction == Direction.NEXT) current + 1 else current - 1
        return next.takeIf { it in 0 until count }
    }

    const val MAX_DURATION_MS = 600L
}
