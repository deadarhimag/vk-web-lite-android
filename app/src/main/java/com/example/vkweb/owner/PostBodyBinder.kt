package com.example.vkweb.owner

import android.text.TextUtils
import android.util.TypedValue
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.widget.TextViewCompat
import com.example.vkweb.R
import com.example.vkweb.ui.LinkOnlyMovementMethod
import com.example.vkweb.ui.Views
import com.google.android.material.color.MaterialColors

/**
 * Текст, фото и вложения-строки одного поста (layout/post_body.xml). Одинаково для самого поста
 * и для записи, которой поделились. Длинный текст сворачивается до «Показать полностью».
 */
class PostBodyBinder(
    root: View,
    private val actions: OwnerActions,
    private val expanded: ExpandedTexts,
    /** Текст развернули: строку списка нужно перерисовать. */
    private val onExpanded: () -> Unit,
) {
    private val text: TextView = root.findViewById(R.id.body_text)
    private val more: TextView = root.findViewById(R.id.body_more)
    private val extras: LinearLayout = root.findViewById(R.id.body_extras)
    private val photos = PostPhotosBinder(root, actions)
    private val linkColor = MaterialColors.getColor(root, androidx.appcompat.R.attr.colorPrimary)
    private val mutedColor = MaterialColors.getColor(root, com.google.android.material.R.attr.colorOnSurfaceVariant)

    init {
        LinkOnlyMovementMethod.attach(text)
    }

    /** [expandKey] — под каким ключом помнить, что текст развёрнут. */
    fun bind(post: WallPost, expandKey: String) {
        bindText(post.text, expandKey)
        photos.bind(post.photos)
        bindExtras(post, post.extras)
    }

    private fun bindText(raw: String, expandKey: String) {
        if (raw.isBlank()) {
            text.visibility = View.GONE
            more.visibility = View.GONE
            return
        }
        val short = if (expanded.isExpanded(expandKey)) null else OwnerFormat.collapse(raw)
        text.visibility = View.VISIBLE
        text.text = PostText.build(short ?: raw, actions::openLink)
        more.visibility = if (short == null) View.GONE else View.VISIBLE
        more.setOnClickListener {
            expanded.expand(expandKey)
            onExpanded()
        }
    }

    private fun bindExtras(post: WallPost, list: List<PostExtra>) {
        extras.removeAllViews()
        extras.visibility = if (list.isEmpty()) View.GONE else View.VISIBLE
        list.forEach { extras.addView(extraView(post, it), LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)) }
    }

    private fun extraView(post: WallPost, e: PostExtra) = TextView(extras.context).apply {
        TextViewCompat.setTextAppearance(this, com.google.android.material.R.style.TextAppearance_Material3_BodyMedium)
        text = context.getString(labelOf(e.kind), e.title)
        maxLines = 2
        ellipsize = TextUtils.TruncateAt.END
        val pad = Views.dp(extras, EXTRA_PADDING_DP)
        setPadding(0, pad, 0, pad)
        if (e.kind == "audio" && e.trackId.isNotEmpty()) {
            // Трек записи играет приложение (как музыка из переписки): очередь — треки этой записи.
            setTextColor(linkColor)
            setBackgroundResource(selectableBackground())
            setOnClickListener { actions.playAudio(post, e) }
        } else if (e.url.isEmpty()) {
            setTextColor(mutedColor)
        } else {
            setTextColor(linkColor)
            setBackgroundResource(selectableBackground())
            setOnClickListener { actions.openLink(e.url) }
        }
    }

    private fun labelOf(kind: String): Int = when (kind) {
        "audio" -> R.string.post_audio
        "link" -> R.string.post_link
        "doc" -> R.string.post_doc
        "poll" -> R.string.post_poll
        "video" -> R.string.post_video
        else -> R.string.post_attachment
    }

    private fun selectableBackground(): Int {
        val value = TypedValue()
        extras.context.theme.resolveAttribute(android.R.attr.selectableItemBackground, value, true)
        return value.resourceId
    }

    private companion object {
        const val EXTRA_PADDING_DP = 6
    }
}
