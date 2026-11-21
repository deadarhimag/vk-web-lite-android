package com.example.vkweb.messages

import android.graphics.Typeface
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.example.vkweb.R
import com.example.vkweb.offline.ImageCache
import com.example.vkweb.sync.DialogNotifications
import com.example.vkweb.sync.DialogNotify
import com.example.vkweb.ui.Views
import com.google.android.material.color.MaterialColors

/** Список диалогов. */
class DialogsAdapter(
    private val onClick: (DialogEntry) -> Unit,
    /** Долгое нажатие — действия с беседой (4.0). */
    private val onLongClick: (DialogEntry) -> Unit = {},
) :
    ListAdapter<DialogEntry, DialogsAdapter.Holder>(DIFF) {

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): Holder {
        val view = LayoutInflater.from(parent.context).inflate(R.layout.item_dialog, parent, false)
        return Holder(view, onClick, onLongClick)
    }

    /** Собеседники, которым есть неотправленное: у них в списке значок «ждёт отправки». */
    var pendingPeers: Set<String> = emptySet()
        set(value) {
            if (field == value) return
            field = value
            notifyItemRangeChanged(0, itemCount)
        }

    /** Черновики (3.94): у переписки с недописанным вместо последнего сообщения — «Черновик: …». */
    var drafts: Map<String, String> = emptyMap()
        set(value) {
            if (field == value) return
            field = value
            notifyItemRangeChanged(0, itemCount)
        }

    /** Свои настройки уведомлений диалогов (4.47): у «не зовущих» — приглушённый счётчик, как у заглушённых в VK. */
    var notifyRules: Map<String, DialogNotify> = emptyMap()
        set(value) {
            if (field == value) return
            field = value
            notifyItemRangeChanged(0, itemCount)
        }

    /** Открытая переписка в двух колонках (3.94): её строка выделена. null — ничего не выделено. */
    var selectedPeer: String? = null
        set(value) {
            if (field == value) return
            field = value
            notifyItemRangeChanged(0, itemCount)
        }

    override fun onBindViewHolder(holder: Holder, position: Int) {
        val item = getItem(position)
        holder.bind(
            item, item.peerId in pendingPeers, DialogNotifications.quiet(item, notifyRules[item.peerId]),
            selected = item.peerId == selectedPeer,
        )
        holder.bindDraft(drafts[item.peerId])
        holder.itemView.isActivated = item.peerId == selectedPeer
    }

    class Holder(
        view: View,
        private val onClick: (DialogEntry) -> Unit,
        private val onLongClick: (DialogEntry) -> Unit,
    ) : RecyclerView.ViewHolder(view) {
        private val avatar: ImageView = view.findViewById(R.id.dialog_avatar)
        private val title: TextView = view.findViewById(R.id.dialog_title)
        private val snippet: TextView = view.findViewById(R.id.dialog_snippet)
        private val time: TextView = view.findViewById(R.id.dialog_time)
        private val unread: View = view.findViewById(R.id.dialog_unread)
        private val unreadCount: TextView = view.findViewById(R.id.dialog_unread_count)
        private val pending: View = view.findViewById(R.id.dialog_pending)
        private val onlineDot: View = view.findViewById(R.id.dialog_online)
        private val muted: View = view.findViewById(R.id.dialog_muted)
        private var current: DialogEntry? = null

        private val images = ImageCache.get(view.context)
        private val avatarPx = Views.dp(view, AVATAR_DP)

        init {
            view.setOnClickListener { current?.let(onClick) }
            view.setOnLongClickListener { current?.let(onLongClick); current != null }
            Views.clipCircle(avatar)
        }

        /** Черновик — вместо последнего сообщения, с пометкой цветом ошибки, как в VK. */
        fun bindDraft(draft: String?) {
            if (draft.isNullOrBlank()) return
            val label = itemView.context.getString(R.string.dialog_draft)
            val text = android.text.SpannableStringBuilder(label).append(' ').append(DraftMap.preview(draft))
            text.setSpan(
                android.text.style.ForegroundColorSpan(
                    com.google.android.material.color.MaterialColors.getColor(snippet, com.google.android.material.R.attr.colorError),
                ),
                0, label.length, android.text.Spanned.SPAN_EXCLUSIVE_EXCLUSIVE,
            )
            snippet.text = text
        }

        fun bind(item: DialogEntry, hasPending: Boolean, quiet: Boolean = item.muted, selected: Boolean = false) {
            current = item
            pending.visibility = if (hasPending) View.VISIBLE else View.GONE
            // Кружок с инициалами, пока фото нет; уже показанное фото при обновлении строки не мигает.
            images.load(avatar, item.avatar, avatarPx) { AvatarBadge(item.title) }
            title.text = item.title
            // В сети (4.2): только у людей — у бесед и сообществ online не приходит (vk_reader.js).
            onlineDot.visibility = if (item.online) View.VISIBLE else View.GONE
            snippet.text = item.snippet
            // 4.49: непрочитанный — имя полужирным, текст основным цветом, время синим (если диалог «зовёт»);
            // прочитанный — обычное начертание и приглушённый текст. Заглушённый — со значком колокольчика.
            // На выделенной строке (две колонки) синий на голубом читается хуже (3,9:1) — там время приглушённое.
            val style = DialogRowStyle.of(item, quiet, selected)
            title.typeface = if (style.emphasized) TITLE_UNREAD else TITLE_READ
            snippet.setTextColor(MaterialColors.getColor(snippet, if (style.emphasized) com.google.android.material.R.attr.colorOnSurface else com.google.android.material.R.attr.colorOnSurfaceVariant))
            time.setTextColor(MaterialColors.getColor(time, if (style.accentTime) androidx.appcompat.R.attr.colorPrimary else com.google.android.material.R.attr.colorOnSurfaceVariant))
            muted.visibility = if (style.mutedIcon) View.VISIBLE else View.GONE
            time.text = item.time
            time.visibility = if (item.time.isEmpty()) View.GONE else View.VISIBLE
            val count = if (item.unread) item.unreadCount else 0
            unread.visibility = if (item.unread && count == 0) View.VISIBLE else View.GONE
            unreadCount.visibility = if (count > 0) View.VISIBLE else View.GONE
            if (count > 0) {
                unreadCount.text = if (count > MAX_SHOWN) "$MAX_SHOWN+" else count.toString()
                // Беседа без уведомлений — приглушённый счётчик: заметно, но не зовёт.
                unreadCount.setBackgroundResource(if (quiet) R.drawable.counter_badge_muted else R.drawable.counter_badge)
                unreadCount.setTextColor(
                    MaterialColors.getColor(unreadCount, if (quiet) com.google.android.material.R.attr.colorSurface else com.google.android.material.R.attr.colorOnPrimary),
                )
            }
            // Для озвучивания: «3 непрочитанных», а не просто число.
            unreadCount.contentDescription = if (count > 0) unreadCount.resources.getQuantityString(R.plurals.dialog_unread_description, count, count) else null
        }
    }

    private companion object {
        /** Начертания имени (4.49): у тех же шрифтов, что TitleMedium темы. */
        val TITLE_UNREAD: Typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
        val TITLE_READ: Typeface = Typeface.create("sans-serif", Typeface.NORMAL)
        const val AVATAR_DP = 52
        const val MAX_SHOWN = 999
        val DIFF = object : DiffUtil.ItemCallback<DialogEntry>() {
            override fun areItemsTheSame(a: DialogEntry, b: DialogEntry) = a.peerId == b.peerId
            override fun areContentsTheSame(a: DialogEntry, b: DialogEntry) = a == b
        }
    }
}
