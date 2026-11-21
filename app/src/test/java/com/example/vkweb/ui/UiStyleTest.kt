package com.example.vkweb.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Оформление интерфейса: нормы, по которым сделан весь интерфейс приложения. Проверяются по ресурсам, поэтому
 * новый экран, который выбивается из общего вида, виден сразу — без запуска на телефоне.
 */
class UiStyleTest {

    /** Папка res модуля: тест запускается из корня проекта или из папки модуля. */
    private val res: File = generateSequence(File("").absoluteFile) { it.parentFile }
        .flatMap { sequenceOf(File(it, "src/main/res"), File(it, "app/src/main/res")) }
        .firstOrNull { it.isDirectory } ?: error("Не найдена папка res: тест запускают из проекта VkWeb")

    private fun files(dir: String) = File(res, dir).listFiles { f -> f.extension == "xml" }.orEmpty().sortedBy { it.name }

    private fun strings(): Map<String, String> =
        Regex("<string name=\"([^\"]+)\">([^<]*)</string>").findAll(File(res, "values/strings.xml").readText())
            .associate { it.groupValues[1] to it.groupValues[2] }

    @Test fun `цвета только из палитры и темы, без литералов в разметке и значках`() {
        // Значок приложения рисуется своими цветами; остальное берёт цвета из темы (?attr/…) или палитры (@color/…).
        val literal = Regex("=\"#[0-9A-Fa-f]{6,8}\"")
        val offenders = (files("layout") + files("drawable"))
            .filterNot { it.name.startsWith("ic_launcher") }
            .filter { literal.containsMatchIn(it.readText()) }
            .map { it.name }
        assertEquals("Цвет задан литералом: вынесите в values/colors.xml", emptyList<String>(), offenders)
    }

    @Test fun `белый и чёрный в разметке — из палитры`() {
        // 4.25: на экранах фото, видео и клипов было ~35 @android:color/white|black мимо media_on и media_background.
        // Заливка значков (fillColor в drawable) — не цвет, а маска под tint: её правило не касается.
        val system = Regex("\"@android:color/(white|black)\"")
        val offenders = files("layout").filter { system.containsMatchIn(it.readText()) }.map { it.name }
        assertEquals("Возьмите @color/media_on или @color/media_background", emptyList<String>(), offenders)
    }

    @Test fun `текст растёт с крупным шрифтом — без низкой фиксированной высоты`() {
        // 4.27: счётчики непрочитанных были высотой ровно 20dp — при крупном шрифте в настройках телефона (130 % и выше)
        // цифры обрезались снизу. Низкий текст — wrap_content с minHeight; 48dp и выше (кнопки) — запас есть.
        val fixed = Regex("<TextView[^>]*?android:layout_height=\"(\\d+)dp\"", RegexOption.DOT_MATCHES_ALL)
        val offenders = files("layout").flatMap { f ->
            fixed.findAll(f.readText()).map { it.groupValues[1].toInt() }.filter { it < 48 }.map { "${f.name}: ${it}dp" }
        }
        assertEquals("Задайте wrap_content и minHeight", emptyList<String>(), offenders)
    }

    @Test fun `размер текста — из типографики темы`() {
        // 4.23: размер в sp прямо в разметке не следует теме и выпадает из шкалы textAppearance… (был на экране сбоя).
        val offenders = files("layout").filter { "android:textSize=" in it.readText() }.map { it.name }
        assertEquals("Задайте textAppearance вместо textSize", emptyList<String>(), offenders)
    }

    @Test fun `подписи в настройках не длиннее, чем у остальных`() {
        // Самая длинная из исходных — 136 знаков; длиннее ломает ритм экрана настроек.
        val tooLong = strings().filter { (name, text) -> name.endsWith("_summary") && text.length > 136 }.keys
        assertEquals("Сократите подпись; подробности — в README", emptySet<String>(), tooLong)
    }

    @Test fun `плашки пишутся через тире, а не через двоеточие`() {
        val colon = strings().filter { (name, text) -> name.endsWith("_banner") && ": " in text }.keys
        assertEquals("Плашки: «Нет сети — показано сохранённое»", emptySet<String>(), colon)
    }

    @Test fun `названия разделов настроек короткие`() {
        val prefs = File(res, "xml/preferences.xml").readText()
        val strings = strings()
        val titles = Regex("<PreferenceCategory[^>]*?app:title=\"@string/(\\w+)\"", RegexOption.DOT_MATCHES_ALL)
            .findAll(prefs).map { strings.getValue(it.groupValues[1]) }.toList()
        assertTrue("Разделов настроек не нашли", titles.size >= 9)
        val long = titles.filter { it.split(' ').size > 3 }
        assertEquals("Название раздела — не больше трёх слов", emptyList<String>(), long)
    }

    @Test fun `отступы из сетки приложения`() {
        // Значения исходного интерфейса; 72dp — запас под плашкой «нет сети» внизу списка, 20 и 32 — шапка меню.
        val scale = setOf(0, 1, 2, 3, 4, 6, 8, 10, 12, 14, 16, 20, 24, 28, 32, 56, 72)
        val spacing = Regex("(?:layout_margin\\w*|padding\\w*)=\"(-?\\d+)dp\"")
        val offenders = files("layout").flatMap { file ->
            spacing.findAll(file.readText()).map { it.groupValues[1].toInt() }.filter { it !in scale }.map { "${file.name}: ${it}dp" }
        }
        assertEquals("Отступ вне сетки приложения $scale", emptyList<String>(), offenders)
    }

    @Test fun `кнопки не ниже 48dp, счётчики и действия под записью тоже`() {
        val small = Regex("<(?:ImageButton|com\\.google\\.android\\.material\\.button\\.MaterialButton)[^>]*?android:layout_height=\"(\\d+)dp\"", RegexOption.DOT_MATCHES_ALL)
        val buttons = files("layout").flatMap { f -> small.findAll(f.readText()).map { it.groupValues[1].toInt() }.filter { it < 48 }.map { "${f.name}: ${it}dp" } }
        assertEquals("Нажимаемое — не меньше 48dp", emptyList<String>(), buttons)

        val styles = File(res, "values/styles.xml").readText()
        for (style in listOf("OwnerCounter", "PostStat")) {
            val block = Regex("<style name=\"$style\">(.*?)</style>", RegexOption.DOT_MATCHES_ALL).find(styles)?.groupValues?.get(1).orEmpty()
            val height = Regex("layout_height\">(\\d+)dp").find(block)?.groupValues?.get(1)?.toInt()
            assertTrue("Стиль $style: высота ${height}dp меньше 48dp", height == null || height >= 48)
        }
        // 4.1: полосы перемотки и кнопки-подписи проигрывателя (скорость, качество) — тоже нажимаемое.
        val seekBars = Regex("<SeekBar[^>]*?android:layout_height=\"(\\d+)dp\"", RegexOption.DOT_MATCHES_ALL)
        val thinSeek = files("layout").flatMap { f -> seekBars.findAll(f.readText()).map { it.groupValues[1].toInt() }.filter { it < 48 }.map { "${f.name}: ${it}dp" } }
        assertEquals("Полоса перемотки — не меньше 48dp", emptyList<String>(), thinSeek)
        val video = File(res, "layout/activity_video.xml").readText()
        for (id in listOf("video_speed", "video_quality")) {
            val h = Regex("android:id=\"@\\+id/$id\"[^>]*?android:layout_height=\"(\\d+)dp\"", RegexOption.DOT_MATCHES_ALL)
                .find(video)?.groupValues?.get(1)?.toInt() ?: 48
            assertTrue("Кнопка $id: ${h}dp меньше 48dp", h >= 48)
        }

        val stats = Regex("android:id=\"@\\+id/post_stats\"[^>]*?android:layout_height=\"(\\d+)dp\"", RegexOption.DOT_MATCHES_ALL)
            .find(File(res, "layout/item_post.xml").readText())?.groupValues?.get(1)?.toInt() ?: 48
        assertTrue("Ряд действий под записью ${stats}dp меньше 48dp", stats >= 48)
    }

    @Test fun `цвета в коде — тоже из палитры`() {
        // 4.1: правило о цветах проверялось только в разметке, а в коде были 0xFFFFFFFF, Color.WHITE и красный «Нравится».
        // Исключения: палитра аватаров (подобрана под контраст и проверяется своим тестом) и кружок с инициалами.
        val java = File(res.parentFile, "java")
        val literal = Regex("0x[0-9A-Fa-f]{8}\\.toInt\\(\\)|(?<![\\w.])Color\\.(WHITE|BLACK|RED|GREEN|BLUE|GRAY|YELLOW)\\b|Color\\.(rgb|argb|parseColor)\\(")
        val allowed = setOf("AvatarPalette.kt", "AvatarBadge.kt")
        val offenders = java.walkTopDown().filter { it.extension == "kt" && it.name !in allowed }
            .filter { literal.containsMatchIn(it.readText()) }.map { it.name }.toList()
        assertEquals("Цвет задан в коде: вынесите в values/colors.xml", emptyList<String>(), offenders)
    }

    @Test fun `пустые экраны и ошибка — одной разметкой`() {
        // 4.51: «пусто», «нет сети», «данные не пришли» и «страница не загрузилась» — layout/view_empty_state.xml
        // (значок, заголовок, пояснение, кнопки), а не своя строка текста на каждом экране.
        for (file in listOf("panel_chrome.xml", "activity_messages.xml", "activity_main.xml")) {
            assertTrue("$file: нет view_empty_state", "@layout/view_empty_state" in File(res, "layout/$file").readText())
        }
    }

    @Test fun `списки своих экранов и диалогов обновляются потягиванием`() {
        // 4.52: рамка panel_refresh у каждой панели (её находит ui/PanelFrame.kt), dialogs_refresh — у списка диалогов.
        for (file in listOf("panel_music.xml", "panel_people.xml", "panel_owner.xml", "panel_video.xml", "panel_clips.xml")) {
            assertTrue("$file: нет panel_refresh", "@+id/panel_refresh" in File(res, "layout/$file").readText())
        }
        assertTrue("activity_messages.xml: нет dialogs_refresh", "@+id/dialogs_refresh" in File(res, "layout/activity_messages.xml").readText())
    }

    @Test fun `списки строк при первой загрузке — силуэтами`() {
        // 4.56: друзья и сообщества, музыка, диалоги — SkeletonView вместо крутилки (её находят PanelFrame и экран сообщений).
        for ((file, id) in listOf("panel_people.xml" to "panel_skeleton", "panel_music.xml" to "panel_skeleton", "activity_messages.xml" to "dialogs_skeleton")) {
            val text = File(res, "layout/$file").readText()
            assertTrue("$file: нет $id", "<com.example.vkweb.ui.SkeletonView" in text && "@+id/$id" in text)
        }
    }
}
