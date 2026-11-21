package com.example.vkweb.photos

import com.example.vkweb.text.RuPlural

/** Состояние сохранения для шапки: текст и доля готового (null - неизвестно сколько). */
data class SaveState(val text: String, val fraction: Float?, val running: Boolean)

/** Подписи экрана фото (5.00.12). Без зависимостей от Android: проверяется обычными тестами. */
object PhotosFormat {

    fun photos(n: Int): String = RuPlural.count(n.toLong(), "фотография", "фотографии", "фотографий")

    fun albums(n: Int): String = RuPlural.count(n.toLong(), "альбом", "альбома", "альбомов")

    /**
     * Ход и итог сохранения альбома: "Сохранено 12 из 155", "Готово: сохранено 150, уже были 3, не удалось 2" и т.п.
     * [folder] - куда (путь внутри папки приложения).
     */
    fun save(p: AlbumSaveProgress): SaveState {
        // Ход показывается в списке альбомов: первой строкой - какой альбом
        val album = if (p.title.isBlank()) "" else "\"${p.title.trim()}\"\n"
        val total = p.total
        val fraction = if (total > 0) (p.handled.toFloat() / total).coerceIn(0f, 1f) else null
        if (!p.finished) {
            val text = when {
                total == 0 -> "Получаю список фото…"
                else -> "Сохранено ${p.handled} из $total"
            }
            return SaveState(album + text, fraction, running = true)
        }
        val parts = ArrayList<String>()
        if (p.saved > 0) parts += "сохранено ${p.saved}"
        if (p.skipped > 0) parts += "уже были ${p.skipped}"
        if (p.missing > 0) parts += "удалены на VK ${p.missing}"
        if (p.failed > 0) parts += "не удалось ${p.failed}"
        val head = when {
            p.cancelled -> "Остановлено"
            p.reason.isNotEmpty() && p.handled == 0 -> "Не удалось: ${p.reason}"
            else -> "Готово"
        }
        val body = if (parts.isEmpty()) "" else ": " + parts.joinToString(", ")
        return SaveState("$album$head$body\nПапка: ${p.folder}", fraction, running = false)
    }
}
