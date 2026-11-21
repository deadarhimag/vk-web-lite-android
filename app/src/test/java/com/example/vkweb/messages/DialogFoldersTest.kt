package com.example.vkweb.messages

import org.junit.Assert.assertEquals
import org.junit.Test

class DialogFoldersTest {
    private fun d(peer: String, unread: Boolean = false, folders: List<Long> = emptyList()) =
        DialogEntry(peer, "Имя", "текст", "12:00", unread, online = false, folderIds = folders)

    private val list = listOf(d("1", unread = true), d("-220050914"), d("2", folders = listOf(4)))
    private val unread = ChatFolder(3, "Непрочитанные", "unread", emptySet())
    private val business = ChatFolder(1, "Бизнес", "business", setOf("-220050914"))
    private val own = ChatFolder(4, "Работа", "", emptySet())
    private val channels = ChatFolder(2, "Каналы", "channels", emptySet())

    @Test fun `фильтр — все, непрочитанные, по списку папки и по folder_ids`() {
        assertEquals(3, DialogFolders.filter(list, null).size)
        assertEquals(listOf("1"), DialogFolders.filter(list, unread).map { it.peerId })
        assertEquals(listOf("-220050914"), DialogFolders.filter(list, business).map { it.peerId })
        assertEquals(listOf("2"), DialogFolders.filter(list, own).map { it.peerId })
    }

    @Test fun `пустые папки не показываются, кроме «Непрочитанных»`() {
        assertEquals(listOf(3L, 1L, 4L), DialogFolders.visible(listOf(unread, business, own, channels), list).map { it.id })
    }

    @Test fun `список папок сохраняется и читается без потерь, испорченное — пусто`() {
        val folders = listOf(ChatFolder(1, "Непрочитанные", "unread", emptySet()), ChatFolder(7, "Работа", "", setOf("5", "2000000001")))
        org.junit.Assert.assertEquals(folders, DialogFolders.decode(DialogFolders.encode(folders)))
        org.junit.Assert.assertEquals(emptyList<ChatFolder>(), DialogFolders.decode("не json"))
        org.junit.Assert.assertEquals(emptyList<ChatFolder>(), DialogFolders.decode(null))
    }
}
