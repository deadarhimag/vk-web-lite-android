package com.example.vkweb.ui

import android.app.ActivityOptions
import android.content.Context
import android.os.Bundle

/**
 * Переход между окнами при смене раздела (4.50): сообщения и остальные разделы — разные окна, но для человека это
 * переключение вкладки нижней панели или меню. Анимация «открылось новое окно» здесь лишняя.
 */
object Transitions {
    /** Для startActivity(intent, options): без анимации открытия и закрытия. */
    fun none(context: Context): Bundle = ActivityOptions.makeCustomAnimation(context, 0, 0).toBundle()
}
