package com.example.vkweb.config

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Серверы VK в скриптах страниц (assets/shared/net_tap.js, HOSTS) — те же, что в [AppConfig] (4.65). Раньше списки в
 * скриптах были переписаны вручную в пяти местах и разошлись с приложением.
 */
class HostListsTest {

    private val netTap: String = generateSequence(File("").absoluteFile) { it.parentFile }
        .flatMap { sequenceOf(File(it, "src/main/assets/shared/net_tap.js"), File(it, "app/src/main/assets/shared/net_tap.js")) }
        .first { it.isFile }
        .readText()

    private fun jsList(name: String): Set<String> {
        val body = Regex("""\b$name: \[([^\]]*)\]""").find(netTap)?.groupValues?.get(1)
            ?: error("В net_tap.js нет списка $name")
        return Regex("'([^']+)'").findAll(body).map { it.groupValues[1] }.toSet()
    }

    @Test fun `списки серверов в скриптах совпадают с приложением`() {
        assertEquals(AppConfig.VK_DOMAINS, jsList("vk"))
        assertEquals(AppConfig.IMAGE_CDN_DOMAINS, jsList("imageCdn"))
        assertEquals(AppConfig.VIDEO_CDN_DOMAINS, jsList("videoCdn"))
        assertEquals(AppConfig.AUDIO_DOMAINS, jsList("audio"))
    }

    @Test fun `в скриптах больше нет своих регулярных выражений серверов`() {
        val root = generateSequence(File("").absoluteFile) { it.parentFile }
            .flatMap { sequenceOf(File(it, "src/main/assets"), File(it, "app/src/main/assets")) }
            .first { it.isDirectory }
        val copies = root.walkTopDown().filter { it.extension == "js" }
            .filter { Regex("""(userapi|vkuser|okcdn|vkuseraudio)\\\.""").containsMatchIn(it.readText()) }
            .map { it.name }.toList()
        assertTrue("Список серверов переписан в скрипте — используйте NetTap.onHost: $copies", copies.isEmpty())
    }
}
