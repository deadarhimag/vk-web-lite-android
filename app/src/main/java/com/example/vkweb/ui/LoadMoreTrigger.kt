package com.example.vkweb.ui

import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.example.vkweb.config.AppConfig

/**
 * Догрузка своего списка: когда до конца осталось [preloadDistance] строк, просит следующую порцию
 * ([onLoadMore]) — один раз, пока не придёт ответ ([onLoaded]). Если страница так и не ответила,
 * через [AppConfig.LOAD_MORE_TIMEOUT_MS] можно просить снова: иначе список застрял бы навсегда.
 */
class LoadMoreTrigger(
    private val list: RecyclerView,
    private val layout: LinearLayoutManager,
    private val preloadDistance: Int,
    private val onLoadMore: () -> Unit,
) {
    /** Есть ли что догружать (по последнему ответу страницы). */
    var hasMore: Boolean = false
        private set
    private var waiting = false
    private val giveUp = Runnable { waiting = false }
    private val guard = LoadMoreGuard()

    init {
        list.addOnScrollListener(object : RecyclerView.OnScrollListener() {
            override fun onScrolled(recyclerView: RecyclerView, dx: Int, dy: Int) {
                if (dy > 0 && isNearEnd()) request()
            }
        })
    }

    /** Попросить следующую порцию, если она есть и ещё не запрошена. */
    fun request() {
        if (!hasMore || waiting || guard.stopped) return
        waiting = true
        guard.onRequested(list.adapter?.itemCount ?: 0)
        list.removeCallbacks(giveUp)
        list.postDelayed(giveUp, AppConfig.LOAD_MORE_TIMEOUT_MS)
        onLoadMore()
    }

    /**
     * Список изменился (пришла порция): если конец всё ещё на виду, просим следующую. Без этого короткий список,
     * целиком поместившийся на экране, не догружался бы вовсе — прокрутки, которая запускает догрузку, в нём нет.
     */
    fun onListChanged() {
        list.post { if (isNearEnd()) request() }
    }

    /** Пришла порция ([shown] — сколько теперь показано): можно просить следующую. */
    fun onLoaded(hasMore: Boolean, shown: Int = -1) {
        this.hasMore = hasMore
        waiting = false
        list.removeCallbacks(giveUp)
        if (shown >= 0) {
            val wasStopped = guard.stopped
            guard.onLoaded(shown)
            if (guard.stopped && !wasStopped) {
                com.example.vkweb.diag.AppLog.w(com.example.vkweb.diag.LogTag.SCREEN, "догрузка остановлена: список не растёт", "показано" to shown)
            }
        }
    }

    /** Другой список: всё сначала. */
    fun reset() {
        onLoaded(hasMore = false)
        guard.reset()
    }

    private fun isNearEnd(): Boolean {
        val count = list.adapter?.itemCount ?: return false
        return layout.findLastVisibleItemPosition() >= count - preloadDistance
    }
}
