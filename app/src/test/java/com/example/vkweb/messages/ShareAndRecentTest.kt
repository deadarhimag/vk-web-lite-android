package com.example.vkweb.messages

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** «Поделиться» в приложение и недавние переписки на значке (4.57). */
class ShareAndRecentTest {

    @Test fun `ссылка из браузера — заголовок перед адресом, без повтора`() {
        assertEquals("Новости\nhttps://example.com/a", ShareContent.of("https://example.com/a", "Новости", emptyList())!!.text)
        assertEquals("Новости https://x.ru", ShareContent.of("Новости https://x.ru", "Новости", emptyList())!!.text)
        assertEquals("Тема", ShareContent.of(null, "Тема", emptyList())!!.text)
    }

    @Test fun `файлы — только content, без повторов, не больше десяти`() {
        val uris = listOf("content://a/1", "file:///sdcard/x.jpg", null, "content://a/1") + (2..20).map { "content://a/$it" }
        val share = ShareContent.of(null, null, uris)!!
        assertEquals(AttachmentPolicy.MAX_FILES, share.uris.size)
        assertTrue(share.uris.all { it.startsWith("content://") })
        assertEquals(share.uris.distinct(), share.uris)
    }

    @Test fun `нечего отправлять — null`() {
        assertNull(ShareContent.of("  ", null, listOf("file:///x")))
        assertEquals(4000, ShareContent.of("я".repeat(5000), null, emptyList())!!.text.length)
    }

    @Test fun `недавние — новые первыми, без повторов, две`() {
        val a = RecentChats.Chat("1", "Аня")
        val b = RecentChats.Chat("2", "Борис")
        val c = RecentChats.Chat("2000000005", "Беседа")
        var list = RecentChats.opened(emptyList(), a)
        list = RecentChats.opened(list, b)
        assertEquals(listOf("2", "1"), list.map { it.peerId })
        list = RecentChats.opened(list, a.copy(title = "Анна"))
        assertEquals(listOf("1", "2"), list.map { it.peerId })
        assertEquals("Анна", list.first().title)
        list = RecentChats.opened(list, c)
        assertEquals(listOf("2000000005", "1"), list.map { it.peerId })
    }

    @Test fun `на значке — переписки, затем разделы, всего не больше мест`() {
        val sections = listOf("сообщения", "музыка", "друзья", "видео")
        val chats = listOf(RecentChats.Chat("1", "Аня"), RecentChats.Chat("2", "Борис"))
        assertEquals(chats to listOf("сообщения", "музыка"), RecentChats.plan(chats, sections, 4))
        assertEquals(emptyList<RecentChats.Chat>() to sections, RecentChats.plan(emptyList(), sections, 4))
        assertEquals(chats.take(1) to emptyList<String>(), RecentChats.plan(chats, sections, 1))
    }

    @Test fun `сохраняется и читается, чужое отбрасывается`() {
        val list = listOf(RecentChats.Chat("1", "Аня", "https://sun9-1.userapi.com/a.jpg"), RecentChats.Chat("-5", "Сообщество"))
        assertEquals(list, RecentChats.fromJson(RecentChats.toJson(list)))
        assertTrue(RecentChats.fromJson("""[{"peer":"abc","title":"x"}]""").isEmpty())
        assertTrue(RecentChats.fromJson("не json").isEmpty())
    }

    @Test fun `файлы самого приложения не принимаются (4_65)`() {
        val own = "io.github.deadarhimag.vkweblite"
        val uris = listOf(
            "content://$own.files/shared/log.txt",
            "content://0@$own.files/shared/log.txt",
            "content://IO.GITHUB.DEADARHIMAG.VKWEBLITE.FILES/shared/x",
            "content://$own/x",
            "content://media/external/images/1",
            "content://com.android.providers.downloads.documents/document/5",
            "content://$own.evil.example/x",
        )
        val share = ShareContent.of(null, null, uris, ownPackage = own)!!
        assertEquals(listOf("content://media/external/images/1", "content://com.android.providers.downloads.documents/document/5"), share.uris)
        assertNull(ShareContent.of(null, null, listOf("content://$own.files/a"), ownPackage = own))
        // Похожее, но чужое имя — не своё.
        assertTrue(ShareContent.of(null, null, listOf("content://${own}x.files/a"), ownPackage = own)!!.uris.isNotEmpty())
    }
}
