package com.example.vkweb.diag

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ReportFormatTest {

    @Test fun `разделы отчёта`() {
        val text = ReportFormat.assemble(
            "Отчёт",
            listOf(ReportFormat.Section("Приложение", listOf("версия=1")), ReportFormat.Section("Пусто", emptyList())),
        )
        assertEquals("# Отчёт\n\n== Приложение ==\nверсия=1\n\n== Пусто ==\n(нет данных)\n", text)
    }

    @Test fun `настройки по алфавиту, наборы отсортированы`() {
        val lines = ReportFormat.settings(mapOf("b" to true, "a" to setOf("z", "x"), "c" to null, "d" to "5"))
        assertEquals(listOf("a=[x,z]", "b=true", "c=—", "d=5"), lines)
    }

    @Test fun `размеры`() {
        assertEquals("0 Б", ReportFormat.size(0))
        assertEquals("512 Б", ReportFormat.size(512))
        assertEquals("1,5 КБ", ReportFormat.size(1536))
        assertEquals("2 КБ", ReportFormat.size(2048))
        assertEquals("3,2 МБ", ReportFormat.size(3_400_000))
        assertTrue(ReportFormat.size(1_000_000_000).endsWith("МБ"))
    }
}
