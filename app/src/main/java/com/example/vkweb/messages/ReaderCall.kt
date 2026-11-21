package com.example.vkweb.messages

/**
 * Текст вызова функции скрипта чтения (assets/vk_reader.js, window.__vkReader) — 4.66. Без зависимостей от Android:
 * проверяется тестами. Функции нет (скрипт не встал, старая версия) — вызов возвращает false, а не бросает TypeError.
 */
object ReaderCall {
    private val NAME = Regex("^[A-Za-z][A-Za-z0-9]{0,40}$")

    /** [args] — уже готовые выражения JS (строки — в кавычках, числа, массивы). */
    fun script(name: String, vararg args: String): String {
        require(NAME.matches(name)) { "Недопустимое имя функции: $name" }
        val f = "window.__vkReader.$name"
        return "(window.__vkReader&&typeof $f==='function')?$f(${args.joinToString(",")}):false"
    }
}
