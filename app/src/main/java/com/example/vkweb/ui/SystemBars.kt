package com.example.vkweb.ui

import android.app.Activity
import android.graphics.Color
import android.os.Build
import android.view.View
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import com.google.android.material.appbar.MaterialToolbar

/**
 * Экран под системными панелями (edge-to-edge, 3.94). С targetSdk 35 Android 15 рисует приложение под строкой
 * состояния и панелью навигации всегда, а цвета панелей из темы игнорирует. Чтобы вид был одинаковым на всех версиях,
 * так рисуем и на старых: панель приложения продлевается под строку состояния своим цветом, а содержимое отступает от
 * панели навигации и клавиатуры.
 *
 * Клавиатура: в этом режиме adjustResize окно не сжимает — нижний отступ сам берёт высоту клавиатуры, поэтому поле
 * ввода и страница по-прежнему поднимаются над ней.
 */
object SystemBars {

    private val SIDES = WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout()

    /** Вызывать после setContentView. Цвет значков строки состояния и навигации — из темы (windowLight…). */
    fun edgeToEdge(activity: Activity) {
        val window = activity.window
        WindowCompat.setDecorFitsSystemWindows(window, false)
        @Suppress("DEPRECATION")
        window.statusBarColor = Color.TRANSPARENT
        @Suppress("DEPRECATION")
        window.navigationBarColor = Color.TRANSPARENT
        // Навигация тремя кнопками: система кладёт под них полупрозрачную подложку — кнопки видны на любом фоне.
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) window.isNavigationBarContrastEnforced = true
    }

    /**
     * Проигрыватель во весь экран: системные панели скрыты, показываются на время свайпом от края (видео и клипы — одно
     * правило, код ревью 4.43).
     */
    fun immersive(activity: Activity) {
        val window = activity.window
        WindowCompat.setDecorFitsSystemWindows(window, false)
        androidx.core.view.WindowInsetsControllerCompat(window, window.decorView).apply {
            hide(WindowInsetsCompat.Type.systemBars())
            systemBarsBehavior = androidx.core.view.WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        }
    }

    /**
     * Панель приложения продлевается под строку состояния (и вырез экрана сверху): высота — своя плюс отступ, фон — её
     * цвет. Боковые отступы — у содержимого ([bottom]), в которое панель вложена.
     */
    fun toolbar(toolbar: MaterialToolbar) = top(toolbar)

    /** Заголовок колонки (список диалогов в двух колонках) — как панель приложения: под строкой состояния. */
    fun paneTop(view: View) = top(view)

    private fun top(toolbar: View) {
        val baseHeight = toolbar.layoutParams.height
        val start = Padding(toolbar)
        ViewCompat.setOnApplyWindowInsetsListener(toolbar) { view, insets ->
            val bars = insets.getInsets(SIDES)
            view.setPadding(start.left, start.top + bars.top, start.right, start.bottom)
            if (baseHeight > 0) {
                val params = view.layoutParams
                val wanted = baseHeight + bars.top
                if (params.height != wanted) {
                    params.height = wanted
                    view.layoutParams = params
                }
            }
            insets
        }
    }

    /**
     * Нижний край содержимого: отступ от панели навигации или клавиатуры — что выше. Слева и справа — от выреза и боковой
     * панели навигации. Верх не трогается (его занимает [toolbar]).
     */
    fun bottom(view: View, sides: Boolean = true, bar: BottomBar? = null) = pad(view, sides = sides, top = false, bar = bar)

    /**
     * Нижняя панель разделов (4.50) под содержимым: пока она видна, она сама стоит над панелью навигации (её отступ —
     * у неё), и содержимому нижний отступ не нужен. Клавиатура панель прячет: о ней сообщает [onImeChanged].
     */
    interface BottomBar {
        /** Панель на экране, если клавиатура закрыта. */
        val isShown: Boolean
        fun onImeChanged(visible: Boolean)
    }

    /** Весь экран без своей панели под строкой состояния (отчёт о сбое): отступы со всех сторон. */
    fun all(view: View) = pad(view, sides = true, top = true)

    /** Отступы содержимого от панелей и клавиатуры (одно правило для [bottom] и [all] — код ревью 4.43). */
    private fun pad(view: View, sides: Boolean, top: Boolean, bar: BottomBar? = null) {
        val start = Padding(view)
        ViewCompat.setOnApplyWindowInsetsListener(view) { v, insets ->
            val bars = insets.getInsets(SIDES)
            val ime = insets.getInsets(WindowInsetsCompat.Type.ime())
            val imeOn = insets.isVisible(WindowInsetsCompat.Type.ime())
            bar?.onImeChanged(imeOn)
            // Под нижней панелью разделов отступ от панели навигации не нужен: она сама над ней. С клавиатурой панели нет.
            val bottom = if (bar?.isShown == true && !imeOn) 0 else maxOf(bars.bottom, ime.bottom)
            v.setPadding(
                start.left + if (sides) bars.left else 0,
                start.top + if (top) bars.top else 0,
                start.right + if (sides) bars.right else 0,
                start.bottom + bottom,
            )
            insets
        }
        refreshOnFocus(view)
    }

    /**
     * Окно снова в фокусе (закрылось окно поверх — с полем ввода и своей клавиатурой): пересчитать отступы (4.10).
     * Клавиатура принадлежала тому окну, и экран под ним об её уходе не узнавал — нижний отступ оставался высотой
     * клавиатуры, и содержимое так и висело поднятым, пока что-нибудь не перестроит экран.
     */
    private fun refreshOnFocus(view: View) {
        view.viewTreeObserver.addOnWindowFocusChangeListener { hasFocus ->
            if (hasFocus) ViewCompat.requestApplyInsets(view)
        }
    }

    /** Исходные отступы из разметки: к ним прибавляются системные, а не накапливаются при каждом пересчёте. */
    private class Padding(view: View) {
        val left = view.paddingLeft
        val top = view.paddingTop
        val right = view.paddingRight
        val bottom = view.paddingBottom
    }
}
