package com.example.vkweb.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Вид меню разделов (4.50): нижняя панель при жесте «Назад» от края, иначе боковое меню. */
class NavStyleTest {

    @Test fun `закреплённое на широком экране — всегда слева`() {
        assertEquals(NavStyle.PINNED, NavStyle.pick(permanent = true, edgeBackGesture = true))
        assertEquals(NavStyle.PINNED, NavStyle.pick(permanent = true, edgeBackGesture = false))
    }

    @Test fun `жест от края — нижняя панель, кнопки — боковое меню`() {
        assertEquals(NavStyle.BOTTOM, NavStyle.pick(permanent = false, edgeBackGesture = true))
        assertEquals(NavStyle.DRAWER, NavStyle.pick(permanent = false, edgeBackGesture = false))
    }

    @Test fun `жест от края — по зонам жестов с обеих сторон`() {
        assertTrue(NavStyle.edgeBackGesture(34, 60, 60, navigationMode = -1))
        // Три кнопки: зон по бокам нет.
        assertFalse(NavStyle.edgeBackGesture(34, 0, 0, navigationMode = 2))
        // Боковая панель кнопок в альбомной ориентации — зона только с одной стороны.
        assertFalse(NavStyle.edgeBackGesture(34, 0, 126, navigationMode = -1))
    }

    @Test fun `пока окна нет — по настройке Android`() {
        assertTrue(NavStyle.edgeBackGesture(30, null, null, navigationMode = NavStyle.NAVIGATION_MODE_GESTURAL))
        assertFalse(NavStyle.edgeBackGesture(30, null, null, navigationMode = 0))
        assertFalse(NavStyle.edgeBackGesture(30, null, null, navigationMode = -1))
    }

    @Test fun `до Android 10 жеста нет`() {
        assertFalse(NavStyle.edgeBackGesture(28, 60, 60, navigationMode = 2))
    }

    @Test fun `разделы панели — по порядку, скрытые пропускаются`() {
        val preferred = listOf("сообщения", "друзья", "сообщества", "музыка", "видео", "клипы")
        assertEquals(listOf("сообщения", "друзья", "сообщества", "музыка"), NavStyle.bottomSections(preferred, emptySet()))
        assertEquals(listOf("сообщения", "сообщества", "музыка", "видео"), NavStyle.bottomSections(preferred, setOf("друзья")))
        assertEquals(listOf("музыка"), NavStyle.bottomSections(preferred, setOf("сообщения", "друзья", "сообщества", "видео", "клипы")))
    }

    @Test fun `отметка на панели`() {
        val bar = listOf(1, 2, 3, 4)
        assertEquals(2, NavStyle.bottomSelection(2, inOtherSection = false, onBar = bar, more = 99))
        // Раздел не на панели (клипы) или раздел из бокового меню (лента) — «Ещё».
        assertEquals(99, NavStyle.bottomSelection(6, inOtherSection = false, onBar = bar, more = 99))
        assertEquals(99, NavStyle.bottomSelection(null, inOtherSection = true, onBar = bar, more = 99))
        // Страница вне разделов (человек, запись) — отметка не меняется.
        assertNull(NavStyle.bottomSelection(null, inOtherSection = false, onBar = bar, more = 99))
    }

    @Test fun `средняя ширина — полоса значков слева`() {
        // 4.58: от 600dp (и меню не закреплено) — полоса, при любой навигации Android.
        assertEquals(NavStyle.RAIL, NavStyle.pick(permanent = false, edgeBackGesture = true, widthDp = 700))
        assertEquals(NavStyle.RAIL, NavStyle.pick(permanent = false, edgeBackGesture = false, widthDp = 600))
        assertEquals(NavStyle.BOTTOM, NavStyle.pick(permanent = false, edgeBackGesture = true, widthDp = 599))
        assertEquals(NavStyle.DRAWER, NavStyle.pick(permanent = false, edgeBackGesture = false, widthDp = 411))
        // Закреплённое на широком — главнее полосы.
        assertEquals(NavStyle.PINNED, NavStyle.pick(permanent = true, edgeBackGesture = true, widthDp = 900))
    }

    @Test fun `выбор в настройках — нижняя панель при любой навигации и ширине`() {
        val bottom = NavStyle.Choice.BOTTOM
        assertEquals(NavStyle.BOTTOM, NavStyle.pick(permanent = false, edgeBackGesture = false, choice = bottom))
        assertEquals(NavStyle.BOTTOM, NavStyle.pick(permanent = false, edgeBackGesture = true, choice = bottom))
        assertEquals(NavStyle.BOTTOM, NavStyle.pick(permanent = false, edgeBackGesture = false, widthDp = 700, choice = bottom))
    }

    @Test fun `выбор в настройках — боковое меню при любой навигации и ширине`() {
        val drawer = NavStyle.Choice.DRAWER
        assertEquals(NavStyle.DRAWER, NavStyle.pick(permanent = false, edgeBackGesture = true, choice = drawer))
        assertEquals(NavStyle.DRAWER, NavStyle.pick(permanent = false, edgeBackGesture = true, widthDp = 700, choice = drawer))
    }

    @Test fun `закрепление главнее выбора`() {
        NavStyle.Choice.entries.forEach {
            assertEquals(NavStyle.PINNED, NavStyle.pick(permanent = true, edgeBackGesture = true, widthDp = 900, choice = it))
        }
    }

    @Test fun `автоматически — как без выбора`() {
        for (edge in listOf(true, false)) for (width in listOf(0, 411, 599, 600, 900)) {
            assertEquals(
                NavStyle.pick(permanent = false, edgeBackGesture = edge, widthDp = width),
                NavStyle.pick(permanent = false, edgeBackGesture = edge, widthDp = width, choice = NavStyle.Choice.AUTO),
            )
        }
    }

    @Test fun `сохранённый выбор читается, неизвестный — автоматически`() {
        assertEquals(NavStyle.Choice.AUTO, NavStyle.Choice.DEFAULT)
        assertEquals(NavStyle.Choice.AUTO, NavStyle.Choice.fromName(null))
        assertEquals(NavStyle.Choice.AUTO, NavStyle.Choice.fromName("SIDE"))
        NavStyle.Choice.entries.forEach { assertEquals(it, NavStyle.Choice.fromName(it.name)) }
    }
}
