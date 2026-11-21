package com.example.vkweb.web

/**
 * Страница грузится заново по кругу (4.0, отчёты 13:11 и 16:19): m.vk.ru/audio, открытая полной загрузкой, начинала
 * загружаться снова каждую секунду — 41 раз за минуту, около 450 КБ за круг, свой экран музыки получал то 0, то 6
 * треков, а включить трек плейлиста было нельзя (в вёрстке пусто). Здесь — только распознавание: тот же путь начал
 * грузиться [MAX_STARTS] раз за [WINDOW_MS]. Без зависимостей от Android: проверяется обычными тестами.
 */
class ReloadLoop(private val maxStarts: Int = MAX_STARTS, private val windowMs: Long = WINDOW_MS) {

    private var path: String? = null
    private val starts = ArrayDeque<Long>()

    /** Началась загрузка [pathOf]; true — это круг. */
    fun onStart(pathOf: String?, nowMs: Long): Boolean {
        if (pathOf == null) return false
        if (pathOf != path) {
            path = pathOf
            starts.clear()
        }
        starts.addLast(nowMs)
        while (starts.isNotEmpty() && nowMs - starts.first() > windowMs) starts.removeFirst()
        if (starts.size < maxStarts) return false
        starts.clear()
        return true
    }

    companion object {
        const val MAX_STARTS = 4
        const val WINDOW_MS = 8_000L
    }
}
