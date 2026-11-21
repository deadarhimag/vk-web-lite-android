package com.example.vkweb.ui

import android.app.Activity
import android.content.pm.ActivityInfo
import android.graphics.drawable.ColorDrawable
import android.view.View
import android.view.ViewGroup
import android.webkit.WebChromeClient
import android.widget.FrameLayout
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import com.example.vkweb.R

/** Полноэкранное видео из WebView. */
class FullscreenController(
    private val activity: Activity,
    private val normalContent: View,
    /** Поворачивать ли в альбомную на время полного экрана (настройка «Поворачивать горизонтальное видео», 3.88). */
    private val rotate: () -> Boolean = { false },
    /**
     * Можно ли странице во весь экран (4.38): видео скрытой страницы — нет. Отчёт: «пропадание меню сверху» —
     * весь экран со шапкой прятался под чёрное полноэкранное видео страницы, которое человек не запускал.
     */
    private val allowed: () -> Boolean = { true },
) {
    private var customView: View? = null
    private var callback: WebChromeClient.CustomViewCallback? = null

    val isActive: Boolean get() = customView != null

    fun enter(view: View, callback: WebChromeClient.CustomViewCallback) {
        if (isActive) {
            callback.onCustomViewHidden()
            return
        }
        if (!allowed()) {
            com.example.vkweb.diag.AppLog.i(com.example.vkweb.diag.LogTag.PAGE, "полный экран скрытой страницы отклонён")
            callback.onCustomViewHidden()
            return
        }
        com.example.vkweb.diag.AppLog.i(com.example.vkweb.diag.LogTag.PAGE, "видео страницы во весь экран")
        customView = view
        this.callback = callback
        // 4.88: видео страницы во весь экран — экран не гаснет, пока этот вид на экране (уходит вместе с ним в dismiss).
        view.keepScreenOn = true
        // Обычное содержимое скрыто, а фон окна у главного экрана убран: под видео (и по краям от него) — чёрный.
        activity.window.setBackgroundDrawable(ColorDrawable(activity.getColor(R.color.media_background)))
        decor.addView(
            view, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT),
        )
        normalContent.visibility = View.GONE
        insets.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        insets.hide(WindowInsetsCompat.Type.systemBars())
        // Видео страницы во весь экран (VK показывает так горизонтальные ролики) — альбомно, даже без автоповорота.
        if (rotate()) activity.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
    }

    /** Выход по кнопке «Назад»: сообщаем странице, она в ответ вызовет [dismiss]. */
    fun exit() {
        val cb = callback
        dismiss()
        cb?.onCustomViewHidden()
    }

    /** Выход по инициативе страницы. Повторный вызов безопасен. */
    fun dismiss() {
        val view = customView ?: return
        decor.removeView(view)
        customView = null
        callback = null
        normalContent.visibility = View.VISIBLE
        // Содержимое снова закрывает окно целиком: фон под ним не нужен (см. MainActivity.onCreate).
        activity.window.setBackgroundDrawable(null)
        insets.show(WindowInsetsCompat.Type.systemBars())
        activity.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_FULL_USER
    }

    private val decor: FrameLayout get() = activity.window.decorView as FrameLayout

    private val insets: WindowInsetsControllerCompat
        get() = WindowCompat.getInsetsController(activity.window, activity.window.decorView)
}
