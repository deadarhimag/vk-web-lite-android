package com.example.vkweb.ui

import android.content.Context
import android.util.AttributeSet
import android.view.View
import android.widget.LinearLayout
import com.example.vkweb.R

/**
 * LinearLayout с предельной шириной (`android:maxWidth`), 4.1. Пузырь сообщения ограничивал только отступ 56dp с другой
 * стороны: на планшете и в двух колонках длинная строка тянулась почти на всю ширину, читать было неудобно.
 */
class MaxWidthLinearLayout @JvmOverloads constructor(
    context: Context, attrs: AttributeSet? = null, defStyleAttr: Int = 0,
) : LinearLayout(context, attrs, defStyleAttr) {

    private val maxWidthPx: Int

    init {
        val a = context.obtainStyledAttributes(attrs, R.styleable.MaxWidthLinearLayout, defStyleAttr, 0)
        maxWidthPx = a.getDimensionPixelSize(R.styleable.MaxWidthLinearLayout_android_maxWidth, 0)
        a.recycle()
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        var spec = widthMeasureSpec
        if (maxWidthPx > 0) {
            val size = View.MeasureSpec.getSize(widthMeasureSpec)
            val mode = View.MeasureSpec.getMode(widthMeasureSpec)
            if (mode == View.MeasureSpec.UNSPECIFIED || size > maxWidthPx) {
                spec = View.MeasureSpec.makeMeasureSpec(maxWidthPx, if (mode == View.MeasureSpec.EXACTLY) View.MeasureSpec.EXACTLY else View.MeasureSpec.AT_MOST)
            }
        }
        super.onMeasure(spec, heightMeasureSpec)
    }
}
