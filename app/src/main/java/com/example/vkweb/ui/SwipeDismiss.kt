package com.example.vkweb.ui

import kotlin.math.abs

/**
 * Закрытие просмотра фото смахиванием вверх или вниз (3.90), как в галереях и приложении VK: фото едет за пальцем,
 * фон тускнеет; отпустили далеко или бросили быстро — просмотр закрывается, иначе фото возвращается на место.
 * Без зависимостей от Android: проверяется обычными тестами.
 */
object SwipeDismiss {
    /** Доля высоты экрана, после которой отпускание закрывает. */
    private const val DISTANCE_FRACTION = 0.18f
    /** Бросок, закрывающий и с малого расстояния, dp/с. */
    private const val FLING_DP_PER_SEC = 1000f
    /** Минимальный сдвиг для броска, dp: случайное подёргивание пальца не закрывает. */
    private const val FLING_MIN_DP = 24f
    /** Жест вертикальный, если по вертикали заметно больше, чем по горизонтали (иначе это листание фото). */
    private const val VERTICAL_RATIO = 1.5f

    /** Началось ли закрывающее смахивание: сдвиг больше [slopPx] и преимущественно вертикальный. */
    fun starts(dx: Float, dy: Float, slopPx: Float): Boolean = abs(dy) > slopPx && abs(dy) > abs(dx) * VERTICAL_RATIO

    /** Закрыть ли при отпускании: далеко или быстрым броском в ту же сторону. */
    fun shouldDismiss(dy: Float, velocityY: Float, heightPx: Int, density: Float): Boolean {
        if (heightPx <= 0) return false
        if (abs(dy) >= heightPx * DISTANCE_FRACTION) return true
        val fast = abs(velocityY) >= FLING_DP_PER_SEC * density && velocityY * dy > 0
        return fast && abs(dy) >= FLING_MIN_DP * density
    }

    /** Непрозрачность фона при сдвиге [dy]: от 1 до 0.2 к половине экрана. */
    fun backgroundAlpha(dy: Float, heightPx: Int): Float {
        if (heightPx <= 0) return 1f
        return (1f - abs(dy) / (heightPx / 2f) * 0.8f).coerceIn(0.2f, 1f)
    }
}
