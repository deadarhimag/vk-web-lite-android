package com.example.vkweb.config

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Резервные копии и перенос на новый телефон (4.28): в облако — ничего, при переносе — только настройки.
 * Вход в VK (cookies WebView), переписки, черновики и журнал на другой телефон не уезжают.
 */
class BackupRulesTest {

    private val app: File = generateSequence(File("").absoluteFile) { it.parentFile }
        .flatMap { sequenceOf(it, File(it, "app")) }
        .firstOrNull { File(it, "src/main/AndroidManifest.xml").isFile } ?: error("Не найден модуль app")

    private val manifest = File(app, "src/main/AndroidManifest.xml").readText()
    private val rules = File(app, "src/main/res/xml/data_extraction_rules.xml").readText()
        .replace(Regex("<!--.*?-->", RegexOption.DOT_MATCHES_ALL), "")

    private fun section(name: String): String =
        Regex("<$name>(.*?)</$name>", RegexOption.DOT_MATCHES_ALL).find(rules)?.groupValues?.get(1) ?: error("Нет <$name>")

    @Test fun `манифест подключает правила и не включает копии до Android 12`() {
        assertTrue("allowBackup=false", "android:allowBackup=\"false\"" in manifest)
        assertTrue("dataExtractionRules", "android:dataExtractionRules=\"@xml/data_extraction_rules\"" in manifest)
    }

    @Test fun `в облако — ничего`() {
        val cloud = section("cloud-backup")
        assertTrue("В облаке не должно быть include", "<include" !in cloud)
        val domains = listOf("root", "file", "database", "sharedpref", "external", "device_root", "device_file", "device_database", "device_sharedpref")
        val missing = domains.filter { "<exclude domain=\"$it\" path=\".\" />" !in cloud }
        assertEquals("Исключите целиком", emptyList<String>(), missing)
    }

    @Test fun `при переносе — только файл настроек, и его имя совпадает с applicationId`() {
        val transfer = section("device-transfer")
        val includes = Regex("<include domain=\"(\\w+)\" path=\"([^\"]+)\"").findAll(transfer).map { it.groupValues[1] to it.groupValues[2] }.toList()
        val id = Regex("applicationId = \"([^\"]+)\"").find(File(app, "build.gradle.kts").readText())?.groupValues?.get(1)
            ?: error("Нет applicationId")
        // Сменился applicationId — файл настроек называется иначе, и перенос молча перестал бы его брать.
        assertEquals(listOf("sharedpref" to "${id}_preferences.xml"), includes)
    }
}
