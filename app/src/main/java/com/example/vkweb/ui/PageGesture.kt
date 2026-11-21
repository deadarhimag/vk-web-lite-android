package com.example.vkweb.ui

import kotlin.math.abs

/**
 * Листает ли жест фото. Без зависимостей от Android: проверяется обычными тестами.
 * Листают либо быстрый бросок, либо медленное, но длинное движение пальца (за четверть ширины экрана).
 * У увеличенного фото палец двигает саму картинку, поэтому длинное движение листанием не считается — только бросок
 * (и только когда картинка уже упёрлась в край: это проверяет [ZoomImageView]).
 */
object PageGesture {

    /** Следующее фото (палец ушёл влево). */
    const val NEXT = 1

    /** Предыдущее фото (палец ушёл вправо). */
    const val PREVIOUS = -1

    const val NONE = 0

    /** Движение должно идти больше вбок, чем вверх-вниз, иначе это прокрутка или смахивание. */
    private const val HORIZONTAL_RATIO = 1.5f
    private const val DISTANCE_FRACTION = 0.25f
    private const val FLING_DP_PER_SECOND = 500f
    private const val MIN_FLING_DISTANCE_DP = 32f

    /**
     * @param dx смещение пальца по горизонтали за жест, px (вправо — плюс)
     * @param dy смещение по вертикали, px
     * @param vx скорость броска по горизонтали, px/с; null — палец отпущен без броска
     * @param viewWidth ширина экрана, px
     * @param zoomed фото увеличено
     * @param density плотность экрана: переводит dp в px
     * @return [NEXT], [PREVIOUS] или [NONE]
     */
    fun direction(dx: Float, dy: Float, vx: Float?, viewWidth: Float, zoomed: Boolean, density: Float): Int {
        if (abs(dx) < abs(dy) * HORIZONTAL_RATIO) return NONE
        val byFling = vx != null &&
            abs(vx) >= FLING_DP_PER_SECOND * density &&
            abs(dx) >= MIN_FLING_DISTANCE_DP * density &&
            (vx < 0) == (dx < 0)
        val byDistance = !zoomed && abs(dx) >= viewWidth * DISTANCE_FRACTION
        if (!byFling && !byDistance) return NONE
        return if (dx < 0) NEXT else PREVIOUS
    }
}
