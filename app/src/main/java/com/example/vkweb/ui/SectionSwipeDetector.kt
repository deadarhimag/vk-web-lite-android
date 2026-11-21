package com.example.vkweb.ui

import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.webkit.WebView
import android.widget.AbsSeekBar

/**
 * Касания для перехода между разделами свайпом (4.13): окно отдаёт сюда все касания ([onTouch] из dispatchTouchEvent),
 * ничего не перехватывается — экраны получают касания как обычно, а по отпусканию пальца решается, был ли это свайп
 * ([SectionSwipe]).
 *
 * Не свайп разделов: начало у левого и правого края (там меню приложения и системное «Назад»), второй палец и касание
 * того, что само листается вбок (полоса фильтров, ряд фото в записи, ползунок) — у них свой жест.
 */
class SectionSwipeDetector(
    private val root: View,
    private val onSwipe: (SectionSwipe.Direction) -> Unit,
) {
    private val density = root.resources.displayMetrics.density
    private var tracking = false
    private var downX = 0f
    private var downY = 0f
    private var downAt = 0L

    /**
     * @return true — отпускание пальца завершило свайп разделов: окно должно отдать экранам под пальцем отмену вместо
     * отпускания (4.40). Иначе короткий свайп внутри одной карточки был ещё и нажатием на неё: отчёт 4.39 — свайп к
     * подборке «ТВ-каналы» открыл ролик со старой подборки («самопроизвольные запуски видео и клипов»).
     */
    private fun onTouch(event: MotionEvent): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                downX = event.rawX
                downY = event.rawY
                downAt = event.eventTime
                tracking = startAllowed(event)
            }
            MotionEvent.ACTION_POINTER_DOWN, MotionEvent.ACTION_CANCEL -> tracking = false
            MotionEvent.ACTION_UP -> {
                if (!tracking) return false
                tracking = false
                val direction = SectionSwipe.direction(
                    event.rawX - downX, event.rawY - downY, event.eventTime - downAt, Limits.MIN_DISTANCE_DP * density,
                ) ?: return false
                onSwipe(direction)
                return true
            }
        }
        return false
    }

    /** Свайп принимается с этого касания: решается в его начале и до конца не меняется. */
    private var armed = false

    /**
     * Касание окна целиком (из Activity.dispatchTouchEvent; одно для главного окна и сообщений — код ревью 4.43):
     * [allowed] решает в начале касания, следить ли за свайпом; распознанный свайп отдаёт экранам под пальцем отмену
     * вместо отпускания (4.40) — свайп не нажимает карточку. [dispatch] — обычная раздача касания окном.
     */
    fun dispatch(event: MotionEvent, allowed: () -> Boolean, dispatch: (MotionEvent) -> Boolean): Boolean {
        if (event.actionMasked == MotionEvent.ACTION_DOWN) armed = allowed()
        if (!armed || !onTouch(event)) return dispatch(event)
        val cancel = MotionEvent.obtain(event).apply { action = MotionEvent.ACTION_CANCEL }
        dispatch(cancel)
        cancel.recycle()
        return true
    }

    /** Окно сейчас не принимает свайп (меню открыто, не тот экран): забыть начатое. */
    fun reset() {
        tracking = false
    }

    private fun startAllowed(event: MotionEvent): Boolean {
        val edge = Limits.EDGE_DP * density
        val width = root.width
        if (width <= 0 || event.rawX < edge || event.rawX > width - edge) return false
        return !scrollsSideways(root, event.rawX.toInt(), event.rawY.toInt())
    }

    /** Под пальцем есть видимое, что само листается вбок. Страница VK (WebView) не в счёт: над ней свой экран. */
    private fun scrollsSideways(view: View, x: Int, y: Int): Boolean {
        if (!view.isShown || view is WebView) return false
        view.getLocationOnScreen(location)
        if (x < location[0] || x >= location[0] + view.width || y < location[1] || y >= location[1] + view.height) return false
        if (view is AbsSeekBar || view.javaClass.name.endsWith("Slider")) return true
        if (view.canScrollHorizontally(1) || view.canScrollHorizontally(-1)) return true
        if (view is ViewGroup) {
            for (i in view.childCount - 1 downTo 0) if (scrollsSideways(view.getChildAt(i), x, y)) return true
        }
        return false
    }

    private val location = IntArray(2)

    private object Limits {
        /** Не короче: случайное движение пальца при нажатии — не свайп. */
        const val MIN_DISTANCE_DP = 96
        /** У краёв — меню приложения (слева) и системное «Назад» (оба края). */
        const val EDGE_DP = 40
    }
}
