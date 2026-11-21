package com.example.vkweb.ui

/**
 * Защита от бесконечной догрузки (4.0, по отчёту с телефона): плейлист на 268 треков просил «ещё» 38 раз за 43 секунды —
 * страница каждый раз присылала те же 100 треков и «есть ещё», список не рос, а конец оставался на виду. Теперь после
 * [maxStalls] догрузок подряд без прибавки список считается дочитанным, пока не придёт новый список или он не вырастет.
 * Без зависимостей от Android: проверяется обычными тестами.
 */
class LoadMoreGuard(private val maxStalls: Int = MAX_STALLS) {

    private var requestedAt: Int? = null
    private var stalls = 0

    /** Догрузки остановлены. */
    val stopped: Boolean get() = stalls >= maxStalls

    /** Попросили следующую порцию, когда показано [shown]. */
    fun onRequested(shown: Int) {
        requestedAt = shown
    }

    /** Пришла порция; показано теперь [shown]. */
    fun onLoaded(shown: Int) {
        val before = requestedAt ?: return
        requestedAt = null
        stalls = if (shown > before) 0 else stalls + 1
    }

    fun reset() {
        requestedAt = null
        stalls = 0
    }

    companion object {
        const val MAX_STALLS = 3
    }
}
