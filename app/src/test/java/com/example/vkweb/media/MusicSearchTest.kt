package com.example.vkweb.media

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class MusicSearchTest {

    private fun track(id: String, artist: String) = MusicTrack(id, "Песня", artist, 100, "")

    @Test fun `запрос чистится, пустой не уходит`() {
        assertEquals("Ария live", MusicSearch.clean("  Ария \n live "))
        assertEquals("", MusicSearch.clean(null))
        assertNull(MusicSearch.pathFor("   "))
        assertEquals(MusicSearch.MAX_QUERY, MusicSearch.clean("я".repeat(500)).length)
    }

    @Test fun `адрес поиска — с закодированным запросом, пробел — кодом 20, а не плюсом`() {
        assertEquals("/audio?section=general&q=%D0%9A%D0%B8%D0%BD%D0%BE%20%26%20Co", MusicSearch.pathFor("Кино & Co"))
    }

    @Test fun `запрос из адреса страницы поиска`() {
        assertEquals("Кино & Co", MusicSearch.queryOf("/audio", "section=general&q=%D0%9A%D0%B8%D0%BD%D0%BE%20%26%20Co"))
        assertEquals("a b", MusicSearch.queryOf("/audio", "q=a+b&offset=50"))
        assertNull("не страница музыки", MusicSearch.queryOf("/search", "q=a"))
        assertNull("главная музыки", MusicSearch.queryOf("/audio", null))
        assertNull("пустой запрос", MusicSearch.queryOf("/audio", "section=general&q="))
        assertNull("плейлист", MusicSearch.queryOf("/audio", "act=audio_playlist7_1&q=a"))
        assertNull("не параметр q", MusicSearch.queryOf("/audio", "faq=1"))
    }

    @Test fun `тот же запрос — без учёта регистра, ё и знаков`() {
        assertTrue(MusicSearch.sameQuery("Ёлка", "елка"))
        assertTrue(MusicSearch.sameQuery("AC/DC", "ac dc"))
        assertFalse(MusicSearch.sameQuery("Кино", "Кинолента"))
    }

    @Test fun `исполнители трека — каждый отдельно, без повторов`() {
        assertEquals(listOf("Баста", "Скриптонит"), MusicSearch.artistsOf("Баста feat. Скриптонит"))
        assertEquals(listOf("A", "B", "C"), MusicSearch.artistsOf("A, B & C"))
        assertEquals(listOf("A", "B"), MusicSearch.artistsOf("A ft. B, a"))
        assertEquals(listOf("Macan", "Scirena"), MusicSearch.artistsOf("Macan x Scirena"))
        assertEquals("одно имя", listOf("Сплин"), MusicSearch.artistsOf("Сплин"))
        assertEquals("«x» внутри слова — не разделитель", listOf("Xxxtentacion"), MusicSearch.artistsOf("Xxxtentacion"))
        assertEquals(emptyList<String>(), MusicSearch.artistsOf("  "))
        assertEquals(MusicSearch.MAX_ARTISTS, MusicSearch.artistsOf("a, b, c, d, e, f").size)
    }

    @Test fun `треки исполнителя — его имя целыми словами`() {
        val list = listOf(
            track("1_1", "Кино"), track("1_2", "Кино feat. Земфира"), track("1_3", "Кинопленка"),
            track("1_4", "Группа КИНО"), track("1_5", "Другой"),
        )
        assertEquals(listOf("1_1", "1_2", "1_4"), MusicSearch.byArtist(list, "кино").map { it.id })
        assertTrue("пустое имя — без отбора", MusicSearch.isBy("Любой", " "))
    }

    @Test fun `треки исполнителя — разметка выделения и написание через дефис не мешают (4_88)`() {
        assertTrue(MusicSearch.isBy("<em class=\"found\">Король</em> <em>и</em> Шут", "Король и Шут"))
        assertTrue(MusicSearch.isBy("<em>Би</em>-<em>2</em>", "Би-2"))
        assertTrue("без пробела и с пробелом", MusicSearch.isBy("Би 2", "Би-2"))
        assertTrue(MusicSearch.isBy("AC/DC", "ACDC"))
        assertTrue("соисполнитель", MusicSearch.isBy("Земфира feat. Би 2", "Би-2"))
        assertFalse("часть слова — не исполнитель", MusicSearch.isBy("Кинопленка", "Кино"))
        assertFalse(MusicSearch.isBy("Другой", "Кино"))
    }

    // ---------------------------------------------------------------- куда идут результаты поиска

    private val m = MusicSection.MUSIC
    private val p = MusicSection.PLAYLISTS
    private val key = "/audio?section=general&q=kino"

    @Test fun `результаты поиска — в поиск, если экран ждёт этот запрос`() {
        assertEquals(MusicRouting.Target.SEARCH, MusicRouting.target("search", m, null, key, "Кино", "кино"))
        assertEquals("в плейлистах тоже", MusicRouting.Target.SEARCH, MusicRouting.target("search", p, "7_1", key, "Кино", "Кино"))
        assertEquals("запрос страница не сообщила", MusicRouting.Target.SEARCH, MusicRouting.target("search", m, null, key, "Кино", ""))
    }

    @Test fun `результаты прежнего запроса не попадают в новый поиск`() {
        assertEquals(MusicRouting.Target.IGNORE, MusicRouting.target("search", m, null, key, "Сплин", "Кино"))
    }

    @Test fun `поиск, которого экран не ждёт, — как раньше, никуда`() {
        assertEquals(MusicRouting.Target.IGNORE, MusicRouting.target("search", m, null, key))
        assertEquals("кроме открытого плейлиста с нестандартным адресом", MusicRouting.Target.OPENED, MusicRouting.target("search", p, "7_1", key))
    }

    @Test fun `во время поиска «Мои треки» и плейлисты идут в свои списки`() {
        assertEquals(MusicRouting.Target.MY, MusicRouting.target("my", m, null, "/audio", "Кино", ""))
        assertEquals(MusicRouting.Target.OPENED, MusicRouting.target("playlist", p, "7_1", "/audio?act=audio_playlist7_1", "Кино", ""))
    }
}
