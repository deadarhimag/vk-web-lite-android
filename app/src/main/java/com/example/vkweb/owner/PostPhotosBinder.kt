package com.example.vkweb.owner

import android.content.res.ColorStateList
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.widget.TextViewCompat
import com.example.vkweb.R
import com.example.vkweb.offline.ImageCache
import com.example.vkweb.ui.AspectImageView
import com.example.vkweb.ui.Views

/**
 * Фото и превью видео поста: первое — крупно, с настоящими пропорциями; остальные — рядом снизу
 * (до [MAX_THUMBS], на последнем — «+N»). У превью видео — значок «играть».
 */
class PostPhotosBinder(root: View, private val actions: OwnerActions) {
    private val mainBox: View = root.findViewById(R.id.body_main_photo_box)
    private val main: AspectImageView = root.findViewById(R.id.body_main_photo)
    private val mainPlay: View = root.findViewById(R.id.body_main_play)
    private val thumbs: LinearLayout = root.findViewById(R.id.body_thumbs)
    private val images = ImageCache.get(root.context)
    private val screenPx = root.resources.displayMetrics.widthPixels
    private val gapPx = Views.dp(root, THUMB_GAP_DP)

    fun bind(photos: List<PostPhoto>) {
        thumbs.removeAllViews()
        if (photos.isEmpty()) {
            mainBox.visibility = View.GONE
            thumbs.visibility = View.GONE
            return
        }
        bindMain(photos[0], photos)
        bindThumbs(photos.drop(1), photos)
    }

    private fun bindMain(photo: PostPhoto, all: List<PostPhoto>) {
        mainBox.visibility = View.VISIBLE
        main.ratio = AspectImageView.ratioOf(photo.width, photo.height)
        images.load(main, photo.url, screenPx)
        main.setOnClickListener { actions.openPhoto(photo, all) }
        mainPlay.visibility = if (photo.open.isNotEmpty()) View.VISIBLE else View.GONE
    }

    private fun bindThumbs(rest: List<PostPhoto>, all: List<PostPhoto>) {
        thumbs.visibility = if (rest.isEmpty()) View.GONE else View.VISIBLE
        val shown = rest.take(MAX_THUMBS)
        val hidden = rest.size - shown.size
        shown.forEachIndexed { index, photo ->
            val box = FrameLayout(thumbs.context)
            box.addView(thumbImage(photo, all), FrameLayout.LayoutParams(MATCH, ViewGroup.LayoutParams.WRAP_CONTENT))
            when {
                index == shown.lastIndex && hidden > 0 -> box.addView(
                    moreLabel(hidden), FrameLayout.LayoutParams(MATCH, MATCH),
                )
                photo.open.isNotEmpty() -> {
                    val size = Views.dp(thumbs, PLAY_SIZE_DP)
                    box.addView(playMark(), FrameLayout.LayoutParams(size, size, Gravity.CENTER))
                }
            }
            thumbs.addView(box, cell(first = index == 0))
        }
        // Неполный ряд: пустые места, чтобы превью не растягивались на всю ширину.
        repeat(MAX_THUMBS - shown.size) { thumbs.addView(View(thumbs.context), cell(first = false)) }
    }

    private fun thumbImage(photo: PostPhoto, all: List<PostPhoto>) = AspectImageView(thumbs.context).apply {
        ratio = 1f
        scaleType = ImageView.ScaleType.CENTER_CROP
        setBackgroundResource(R.drawable.photo_placeholder)
        contentDescription = context.getString(R.string.photo_description)
        setOnClickListener { actions.openPhoto(photo, all) }
        images.load(this, photo.url, screenPx / MAX_THUMBS)
    }

    private fun moreLabel(hidden: Int) = TextView(thumbs.context).apply {
        TextViewCompat.setTextAppearance(this, com.google.android.material.R.style.TextAppearance_Material3_TitleMedium)
        text = context.getString(R.string.post_more_photos, hidden)
        gravity = Gravity.CENTER
        setTextColor(context.getColor(R.color.media_on))
        setBackgroundColor(context.getColor(R.color.media_scrim))
    }

    private fun playMark() = ImageView(thumbs.context).apply {
        setImageResource(R.drawable.ic_play)
        setBackgroundResource(R.drawable.play_overlay)
        imageTintList = ColorStateList.valueOf(context.getColor(R.color.media_on))
        val pad = Views.dp(thumbs, PLAY_PADDING_DP)
        setPadding(pad, pad, pad, pad)
        importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
    }

    private fun cell(first: Boolean) = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply {
        if (!first) marginStart = gapPx
    }

    private companion object {
        const val MAX_THUMBS = 4
        const val THUMB_GAP_DP = 4
        const val PLAY_SIZE_DP = 32
        const val PLAY_PADDING_DP = 6
        const val MATCH = ViewGroup.LayoutParams.MATCH_PARENT
    }
}
