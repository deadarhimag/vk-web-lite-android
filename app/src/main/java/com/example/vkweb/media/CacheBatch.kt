package com.example.vkweb.media

/**
 * Одна просьба «в кэш» (4.97): один трек из меню или весь список. Считает, чем кончился каждый трек, и один раз
 * сообщает итог ([onFinished]) — когда исход известен у всех [total]. Потокобезопасно; без Android.
 */
class CacheBatch(
    val total: Int,
    /** Подпись для итога: название трека (один трек) или пусто. */
    val label: String = "",
    private val onFinished: (CacheBatch) -> Unit,
) {
    var saved = 0
        private set
    var already = 0
        private set
    var failed = 0
        private set
    var cancelled = 0
        private set
    private var finished = false

    /** Сохранён сейчас. */
    fun onSaved() = count { saved++ }

    /** Уже был в кэше (или его как раз сохраняет другая задача). */
    fun onAlready() = count { already++ }

    /** Не вышло: нет адреса потока, ошибка сети или сборки mp3. */
    fun onFailed(n: Int = 1) = count { failed += n }

    /** Снят с очереди («Остановить кэширование»). */
    fun onCancelled() = count { cancelled++ }

    val done: Int @Synchronized get() = saved + already + failed + cancelled
    val isFinished: Boolean @Synchronized get() = finished

    private fun count(change: () -> Unit) {
        val report = synchronized(this) {
            if (finished) return
            change()
            finished = saved + already + failed + cancelled >= total
            finished
        }
        if (report) onFinished(this)
    }

    init {
        if (total <= 0) {
            finished = true
            onFinished(this)
        }
    }
}
