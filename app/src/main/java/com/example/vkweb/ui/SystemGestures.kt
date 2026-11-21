package com.example.vkweb.ui

import android.graphics.Rect
import android.os.Build
import android.view.View
import androidx.core.view.WindowInsetsCompat

/**
 * Элементы, которые тянут пальцем от края экрана (4.1). При жестовой навигации Android считает движение от левого или
 * правого края жестом «Назад»: полоса перемотки видео и клипов идёт от края до края, и перемотка у краёв закрывала
 * проигрыватель. Исключение — на всю полосу; Android сам ограничивает его высоту (до 200dp на край), полосе хватает.
 */
object SystemGestures {

    /**
     * Нижняя полоса проигрывателя — выше зоны системного жеста снизу (4.41). Проигрыватель во весь экран, системные
     * панели скрыты, и полоса перемотки стояла у самого края: движение вверх от края, чтобы достать кнопки Android,
     * перематывало ролик. Снизу этот жест у Android обязательный — исключить его ([exclude]) нельзя, поэтому полоса
     * поднимается на высоту этой зоны (или скрытой панели навигации — что больше); исходный отступ сохраняется.
     */
    fun keepAboveBottomGestures(view: View) {
        val base = view.paddingBottom
        androidx.core.view.ViewCompat.setOnApplyWindowInsetsListener(view) { v, insets ->
            val gestures = insets.getInsets(WindowInsetsCompat.Type.mandatorySystemGestures()).bottom
            val navigation = insets.getInsetsIgnoringVisibility(WindowInsetsCompat.Type.navigationBars()).bottom
            v.setPadding(v.paddingLeft, v.paddingTop, v.paddingRight, base + maxOf(gestures, navigation))
            insets
        }
        androidx.core.view.ViewCompat.requestApplyInsets(view)
    }

    /** Жесты над [view] — её собственные, не системные. Обновляется при каждой смене размера. */
    fun exclude(view: View) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return
        view.addOnLayoutChangeListener { v, left, top, right, bottom, _, _, _, _ ->
            v.systemGestureExclusionRects = listOf(Rect(0, 0, right - left, bottom - top))
        }
    }
}
