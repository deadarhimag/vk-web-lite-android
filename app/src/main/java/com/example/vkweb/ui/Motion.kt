package com.example.vkweb.ui

import android.animation.ValueAnimator
import android.app.ActivityOptions
import android.os.Bundle
import android.view.View
import android.view.ViewGroup
import androidx.transition.Fade
import androidx.transition.TransitionManager

/**
 * Плавные переходы (4.55). Короткие и только там, где меняется «где я»: список ↔ переписка, появление своего экрана
 * раздела, открытие фото. Если в Android выключены анимации (Спец. возможности → «Убрать анимацию» или масштаб
 * анимации 0) — всё мгновенно, как раньше.
 */
object Motion {

    private const val FADE_MS = 150L

    /** Анимации включены в Android. */
    fun enabled(): Boolean = ValueAnimator.areAnimatorsEnabled()

    /**
     * Смена экрана внутри [container] (список ↔ переписка): короткое растворение, без сдвига (4.83). Сдвиг вбок на 30 dp
     * (4.55) выглядел как рывок: переписка на миг появлялась смещённой и возвращалась. Вызывать перед тем, как поменять
     * видимость.
     *
     * [lists] — списки, которые растворяются целиком, без своих строк (4.80): строки, которые список добавляет и убирает
     * в это время (прежняя переписка ушла, сохранённая пришла), иначе анимировались каждая сама по себе.
     */
    fun crossFade(container: ViewGroup, vararg lists: View) {
        if (!enabled() || !container.isLaidOut) return
        val transition = Fade().setDuration(FADE_MS)
        lists.forEach { transition.excludeChildren(it, true) }
        TransitionManager.beginDelayedTransition(container, transition)
    }

    /** Экран появляется: из прозрачного за [FADE_MS] (если он и так виден — ничего). */
    fun fadeIn(view: View) {
        if (view.visibility == View.VISIBLE) return
        view.visibility = View.VISIBLE
        if (!enabled()) return
        view.alpha = 0f
        view.animate().alpha(1f).setDuration(FADE_MS).withEndAction { view.alpha = 1f }.start()
    }

    /** Окно открывается «из» [source] (фото из пузыря или записи): растёт из его границ. */
    fun fromView(source: View?): Bundle? {
        if (source == null || !enabled() || source.width == 0 || source.height == 0) return null
        return ActivityOptions.makeScaleUpAnimation(source, 0, 0, source.width, source.height).toBundle()
    }
}
