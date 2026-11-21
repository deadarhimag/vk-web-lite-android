package com.example.vkweb.messages

import android.view.View
import android.widget.TextView
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.example.vkweb.R

/**
 * Кнопка «вниз» в переписке (3.94): видна, когда список прокручен вверх больше чем на пару сообщений; нажатие — к
 * последнему (близко — плавно, далеко — сразу). На кнопке — сколько новых входящих пришло, пока человек читал выше;
 * доехал до конца — счётчик сбрасывается.
 */
class ChatScrollDown(
    private val list: RecyclerView,
    private val container: View,
    button: View,
    private val counter: TextView,
    private val isChatShown: () -> Boolean,
) {
    private var unseen = 0

    init {
        button.setOnClickListener { scrollToEnd() }
        list.addOnScrollListener(object : RecyclerView.OnScrollListener() {
            override fun onScrolled(recyclerView: RecyclerView, dx: Int, dy: Int) = update()
        })
    }

    /** Пришли [count] новых входящих, а человек не внизу. */
    fun addUnseen(count: Int) {
        if (count <= 0) return
        unseen += count
        update()
    }

    /** Другая переписка или список диалогов: кнопки и счётчика нет. */
    fun reset() {
        unseen = 0
        setShown(false)
    }

    fun update() {
        val last = lastVisible()
        val size = list.adapter?.itemCount ?: 0
        val away = size > 0 && last != RecyclerView.NO_POSITION && last < size - 1 - NEAR_END_ITEMS
        if (!away) unseen = 0
        setShown(away && isChatShown())
        counter.visibility = if (unseen > 0) View.VISIBLE else View.GONE
        counter.text = if (unseen > MAX_SHOWN) "$MAX_SHOWN+" else unseen.toString()
        container.contentDescription = if (unseen > 0) {
            container.resources.getQuantityString(R.plurals.chat_scroll_down_new, unseen, unseen)
        } else {
            null
        }
    }

    private fun scrollToEnd() {
        val size = list.adapter?.itemCount ?: return
        if (size == 0) return
        if (size - 1 - lastVisible() <= SMOOTH_MAX_ITEMS) list.smoothScrollToPosition(size - 1) else list.scrollToPosition(size - 1)
        unseen = 0
        update()
    }

    private fun lastVisible(): Int = (list.layoutManager as? LinearLayoutManager)?.findLastVisibleItemPosition() ?: RecyclerView.NO_POSITION

    private fun setShown(shown: Boolean) {
        if (shown == (container.visibility == View.VISIBLE)) return
        container.animate().cancel()
        if (shown) {
            container.alpha = 0f
            container.visibility = View.VISIBLE
            container.animate().alpha(1f).setDuration(FADE_MS).start()
        } else {
            container.animate().alpha(0f).setDuration(FADE_MS).withEndAction { container.visibility = View.GONE }.start()
        }
    }

    private companion object {
        /** Последние пару сообщений не видно — ещё «внизу» (как и автопрокрутка к новым в MessagesActivity). */
        const val NEAR_END_ITEMS = 2
        const val SMOOTH_MAX_ITEMS = 30
        const val MAX_SHOWN = 99
        const val FADE_MS = 150L
    }
}
