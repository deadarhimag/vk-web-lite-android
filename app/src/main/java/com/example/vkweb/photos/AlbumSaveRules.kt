package com.example.vkweb.photos

/**
 * Имена папок и файлов при сохранении альбома (5.00.12). Без зависимостей от Android: проверяется обычными тестами.
 *
 * Папка альбома - его название внутри "Photos" папки приложения в «Загрузках» (Download/VkWebLite/Photos). Какой
 * альбом в какой папке, приложение помнит у себя ([AlbumSaver]): повторное сохранение того же альбома пишет туда же
 * и пропускает уже сохранённое, а другой альбом с тем же названием получает "<название> (2)". Файл -
 * "<владелец>_<номер>.<расширение>": по нему видно, что фото уже есть.
 */
object AlbumSaveRules {

    /** Подпапка папки приложения для фото. */
    const val PHOTOS_DIR = "Photos"

    private const val MAX_TITLE = 60
    private val FORBIDDEN = Regex("[\\\\/:*?\"<>|\\p{Cntrl}]")
    private val SPACES = Regex("\\s+")

    /** Название папки: без символов, запрещённых в именах файлов, и без точек по краям; пустое - "Альбом <номер>". */
    fun folderName(title: String, owner: Long, album: String): String {
        val clean = SPACES.replace(FORBIDDEN.replace(title, " "), " ").trim().trim('.').trim().take(MAX_TITLE).trim()
        return clean.ifEmpty { "Альбом ${owner}_${album.ifEmpty { "all" }}" }
    }

    /**
     * Какую папку взять. [markerOf] - чей альбом в существующей папке (null - папки нет, "" - неизвестно чей).
     * Своя или неизвестно чья - она же (докачивается); занятая другим альбомом - следующая "(2)", "(3)"...
     */
    fun chooseFolder(base: String, key: String, markerOf: (String) -> String?): String {
        for (n in 1..MAX_SUFFIX) {
            val name = if (n == 1) base else "$base ($n)"
            val marker = markerOf(name)
            if (marker == null || marker == key || marker.isEmpty()) return name
        }
        return "$base ($key)"
    }

    private const val MAX_SUFFIX = 50

    /** Имя файла без расширения. */
    fun baseName(item: PhotoItem): String = item.key

    /** Расширение по типу ответа сервера; неизвестный тип картинки - jpg. */
    fun extension(contentType: String?): String = when (contentType.orEmpty().substringBefore(';').trim().lowercase()) {
        "image/png" -> "png"
        "image/webp" -> "webp"
        "image/gif" -> "gif"
        "image/heic", "image/heif" -> "heic"
        else -> "jpg"
    }

    /** Файл уже сохранён: среди имён папки есть "<базовое>.<что угодно>" (кроме недокачанных .part). */
    fun alreadySaved(existing: Set<String>, item: PhotoItem): Boolean {
        val prefix = baseName(item) + "."
        return existing.any { it.startsWith(prefix) && !it.endsWith(PART) }
    }

    /**
     * Снимка нет на сервере VK (404/410): у всех размеров один путь, перебирать их бесполезно. Отчёт 12.11: в
     * «Сохранённых фото» часть снимков удалена владельцами, и каждый давал по 5 запросов с 404.
     */
    fun isGone(code: Int): Boolean = code == 404 || code == 410

    /** Адреса для попытки по порядку: крупный, затем запасные размеры, без повторов и пустых. */
    fun candidates(item: PhotoItem): List<String> = (listOf(item.full) + item.fallback).filter { it.isNotEmpty() }.distinct()

    const val PART = ".part"
}

/** Как идёт сохранение. [listDone] - страница прислала весь список; [finished] - больше ничего не будет. */
data class AlbumSaveProgress(
    val job: String,
    val placeKey: String,
    val title: String,
    /** Куда - как показать человеку ("Загрузки/VkWebLite/Photos/Лето"). */
    val folder: String,
    /** Куда - путь внутри папки приложения в «Загрузках» ("Photos/Лето"): по нему открывается папка. */
    val relative: String = "",
    val saved: Int = 0,
    val skipped: Int = 0,
    val failed: Int = 0,
    /** Снимков уже нет на сервере VK (удалены владельцем). */
    val missing: Int = 0,
    val total: Int = 0,
    val listDone: Boolean = false,
    val finished: Boolean = false,
    val cancelled: Boolean = false,
    val reason: String = "",
) {
    val handled: Int get() = saved + skipped + failed + missing
}
