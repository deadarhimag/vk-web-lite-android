package com.example.vkweb.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import kotlin.math.pow

/**
 * Контраст палитры (4.23) — по ресурсам, для светлой, тёмной и чёрной темы. Ловит пары, которые на глаз «нормальные»,
 * а на телефоне читаются плохо: так был найден приглушённый счётчик непрочитанных (2,6:1 в тёмной теме).
 * Цвета обоев (Material You) не проверяются: их строит система.
 */
class PaletteContrastTest {

    private val res: File = generateSequence(File("").absoluteFile) { it.parentFile }
        .flatMap { sequenceOf(File(it, "src/main/res"), File(it, "app/src/main/res")) }
        .firstOrNull { it.isDirectory } ?: error("Не найдена папка res: тест запускают из проекта VkWeb")

    private val colors: Map<String, String> =
        Regex("<color name=\"(\\w+)\">(#[0-9A-Fa-f]{6,8})</color>").findAll(File(res, "values/colors.xml").readText())
            .associate { it.groupValues[1] to it.groupValues[2] }

    private fun style(name: String): Map<String, String> {
        val text = File(res, "values/themes.xml").readText()
        val block = Regex("<style name=\"${Regex.escape(name)}\"[^>]*>(.*?)</style>", RegexOption.DOT_MATCHES_ALL)
            .find(text)?.groupValues?.get(1) ?: error("Нет стиля $name")
        return Regex("<item name=\"(?:android:)?(\\w+)\">([^<]+)</item>").findAll(block)
            .associate { it.groupValues[1] to it.groupValues[2].trim() }
    }

    /** Атрибуты темы: базовая тема и, для чёрной, наложение поверх тёмной. */
    private fun theme(vararg styles: String): Map<String, String> = styles.fold(emptyMap()) { acc, s -> acc + style(s) }

    private val light = theme("Base.Theme.VkWeb.Light")
    private val dark = theme("Base.Theme.VkWeb.Dark")
    private val black = theme("Base.Theme.VkWeb.Dark", "ThemeOverlay.VkWeb.Black")
    private val themes = mapOf("светлая" to light, "тёмная" to dark, "чёрная" to black)

    /** Цвет атрибута: ?attr/x и @color/x раскрываются, пока не получится #RRGGBB. */
    private fun Map<String, String>.color(attr: String, depth: Int = 0): String {
        require(depth < 8) { "Цикл в теме: $attr" }
        val value = this[attr.removePrefix("?attr/").removePrefix("?android:attr/")] ?: error("В теме нет $attr")
        return when {
            value.startsWith("#") -> value
            value.startsWith("@color/") -> colors[value.removePrefix("@color/")] ?: error("Нет цвета $value")
            value.startsWith("?") -> color(value, depth + 1)
            else -> error("Непонятное значение $attr = $value")
        }
    }

    private fun luminance(hex: String): Double {
        val h = hex.removePrefix("#").takeLast(6)
        fun channel(i: Int): Double {
            val c = h.substring(i, i + 2).toInt(16) / 255.0
            return if (c <= 0.03928) c / 12.92 else ((c + 0.055) / 1.055).pow(2.4)
        }
        return 0.2126 * channel(0) + 0.7152 * channel(2) + 0.0722 * channel(4)
    }

    private fun contrast(a: String, b: String): Double {
        val (hi, lo) = listOf(luminance(a), luminance(b)).sortedDescending()
        return (hi + 0.05) / (lo + 0.05)
    }

    /** Атрибут цвета заливки из drawable (`<solid android:color="?attr/…">`). */
    private fun solidOf(drawable: String): String =
        Regex("<solid android:color=\"\\?(?:android:)?attr/(\\w+)\"").find(File(res, "drawable/$drawable.xml").readText())
            ?.groupValues?.get(1) ?: error("В $drawable нет заливки из темы")

    private fun check(minimum: Double, pairs: List<Triple<String, String, String>>) {
        val bad = themes.flatMap { (name, t) ->
            pairs.mapNotNull { (what, fg, bg) ->
                val c = contrast(t.color(fg), t.color(bg))
                if (c < minimum) "$name: $what — %.2f:1".format(c) else null
            }
        }
        assertEquals("Контраст ниже $minimum:1", emptyList<String>(), bad)
    }

    @Test fun `текст читается — не меньше 4,5 к 1`() = check(
        4.5,
        listOf(
            Triple("основной текст на фоне", "colorOnBackground", "colorBackground"),
            Triple("основной текст на поверхности", "colorOnSurface", "colorSurface"),
            Triple("приглушённый на фоне", "colorOnSurfaceVariant", "colorBackground"),
            Triple("приглушённый на поверхности", "colorOnSurfaceVariant", "colorSurface"),
            Triple("приглушённый на входящем пузыре", "colorOnSurfaceVariant", "colorSurfaceContainerHigh"),
            Triple("приглушённый на выделенной строке", "colorOnSurfaceVariant", "colorSecondaryContainer"),
            Triple("текст своего пузыря", "colorOnBubbleOut", "colorBubbleOut"),
            Triple("счётчик непрочитанных", "colorOnPrimary", solidOf("counter_badge")),
            // Цвет цифр приглушённого счётчика задан в DialogsAdapter: colorSurface.
            Triple("приглушённый счётчик", "colorSurface", solidOf("counter_badge_muted")),
            // 4.49: время непрочитанного диалога — цветом акцента (на выделенной строке — приглушённым, см. DialogsAdapter).
            Triple("время непрочитанного на фоне списка", "colorPrimary", "colorBackground"),
            Triple("текст непрочитанного на выделенной строке", "colorOnSurface", "colorSecondaryContainer"),
        ),
    )

    @Test fun `выделение и значки различимы`() {
        // Выделенная строка диалога в двух колонках — заметно отличается от фона списка.
        check(1.2, listOf(Triple("выделенная строка", "colorSecondaryContainer", "colorBackground")))
        // Значки и отметки (не текст) — 3:1.
        check(3.0, listOf(Triple("отметка «в сети»", "colorOnline", "colorBackground"), Triple("синий на фоне", "colorPrimary", "colorBackground")))
    }

    @Test fun `обводка отметки «в сети» совпадает с подложкой строки`() {
        val ring = File(res, "color/online_ring.xml").readText()
        assertTrue("Выделенная строка: обводка цветом выделения", "?attr/colorSecondaryContainer\" android:state_activated=\"true\"" in ring)
        assertTrue("Обычная строка: обводка цветом фона", "?android:attr/colorBackground" in ring)
        assertTrue("Выделение строки — тем же цветом", "?attr/colorSecondaryContainer" in File(res, "drawable/dialog_row_background.xml").readText())
    }

    @Test fun `подписи поверх фото и видео читаются и на белом кадре`() {
        // media_scrim поверх самого светлого кадра (белого) и media_on на нём — не меньше 4,5:1.
        val scrim = colors.getValue("media_scrim").removePrefix("#")
        val alpha = scrim.take(2).toInt(16) / 255.0
        val black = scrim.takeLast(6).chunked(2).map { it.toInt(16) }
        val blended = "#" + black.joinToString("") { "%02X".format((it * alpha + 255 * (1 - alpha)).toInt()) }
        val c = contrast(colors.getValue("media_on"), blended)
        assertTrue("media_on на media_scrim поверх белого — %.2f:1".format(c), c >= 4.5)
    }

    @Test fun `у полос проигрывателя есть затемнение`() {
        // 4.25: верхняя полоса видео (название) лежала прямо на кадре; нижняя и у клипов — на media_scrim.
        val video = File(res, "layout/activity_video.xml").readText()
        for (id in listOf("video_controls", "video_bottom")) {
            val tag = Regex("android:id=\"@\\+id/$id\"[^>]*?>", RegexOption.DOT_MATCHES_ALL).find(video)?.value.orEmpty()
            assertTrue("$id: нет затемнения", "@color/media_scrim" in tag || "@drawable/media_scrim_top" in tag)
        }
        assertTrue("media_scrim_top: от media_scrim", "@color/media_scrim" in File(res, "drawable/media_scrim_top.xml").readText())
    }

    @Test fun `в нижней панели обводка — цветом панели`() {
        // 4.24: выбор получателя пересылки — BottomSheetDialog, его фон в Material 3 — colorSurfaceContainerLow.
        assertTrue("item_forward_target: точка для панели", "@drawable/online_dot_sheet" in File(res, "layout/item_forward_target.xml").readText())
        assertTrue("online_dot_sheet: обводка цветом панели", "?attr/colorSurfaceContainerLow" in File(res, "drawable/online_dot_sheet.xml").readText())
    }
}
