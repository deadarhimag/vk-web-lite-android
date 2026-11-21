package com.example.vkweb.ui

import androidx.annotation.StringRes

/**
 * Панель своего экрана, как её видит [com.example.vkweb.overlay.PageOverlay]: показана ли, есть ли
 * что показать и не сохранённое ли это. Данные панель получает от своего экрана (у каждого — свои).
 */
interface OverlayPanel {
    val isShown: Boolean

    /** Есть что показать (свежее или сохранённое). */
    val hasData: Boolean

    /** Показано сохранённое, свежего от страницы ещё не было. */
    val isShowingSaved: Boolean

    fun hide()

    /** Забыть всё показанное (сохранённое удалили в настройках): как при первом открытии. */
    fun clear()

    /** Общее вокруг данных (4.51: [PageOverlay] ставит «Обновить» и показывает выбор, когда данные не пришли). */
    val frame: PanelFrame

    /** Плашка внизу («нет сети», «не удалось обновить»); null — убрать. */
    fun setBanner(@StringRes text: Int?)
}
