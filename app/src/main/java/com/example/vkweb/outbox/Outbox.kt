package com.example.vkweb.outbox

import android.content.Context
import android.os.Handler
import android.os.Looper
import com.example.vkweb.diag.AppLog
import com.example.vkweb.diag.LogTag
import com.example.vkweb.offline.OfflineStore
import com.example.vkweb.sync.OutboxAccess

/**
 * Очередь неотправленных сообщений: одна на весь процесс. Её используют и экран переписки, и фоновая проверка,
 * поэтому правила простые: всё из главного потока, каждое изменение записывается на диск, подписчикам сообщается.
 * Пока очередь читается с диска (первый раз за запуск), изменения ждут — ничего не теряется и не затирается.
 * Что делать с сообщениями (когда отправлять, как подтверждать) решает [OutboxLogic], здесь только хранение.
 */
class Outbox private constructor(context: Context) : OutboxAccess {

    private val store = OfflineStore.get(context)
    private val app = context.applicationContext
    private val main = Handler(Looper.getMainLooper())
    private val waiting = ArrayList<() -> Unit>()
    private val listeners = LinkedHashSet<() -> Unit>()
    private var items: List<OutboxItem> = emptyList()
    private var loaded = false
    private var loading = false
    private var pendingWrites = 0
    private var persisted = true
    private val persistenceWaiters = ArrayList<(Boolean) -> Unit>()
    private val receipts = HashMap<String, Long>()

    override fun receiptFloor(peerId: String): Long = receipts[peerId] ?: 0
    override fun recordReceipt(peerId: String, cmid: Long) {
        if (cmid > receiptFloor(peerId)) receipts[peerId] = cmid
    }

    override fun whenPersisted(done: (Boolean) -> Unit) = whenReady(onCancelled = { done(false) }) {
        val mine = generation
        if (pendingWrites == 0) done(persisted)
        else persistenceWaiters += { ok -> done(ok && mine == generation) }
    }

    private fun save() {
        pendingWrites++
        store.saveOutbox(items) { ok ->
            persisted = ok
            pendingWrites--
            if (pendingWrites == 0) {
                val callbacks = persistenceWaiters.toList()
                persistenceWaiters.clear()
                callbacks.forEach { it(ok) }
                if (ok) notifyChanged()
            }
        }
    }
    /** Растёт при [reset]: чтение, начатое до очистки, не должно вернуть удалённое. */
    private var generation = 0

    /** Выполнить [action], когда очередь прочитана с диска (сразу, если уже прочитана). Главный поток. */
    fun whenReady(onCancelled: () -> Unit = {}, action: () -> Unit) {
        if (loaded) {
            action()
            return
        }
        val requestedGeneration = generation
        waiting += { if (requestedGeneration == generation) action() else onCancelled() }
        if (loading) return
        loading = true
        val startedAt = generation
        store.loadOutbox { saved ->
            // Приложение могло закрыться между нажатием «Отправить» и подтверждением: такое сомнительно.
            // Если за время чтения очередь удалили из настроек, прочитанное устарело.
            items = if (startedAt == generation) OutboxLogic.restore(saved.orEmpty()) else items
            loaded = true
            loading = false
            if (items.isNotEmpty()) AppLog.i(LogTag.OUTBOX, "очередь прочитана", "сообщений" to items.size)
            val actions = ArrayList(waiting)
            waiting.clear()
            actions.forEach { it() }
            if (items.isNotEmpty()) notifyChanged()
        }
    }

    /** Текущее содержимое. До [whenReady] оно может быть ещё пустым. */
    override fun items(): List<OutboxItem> = items

    /** Изменить очередь целиком (см. [OutboxLogic]); запись на диск и уведомление подписчиков — здесь. */
    override fun update(transform: (List<OutboxItem>) -> List<OutboxItem>) {
        whenReady {
            val changed = transform(items)
            if (changed == items) return@whenReady
            val before = items
            items = changed
            // Отложенное стало сомнительным или не ушло (4.17) — сообщить: человек об этом не знает, его не было рядом.
            com.example.vkweb.sync.ScheduledSendNotifier.onStatesChanged(app, before, changed)
            save()
        }
    }

    /** Доставлено: об отложенных — уведомление «отправлено» (4.17) и подписчикам (4.18: отметить непрочитанной). */
    override fun delivered(items: List<OutboxItem>) {
        val scheduled = items.filter { it.sendAt > 0 }
        if (scheduled.isEmpty()) return
        com.example.vkweb.sync.ScheduledSendNotifier.onDelivered(app, scheduled)
        ArrayList(scheduledListeners).forEach { it(scheduled) }
    }

    private val scheduledListeners = LinkedHashSet<(List<OutboxItem>) -> Unit>()

    /**
     * Отложенные доставлены (4.18): у кого есть окно страницы VK (экран сообщений или фоновая проверка), тот отмечает
     * переписку непрочитанной — чтобы ушедшее без человека сообщение было видно и в приложении, и на сайте VK.
     */
    fun addScheduledDeliveredListener(listener: (List<OutboxItem>) -> Unit) {
        scheduledListeners += listener
    }

    fun removeScheduledDeliveredListener(listener: (List<OutboxItem>) -> Unit) {
        scheduledListeners -= listener
    }

    /** Очередь удалена из настроек: забыть и память тоже. */
    fun reset() {
        generation++
        receipts.clear()
        items = emptyList()
        loaded = true
        // На диск — тоже пустое: запись, поставленная раньше, не воскресит удалённое.
        save()
        notifyChanged()
    }

    /** Подписаться на изменения очереди (вызывается в главном потоке). */
    fun addListener(listener: () -> Unit) {
        listeners += listener
    }

    /** Отписаться: экран, который закрылся, не должен получать изменения. */
    fun removeListener(listener: () -> Unit) {
        listeners -= listener
    }

    private fun notifyChanged() {
        main.post { ArrayList(listeners).forEach { it() } }
    }

    companion object {
        // Хранит только applicationContext (get) — не утечка экрана (Lint StaticFieldLeak).
        @android.annotation.SuppressLint("StaticFieldLeak")
        @Volatile private var instance: Outbox? = null

        fun get(context: Context): Outbox =
            instance ?: synchronized(this) { instance ?: Outbox(context.applicationContext).also { instance = it } }
    }
}
