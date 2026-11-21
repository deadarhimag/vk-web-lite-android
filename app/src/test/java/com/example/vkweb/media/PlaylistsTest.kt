package com.example.vkweb.media

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PlaylistsTest {

    private fun payload(items: String, extra: String = "") =
        PlaylistsPayload.fromJson(JSONObject("""{"type":"playlists","items":[$items]$extra}"""))

    private fun item(id: String, url: String = "/audio?act=audio_playlist7_1", cover: String = "https://x/c.jpg", count: Int = 5) =
        """{"id":"$id","title":"Название","subtitle":"Автор","cover":"$cover","count":$count,"url":"$url"}"""

    @Test fun `плейлист разбирается`() {
        val p = payload(item("7_1")).items.single()
        assertEquals("7_1", p.id)
        assertEquals("Название", p.title)
        assertEquals("Автор", p.subtitle)
        assertEquals("https://x/c.jpg", p.coverUrl)
        assertEquals(5, p.count)
        assertEquals("/audio?act=audio_playlist7_1", p.url)
    }

    @Test fun `чужой адрес и «javascript» вместо адреса страницы не принимаются`() {
        assertEquals("", payload(item("7_1", url = "https://evil.example/x")).items.single().url)
        assertEquals("", payload(item("7_1", url = "//evil.example/x")).items.single().url)
        assertEquals("", payload(item("7_1", url = "javascript:alert(1)")).items.single().url)
    }

    @Test fun `обложка только по https`() {
        assertEquals("", payload(item("7_1", cover = "http://x/c.jpg")).items.single().coverUrl)
    }

    @Test fun `плохие номера и повторы отбрасываются`() {
        val items = payload(item("7_1") + "," + item("abc") + "," + item("7_1") + "," + item("-5_9")).items
        assertEquals(listOf("7_1", "-5_9"), items.map { it.id })
    }

    @Test fun `число треков в разумных пределах`() {
        assertEquals(0, payload(item("7_1", count = -4)).items.single().count)
        assertEquals(1_000_000, payload(item("7_1", count = 999_999_999)).items.single().count)
    }

    @Test fun `признаки «весь список», «ещё», «не нашли» читаются, по умолчанию выключены`() {
        val p = payload("", ""","hasMore":true,"complete":true,"none":true""")
        assertTrue(p.hasMore && p.complete && p.none)
        val d = payload("")
        assertFalse(d.hasMore || d.complete || d.none)
    }

    @Test fun `тип страницы в сообщении музыки, без поля — по виду списка`() {
        fun music(extra: String) = MusicPayload.fromJson(JSONObject("""{"type":"music","items":[]$extra}"""))
        assertEquals("playlist", music(""","kind":"list","page":"playlist","title":"Рок"""").page)
        assertEquals("Рок", music(""","kind":"list","page":"playlist","title":"Рок"""").title)
        assertEquals("my", music(""","kind":"my"""").page)
        assertEquals("other", music(""","kind":"list"""").page)
    }

    // ---- маршрутизация

    private val M = MusicSection.MUSIC
    private val P = MusicSection.PLAYLISTS
    private val key = "/audio?act=audio_playlist7_1&access_hash=k"

    @Test fun `«Мои треки» идут в свой список всегда`() {
        assertEquals(MusicRouting.Target.MY, MusicRouting.target("my", M, null, "/audio"))
        assertEquals(MusicRouting.Target.MY, MusicRouting.target("my", P, "7_1", "/audio"))
    }

    @Test fun `треки открытого плейлиста — в него`() {
        assertEquals(MusicRouting.Target.OPENED, MusicRouting.target("playlist", P, "7_1", key))
        assertEquals("в «Музыке» тоже: это данные того же плейлиста", MusicRouting.Target.OPENED, MusicRouting.target("playlist", M, "7_1", key))
    }

    @Test fun `страница перешла на другой плейлист — не подмешивать в открытый`() {
        assertEquals(MusicRouting.Target.OPEN_NEW, MusicRouting.target("playlist", P, "9_9", key))
        assertEquals(MusicRouting.Target.IGNORE, MusicRouting.target("playlist", M, "9_9", key))
    }

    @Test fun `плейлист открылся сам — показать, только если человек на вкладке «Плейлисты»`() {
        assertEquals(MusicRouting.Target.OPEN_NEW, MusicRouting.target("playlist", P, null, key))
        assertEquals("человек выбрал «Музыка» — не перебиваем", MusicRouting.Target.IGNORE, MusicRouting.target("playlist", M, null, key))
    }

    @Test fun `рекомендации и поиск не попадают ни в один список`() {
        assertEquals(MusicRouting.Target.IGNORE, MusicRouting.target("other", M, null, "/audio?act=search"))
        assertEquals(MusicRouting.Target.IGNORE, MusicRouting.target("other", P, null, "/audio?act=search"))
        assertEquals(MusicRouting.Target.IGNORE, MusicRouting.target("other", M, "7_1", "/audio?act=search"))
        assertEquals("плейлист с нестандартным адресом, открытый из списка", MusicRouting.Target.OPENED, MusicRouting.target("other", P, "7_1", "/audio?act=x"))
    }

    @Test fun `номер плейлиста из адреса страницы`() {
        assertEquals("7_1", MusicRouting.playlistIdOf(key))
        assertEquals("-42_5", MusicRouting.playlistIdOf("/audio?act=audio_playlist-42_5"))
        assertNull(MusicRouting.playlistIdOf("/audio?act=audio_playlists7"))
        assertNull(MusicRouting.playlistIdOf("/audio"))
    }

    @Test fun `плейлист переживает запись в файл и чтение`() {
        val entry = PlaylistEntry("-5_9", "Утро", "Автор", "https://x/c.jpg", 12, "/audio?act=audio_playlist-5_9&access_hash=k")
        val back = PlaylistsPayload.parseItems(org.json.JSONArray().put(entry.toJson())).single()
        assertEquals(entry, back)
    }
}
