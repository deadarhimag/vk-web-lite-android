package com.example.vkweb.ui

import android.content.Context
import android.util.AttributeSet
import androidx.appcompat.widget.AppCompatImageView

/**
 * Картинка с заданным соотношением сторон: высота = ширина × [ratio].
 * Высота известна ещё до загрузки фото, поэтому лента не прыгает, когда картинки догружаются.
 */
class AspectImageView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
) : AppCompatImageView(context, attrs) {

    /** Высота / ширина; ограничивается, чтобы очень длинное фото не занимало несколько экранов. */
    var ratio: Float = 1f
        set(value) {
            val v = value.coerceIn(MIN_RATIO, MAX_RATIO)
            if (v == field) return
            field = v
            requestLayout()
        }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val width = MeasureSpec.getSize(widthMeasureSpec)
        setMeasuredDimension(width, (width * ratio).toInt())
    }

    companion object {
        const val MIN_RATIO = 0.25f
        const val MAX_RATIO = 1.3f

        fun ratioOf(width: Int, height: Int): Float =
            if (width <= 0 || height <= 0) 0.75f else height.toFloat() / width
    }
}
