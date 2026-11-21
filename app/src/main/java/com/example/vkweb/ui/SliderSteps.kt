package com.example.vkweb.ui

import kotlin.math.roundToInt

/**
 * Значение ползунка с шагом (4.24): в пределах [min]…[max], к ближайшему шагу от [min]. Без Android — проверяется
 * обычными тестами; ползунок настроек — [com.example.vkweb.settings.StepSeekBarPreference].
 */
object SliderSteps {

    fun snap(value: Int, min: Int, max: Int, step: Int): Int {
        require(max >= min) { "max < min" }
        if (step <= 1) return value.coerceIn(min, max)
        val v = value.coerceIn(min, max)
        val nearest = (min + ((v - min).toDouble() / step).roundToInt() * step).coerceAtMost(max)
        // Диапазон не делится на шаг (75…100 по 10): край max — тоже допустимое значение, и к нему может быть ближе.
        return if (max - v < kotlin.math.abs(v - nearest)) max else nearest
    }
}
