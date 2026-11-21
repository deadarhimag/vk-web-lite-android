package com.example.vkweb.messages

import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.InsetDrawable
import android.graphics.drawable.LayerDrawable
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.example.vkweb.R
import com.example.vkweb.offline.ImageCache
import com.example.vkweb.ui.Views
import com.google.android.material.color.MaterialColors

/**
 * Получатели пересылки (layout/item_forward_target.xml, 3.91): фото переписки (как в списке диалогов; нет — кружок с
 * инициалами, у «Избранного» — закладка на акцентном кружке), имя и кто это.
 */
class ForwardTargetAdapter(
    private val onPick: (ForwardTargets.Target) -> Unit,
) : ListAdapter<ForwardTargets.Target, ForwardTargetAdapter.Holder>(Diff) {

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int) =
        Holder(LayoutInflater.from(parent.context).inflate(R.layout.item_forward_target, parent, false))

    override fun onBindViewHolder(holder: Holder, position: Int) = holder.bind(getItem(position))

    inner class Holder(view: View) : RecyclerView.ViewHolder(view) {
        private val avatar: ImageView = view.findViewById(R.id.forward_avatar)
        private val online: View = view.findViewById(R.id.forward_online)
        private val name: TextView = view.findViewById(R.id.forward_name)
        private val kind: TextView = view.findViewById(R.id.forward_kind)
        private val images = ImageCache.get(view.context)
        private val avatarPx = Views.dp(view, AVATAR_DP)
        private var target: ForwardTargets.Target? = null

        init {
            Views.clipCircle(avatar)
            view.setOnClickListener { target?.let(onPick) }
        }

        fun bind(t: ForwardTargets.Target) {
            target = t
            val ctx = itemView.context
            name.text = t.title
            kind.text = ctx.getString(
                when (t.kind) {
                    ForwardTargets.Kind.SELF -> R.string.forward_kind_self
                    ForwardTargets.Kind.CHAT -> R.string.forward_kind_chat
                    ForwardTargets.Kind.GROUP -> R.string.forward_kind_group
                    ForwardTargets.Kind.USER -> if (t.online) R.string.forward_kind_online else R.string.forward_kind_user
                },
            )
            online.visibility = if (t.online && t.kind == ForwardTargets.Kind.USER) View.VISIBLE else View.GONE
            if (t.kind == ForwardTargets.Kind.SELF && t.avatar.isEmpty()) {
                avatar.setImageDrawable(selfBadge(avatar))
            } else {
                images.load(avatar, t.avatar.ifEmpty { null }, avatarPx) { AvatarBadge(t.title) }
            }
            itemView.contentDescription = ctx.getString(R.string.forward_to_description, t.title) + ", " + kind.text
        }
    }

    /** «Избранное»: закладка на кружке акцентного цвета. */
    private fun selfBadge(view: View): Drawable {
        val ctx = view.context
        val circle = GradientDrawable().apply {
            shape = GradientDrawable.OVAL
            setColor(MaterialColors.getColor(view, com.google.android.material.R.attr.colorPrimary))
        }
        val icon = ContextCompat.getDrawable(ctx, R.drawable.ic_section_fave)?.mutate()?.apply {
            setTint(MaterialColors.getColor(view, com.google.android.material.R.attr.colorOnPrimary))
        } ?: return circle
        val inset = Views.dp(view, ICON_INSET_DP)
        return LayerDrawable(arrayOf(circle, InsetDrawable(icon, inset)))
    }

    private object Diff : DiffUtil.ItemCallback<ForwardTargets.Target>() {
        override fun areItemsTheSame(a: ForwardTargets.Target, b: ForwardTargets.Target) = a.peerId == b.peerId
        override fun areContentsTheSame(a: ForwardTargets.Target, b: ForwardTargets.Target) = a == b
    }

    private companion object {
        const val AVATAR_DP = 48
        const val ICON_INSET_DP = 12
    }
}
