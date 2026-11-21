package com.example.vkweb

import com.example.vkweb.files.AppFilesProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * 4.99: поставщик папки приложения получил тот же адрес, что FileProvider («.files»). Android отдал адрес первому, и
 * любое «Поделиться» (журнал, отчёт о сбое, собранное) роняло приложение: у найденного поставщика не было путей.
 */
class ManifestProvidersTest {

    private val manifest = listOf(File("src/main/AndroidManifest.xml"), File("app/src/main/AndroidManifest.xml"))
        .first { it.isFile }.readText()

    private val providers = Regex("<provider\\b(.*?)(/>|</provider>)", RegexOption.DOT_MATCHES_ALL).findAll(manifest).map { it.groupValues[1] }.toList()

    private fun authorityOf(provider: String) = Regex("android:authorities=\"([^\"]+)\"").find(provider)!!.groupValues[1]

    @Test
    fun authoritiesAreUnique() {
        val all = providers.flatMap { authorityOf(it).split(';') }
        assertTrue("Поставщиков не нашли", all.size >= 2)
        assertEquals("Адреса поставщиков повторяются", all.size, all.toSet().size)
    }

    @Test
    fun documentsProviderAuthorityMatchesCode() {
        val documents = providers.single { ".files.AppFilesProvider" in it }
        assertEquals("\${applicationId}" + AppFilesProvider.AUTHORITY_SUFFIX, authorityOf(documents))
    }

    @Test
    fun fileProviderKeepsItsPaths() {
        val fileProvider = providers.single { "androidx.core.content.FileProvider" in it }
        assertTrue("FileProvider без путей", "android.support.FILE_PROVIDER_PATHS" in fileProvider)
        assertEquals("\${applicationId}.files", authorityOf(fileProvider))
    }
}
