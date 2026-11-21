package com.example.vkweb.text

import kotlin.math.abs

/**
 * Склонение по числу (3.94: одно место вместо копий в списках людей и в VK Видео): 1 друг, 2 друга, 5 друзей;
 * 11–14 — всегда «многие»; отрицательные — как положительные. Для строк из ресурсов есть `<plurals>`; здесь — для
 * текстов, которые собираются в коде и проверяются обычными тестами.
 */
object RuPlural {

    fun pick(n: Long, one: String, few: String, many: String): String {
        val a = abs(n)
        val mod100 = a % 100
        val mod10 = a % 10
        return when {
            mod100 in 11..14 -> many
            mod10 == 1L -> one
            mod10 in 2..4 -> few
            else -> many
        }
    }

    /** «5 друзей». */
    fun count(n: Long, one: String, few: String, many: String): String = "$n " + pick(n, one, few, many)
}
