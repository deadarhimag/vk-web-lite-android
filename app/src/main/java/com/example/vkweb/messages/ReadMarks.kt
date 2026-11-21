package com.example.vkweb.messages

/**
 * Отметки о прочтении, отправленные странице и ждущие её ответа «convaction» (4.67, код ревью: было написано дважды —
 * в экране сообщений и в фоновой работе sync/HeadlessSync.kt):
 *  - отложенные «прочитано» ([Pending], 4.13/4.35) — переписку открыли без сети или нажали «Прочитано» в уведомлении;
 *  - «непрочитана» для переписки, где ушло отложенное сообщение (4.18).
 * Что отправлять и как понимать ответ — здесь; отправку и журнал делают сами экран и фоновая работа.
 * Без зависимостей от Android: проверяется тестами.
 */
class ReadMarks(private val pending: Pending) {

    /** Хранилище отложенных «прочитано» (messages/PendingReads.kt). */
    interface Pending {
        fun all(): Set<String>
        fun remove(peer: String)
    }

    enum class Result {
        /** «Прочитано» принято: отложенная отметка снята. */
        READ_OK,
        /** «Прочитано» не принято: отметка остаётся до следующего раза. */
        READ_FAILED,
        UNREAD_OK,
        UNREAD_FAILED,
        /** Ответ не на наши отметки (действие с беседой по выбору человека). */
        OTHER,
    }

    private val reads = LinkedHashSet<String>()
    private val unreads = LinkedHashSet<String>()

    /** Ждут ответа: «прочитано» и «непрочитана». */
    val readsWaiting: Int get() = reads.size
    val unreadsWaiting: Int get() = unreads.size
    val isWaiting: Boolean get() = reads.isNotEmpty() || unreads.isNotEmpty()

    /** Отложенные «прочитано», ещё не отправленные: теперь считаются отправленными — отправьте их ([READ]). */
    fun takeReads(): List<String> {
        val peers = (pending.all() - reads).toList()
        reads += peers
        return peers
    }

    /** Переписки, где ушло отложенное: те, что ещё не ждут ответа, — отправьте им [UNREAD]. */
    fun takeUnreads(peers: Collection<String>): List<String> = peers.distinct().filter { unreads.add(it) }

    /** Ждущие «непрочитана» — для повтора (запрос мог оборваться переходом окна). */
    fun unreadsInFlight(): List<String> = unreads.toList()

    /** Ответ страницы на действие [action] с перепиской [peer]. */
    fun onResult(action: String, peer: String, ok: Boolean): Result = when {
        action == READ && reads.remove(peer) -> if (ok) {
            pending.remove(peer)
            Result.READ_OK
        } else {
            Result.READ_FAILED
        }
        action == UNREAD && unreads.remove(peer) -> if (ok) Result.UNREAD_OK else Result.UNREAD_FAILED
        else -> Result.OTHER
    }

    /** Ответа не дождались: ждать перестаём (отложенные «прочитано» остаются в [Pending] до следующего раза). */
    fun forget() {
        reads.clear()
        unreads.clear()
    }

    companion object {
        /** Действия страницы (window.__vkReader.convAction). */
        const val READ = "read"
        const val UNREAD = "unread"
    }
}
