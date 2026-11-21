package com.example.vkweb.media

/**
 * Скорость воспроизведения (4.0): голосовые — 1× → 1,5× → 2× по кругу, видео — ещё и 0,5×, 0,75×, 1,25×. Без
 * зависимостей от Android: проверяется обычными тестами.
 */
object PlaybackSpeed {

    val VOICE = floatArrayOf(1f, 1.5f, 2f)
    val VIDEO = floatArrayOf(0.5f, 0.75f, 1f, 1.25f, 1.5f, 2f)

    /** Следующая в круге [steps] после [current]; незнакомая — первая. */
    fun next(current: Float, steps: FloatArray = VOICE): Float {
        val at = steps.indexOfFirst { it == current }
        return if (at < 0) steps[0] else steps[(at + 1) % steps.size]
    }

    /** Ближайшая допустимая: сохранённое значение могло быть любым. */
    fun valid(value: Float, steps: FloatArray = VOICE): Float = steps.minByOrNull { kotlin.math.abs(it - value) } ?: 1f

    /** «1×», «1,5×», «0,75×». */
    fun label(speed: Float): String {
        val text = if (speed == speed.toInt().toFloat()) speed.toInt().toString() else speed.toString().replace('.', ',')
        return "$text×"
    }
}
