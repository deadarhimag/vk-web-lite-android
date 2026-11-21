package com.example.vkweb.ui

import android.view.View
import android.view.ViewGroup
import androidx.recyclerview.widget.RecyclerView
import androidx.swiperefreshlayout.widget.SwipeRefreshLayout
import com.google.android.material.color.MaterialColors

/**
 * Обновление потягиванием (4.52): список дотянули вниз от самого верха — просим свежее. Индикатор крутится, пока свежее
 * не придёт ([done]) или экран не перестанет ждать.
 *
 * Жест начинается, только когда виден список и он уже наверху: внутри рамки может быть несколько списков (музыка —
 * треки, плейлисты, плейлист) или список скрыт (переписка, страница VK) — тогда рамка касаний не забирает.
 *
 * @param onRefresh попросить свежее; false — не получилось начать (нет сети): индикатор сразу убирается.
 */
class PullRefresh(private val layout: SwipeRefreshLayout, private val onRefresh: () -> Boolean) {

    init {
        layout.setColorSchemeColors(MaterialColors.getColor(layout, androidx.appcompat.R.attr.colorPrimary))
        layout.setProgressBackgroundColorSchemeColor(
            MaterialColors.getColor(layout, com.google.android.material.R.attr.colorSurfaceContainerHigh),
        )
        layout.setOnChildScrollUpCallback { _, _ -> visibleList()?.canScrollVertically(-1) ?: true }
        layout.setOnRefreshListener { if (!onRefresh()) layout.isRefreshing = false }
    }

    val isRefreshing: Boolean get() = layout.isRefreshing

    /** Свежее пришло (или ждать перестали): индикатор убирается. */
    fun done() {
        if (layout.isRefreshing) layout.isRefreshing = false
    }

    /** Видимый список внутри рамки; нет — жест не начинается. */
    private fun visibleList(): View? = find(layout)

    private fun find(view: View): View? {
        if (!view.isShown) return null
        if (view is RecyclerView) return view
        if (view !is ViewGroup) return null
        for (i in 0 until view.childCount) find(view.getChildAt(i))?.let { return it }
        return null
    }
}
