package com.example.vkweb.messages

/**
 * Когда в следующий раз перезагрузить страницу мессенджера, пока открыт экран сообщений.
 * Без зависимостей от Android: проверяется обычными тестами.
 *
 * Раньше страница перезагружалась целиком (документ, скрипты VK, запросы API) каждые [baseMs] — даже когда за
 * всё это время в переписке ничего не изменилось. Теперь пауза удваивается после каждой перезагрузки, которая
 * ничего нового не принесла, — до [maxFactor] раз от настройки. Новое сообщение, своё действие (отправка, другой
 * диалог, возврат на экран) возвращают паузу к настройке: пока идёт разговор, обновление такое же частое.
 * Новые сообщения между перезагрузками страница и так присылает сама (её постоянное соединение с VK).
 */
class RefreshBackoff(
    /** Пауза из настройки; 0 — автообновление выключено. */
    val baseMs: Long,
    private val maxFactor: Int = DEFAULT_MAX_FACTOR,
) {
    /** Сколько перезагрузок подряд не принесли нового. */
    var idleStreak: Int = 0
        private set

    /** Было ли новое с прошлой перезагрузки. */
    private var changed = false

    /** Пауза до следующей перезагрузки; null — автообновление выключено. */
    fun nextDelayMs(): Long? {
        if (baseMs <= 0) return null
        var factor = 1L
        repeat(idleStreak) { if (factor < maxFactor) factor *= 2 }
        return baseMs * factor.coerceAtMost(maxFactor.toLong())
    }

    /** Страница прислала данные, которых на экране не было. */
    fun onChange() {
        changed = true
    }

    /** Человек что-то сделал на экране: вероятно, ждёт ответа — обновлять снова часто. */
    fun onUserActivity() {
        idleStreak = 0
        changed = false
    }

    /** Пора перезагружать: подвести итог прошлого периода. */
    fun onReload() {
        idleStreak = if (changed) 0 else (idleStreak + 1).coerceAtMost(MAX_STREAK)
        changed = false
    }

    companion object {
        /** До 8 раз реже настройки: 60 с → не реже раза в 8 минут. */
        const val DEFAULT_MAX_FACTOR = 8
        private const val MAX_STREAK = 30
    }
}
