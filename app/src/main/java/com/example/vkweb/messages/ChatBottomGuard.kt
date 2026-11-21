package com.example.vkweb.messages

import android.view.ViewTreeObserver
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.example.vkweb.diag.AppLog
import com.example.vkweb.diag.LogTag

/**
 * «Внизу — значит внизу» на уровне кадра (4.86). Человек у последнего сообщения, список не листают, и пришло обновление
 * (свежая история, отметки прочтения, картинки реакций): если после раскладки последнее сообщение оказалось не целиком
 * на экране, кадр не рисуется — список возвращается к последнему, и рисуется уже правильный кадр.
 *
 * Запись 4.84–4.85: сразу после открытия переписки, на первом ответе страницы, раскладка ставила переписку на сотни
 * пикселей ниже («последняя=31@2239» при нижнем крае 1747) — переписка прыгала. Исправлять раскладку RecyclerView
 * изнутри нечем, а неверный кадр так и не показывается.
 */
class ChatBottomGuard(private val list: RecyclerView) {

    private var armed = false
    private val check = ViewTreeObserver.OnPreDrawListener { onPreDraw() }

    init {
        // Любая смена данных списка, пока человек внизу, — проверить следующий кадр.
        list.adapter?.registerAdapterDataObserver(object : RecyclerView.AdapterDataObserver() {
            override fun onChanged() = armIfAtBottom()
            override fun onItemRangeChanged(positionStart: Int, itemCount: Int) = armIfAtBottom()
            override fun onItemRangeChanged(positionStart: Int, itemCount: Int, payload: Any?) = armIfAtBottom()
            override fun onItemRangeInserted(positionStart: Int, itemCount: Int) = armIfAtBottom()
            override fun onItemRangeRemoved(positionStart: Int, itemCount: Int) = armIfAtBottom()
            override fun onItemRangeMoved(fromPosition: Int, toPosition: Int, itemCount: Int) = armIfAtBottom()
        })
    }

    /** Сейчас что-то поменяется без уведомления списка (например, текст в строке): проверить следующий кадр. */
    fun armIfAtBottom() {
        if (armed || !isAtBottom()) return
        armed = true
        list.viewTreeObserver.addOnPreDrawListener(check)
    }

    private fun disarm() {
        armed = false
        if (list.viewTreeObserver.isAlive) list.viewTreeObserver.removeOnPreDrawListener(check)
    }

    private fun layout(): LinearLayoutManager? = list.layoutManager as? LinearLayoutManager

    private fun isAtBottom(): Boolean {
        val count = list.adapter?.itemCount ?: 0
        val lm = layout() ?: return false
        return count > 0 && list.isShown && list.scrollState == RecyclerView.SCROLL_STATE_IDLE &&
            lm.findLastCompletelyVisibleItemPosition() == count - 1
    }

    /** @return false — кадр отменён: список вернулся к последнему сообщению и нарисуется заново. */
    private fun onPreDraw(): Boolean {
        disarm()
        val lm = layout() ?: return true
        val count = list.adapter?.itemCount ?: 0
        // Человек листает или идёт плавная прокрутка к новому — не мешаем.
        if (count == 0 || list.scrollState != RecyclerView.SCROLL_STATE_IDLE || lm.isSmoothScrolling) return true
        val bottomEdge = list.height - list.paddingBottom
        val last = lm.findViewByPosition(count - 1)
        if (last != null) {
            if (last.bottom <= bottomEdge + SLACK_PX) return true
            // Последнее выше экрана целиком не поместится — правильного «внизу» для него нет.
            if (last.height > bottomEdge - list.paddingTop) return true
        }
        AppLog.d(
            LogTag.MESSAGES, "переписка: кадр со сдвигом не показан — снова к последнему",
            "последняя" to (last?.bottom ?: "вне экрана"), "нижний край" to bottomEdge,
        )
        list.scrollToPosition(count - 1)
        return false
    }

    private companion object {
        /** Округления раскладки — не сдвиг. */
        const val SLACK_PX = 2
    }
}
