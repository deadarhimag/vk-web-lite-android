package com.example.vkweb.offline

/**
 * Что и как давно сохранено для работы без сети — строка отчёта на категорию. Без зависимостей от Android:
 * проверяется обычными тестами. Когда обновлялось сохранённое — первое, что нужно знать, если «показывает старое».
 */
object CacheReport {

    class Entry(val bytes: Long, val modifiedAt: Long)

    fun line(title: String, entries: List<Entry>, now: Long): String {
        if (entries.isEmpty()) return "$title: ничего не сохранено"
        val newest = entries.maxOf { it.modifiedAt }
        val oldest = entries.minOf { it.modifiedAt }
        val size = entries.sumOf { it.bytes }
        return buildString {
            append(title).append(": файлов ").append(entries.size).append(", ").append(size(size))
            append(", обновлено ").append(age(now - newest)).append(" назад")
            if (entries.size > 1) append(", самое старое — ").append(age(now - oldest)).append(" назад")
        }
    }

    /** «40 с», «5 мин», «3 ч», «2 дн». */
    fun age(ms: Long): String {
        val seconds = (ms / 1000).coerceAtLeast(0)
        return when {
            seconds < 90 -> "$seconds с"
            seconds < 90 * 60 -> "${(seconds + 30) / 60} мин"
            seconds < 36 * 3600 -> "${(seconds + 1800) / 3600} ч"
            else -> "${(seconds + 43_200) / 86_400} дн"
        }
    }

    fun size(bytes: Long): String = if (bytes < 1024) "$bytes Б" else "${bytes / 1024} КБ"
}
