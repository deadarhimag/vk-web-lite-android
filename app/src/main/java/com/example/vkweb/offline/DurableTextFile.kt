package com.example.vkweb.offline

import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.nio.file.Files
import java.nio.file.StandardCopyOption

/** Same-directory atomic replacement. A failed replacement must preserve the previous file. */
object DurableTextFile {
    fun fingerprint(text: String): String = java.security.MessageDigest.getInstance("SHA-256")
        .digest(text.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it.toInt() and 255) }

    fun write(file: File, text: String, replace: (File, File) -> Unit = ::replaceAtomically) {
        val parent = file.absoluteFile.parentFile ?: throw IOException("No parent directory")
        if (!parent.isDirectory && !parent.mkdirs()) throw IOException("Cannot create directory")
        val temporary = File(file.path + ".tmp")
        try {
            FileOutputStream(temporary).use { output ->
                output.write(text.toByteArray(Charsets.UTF_8))
                output.fd.sync()
            }
            replace(temporary, file)
        } finally {
            temporary.delete()
        }
    }

    private fun replaceAtomically(source: File, target: File) {
        // No delete-then-rename fallback: if the filesystem cannot do this safely, report failure.
        Files.move(source.toPath(), target.toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
    }
}
