package com.example.vkweb.ui

import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import androidx.recyclerview.widget.SimpleItemAnimator

/**
 * Обновление своих списков без дёрганий.
 *  - [prepare]: изменённая строка перерисовывается на месте, без анимации «растворения»
 *    (иначе каждая обновившаяся цифра мигала бы всей строкой);
 *  - [submitAnchored]: новый список подставляется так, что строка, на которую человек смотрит,
 *    остаётся на том же месте экрана, даже если выше что-то добавилось или переставилось.
 *    Если человек в самом начале списка, он там и остаётся — и видит новое.
 */
object ListUpdates {

    fun prepare(list: RecyclerView) {
        (list.itemAnimator as? SimpleItemAnimator)?.supportsChangeAnimations = false
    }

    fun <T : Any> submitAnchored(
        list: RecyclerView,
        adapter: ListAdapter<T, *>,
        items: List<T>,
        key: (T) -> Any,
        onCommitted: () -> Unit = {},
    ) {
        val layout = list.layoutManager as? LinearLayoutManager
        val shown = adapter.currentList
        val first = layout?.findFirstVisibleItemPosition() ?: RecyclerView.NO_POSITION
        if (layout == null || shown.isEmpty() || first == RecyclerView.NO_POSITION) {
            adapter.submitList(items, onCommitted)
            return
        }
        val firstView = layout.findViewByPosition(first)
        val offset = (firstView?.top ?: 0) - list.paddingTop
        val atTop = first == 0 && offset >= 0
        val anchor = shown.getOrNull(first)?.let(key)
        adapter.submitList(items) {
            when {
                atTop -> layout.scrollToPosition(0)
                anchor != null -> {
                    val index = items.indexOfFirst { key(it) == anchor }
                    if (index >= 0) layout.scrollToPositionWithOffset(index, offset)
                }
            }
            onCommitted()
        }
    }
}
