package com.example.vkweb.ui

import android.graphics.Outline
import android.view.View
import android.view.ViewOutlineProvider
import android.view.inputmethod.InputMethodManager

/** Обрезка вида по кругу (аватары) или со скруглёнными углами (обложки, фото); клавиатура у поля ввода. */
object Views {
    /** Заглушка обложки трека или плейлиста (нота), пока обложка не загрузилась или её нет. */
    fun musicPlaceholder(view: View): android.graphics.drawable.Drawable? =
        androidx.appcompat.content.res.AppCompatResources.getDrawable(view.context, com.example.vkweb.R.drawable.ic_section_music)

    /** Спрятать клавиатуру поля [view] (поиск запущен, экран сменился). */
    fun hideKeyboard(view: View) {
        view.context.getSystemService(InputMethodManager::class.java)?.hideSoftInputFromWindow(view.windowToken, 0)
    }

    /** Фокус и клавиатура — полю [view] (ответ на комментарий). */
    fun showKeyboard(view: View) {
        view.requestFocus()
        view.context.getSystemService(InputMethodManager::class.java)?.showSoftInput(view, InputMethodManager.SHOW_IMPLICIT)
    }

    fun clipCircle(view: View) {
        view.outlineProvider = object : ViewOutlineProvider() {
            override fun getOutline(v: View, outline: Outline) = outline.setOval(0, 0, v.width, v.height)
        }
        view.clipToOutline = true
    }

    fun clipRounded(view: View, radiusPx: Float) {
        view.outlineProvider = object : ViewOutlineProvider() {
            override fun getOutline(v: View, outline: Outline) = outline.setRoundRect(0, 0, v.width, v.height, radiusPx)
        }
        view.clipToOutline = true
    }

    fun dp(view: View, value: Int): Int = (value * view.resources.displayMetrics.density).toInt()
}
