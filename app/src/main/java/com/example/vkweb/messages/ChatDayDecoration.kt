package com.example.vkweb.messages

import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.RectF
import android.util.TypedValue
import android.view.View
import androidx.recyclerview.widget.RecyclerView
import com.example.vkweb.ui.Views
import com.google.android.material.color.MaterialColors

/**
 * Разделители дней в переписке (3.94): плашка «Сегодня», «Вчера», «12 октября» над первым сообщением дня. Рисуется
 * поверх списка, а не отдельной строкой: модель переписки, слияние со свежим и привязка прокрутки к сообщению
 * ([ListUpdates]) остаются как были. Даты — [ChatMessage.dateSec]; где их нет, разделителей нет.
 */
class ChatDayDecoration(private val list: RecyclerView, private val adapter: ChatAdapter) : RecyclerView.ItemDecoration() {

    private val pillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = MaterialColors.getColor(list, com.google.android.material.R.attr.colorSurfaceContainerHigh)
    }
    private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = MaterialColors.getColor(list, com.google.android.material.R.attr.colorOnSurfaceVariant)
        textAlign = Paint.Align.CENTER
        // 4.1: через TypedValue, а не scaledDensity (устарел): с Android 14 крупный шрифт растёт нелинейно, и плашка
        // считалась бы не так, как остальной текст.
        textSize = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP, TEXT_SP, list.resources.displayMetrics)
        isFakeBoldText = true
    }
    private val padPx = Views.dp(list, PILL_PAD_DP).toFloat()
    /** При крупном шрифте плашка и отступ над сообщением растут вместе с текстом, а не обрезают его. */
    private val pillHeightPx = maxOf(Views.dp(list, PILL_HEIGHT_DP).toFloat(), textPaint.textSize * PILL_TO_TEXT)
    private val headerPx = maxOf(Views.dp(list, HEADER_DP), (pillHeightPx + Views.dp(list, HEADER_DP - PILL_HEIGHT_DP)).toInt())
    private val pill = RectF()
    private val textBounds = Rect()

    /** Дни сообщений, посчитанные для текущего списка (пересчёт — только когда список сменился). */
    private var daysFor: List<ChatMessage>? = null
    private var days: List<Long?> = emptyList()

    private fun headerDay(position: Int): Long? {
        val items = adapter.currentList
        if (items !== daysFor) {
            daysFor = items
            days = ChatDays.days(items)
        }
        return ChatDays.headerAt(days, position)
    }

    override fun getItemOffsets(outRect: Rect, view: View, parent: RecyclerView, state: RecyclerView.State) {
        val position = parent.getChildAdapterPosition(view)
        if (position == RecyclerView.NO_POSITION) return
        if (headerDay(position) != null) outRect.top = headerPx
    }

    override fun onDraw(c: Canvas, parent: RecyclerView, state: RecyclerView.State) {
        val now = System.currentTimeMillis() / 1000
        for (i in 0 until parent.childCount) {
            val child = parent.getChildAt(i)
            val position = parent.getChildAdapterPosition(child)
            if (position == RecyclerView.NO_POSITION) continue
            val day = headerDay(position) ?: continue
            val label = ChatDays.label(day, now)
            textPaint.getTextBounds(label, 0, label.length, textBounds)
            val cx = parent.width / 2f
            // Плашка — посередине отступа над сообщением; сдвигается вместе с ним (и при анимации появления).
            val cy = child.top + child.translationY - headerPx / 2f
            val halfWidth = textPaint.measureText(label) / 2f + padPx
            pill.set(cx - halfWidth, cy - pillHeightPx / 2f, cx + halfWidth, cy + pillHeightPx / 2f)
            pillPaint.alpha = (child.alpha * 255).toInt()
            textPaint.alpha = pillPaint.alpha
            c.drawRoundRect(pill, pillHeightPx / 2f, pillHeightPx / 2f, pillPaint)
            c.drawText(label, cx, cy - textBounds.exactCenterY(), textPaint)
        }
    }

    private companion object {
        const val HEADER_DP = 40
        const val PILL_HEIGHT_DP = 24
        const val PILL_PAD_DP = 12
        const val TEXT_SP = 12f
        /** Высота плашки к размеру шрифта: 24dp при 12sp. */
        const val PILL_TO_TEXT = 2f
    }
}
