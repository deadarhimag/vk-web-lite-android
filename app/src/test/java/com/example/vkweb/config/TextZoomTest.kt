package com.example.vkweb.config

import org.junit.Assert.assertEquals
import org.junit.Test

/** Размер текста страниц (4.23): шаг 10 %, пределы 80–150 %. */
class TextZoomTest {

    @Test fun `ровные значения не меняются`() {
        for (v in AppConfig.MIN_TEXT_ZOOM..AppConfig.MAX_TEXT_ZOOM step AppConfig.TEXT_ZOOM_STEP) {
            assertEquals(v, AppConfig.textZoom(v))
        }
    }

    @Test fun `промежуточные — к ближайшему шагу`() {
        assertEquals(110, AppConfig.textZoom(113))
        assertEquals(120, AppConfig.textZoom(117))
        assertEquals(100, AppConfig.textZoom(104))
        assertEquals(110, AppConfig.textZoom(105))
        assertEquals(150, AppConfig.textZoom(147))
    }

    @Test fun `за пределами — к краю`() {
        assertEquals(AppConfig.MIN_TEXT_ZOOM, AppConfig.textZoom(0))
        assertEquals(AppConfig.MIN_TEXT_ZOOM, AppConfig.textZoom(-20))
        assertEquals(AppConfig.MAX_TEXT_ZOOM, AppConfig.textZoom(400))
    }

    @Test fun `ползунок в настройках — те же пределы, шаг и значение по умолчанию`() {
        // 4.24: ползунок (StepSeekBarPreference) берёт шаг из seekBarIncrement в preferences.xml, а приложение — из
        // AppConfig. Разойдутся — ползунок встанет на 115 %, а страница покажет 120 %.
        val xml = generateSequence(java.io.File("").absoluteFile) { it.parentFile }
            .flatMap { sequenceOf(java.io.File(it, "src/main/res/xml/preferences.xml"), java.io.File(it, "app/src/main/res/xml/preferences.xml")) }
            .first { it.isFile }.readText()
        val block = Regex("<com\\.example\\.vkweb\\.settings\\.StepSeekBarPreference(.*?)/>", RegexOption.DOT_MATCHES_ALL)
            .findAll(xml).map { it.groupValues[1] }.single { "app:key=\"text_zoom\"" in it }
        fun attr(name: String) = Regex("$name=\"(\\d+)\"").find(block)?.groupValues?.get(1)?.toInt()
        assertEquals(AppConfig.MIN_TEXT_ZOOM, attr("app:min"))
        assertEquals(AppConfig.MAX_TEXT_ZOOM, attr("android:max"))
        assertEquals(AppConfig.TEXT_ZOOM_STEP, attr("app:seekBarIncrement"))
        assertEquals(AppConfig.DEFAULT_TEXT_ZOOM, attr("app:defaultValue"))
    }

    @Test fun `по умолчанию — ровное значение`() {
        assertEquals(AppConfig.DEFAULT_TEXT_ZOOM, AppConfig.textZoom(AppConfig.DEFAULT_TEXT_ZOOM))
    }
}
