package com.example.vkweb.ui

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.util.AttributeSet
import android.view.View
import com.example.vkweb.R
import com.google.android.material.color.MaterialColors

/**
 * Заготовка списка при первой загрузке (4.56): серые силуэты строк — аватар и две строки текста — вместо пустого экрана
 * с крутилкой. Сразу видно, что и где появится. Слегка «дышит», пока видна (если анимации в Android не выключены).
 *
 * Вид аватара — по android:tag в разметке: "square" — квадрат (обложка трека), иначе круг (человек, диалог).
 * Показ — с той же задержкой, что у индикатора ([R.integer.progress_show_delay_ms]): быстрая загрузка не мигает.
 */
class SkeletonView @JvmOverloads constructor(context: Context, attrs: AttributeSet? = null) : View(context, attrs) {

    private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = MaterialColors.getColor(this@SkeletonView, com.google.android.material.R.attr.colorSurfaceContainerHigh)
    }
    private val density = resources.displayMetrics.density
    private val square get() = tag == "square"
    private val rowPx get() = (if (square) SQUARE_ROW_DP else ROW_DP) * density
    private val avatarPx get() = (if (square) SQUARE_AVATAR_DP else AVATAR_DP) * density
    private val rect = RectF()
    private var pulse: ValueAnimator? = null
    private val showDelayMs = resources.getInteger(R.integer.progress_show_delay_ms).toLong()
    private val reveal = Runnable {
        isPending = false
        visibility = VISIBLE
    }

    init {
        importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_YES
        contentDescription = context.getString(R.string.loading_list)
    }

    /** Показ назначен, но задержка ещё не прошла. */
    var isPending = false
        private set

    /** Показать (с задержкой). */
    fun show() {
        if (visibility == VISIBLE || isPending) return
        isPending = true
        postDelayed(reveal, showDelayMs)
    }

    fun hide() {
        removeCallbacks(reveal)
        isPending = false
        visibility = GONE
    }

    override fun onDraw(canvas: Canvas) {
        val pad = PADDING_DP * density
        val avatar = avatarPx
        val row = rowPx
        val textStart = pad + avatar + pad
        val lineH = LINE_DP * density
        val radius = lineH / 2
        var top = 0f
        var i = 0
        while (top < height) {
            val cy = top + row / 2
            rect.set(pad, cy - avatar / 2, pad + avatar, cy + avatar / 2)
            if (square) canvas.drawRoundRect(rect, 6 * density, 6 * density, paint) else canvas.drawOval(rect, paint)
            val free = width - textStart - pad
            // Разная длина строк — меньше похоже на сетку, больше на список.
            val first = free * FIRST[i % FIRST.size]
            val second = free * SECOND[i % SECOND.size]
            rect.set(textStart, cy - lineH - 3 * density, textStart + first, cy - 3 * density)
            canvas.drawRoundRect(rect, radius, radius, paint)
            rect.set(textStart, cy + 5 * density, textStart + second, cy + 5 * density + lineH * 0.85f)
            canvas.drawRoundRect(rect, radius, radius, paint)
            top += row
            i++
        }
    }

    override fun onVisibilityChanged(changedView: View, visibility: Int) {
        super.onVisibilityChanged(changedView, visibility)
        if (isShown) startPulse() else stopPulse()
    }

    override fun onDetachedFromWindow() {
        stopPulse()
        removeCallbacks(reveal)
        super.onDetachedFromWindow()
    }

    private fun startPulse() {
        if (pulse != null || !ValueAnimator.areAnimatorsEnabled()) return
        pulse = ValueAnimator.ofFloat(1f, 0.55f).apply {
            duration = PULSE_MS
            repeatMode = ValueAnimator.REVERSE
            repeatCount = ValueAnimator.INFINITE
            addUpdateListener { alpha = it.animatedValue as Float }
            start()
        }
    }

    private fun stopPulse() {
        pulse?.cancel()
        pulse = null
        alpha = 1f
    }

    private companion object {
        const val ROW_DP = 72f
        const val AVATAR_DP = 52f
        const val SQUARE_ROW_DP = 64f
        const val SQUARE_AVATAR_DP = 48f
        const val PADDING_DP = 16f
        const val LINE_DP = 14f
        const val PULSE_MS = 800L
        val FIRST = floatArrayOf(0.45f, 0.6f, 0.35f, 0.55f)
        val SECOND = floatArrayOf(0.8f, 0.65f, 0.75f, 0.5f)
    }
}
