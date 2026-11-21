package com.example.vkweb.ui

import android.app.Activity
import android.os.Build
import android.os.SystemClock
import android.view.View
import android.view.ViewTreeObserver

/**
 * Заставка запуска (4.54, Android 12+) держится, пока экран не покажет сохранённое, — но не дольше [MAX_MS]: после неё
 * сразу видны данные, а не пустое окно, которое через мгновение заполнится.
 *
 * Только один раз за запуск процесса (холодный старт): переходы между окнами потом заставкой не задерживаются.
 */
object SplashHold {

    private const val MAX_MS = 1000L
    private const val RECHECK_MS = 50L

    /** Заставку уже держали (или она не нужна) в этом процессе. */
    private var used = false

    /** Готово, можно показывать: [Activity] вызывает, когда сохранённое на экране (или его нет). */
    @Volatile var ready = false

    /** Держать заставку до [ready] на этом окне. Вызывать в onCreate после setContentView. */
    fun hold(activity: Activity) {
        if (used) return
        used = true
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) return
        val content = activity.findViewById<View>(android.R.id.content) ?: return
        val start = SystemClock.uptimeMillis()
        content.viewTreeObserver.addOnPreDrawListener(object : ViewTreeObserver.OnPreDrawListener {
            override fun onPreDraw(): Boolean {
                if (ready || activity.isFinishing || SystemClock.uptimeMillis() - start >= MAX_MS) {
                    content.viewTreeObserver.removeOnPreDrawListener(this)
                    return true
                }
                // Кадр отложен: проверить ещё раз чуть позже.
                content.postDelayed({ content.invalidate() }, RECHECK_MS)
                return false
            }
        })
    }
}
