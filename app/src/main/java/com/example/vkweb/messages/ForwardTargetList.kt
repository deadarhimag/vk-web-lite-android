package com.example.vkweb.messages

import android.content.Context
import android.view.View
import android.widget.EditText
import androidx.core.view.isVisible
import androidx.core.widget.doAfterTextChanged
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.example.vkweb.R

/**
 * Список переписок с поиском в листе выбора (layout sheet_forward) — общий у «Переслать» ([ChatForwarding]) и
 * «Поделиться» ([ShareIntoChat]) (4.66, код ревью 4.64: был написан в обоих почти дословно).
 */
object ForwardTargetList {

    /** Список — не выше этой доли экрана: шапка и поиск всегда видны, лист не уезжает за верх. */
    private const val MAX_HEIGHT_PART = 0.6f

    /** Заполнить список в [root] переписками [targets]; нажатие — [onPick]. */
    fun bind(context: Context, root: View, targets: List<ForwardTargets.Target>, onPick: (ForwardTargets.Target) -> Unit) {
        val list = root.findViewById<RecyclerView>(R.id.forward_list)
        val empty = root.findViewById<View>(R.id.forward_empty)
        val adapter = ForwardTargetAdapter(onPick)
        list.layoutManager = LinearLayoutManager(context)
        list.adapter = adapter
        list.layoutParams = list.layoutParams.apply { height = (context.resources.displayMetrics.heightPixels * MAX_HEIGHT_PART).toInt() }
        adapter.submitList(targets)
        root.findViewById<EditText>(R.id.forward_search).doAfterTextChanged { s ->
            val found = ForwardTargets.filter(targets, s?.toString().orEmpty())
            adapter.submitList(found)
            empty.isVisible = found.isEmpty()
        }
    }
}
