package com.example.vkweb.ui

import android.text.Spannable
import android.text.method.LinkMovementMethod
import android.text.style.ClickableSpan
import android.view.MotionEvent
import android.widget.TextView

/**
 * Нажатия по ссылкам в тексте. В отличие от [LinkMovementMethod] касание мимо ссылки не забирается
 * у TextView, а уходит родителю — так нажатие на текст поста открывает сам пост.
 * После установки TextView нужно сделать некликабельным (см. [attach]).
 */
object LinkOnlyMovementMethod : LinkMovementMethod() {

    fun attach(view: TextView) {
        view.movementMethod = this
        // setMovementMethod делает TextView кликабельным — тогда касания не дошли бы до строки списка.
        view.isClickable = false
        view.isLongClickable = false
        view.isFocusable = false
    }

    override fun onTouchEvent(widget: TextView, buffer: Spannable, event: MotionEvent): Boolean {
        val action = event.action
        if (action != MotionEvent.ACTION_DOWN && action != MotionEvent.ACTION_UP) return false
        val layout = widget.layout ?: return false
        val x = event.x.toInt() - widget.totalPaddingLeft + widget.scrollX
        val y = event.y.toInt() - widget.totalPaddingTop + widget.scrollY
        val line = layout.getLineForVertical(y)
        if (x < layout.getLineLeft(line) || x > layout.getLineRight(line)) return false
        val offset = layout.getOffsetForHorizontal(line, x.toFloat())
        val span = buffer.getSpans(offset, offset, ClickableSpan::class.java).firstOrNull() ?: return false
        if (action == MotionEvent.ACTION_UP) span.onClick(widget)
        return true
    }
}
