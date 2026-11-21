package com.example.vkweb.owner

import android.content.res.ColorStateList
import android.view.View
import android.widget.ImageView
import android.widget.TextView
import androidx.core.widget.TextViewCompat
import androidx.recyclerview.widget.RecyclerView
import com.example.vkweb.R
import com.example.vkweb.messages.AvatarBadge
import com.example.vkweb.offline.ImageCache
import com.example.vkweb.ui.Views
import com.google.android.material.color.MaterialColors
import java.time.ZoneId

/**
 * Пост стены (layout/item_post.xml): автор и время, текст и вложения, запись, которой поделились,
 * счётчики. Нажатие на пост или на комментарии открывает его страницу на сайте.
 */
class PostHolder(
    view: View,
    private val actions: OwnerActions,
    expanded: ExpandedTexts,
    /** Чья страница: подпись автора-владельца не ведёт на ту же страницу. */
    private val ownerId: () -> Long,
) : RecyclerView.ViewHolder(view) {

    private val avatar: ImageView = view.findViewById(R.id.post_avatar)
    private val author: TextView = view.findViewById(R.id.post_author)
    private val date: TextView = view.findViewById(R.id.post_date)
    private val body = PostBodyBinder(view.findViewById(R.id.post_body), actions, expanded, ::refresh)
    private val repost: View = view.findViewById(R.id.post_repost)
    private val repostAvatar: ImageView = view.findViewById(R.id.repost_avatar)
    private val repostAuthor: TextView = view.findViewById(R.id.repost_author)
    private val repostDate: TextView = view.findViewById(R.id.repost_date)
    private val repostBody = PostBodyBinder(view.findViewById(R.id.repost_body), actions, expanded, ::refresh)
    private val likes: TextView = view.findViewById(R.id.post_likes)
    private val comments: TextView = view.findViewById(R.id.post_comments)
    private val reposts: TextView = view.findViewById(R.id.post_reposts)
    private val views: TextView = view.findViewById(R.id.post_views)
    private val images = ImageCache.get(view.context)
    private val avatarPx = Views.dp(view, AVATAR_DP)
    private val mutedColor = MaterialColors.getColor(view, com.google.android.material.R.attr.colorOnSurfaceVariant)
    private val accentColor = MaterialColors.getColor(view, androidx.appcompat.R.attr.colorPrimary)
    private val zone: ZoneId = ZoneId.systemDefault()
    private var current: WallPost? = null

    init {
        Views.clipCircle(avatar)
        Views.clipCircle(repostAvatar)
        view.setOnClickListener { current?.let(::openPost) }
        comments.setOnClickListener { current?.let(::openPost) }
        repost.setOnClickListener { current?.repost?.let(::openPost) }
        // Лайк ставится прямо здесь (от имени страницы VK); остальные счётчики открывают запись.
        likes.setOnClickListener { current?.let(actions::like) }
        // Долгое нажатие на запись — «Поделиться» (в другой мессенджер: ссылка, текст, фото).
        view.setOnLongClickListener { current?.let(actions::share); current != null }
    }

    fun bind(post: WallPost) {
        current = post
        val now = System.currentTimeMillis() / 1000
        bindAuthor(avatar, author, post)
        date.text = OwnerFormat.postDate(post, now, zone)
        body.bind(post, post.key)
        bindRepost(post, now)
        bindStats(post)
        // Описание строки — автор, дата и сам текст: одно «имя, дата» закрывало от экранного чтеца текст записи
        // (код ревью 3.59).
        itemView.contentDescription = listOf(post.fromName, date.text, OwnerFormat.plain(post.text).take(DESCRIPTION_TEXT_MAX))
            .filter { it.isNotEmpty() }.joinToString(", ")
    }

    private fun bindRepost(post: WallPost, now: Long) {
        val r = post.repost
        repost.visibility = if (r == null) View.GONE else View.VISIBLE
        if (r == null) return
        bindAuthor(repostAvatar, repostAuthor, r)
        val text = OwnerFormat.postDate(r, now, zone)
        repostDate.text = text
        repostDate.visibility = if (text.isEmpty()) View.GONE else View.VISIBLE
        repostBody.bind(r, ExpandedTexts.repostKey(post.key))
    }

    private fun bindStats(post: WallPost) {
        likes.text = count(post.likes)
        val likeColor = if (post.liked) accentColor else mutedColor
        likes.setTextColor(likeColor)
        TextViewCompat.setCompoundDrawableTintList(likes, ColorStateList.valueOf(likeColor))
        likes.contentDescription = likes.context.getString(
            if (post.liked) R.string.post_unlike_description else R.string.post_like_description, post.likes,
        )
        comments.text = count(post.comments)
        comments.contentDescription = comments.context.getString(R.string.post_comments_description, post.comments)
        reposts.text = count(post.reposts)
        views.text = count(post.views)
        views.visibility = if (post.views > 0) View.VISIBLE else View.GONE
    }

    private fun count(n: Int) = if (n > 0) OwnerFormat.compact(n.toLong()) else ""

    /** Автор — не сам владелец страницы: нажатие на имя или фото открывает его страницу. */
    private fun bindAuthor(image: ImageView, label: TextView, post: WallPost) {
        label.text = post.fromName
        images.load(image, post.fromPhoto, avatarPx) { AvatarBadge(post.fromName) }
        val link = OwnerFormat.authorLink(post.fromId, ownerId())
        val click = link?.let { target -> View.OnClickListener { actions.openLink(target) } }
        for (v in listOf(image, label)) {
            v.setOnClickListener(click)
            v.isClickable = click != null
        }
    }

    private fun openPost(post: WallPost) {
        if (post.url.isNotEmpty()) actions.openLink(post.url)
    }

    private fun refresh() {
        val position = bindingAdapterPosition
        if (position != RecyclerView.NO_POSITION) bindingAdapter?.notifyItemChanged(position)
    }

    private companion object {
        const val AVATAR_DP = 40
        const val DESCRIPTION_TEXT_MAX = 600
    }
}
