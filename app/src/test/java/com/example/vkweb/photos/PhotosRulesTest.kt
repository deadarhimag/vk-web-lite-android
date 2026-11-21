package com.example.vkweb.photos

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Адреса фото VK, имена папок и файлов при сохранении альбома, подписи хода (5.00.12). */
class PhotosRulesTest {

    private fun item(id: Long, full: String = "https://sun9-1.vkuserphoto.ru/a.jpg", fallback: List<String> = emptyList()) =
        PhotoItem(id = id, owner = 30804332, thumb = full, full = full, fallback = fallback, dateSec = 0)

    @Test fun `адреса страниц - альбомы, альбом, системные альбомы, все фото`() {
        assertEquals(PhotoPlace.Albums(30804332), PhotoPlace.fromPath("/albums30804332"))
        assertEquals(PhotoPlace.Albums(-1), PhotoPlace.fromPath("/albums-1"))
        assertEquals(PhotoPlace.Album(30804332, "-6"), PhotoPlace.fromPath("/album30804332_0"))
        assertEquals(PhotoPlace.Album(30804332, "-7"), PhotoPlace.fromPath("/album30804332_00"))
        assertEquals(PhotoPlace.Album(30804332, "-15"), PhotoPlace.fromPath("/album30804332_000"))
        assertEquals(PhotoPlace.Album(-5, "123"), PhotoPlace.fromPath("/album-5_123"))
        assertEquals(PhotoPlace.Album(30804332, ""), PhotoPlace.fromPath("/photos30804332"))
        assertNull(PhotoPlace.fromPath("/photo30804332_456239054"))
        assertNull(PhotoPlace.fromPath("/albums0"))
        assertNull(PhotoPlace.fromPath("/id30804332"))
        assertNull(PhotoPlace.fromPath(null))
    }

    @Test fun `адрес альбома обратно - системные нулями`() {
        assertEquals("/album30804332_000", PhotoPlace.albumPath(30804332, -15))
        assertEquals("/album30804332_0", PhotoPlace.albumPath(30804332, -6))
        assertEquals("/album-5_123", PhotoPlace.albumPath(-5, 123))
        assertEquals(PhotoPlace.fromPath(PhotoPlace.albumPath(1, -7)), PhotoPlace.Album(1, "-7"))
    }

    @Test fun `имя папки - название альбома без запрещённых символов`() {
        assertEquals("Сохранённые фото", AlbumSaveRules.folderName("Сохранённые фото", 30804332, "-15"))
        assertEquals("a b c", AlbumSaveRules.folderName("a/b:c", 1, "2"))
        assertEquals("Альбом 1_all", AlbumSaveRules.folderName("  ..  ", 1, ""))
        assertEquals("x", AlbumSaveRules.folderName("x\n\t", 1, "2"))
    }

    @Test fun `папка - своя та же, занятая другим альбомом - с номером`() {
        val existing = mapOf("Лето" to "album1_2", "Лето (2)" to "album1_3", "Пусто" to "")
        val marker = { name: String -> existing[name] }
        assertEquals("Лето", AlbumSaveRules.chooseFolder("Лето", "album1_2", marker))
        assertEquals("Лето (2)", AlbumSaveRules.chooseFolder("Лето", "album1_3", marker))
        assertEquals("Лето (3)", AlbumSaveRules.chooseFolder("Лето", "album1_4", marker))
        assertEquals("Пусто", AlbumSaveRules.chooseFolder("Пусто", "album1_9", marker))
        assertEquals("Новый", AlbumSaveRules.chooseFolder("Новый", "album1_9", marker))
    }

    @Test fun `уже сохранённое узнаётся по имени, недокачанное - нет`() {
        val p = item(5)
        assertTrue(AlbumSaveRules.alreadySaved(setOf("30804332_5.jpg"), p))
        assertTrue(AlbumSaveRules.alreadySaved(setOf("30804332_5.webp"), p))
        assertFalse(AlbumSaveRules.alreadySaved(setOf("30804332_5.part"), p))
        assertFalse(AlbumSaveRules.alreadySaved(setOf("30804332_50.jpg"), p))
    }

    @Test fun `расширение по типу и порядок адресов`() {
        assertEquals("jpg", AlbumSaveRules.extension("image/jpeg"))
        assertEquals("png", AlbumSaveRules.extension("image/png; charset=binary"))
        assertEquals("jpg", AlbumSaveRules.extension(null))
        assertTrue(AlbumSaveRules.isGone(404))
        assertFalse(AlbumSaveRules.isGone(424))
        val p = item(1, "https://a/full", listOf("https://a/full", "https://a/m", ""))
        assertEquals(listOf("https://a/full", "https://a/m"), AlbumSaveRules.candidates(p))
    }

    @Test fun `подписи хода и итога`() {
        val base = AlbumSaveProgress("j", "k", "t", "Загрузки/VkWebLite/Photos/t", "Photos/t")
        assertEquals("\"t\"\nПолучаю список фото…", PhotosFormat.save(base).text)
        val running = PhotosFormat.save(base.copy(total = 155, saved = 10, skipped = 2))
        assertEquals("\"t\"\nСохранено 12 из 155", running.text)
        assertTrue(running.running)
        val done = PhotosFormat.save(base.copy(total = 4, saved = 2, missing = 1, failed = 1, finished = true))
        assertEquals("\"t\"\nГотово: сохранено 2, удалены на VK 1, не удалось 1\nПапка: Загрузки/VkWebLite/Photos/t", done.text)
        assertEquals("Сохранено 0 из 5", PhotosFormat.save(base.copy(title = " ", total = 5)).text)
        assertFalse(done.running)
        assertEquals("21 фотография", PhotosFormat.photos(21))
        assertEquals("3 альбома", PhotosFormat.albums(3))
    }
}
