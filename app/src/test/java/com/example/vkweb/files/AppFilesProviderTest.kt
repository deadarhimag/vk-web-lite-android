package com.example.vkweb.files

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.io.File
import java.nio.file.Files

class AppFilesProviderTest {

    private val root: File = Files.createTempDirectory("app-folder").toFile().apply { deleteOnExit() }

    @Test
    fun resolvesInsideRoot() {
        val real = root.toPath().toRealPath().toFile()
        assertEquals(real, AppFilesProvider.resolve(root, "root"))
        assertEquals(File(real, "Music/a.mp3"), AppFilesProvider.resolve(root, "root/Music/a.mp3"))
        assertEquals("root/Music", AppFilesProvider.documentId("Music"))
        assertEquals("root", AppFilesProvider.documentId(""))
    }

    @Test
    fun rejectsEscapesAndForeignIds() {
        assertNull(AppFilesProvider.resolve(root, "root/../secret"))
        assertNull(AppFilesProvider.resolve(root, "root/Music/../../x"))
        assertNull(AppFilesProvider.resolve(root, "/etc/passwd"))
        assertNull(AppFilesProvider.resolve(root, "rootx/a"))
    }

    @Test
    fun rejectsSymlinkOutOfRoot() {
        val outside = Files.createTempDirectory("outside").toFile().apply { deleteOnExit() }
        val link = File(root, "link")
        runCatching { Files.createSymbolicLink(link.toPath(), outside.toPath()) }.onFailure { return }
        assertNull(AppFilesProvider.resolve(root, "root/link"))
        link.delete()
    }
}
