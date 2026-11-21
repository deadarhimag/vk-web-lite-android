package com.example.vkweb.messages

import android.os.Handler
import com.example.vkweb.diag.AppLog
import com.example.vkweb.diag.LogTag

/**
 * Автообновление экрана сообщений (с 4.74 — отдельно от MessagesActivity).
 *
 *  - Пока экран открыт, время от времени просим у страницы свежее: сервер отдаёт новое, оно сливается с показанным без
 *    скачков. Тихо: без индикатора и без запасного показа сайта. Если обновления ничего не приносят, они реже
 *    ([RefreshBackoff]); действие человека — снова часто.
 *  - Свежая история после отправки (4.26): одна на серию. «Отправлено» и «доставлено» одного сообщения, сообщения очереди
 *    и ответ приходят подряд — раньше каждое ставило свой запрос к странице (очередь из 5 — до 10 запросов за пару
 *    секунд), и они срабатывали уже после ухода с экрана. Теперь запрос один, через [AFTER_SEND_MS] после последнего
 *    события. Ушли с экрана раньше — он не теряется: [stop] снимает его, [onStart] ставит снова.
 */
class ChatAutoRefresh(
    private val handler: Handler,
    private val isVisible: () -> Boolean,
    /** Частота из настроек, мс. */
    private val baseMs: () -> Long,
    /** Обновлять сейчас можно: свой экран, есть сеть, ничего не отправляется и не играет на странице. */
    private val canRefresh: () -> Boolean,
    /** Для журнала: какой экран открыт. */
    private val screenName: () -> String,
    /** Попросить у страницы свежее. */
    private val refresh: () -> Unit,
    /** Показан сайт VK вместо своего экрана: историю у страницы не просим. */
    private val onSite: () -> Boolean,
    private val requestHistory: () -> Unit,
) {
    /** Пауза автообновления: растёт, пока обновления ничего не приносят. */
    private var backoff = RefreshBackoff(0)

    private val tick = Runnable { onTick() }

    private val afterSend = Runnable {
        afterSendPending = false
        if (!onSite()) requestHistory()
    }
    private var afterSendPending = false

    /** Человек что-то сделал (открыл переписку, отправил, вернулся на экран): обновлять снова часто. */
    fun onUserActivity() {
        backoff.onUserActivity()
        schedule()
    }

    /** Обновление принесло новое: пауза не растёт. */
    fun onChange() = backoff.onChange()

    /** Экран снова виден: обновлять с частотой из настроек; свежая история после отправки, которую не успели запросить. */
    fun onStart() {
        onUserActivity()
        if (afterSendPending) refreshAfterSend()
    }

    /** Экран ушёл: обновления не идут (флаг «после отправки» остаётся — повторится в [onStart]). */
    fun stop() {
        handler.removeCallbacks(tick)
        handler.removeCallbacks(afterSend)
    }

    /** Свежая история вскоре после отправки (одна на серию событий). */
    fun refreshAfterSend() {
        afterSendPending = true
        handler.removeCallbacks(afterSend)
        handler.postDelayed(afterSend, AFTER_SEND_MS)
    }

    private fun schedule() {
        handler.removeCallbacks(tick)
        if (!isVisible()) return
        val base = baseMs()
        // Настройку могли изменить: пауза считается от новой.
        if (backoff.baseMs != base) backoff = RefreshBackoff(base)
        backoff.nextDelayMs()?.let { handler.postDelayed(tick, it) }
    }

    private fun onTick() {
        if (canRefresh()) {
            backoff.onReload()
            AppLog.d(LogTag.MESSAGES, "автообновление", "экран" to screenName(), "без нового подряд" to backoff.idleStreak.takeIf { it > 0 })
            refresh()
        }
        schedule()
    }

    private companion object {
        const val AFTER_SEND_MS = 500L
    }
}
