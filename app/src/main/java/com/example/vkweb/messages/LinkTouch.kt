package com.example.vkweb.messages

import android.annotation.SuppressLint
import android.text.Spanned
import android.text.style.ClickableSpan
import android.view.MotionEvent
import android.view.View
import android.widget.TextView

/**
 * Нажатие на ссылку в тексте сообщения — открывает её; нажатие мимо ссылок не перехватывается и достаётся пузырю
 * (он открывает действия с сообщением). Обычный способ (LinkMovementMethod) делал текст нажимаемым целиком: нажатие на
 * текст не доходило до пузыря, а на ссылку срабатывало дважды — и ссылка, и меню.
 */
class LinkTouch : View.OnTouchListener {
    private var pressed: ClickableSpan? = null

    @SuppressLint("ClickableViewAccessibility") // доступность: ссылки есть и в меню сообщения («Открыть ссылку»)
    override fun onTouch(v: View, e: MotionEvent): Boolean {
        val view = v as? TextView ?: return false
        return when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                pressed = spanAt(view, e)
                pressed != null
            }
            MotionEvent.ACTION_UP -> {
                val span = pressed
                pressed = null
                if (span != null && spanAt(view, e) === span) span.onClick(view)
                span != null
            }
            MotionEvent.ACTION_CANCEL -> {
                val had = pressed != null
                pressed = null
                had
            }
            else -> pressed != null
        }
    }

    /** Ссылка под пальцем: только в пределах самой строки (нажатие справа от короткой строки — мимо). */
    private fun spanAt(view: TextView, e: MotionEvent): ClickableSpan? {
        val layout = view.layout ?: return null
        val text = view.text as? Spanned ?: return null
        val x = e.x - view.totalPaddingLeft + view.scrollX
        val y = e.y - view.totalPaddingTop + view.scrollY
        if (y < 0 || y > layout.height) return null
        val line = layout.getLineForVertical(y.toInt())
        if (x < layout.getLineLeft(line) || x > layout.getLineRight(line)) return null
        val offset = layout.getOffsetForHorizontal(line, x)
        return text.getSpans(offset, offset, ClickableSpan::class.java).firstOrNull()
    }
}
