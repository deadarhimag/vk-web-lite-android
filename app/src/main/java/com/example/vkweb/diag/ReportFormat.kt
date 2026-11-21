package com.example.vkweb.diag

/**
 * Оформление диагностического отчёта. Без зависимостей от Android: проверяется обычными тестами.
 * Отчёт — обычный текст из разделов: заголовок в рамке `== … ==` и строки под ним.
 */
object ReportFormat {

    /** Раздел отчёта. */
    class Section(val title: String, val lines: List<String>)

    /** Собирает отчёт; пустые разделы показываются с пометкой, чтобы было видно, что их не забыли. */
    fun assemble(title: String, sections: List<Section>): String = buildString {
        appendLine("# $title")
        for (section in sections) {
            appendLine()
            appendLine("== ${section.title} ==")
            if (section.lines.isEmpty()) appendLine("(нет данных)") else section.lines.forEach(::appendLine)
        }
    }

    /**
     * Настройки как строки `имя=значение`, по алфавиту. Наборы значений — отсортированы, чтобы два отчёта
     * с одинаковыми настройками выглядели одинаково и их можно было сравнивать.
     */
    fun settings(values: Map<String, Any?>): List<String> = values.entries
        .sortedBy { it.key }
        .map { (key, value) -> "$key=${settingValue(value)}" }

    private fun settingValue(value: Any?): String = when (value) {
        is Set<*> -> value.map { it.toString() }.sorted().joinToString(",", "[", "]")
        null -> "—"
        else -> value.toString()
    }

    /**
     * Группа ожидания приложения (App Standby): чем дальше от «активной», тем реже система даёт ему работать в фоне.
     * Коды — из UsageStatsManager.STANDBY_BUCKET_*.
     */
    fun standbyBucket(code: Int): String = when {
        code <= 5 -> "освобождено от ограничений"
        code <= 10 -> "активно"
        code <= 20 -> "рабочий набор"
        code <= 30 -> "часто"
        code <= 40 -> "редко (фоновая работа откладывается надолго)"
        code <= 45 -> "ограничено (фоновая работа почти не идёт)"
        else -> "не используется"
    } + " [$code]"

    /** Размер в человеческом виде: 0 Б, 512 Б, 1,5 КБ, 3,2 МБ — одинаково на любом телефоне. */
    fun size(bytes: Long): String = when {
        bytes < 1024 -> "$bytes Б"
        bytes < 1024 * 1024 -> tenths(bytes * 10 / 1024) + " КБ"
        else -> tenths(bytes * 10 / (1024 * 1024)) + " МБ"
    }

    private fun tenths(value: Long): String {
        val whole = value / 10
        val fraction = value % 10
        return if (fraction == 0L) whole.toString() else "$whole,$fraction"
    }
}
