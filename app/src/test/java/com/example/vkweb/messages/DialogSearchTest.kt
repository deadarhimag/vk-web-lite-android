package com.example.vkweb.messages

import org.junit.Assert.assertEquals
import org.junit.Test

class DialogSearchTest {

    private fun d(title: String) = DialogEntry(peerId = title, title = title, snippet = "", time = "", unread = false, online = false)
    private val items = listOf(d("Иван Петров"), d("Семён Ёлкин"), d("Работа: проект"), d("Мама"))

    @Test fun `пустой запрос — весь список`() {
        assertEquals(items, DialogSearch.filter(items, "  "))
    }

    @Test fun `регистр, ё и порядок слов не важны`() {
        assertEquals(listOf("Иван Петров"), DialogSearch.filter(items, "петров  ИВАН").map { it.title })
        assertEquals(listOf("Семён Ёлкин"), DialogSearch.filter(items, "семен елк").map { it.title })
    }

    @Test fun `часть слова`() {
        assertEquals(listOf("Работа: проект"), DialogSearch.filter(items, "проек").map { it.title })
        assertEquals(emptyList<String>(), DialogSearch.filter(items, "нет такого").map { it.title })
    }
}
