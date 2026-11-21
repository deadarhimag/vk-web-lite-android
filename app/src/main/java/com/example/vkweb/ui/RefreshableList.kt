package com.example.vkweb.ui

import android.view.View
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.example.vkweb.diag.FlowLog
import com.example.vkweb.offline.ListMerge

/**
 * Список своего экрана, который показывает сохранённое сразу, а свежее ставит поверх без скачков, и просит страницу
 * догрузить следующую порцию, когда долистали до конца. Общий каркас для «Моих треков», треков плейлиста и списка
 * плейлистов (раньше у каждого была своя копия).
 *
 * Хранит свою копию показанного ([current]): список адаптера обновляется после расчёта разницы в фоне, и две порции
 * подряд сливались бы со старым списком. Правила слияния — [ListMerge], положение прокрутки — [ListUpdates].
 *
 * @param name название списка для журнала ([FlowLog]).
 */
class RefreshableList<T : Any>(
    private val list: RecyclerView,
    private val adapter: ListAdapter<T, *>,
    private val key: (T) -> Any,
    private val name: String,
    onLoadMore: () -> Unit,
) {
    private val layout = LinearLayoutManager(list.context)
    private val loadMore = LoadMoreTrigger(list, layout, PRELOAD_DISTANCE, onLoadMore)

    /** Показанный сейчас список. */
    var current: List<T> = emptyList()
        private set

    /** Показано сохранённое, свежего ещё нет. */
    var isShowingSaved = false
        private set

    /** Свежие данные от страницы приходили (пустой список после этого — «пусто», а не «загрузка»). */
    var isLoaded = false
        private set

    init {
        list.layoutManager = layout
        list.adapter = adapter
        ListUpdates.prepare(list)
    }

    var isVisible: Boolean
        get() = list.visibility == View.VISIBLE
        set(value) { list.visibility = if (value) View.VISIBLE else View.GONE }

    /**
     * Свежая порция. [sameList] — тот же список (сливается с показанным), иначе заменяет; [complete] — страница прислала
     * список целиком: только тогда показанное можно сократить.
     * @return показанный теперь список (см. [current]).
     */
    fun refresh(incoming: List<T>, hasMore: Boolean, sameList: Boolean, complete: Boolean): List<T> {
        isShowingSaved = false
        isLoaded = true
        val items = ListMerge.accept(current, incoming, sameList, complete, key)
        // Другой список — защита от бесконечной догрузки начинает заново; тот же — следит, растёт ли он.
        if (!sameList) loadMore.reset()
        loadMore.onLoaded(hasMore, items.size)
        FlowLog.merged(name, if (sameList) "тот же" else "другой", current, items, key)
        current = items
        ListUpdates.submitAnchored(list, adapter, items, key)
        loadMore.onListChanged()
        return items
    }

    /** Сохранённое: если свежее уже показано — только дополняет его снизу недостающим. */
    fun showSaved(items: List<T>) {
        isShowingSaved = current.isEmpty()
        current = ListMerge.appendMissing(current, items, key)
        adapter.submitList(current)
    }

    fun clear() {
        isShowingSaved = false
        isLoaded = false
        loadMore.reset()
        current = emptyList()
        adapter.submitList(null)
    }

    private companion object {
        const val PRELOAD_DISTANCE = 8
    }
}
