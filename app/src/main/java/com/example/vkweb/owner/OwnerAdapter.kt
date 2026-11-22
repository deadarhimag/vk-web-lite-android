package com.example.vkweb.owner

import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.example.vkweb.R

/** Шапка и стена своего экрана страницы. Строки — [OwnerRow], нажатия — [OwnerActions]. */
class OwnerAdapter(private val actions: OwnerActions) : ListAdapter<OwnerRow, RecyclerView.ViewHolder>(Diff) {

    /** Чей это экран (id владельца в API). */
    var ownerId: Long = 0

    private val expanded = ExpandedTexts()

    /** Другая страница: развёрнутые тексты прежней не нужны. */
    fun reset() = expanded.clear()

    override fun getItemViewType(position: Int): Int = when (getItem(position)) {
        is OwnerRow.Header -> TYPE_HEADER
        is OwnerRow.Post -> TYPE_POST
        OwnerRow.Footer -> TYPE_FOOTER
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
        val inflater = LayoutInflater.from(parent.context)
        return when (viewType) {
            TYPE_HEADER -> OwnerHeaderHolder(
                inflater.inflate(R.layout.item_owner_header, parent, false), actions, expanded,
            )
            TYPE_POST -> PostHolder(inflater.inflate(R.layout.item_post, parent, false), actions, expanded, { ownerId })
            else -> FooterHolder(inflater.inflate(R.layout.item_owner_footer, parent, false))
        }
    }

    override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
        when (val row = getItem(position)) {
            is OwnerRow.Header -> (holder as OwnerHeaderHolder).bind(row)
            is OwnerRow.Post -> (holder as PostHolder).bind(row.post)
            OwnerRow.Footer -> Unit
        }
    }

    /** «Догружается…» внизу стены: только индикатор. */
    private class FooterHolder(view: android.view.View) : RecyclerView.ViewHolder(view)

    private companion object {
        const val TYPE_HEADER = 0
        const val TYPE_POST = 1
        const val TYPE_FOOTER = 2
    }

    private object Diff : DiffUtil.ItemCallback<OwnerRow>() {
        override fun areItemsTheSame(a: OwnerRow, b: OwnerRow) = a.key == b.key
        override fun areContentsTheSame(a: OwnerRow, b: OwnerRow) = a == b
    }
}
