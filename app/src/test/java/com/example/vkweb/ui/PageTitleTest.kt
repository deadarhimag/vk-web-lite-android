package com.example.vkweb.ui

import org.junit.Assert.assertEquals
import org.junit.Test

class PageTitleTest {

    @Test fun `убирается название сайта`() {
        assertEquals("Иван Петров", PageTitle.clean("Иван Петров | ВКонтакте"))
        assertEquals("Клуб любителей чая", PageTitle.clean("Клуб любителей чая — VK"))
        assertEquals("Фото", PageTitle.clean("  Фото | VK  "))
    }

    @Test fun `пустые и служебные заголовки не показываются`() {
        assertEquals(null, PageTitle.clean(null))
        assertEquals(null, PageTitle.clean(""))
        assertEquals(null, PageTitle.clean("ВКонтакте"))
        assertEquals(null, PageTitle.clean("https://m.vk.com/club1"))
    }

    @Test fun `адрес без схемы и номер страницы не показываются`() {
        assertEquals(null, PageTitle.clean("m.vk.ru/club14595052"))
        assertEquals(null, PageTitle.clean("m.vk.com/id1"))
        assertEquals(null, PageTitle.clean("club14595052"))
        assertEquals(null, PageTitle.clean("public123"))
        assertEquals("Клуб 1", PageTitle.clean("Клуб 1 | VK"))
        assertEquals("Иван Петров", PageTitle.clean("Иван Петров"))
    }

    @Test fun `дефис внутри названия сохраняется`() {
        assertEquals("Санкт-Петербург", PageTitle.clean("Санкт-Петербург | ВКонтакте"))
    }
}
