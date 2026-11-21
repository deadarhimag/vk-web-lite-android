package com.example.vkweb.clips

import android.os.Handler
import android.os.Looper
import android.view.View

/**
 * Интерфейс поверх клипа (4.31): автор, подпись, «нравится», комментарии, перемотка, крестик. По умолчанию скрыт —
 * видно только ролик. Нажатие на экран показывает его; пока ролик играет, через [HIDE_MS] без действий он снова
 * уходит. На паузе и пока тянут перемотку — виден. С TalkBack ([alwaysShown]) — виден всегда: искать кнопки на ощупь
 * по невидимому экрану нельзя.
 */
class ClipsChrome(
    private val views: List<View>,
    private val alwaysShown: () -> Boolean,
) {
    private val handler = Handler(Looper.getMainLooper())
    private val hide = Runnable { apply(false, animate = true) }

    /** Виден ли интерфейс сейчас. */
    var isShown = false
        private set

    /** Пауза или перетаскивание перемотки: не прятать. */
    private var held = false

    /** Начальное состояние: скрыт (или виден всегда, если так нужно). */
    fun init() = apply(false, animate = false)

    /** Нажали на экран при скрытом интерфейсе. */
    fun show() {
        apply(true, animate = true)
        scheduleHide()
    }

    /** Новый ролик: снова только видео. */
    fun reset() {
        held = false
        handler.removeCallbacks(hide)
        apply(false, animate = false)
    }

    /** Нажали кнопку интерфейса: отсчёт до скрытия — заново. */
    fun touched() = scheduleHide()

    /** Пауза или перетаскивание — держать видимым; отпустили — спрятать позже. */
    fun hold(value: Boolean) {
        held = value
        if (value) {
            handler.removeCallbacks(hide)
            apply(true, animate = true)
        } else {
            scheduleHide()
        }
    }

    fun release() = handler.removeCallbacks(hide)

    private fun scheduleHide() {
        handler.removeCallbacks(hide)
        if (isShown && !held && !alwaysShown()) handler.postDelayed(hide, HIDE_MS)
    }

    private fun apply(shown: Boolean, animate: Boolean) {
        val target = shown || alwaysShown()
        isShown = target
        views.forEach { view ->
            view.animate().cancel()
            when {
                target -> {
                    if (view.visibility != View.VISIBLE) {
                        view.alpha = if (animate) 0f else 1f
                        view.visibility = View.VISIBLE
                    }
                    if (animate) view.animate().alpha(1f).setDuration(FADE_MS).start() else view.alpha = 1f
                }
                animate -> view.animate().alpha(0f).setDuration(FADE_MS).withEndAction { view.visibility = View.INVISIBLE }.start()
                else -> view.visibility = View.INVISIBLE
            }
        }
    }

    private companion object {
        const val HIDE_MS = 3_000L
        const val FADE_MS = 150L
    }
}
