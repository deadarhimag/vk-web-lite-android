package com.example.vkweb.video

import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * Громкость жестом по видео (4.9): палец вверх — громче, вниз — тише; с 4.16 — по правой половине видео. Без Android: проверяется [VideoVolumeGestureTest].
 *
 * Жест начинается, только если движение явно вертикальное (горизонтальное — не громкость) и палец опустили не у
 * верхнего и нижнего края: оттуда Android открывает шторку и системные кнопки.
 */
object VideoVolumeGesture {

    /** Насколько вертикальным должно быть движение: |dy| больше |dx| во столько раз. */
    private const val VERTICAL_RATIO = 1.5f

    /** Доля высоты видео, проход которой меняет громкость от нуля до максимума. */
    const val FULL_RANGE_FRACTION = 0.6f

    /**
     * Громкость — только правая половина видео (4.16, как в большинстве проигрывателей): левая остаётся свободной —
     * там движение пальцем ничего не меняет.
     */
    fun inVolumeZone(x: Float, width: Int): Boolean = width > 0 && x >= width / 2f

    /** Касание у края ([y] от верха) — системный жест, не громкость. */
    fun inSystemEdge(y: Float, height: Int, edgePx: Float): Boolean = y < edgePx || y > height - edgePx

    /** Движение на ([dx], [dy]) от точки касания — уже жест громкости (больше [slop] и вертикальное). */
    fun starts(dx: Float, dy: Float, slop: Float): Boolean = abs(dy) > slop && abs(dy) > abs(dx) * VERTICAL_RATIO

    /**
     * Громкость после сдвига пальца на [dy] (вниз — плюс) от начальной [startLevel] при высоте видео [height];
     * [max] — наибольшее значение громкости потока музыки на устройстве (бывает 15, 25, 150).
     */
    fun level(startLevel: Int, max: Int, dy: Float, height: Int): Int {
        if (max <= 0 || height <= 0) return startLevel.coerceAtLeast(0)
        val change = -dy / (height * FULL_RANGE_FRACTION) * max
        return (startLevel + change.roundToInt()).coerceIn(0, max)
    }

    /** Процент для подсказки на экране. */
    fun percent(level: Int, max: Int): Int = if (max <= 0) 0 else (level * 100f / max).roundToInt().coerceIn(0, 100)
}
