package com.example.vkweb.people

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.TextView
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.example.vkweb.ui.CoverImage
import com.example.vkweb.R
import com.example.vkweb.messages.AvatarBadge
import com.example.vkweb.offline.ImageCache
import com.example.vkweb.ui.Views

/** Строка своего списка: заголовок группы («Заявки в друзья · 2») или человек/сообщество. */
sealed class PeopleRow {
    abstract val key: String

    data class Header(val id: String, val title: String) : PeopleRow() {
        override val key: String get() = "h:$id"
    }

    data class Item(val entry: PeopleEntry) : PeopleRow() {
        override val key: String get() = (if (entry.request) "r:" else if (entry.suggested) "s:" else "i:") + entry.id
    }
}

/**
 * Список друзей или сообществ. Подписи считаются при показе ([PeopleFormat]), поэтому «был в сети
 * 5 мин. назад» не устаревает в сохранённом списке.
 */
class PeopleAdapter(
    private val onOpen: (PeopleEntry) -> Unit,
    private val onMessage: (PeopleEntry) -> Unit,
    /** Сколько новых записей показать у сообщества (4.0: без прочитанного в приложении, [GroupSeen]). */
    private val counterOf: (PeopleEntry) -> Int = { it.counter },
) : ListAdapter<PeopleRow, RecyclerView.ViewHolder>(Diff) {

    override fun getItemViewType(position: Int): Int = when (getItem(position)) {
        is PeopleRow.Header -> TYPE_HEADER
        is PeopleRow.Item -> TYPE_ITEM
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
        val inflater = LayoutInflater.from(parent.context)
        return if (viewType == TYPE_HEADER) {
            HeaderHolder(inflater.inflate(R.layout.item_people_header, parent, false))
        } else {
            ItemHolder(inflater.inflate(R.layout.item_person, parent, false))
        }
    }

    override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
        when (val row = getItem(position)) {
            is PeopleRow.Header -> (holder as HeaderHolder).bind(row)
            is PeopleRow.Item -> (holder as ItemHolder).bind(row.entry)
        }
    }

    private class HeaderHolder(view: View) : RecyclerView.ViewHolder(view) {
        private val text: TextView = view as TextView
        fun bind(row: PeopleRow.Header) { text.text = row.title }
    }

    private inner class ItemHolder(view: View) : RecyclerView.ViewHolder(view) {
        private val avatar: ImageView = view.findViewById(R.id.person_avatar)
        private val onlineDot: View = view.findViewById(R.id.person_online)
        private val name: TextView = view.findViewById(R.id.person_name)
        private val subtitle: TextView = view.findViewById(R.id.person_subtitle)
        private val counter: TextView = view.findViewById(R.id.person_counter)
        private val message: ImageButton = view.findViewById(R.id.person_message)
        private val photo = CoverImage(avatar, AVATAR_DP)
        private var current: PeopleEntry? = null

        init {
            Views.clipCircle(avatar)
            view.setOnClickListener { current?.let(onOpen) }
            message.setOnClickListener { current?.let(onMessage) }
        }

        fun bind(e: PeopleEntry) {
            current = e
            // У удалённых страниц VK отдаёт заглушку — показываем инициалы.
            photo.load(if (e.isDeactivated) null else e.photo) { AvatarBadge(e.name) }
            avatar.alpha = if (e.isDeactivated) DIMMED else 1f
            name.text = PeopleFormat.displayName(e.name, e.verified)
            val sub = PeopleFormat.subtitle(e, System.currentTimeMillis() / 1000)
            subtitle.text = sub
            subtitle.visibility = if (sub.isEmpty()) View.GONE else View.VISIBLE
            onlineDot.visibility = if (e.online && !e.isDeactivated) View.VISIBLE else View.GONE
            val count = counterOf(e)
            counter.visibility = if (count > 0) View.VISIBLE else View.GONE
            counter.text = if (count > MAX_COUNTER) "$MAX_COUNTER+" else count.toString()
            message.visibility = if (e.kind == PeopleKind.FRIENDS && e.canWrite && !e.isDeactivated) View.VISIBLE else View.GONE
            itemView.contentDescription = listOf(e.name, sub).filter { it.isNotEmpty() }.joinToString(", ")
        }
    }

    private companion object {
        const val TYPE_HEADER = 0
        const val TYPE_ITEM = 1
        const val AVATAR_DP = 52
        const val MAX_COUNTER = 99
        const val DIMMED = 0.5f
    }

    private object Diff : DiffUtil.ItemCallback<PeopleRow>() {
        override fun areItemsTheSame(a: PeopleRow, b: PeopleRow) = a.key == b.key
        override fun areContentsTheSame(a: PeopleRow, b: PeopleRow) = a == b
    }
}
