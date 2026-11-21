package com.example.vkweb.web

import com.example.vkweb.config.Tab

/**
 * С чего начинается работа приложения. Без зависимостей от Android: проверяется обычными тестами.
 *
 * Если стартовый раздел — «Сообщения» со своим экраном, страница VK для него не нужна: она загружалась бы впустую
 * под экраном сообщений, а «Назад» с него открывала бы эту страницу — то есть веб-версию.
 */
object StartPolicy {

    /** Старт сразу открывает свой экран сообщений. */
    fun opensNativeMessages(start: Tab, nativeMessages: Boolean): Boolean = start == Tab.MESSAGES && nativeMessages

    /**
     * Раздел, страница которого грузится в главном окне: стартовый или, если он открывается своим экраном, первый
     * обычный. К нему же уводит переход в скрытый раздел.
     */
    fun homeTab(start: Tab, nativeMessages: Boolean, hidden: Set<Tab>): Tab {
        if (!opensNativeMessages(start, nativeMessages)) return start
        return Tab.entries.firstOrNull { it != Tab.MESSAGES && it !in hidden } ?: Tab.DEFAULT
    }
}
