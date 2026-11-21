package com.example.vkweb.ui

/** Набор фото для просмотра: что открыто и куда можно листать. Без зависимостей от Android: проверяется тестами. */
object PhotoGallery {

    /** Больше фото в одном просмотре не нужно (в записи VK их не больше десяти). */
    const val MAX_PHOTOS = 50

    /**
     * Наибольшая сторона картинки для просмотра по размеру памяти приложения: снимок 4096×4096 — это 64 МБ,
     * на телефоне с небольшой кучей он ронял приложение. Картинка занимает не больше восьмой части кучи
     * (4 байта на пиксель), но не меньше [MIN_SIDE_PX] и не больше [cap].
     */
    fun maxSidePx(maxHeapBytes: Long, cap: Int = 4096): Int =
        Math.sqrt(maxHeapBytes / 8.0 / 4.0).toInt().coerceIn(MIN_SIDE_PX, cap)

    private const val MIN_SIDE_PX = 1024

    /** Что показывать: адреса фото и номер открытого. */
    class Content(val urls: List<String>, val index: Int)

    /** Пустые адреса отбрасываются, а номер открытого фото пересчитывается: он остаётся на том же фото. */
    fun clean(urls: List<String>?, index: Int): Content {
        if (urls.isNullOrEmpty()) return Content(emptyList(), 0)
        val opened = urls.getOrNull(index)
        val kept = urls.filter { it.isNotEmpty() }.take(MAX_PHOTOS)
        if (kept.isEmpty()) return Content(emptyList(), 0)
        val position = if (opened.isNullOrEmpty()) 0 else kept.indexOf(opened).coerceAtLeast(0)
        return Content(kept, position)
    }

    /** Номер фото после листания; на краю остаётся прежним. */
    fun step(index: Int, size: Int, direction: Int): Int = (index + direction).takeIf { it in 0 until size } ?: index

    /** Подпись «2 / 5». */
    fun counter(index: Int, size: Int): String = "${index + 1} / $size"
}
