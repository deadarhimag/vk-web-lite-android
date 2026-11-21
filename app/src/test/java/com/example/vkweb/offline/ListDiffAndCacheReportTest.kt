package com.example.vkweb.offline

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ListDiffAndCacheReportTest {

    private data class Item(val id: Int, val v: String = "")

    private fun diff(a: List<Item>, b: List<Item>) = ListDiff.summarize(a, b) { it.id }

    @Test fun `без изменений`() {
        val l = listOf(Item(1), Item(2))
        val d = diff(l, l)
        assertTrue(d.isSame)
        assertEquals("было 2, стало 2: без изменений", d.describe())
    }

    @Test fun `добавлено, убрано, изменено`() {
        val d = diff(listOf(Item(1, "a"), Item(2), Item(3)), listOf(Item(1, "b"), Item(3), Item(4), Item(5)))
        assertEquals(2, d.added)
        assertEquals(1, d.removed)
        assertEquals(1, d.changed)
        assertFalse(d.reordered)
        assertEquals("было 3, стало 4: +2 −1 ~1", d.describe())
    }

    @Test fun `перестановка замечается`() {
        val d = diff(listOf(Item(1), Item(2), Item(3)), listOf(Item(3), Item(1), Item(2)))
        assertTrue(d.reordered)
        assertEquals("было 3, стало 3: +0 −0 ~0, порядок изменился", d.describe())
    }

    @Test fun `сжатие списка видно по числам`() {
        val d = diff((1..35).map { Item(it) }, (1..6).map { Item(it) })
        assertEquals(29, d.removed)
        assertEquals("было 35, стало 6: +0 −29 ~0", d.describe())
    }

    @Test fun `возраст сохранённого читается человеком`() {
        assertEquals("40 с", CacheReport.age(40_000))
        assertEquals("5 мин", CacheReport.age(5 * 60_000L))
        assertEquals("3 ч", CacheReport.age(3 * 3_600_000L))
        assertEquals("2 дн", CacheReport.age(2 * 86_400_000L))
        assertEquals("0 с", CacheReport.age(-5))
    }

    @Test fun `строка отчёта — файлы, размер, когда обновлялось`() {
        val now = 10_000_000L
        val one = CacheReport.line("Музыка", listOf(CacheReport.Entry(18_000, now - 180_000)), now)
        assertEquals("Музыка: файлов 1, 17 КБ, обновлено 3 мин назад", one)
        val many = CacheReport.line(
            "Сообщения", listOf(CacheReport.Entry(2048, now - 60_000), CacheReport.Entry(2048, now - 7_200_000)), now,
        )
        assertEquals("Сообщения: файлов 2, 4 КБ, обновлено 60 с назад, самое старое — 2 ч назад", many)
        assertEquals("Пусто: ничего не сохранено", CacheReport.line("Пусто", emptyList(), now))
    }

    @Test fun `ListMerge accept — тот же список сливается, другой заменяется`() {
        val shown = listOf(Item(1), Item(2), Item(3))
        val incoming = listOf(Item(2), Item(9))
        assertEquals(listOf(2, 9, 1, 3), ListMerge.accept(shown, incoming, sameList = true, complete = false) { it.id }.map { it.id })
        assertEquals(listOf(2, 9), ListMerge.accept(shown, incoming, sameList = false, complete = false) { it.id }.map { it.id })
        assertEquals(listOf(2, 9), ListMerge.accept(shown, incoming, sameList = true, complete = true) { it.id }.map { it.id })
    }
}
