package com.example.vkweb.messages

/**
 * Ограничения на вложения, которые приложение передаёт странице: файлы идут в скрытую страницу кусками (base64) через
 * вызовы JavaScript, поэтому большие файлы так не отправить — для них есть страница VK, где выбор файла работает
 * как обычно. Без зависимостей от Android: проверяется обычными тестами.
 */
object AttachmentPolicy {
    const val MAX_FILES = 10
    const val MAX_FILE_BYTES = 12L * 1024 * 1024
    const val MAX_TOTAL_BYTES = 24L * 1024 * 1024

    /** Размер куска до base64; кратен трём, чтобы у кусков не было «хвостов» выравнивания посреди файла. */
    const val CHUNK_BYTES = 192 * 1024

    enum class Problem { TOO_MANY, FILE_TOO_BIG, TOTAL_TOO_BIG, EMPTY_FILE }

    /** @param sizes размеры выбранных файлов в байтах; отрицательный — неизвестен (проверится при чтении). */
    fun check(sizes: List<Long>): Problem? = when {
        sizes.size > MAX_FILES -> Problem.TOO_MANY
        sizes.any { it == 0L } -> Problem.EMPTY_FILE
        sizes.any { it > MAX_FILE_BYTES } -> Problem.FILE_TOO_BIG
        sizes.filter { it > 0 }.sum() > MAX_TOTAL_BYTES -> Problem.TOTAL_TOO_BIG
        else -> null
    }

    fun chunkCount(size: Long): Int = ((size + CHUNK_BYTES - 1) / CHUNK_BYTES).toInt().coerceAtLeast(1)

    /** «1,5 МБ», «320 КБ», «12 Б». */
    fun size(bytes: Long): String = when {
        bytes < 0 -> ""
        bytes < 1024 -> "$bytes Б"
        bytes < 1024 * 1024 -> "${bytes / 1024} КБ"
        else -> "%.1f МБ".format(java.util.Locale("ru"), bytes / 1048576.0)
    }
}
