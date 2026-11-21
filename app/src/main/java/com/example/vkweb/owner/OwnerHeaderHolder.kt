package com.example.vkweb.owner

import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import com.example.vkweb.ui.CoverImage
import com.example.vkweb.R
import com.example.vkweb.messages.AvatarBadge
import com.example.vkweb.offline.ImageCache
import com.example.vkweb.people.PeopleFormat
import com.example.vkweb.ui.AspectImageView
import com.example.vkweb.ui.LinkOnlyMovementMethod
import com.example.vkweb.ui.Views
import com.google.android.material.button.MaterialButton
import com.google.android.material.color.MaterialColors
import java.time.ZoneId

/** Шапка страницы человека или сообщества (layout/item_owner_header.xml). Тексты — [OwnerFormat]. */
class OwnerHeaderHolder(
    view: View,
    private val actions: OwnerActions,
    private val expanded: ExpandedTexts,
) : RecyclerView.ViewHolder(view) {

    private val cover: AspectImageView = view.findViewById(R.id.owner_cover)
    private val avatar: ImageView = view.findViewById(R.id.owner_avatar)
    private val online: View = view.findViewById(R.id.owner_online)
    private val name: TextView = view.findViewById(R.id.owner_name)
    private val status: TextView = view.findViewById(R.id.owner_status)
    private val meta: TextView = view.findViewById(R.id.owner_meta)
    private val description: TextView = view.findViewById(R.id.owner_description)
    private val write: MaterialButton = view.findViewById(R.id.owner_write)
    private val countersScroll: View = view.findViewById(R.id.owner_counters_scroll)
    private val counters: LinearLayout = view.findViewById(R.id.owner_counters)
    private val notice: TextView = view.findViewById(R.id.owner_notice)
    private val wallTitle: TextView = view.findViewById(R.id.owner_wall_title)
    private val images = ImageCache.get(view.context)
    private val avatarImage = CoverImage(avatar, AVATAR_DP)
    private val screenPx = view.resources.displayMetrics.widthPixels
    private val mutedColor = MaterialColors.getColor(view, com.google.android.material.R.attr.colorOnSurfaceVariant)
    private val zone: ZoneId = ZoneId.systemDefault()

    init {
        Views.clipCircle(avatar)
        LinkOnlyMovementMethod.attach(description)
    }

    fun bind(row: OwnerRow.Header) {
        val h = row.header
        val deactivated = h.deactivated.isNotEmpty()
        bindCover(h)
        bindAvatar(h, deactivated)
        name.text = PeopleFormat.displayName(h.name, h.verified)
        status.showText(h.status)
        meta.showText(OwnerFormat.headerMeta(h, System.currentTimeMillis() / 1000, zone))
        bindDescription(h)
        write.visibility = if (h.canWrite && !deactivated) View.VISIBLE else View.GONE
        write.setOnClickListener { actions.write(h) }
        bindCounters(OwnerFormat.counters(h))
        notice.showText(row.notice)
        wallTitle.visibility = if (row.wallTitle) View.VISIBLE else View.GONE
    }

    private fun bindCover(h: OwnerHeader) {
        val show = !h.isUser && h.cover.isNotEmpty()
        cover.visibility = if (show) View.VISIBLE else View.GONE
        if (!show) return
        cover.ratio = COVER_RATIO
        images.load(cover, h.cover, screenPx)
    }

    private fun bindAvatar(h: OwnerHeader, deactivated: Boolean) {
        avatarImage.load(if (deactivated) null else h.photo) { AvatarBadge(h.name) }
        val full = h.photoFull.ifEmpty { h.photo }.takeUnless { deactivated }.orEmpty()
        avatar.setOnClickListener(if (full.isEmpty()) null else View.OnClickListener { actions.openAvatar(full) })
        avatar.isClickable = full.isNotEmpty()
        online.visibility = if (h.isUser && h.online && !deactivated) View.VISIBLE else View.GONE
    }

    /** Описание сообщества: сворачивается, нажатие разворачивает. */
    private fun bindDescription(h: OwnerHeader) {
        if (h.isUser || h.description.isBlank()) {
            description.visibility = View.GONE
            return
        }
        val short = if (expanded.isExpanded(ExpandedTexts.DESCRIPTION)) null
        else OwnerFormat.collapse(h.description, DESCRIPTION_CHARS, DESCRIPTION_LINES)
        description.visibility = View.VISIBLE
        description.text = PostText.build(short ?: h.description, actions::openLink)
        description.setOnClickListener(if (short == null) null else View.OnClickListener {
            expanded.expand(ExpandedTexts.DESCRIPTION)
            bindingAdapterPosition.takeIf { it != androidx.recyclerview.widget.RecyclerView.NO_POSITION }?.let { bindingAdapter?.notifyItemChanged(it) }
        })
    }

    private fun bindCounters(list: List<OwnerFormat.Counter>) {
        counters.removeAllViews()
        list.forEach { counters.addView(counterView(it), LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, Views.dp(itemView, COUNTER_HEIGHT_DP))) }
        countersScroll.visibility = if (list.isEmpty()) View.GONE else View.VISIBLE
    }

    private fun counterView(counter: OwnerFormat.Counter) = TextView(itemView.context, null, 0, R.style.OwnerCounter).apply {
        text = counter.text
        val link = counter.link
        if (link == null) {
            setTextColor(mutedColor)
            background = null
        } else {
            setOnClickListener { actions.openLink(link) }
        }
    }

    private fun TextView.showText(value: String) {
        text = value
        visibility = if (value.isBlank()) View.GONE else View.VISIBLE
    }

    private companion object {
        const val AVATAR_DP = 80
        const val COUNTER_HEIGHT_DP = 48
        /** Обложки VK — примерно 3:1. */
        const val COVER_RATIO = 1f / 3f
        const val DESCRIPTION_CHARS = 220
        const val DESCRIPTION_LINES = 4
    }
}
