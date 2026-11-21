package com.example.vkweb.diag

/**
 * Описание того, чем занят главный поток в момент подвисания интерфейса: по снимку стека — самый верхний кадр и первые
 * кадры самого приложения. Без зависимостей от Android: проверяется обычными тестами.
 */
object StallReport {

    private const val APP_PACKAGE = "com.example.vkweb"
    private const val MAX_APP_FRAMES = 3

    /** «Класс.метод(Файл:строка)» — верхний кадр и до трёх кадров приложения: «верх ← приложение ← приложение». */
    fun describe(stack: Array<StackTraceElement>): String {
        if (stack.isEmpty()) return "нет стека"
        val parts = ArrayList<String>()
        parts += frame(stack[0])
        for (i in 1 until stack.size) {
            if (parts.size > MAX_APP_FRAMES) break
            if (stack[i].className.startsWith(APP_PACKAGE)) parts += frame(stack[i])
        }
        return parts.joinToString(" ← ")
    }

    private fun frame(e: StackTraceElement): String =
        "${e.className.substringAfterLast('.')}.${e.methodName}(${e.fileName ?: "?"}:${e.lineNumber})"

    /** Самые частые описания среди снимков одного подвисания: «описание ×3 | описание ×1». */
    fun summarize(samples: List<String>, limit: Int = 2): String =
        samples.groupingBy { it }.eachCount().entries.sortedByDescending { it.value }.take(limit)
            .joinToString(" | ") { "${it.key} ×${it.value}" }
}
