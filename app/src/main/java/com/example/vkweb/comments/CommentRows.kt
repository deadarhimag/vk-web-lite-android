package com.example.vkweb.comments

/**
 * Строки списка комментариев с ветками ответов (4.46). Без зависимостей от Android: проверяется обычными тестами.
 *
 * Под верхним комментарием — его ответы: сначала первые, пришедшие вместе с ним, а после «Показать ответы» — вся ветка
 * (она заменяет первые). Строка «Показать ответы: N» — пока есть непоказанные и ветку можно получить. Если VK ветку
 * не отдал, строка пропадает: остаются первые ответы, остальные — на странице VK.
 */
class CommentRows {

    sealed class Row {
        /** Комментарий ([depth] = 0) или ответ (1); [rootId] — верхний комментарий ветки. [expanded] — ответы видны ниже. */
        data class Item(val comment: VideoComment, val depth: Int, val rootId: Long, val expanded: Boolean) : Row()

        /** «Показать ответы: [remaining]» под веткой [rootId]; [loading] — уже запрошена. */
        data class More(val rootId: Long, val remaining: Int, val loading: Boolean) : Row()
    }

    private val tops = ArrayList<VideoComment>()
    private val threads = HashMap<Long, MutableList<VideoComment>>()
    private val nextOffsets = HashMap<Long, Int>()
    private val threadHasMore = HashMap<Long, Boolean>()
    private val loading = HashSet<Long>()
    private val failed = HashSet<Long>()

    val isEmpty: Boolean get() = tops.isEmpty()

    /** Порция верхних комментариев; [replace] — список заново (первая порция). Повторы по номеру отбрасываются. */
    fun addTop(items: List<VideoComment>, replace: Boolean) {
        if (replace) {
            tops.clear()
            threads.clear()
            nextOffsets.clear()
            threadHasMore.clear()
            loading.clear()
            failed.clear()
        }
        val known = tops.mapTo(HashSet()) { it.id }
        tops += items.filter { known.add(it.id) }
    }

    /** Начать загрузку ветки [rootId]. false — она уже грузится. */
    fun startThread(rootId: Long): Boolean {
        if (!loading.add(rootId)) return false
        failed -= rootId
        return true
    }

    /** С какого места просить ветку [rootId] дальше. */
    fun threadNext(rootId: Long): Int = nextOffsets[rootId] ?: 0

    fun addThread(rootId: Long, items: List<VideoComment>, replace: Boolean, next: Int, hasMore: Boolean) {
        loading -= rootId
        val list = threads.getOrPut(rootId) { ArrayList() }
        if (replace) list.clear()
        val known = list.mapTo(HashSet()) { it.id }
        list += items.filter { known.add(it.id) }
        nextOffsets[rootId] = next
        threadHasMore[rootId] = hasMore
    }

    /**
     * Ветка не пришла. [retry] = true (страница не ответила) — «Показать ответы» возвращается; false (VK отказал) —
     * строки больше нет. @return что-то изменилось (для тайм-аута — ветка ещё ждала ответа).
     */
    fun failThread(rootId: Long, retry: Boolean): Boolean {
        val wasLoading = loading.remove(rootId)
        if (!retry) failed += rootId
        return wasLoading || !retry
    }

    /** Ветку перечитать заново (после своего ответа в неё). */
    fun resetThread(rootId: Long) {
        threads.remove(rootId)
        nextOffsets.remove(rootId)
        threadHasMore.remove(rootId)
        failed -= rootId
    }

    fun rows(): List<Row> {
        val out = ArrayList<Row>()
        for (top in tops) {
            val full = threads[top.id]
            val replies = full ?: top.thread
            out += Row.Item(top, 0, top.id, expanded = replies.isNotEmpty())
            replies.forEach { out += Row.Item(it, 1, top.id, expanded = false) }
            val remaining = when {
                full == null -> top.replies - top.thread.size
                threadHasMore[top.id] == true -> top.replies - full.size
                else -> 0
            }
            if (remaining > 0 && top.id !in failed) out += Row.More(top.id, remaining, top.id in loading)
        }
        return out
    }
}
