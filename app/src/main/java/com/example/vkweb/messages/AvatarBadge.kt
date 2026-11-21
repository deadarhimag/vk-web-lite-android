package com.example.vkweb.messages

import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.drawable.Drawable

/**
 * Кружок с инициалами вместо аватара. Удалённые картинки в облегчённом режиме не грузим:
 * это лишние запросы и ещё одна библиотека. Цвет стабилен для одного собеседника.
 */
class AvatarBadge(private val title: String) : Drawable() {

    private val background = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = AvatarPalette.colorFor(title) }
    private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        textAlign = Paint.Align.CENTER
    }
    private val letters = AvatarPalette.initials(title)
    private val bounds = Rect()

    override fun draw(canvas: Canvas) {
        val r = bounds().width().coerceAtMost(bounds().height()) / 2f
        val cx = bounds().exactCenterX()
        val cy = bounds().exactCenterY()
        canvas.drawCircle(cx, cy, r, background)
        textPaint.textSize = r
        textPaint.getTextBounds(letters, 0, letters.length, bounds)
        canvas.drawText(letters, cx, cy - bounds.exactCenterY(), textPaint)
    }

    override fun setAlpha(alpha: Int) { background.alpha = alpha }
    override fun setColorFilter(colorFilter: android.graphics.ColorFilter?) { background.colorFilter = colorFilter }
    @Deprecated("deprecated in API", ReplaceWith("PixelFormat.TRANSLUCENT"))
    override fun getOpacity(): Int = android.graphics.PixelFormat.TRANSLUCENT

    private fun bounds() = getBounds()
}
