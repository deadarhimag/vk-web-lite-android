package com.example.vkweb.offline

import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.io.IOException
import java.nio.file.Files

class DurableTextFileTest {
    @Test fun `changed content with colliding Java hashes is preserved`() {
        val directory = Files.createTempDirectory("durable-test").toFile()
        try {
            val file = File(directory, "queue.json")
            val first = "[{\"text\":\"Aa\"}]"
            val second = "[{\"text\":\"BB\"}]"
            assertEquals(first.hashCode(), second.hashCode())
            assertNotEquals(DurableTextFile.fingerprint(first), DurableTextFile.fingerprint(second))
            DurableTextFile.write(file, first)
            DurableTextFile.write(file, second)
            assertEquals(second, file.readText())
        } finally { directory.deleteRecursively() }
    }

    @Test fun `failed replacement keeps the last committed file`() {
        val directory = Files.createTempDirectory("durable-test").toFile()
        try {
            val file = File(directory, "queue.json")
            DurableTextFile.write(file, "old")
            try {
                DurableTextFile.write(file, "new") { _, _ -> throw IOException("injected replacement failure") }
                fail("Replacement should fail")
            } catch (_: IOException) { }
            assertEquals("old", file.readText())
            assertFalse(File(file.path + ".tmp").exists())
            DurableTextFile.write(file, "retry")
            assertEquals("retry", file.readText())
        } finally { directory.deleteRecursively() }
    }
}
