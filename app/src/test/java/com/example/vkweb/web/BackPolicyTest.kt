package com.example.vkweb.web

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class BackPolicyTest {

    private val sections = setOf("/feed", "/friends", "/groups", "/mail")

    @Test fun `главная страница раздела — корень`() {
        assertTrue(BackPolicy.isSectionRoot("/friends", sections))
        assertTrue(BackPolicy.isSectionRoot("/friends/", sections))
        assertTrue(BackPolicy.isSectionRoot("/friends?id=1", sections))
        assertTrue(BackPolicy.isSectionRoot("", sections))
        assertTrue(BackPolicy.isSectionRoot(null, sections))
    }

    @Test fun `внутри раздела — не корень`() {
        assertFalse(BackPolicy.isSectionRoot("/id40201973", sections))
        assertFalse(BackPolicy.isSectionRoot("/wall1_2", sections))
        assertFalse(BackPolicy.isSectionRoot("/friends/requests", sections))
    }

    // ---- 4.31: «Назад» — к главной раздела, с главной — закрытие; история страницы не листается.

    private val clips = "https://m.vk.com/clips"
    private val video = "https://m.vk.com/video"
    private val groups = "https://m.vk.ru/groups?tab=my"

    private fun decide(
        path: String?, host: String = "m.vk.ru", screen: Boolean = false, clipsRoot: String? = clips, last: String? = groups,
    ) = BackPolicy.decide(host, path, sections, screen, clipsRoot, video, last)

    @Test fun `с главной раздела — закрытие`() {
        assertEquals(BackPolicy.Step.Close, decide("/friends"))
        assertEquals(BackPolicy.Step.Close, decide("/groups"))
        assertEquals(BackPolicy.Step.Close, decide("/clips"))
        assertEquals(BackPolicy.Step.Close, decide("/clips/trending"))
        assertEquals(BackPolicy.Step.Close, decide(null))
    }

    @Test fun `свой экран раздела показан — закрытие, даже если страница под ним глубже`() {
        // Отчёт 4.29: под списком клипов страница стояла на клипе, и «Назад» уводило на сообщество из другого раздела.
        assertEquals(BackPolicy.Step.Close, decide("/clip-1_2", screen = true))
    }

    @Test fun `клип — в раздел «Клипы», а не на прошлую страницу`() {
        assertEquals(BackPolicy.Step.ToSection(clips), decide("/clip-212788102_456239591"))
        assertEquals(BackPolicy.Step.ToSection(clips), decide("/clip1_2"))
        // Клипы выключены в меню — на главную последнего раздела; её нет — закрытие.
        assertEquals(BackPolicy.Step.ToSection(groups), decide("/clip-1_2", clipsRoot = null))
        assertEquals(BackPolicy.Step.Close, decide("/clip-1_2", clipsRoot = null, last = null))
    }

    @Test fun `видео — в раздел «Видео» с любого сайта`() {
        assertEquals(BackPolicy.Step.ToSection(video), decide("/video-1_2"))
        assertEquals(BackPolicy.Step.ToSection(video), decide("/video-1_2", host = "m.vkvideo.ru"))
    }

    @Test fun `на VK Видео главная и подборки — главная раздела, канал и плейлист — нет`() {
        assertEquals(BackPolicy.Step.Close, decide("/", host = "m.vkvideo.ru"))
        assertEquals(BackPolicy.Step.Close, decide("/trends", host = "m.vkvideo.ru"))
        assertEquals(BackPolicy.Step.Close, decide("/politics/media", host = "m.vkvideo.ru"))
        assertEquals(BackPolicy.Step.ToSection(video), decide("/@vkvideo", host = "m.vkvideo.ru"))
        assertEquals(BackPolicy.Step.ToSection(video), decide("/playlist/-1_2", host = "m.vkvideo.ru"))
    }

    @Test fun `сообщество, человек, запись — на главную раздела, откуда открыли`() {
        assertEquals(BackPolicy.Step.ToSection(groups), decide("/rock_music_on"))
        assertEquals(BackPolicy.Step.ToSection(groups), decide("/wall-1_2"))
        assertEquals(BackPolicy.Step.Close, decide("/id1", last = null))
    }

    @Test fun `страницы проигрывателя — клипы и видео, но не разделы`() {
        assertTrue(BackPolicy.isPlayerPage("/clips"))
        assertTrue(BackPolicy.isPlayerPage("/clip-212788102_456239591"))
        assertTrue(BackPolicy.isPlayerPage("/video-1_2"))
        assertFalse(BackPolicy.isPlayerPage("/video"))
        assertFalse(BackPolicy.isPlayerPage("/feed"))
        assertFalse(BackPolicy.isPlayerPage("/"))
        assertFalse(BackPolicy.isPlayerPage(null))
    }

    @Test fun `клип в разделе «Видео» — обратно в «Видео», а не в «Клипы» (4_38)`() {
        // Отчёт: «Назад» перекидывало между «Видео» и «Клипами» — страница видео сама уходила на клип.
        assertEquals(
            BackPolicy.Step.ToSection(video),
            BackPolicy.decide("m.vk.ru", "/clip-1_2", sections, false, clips, video, groups, inVideoSection = true),
        )
        assertEquals(BackPolicy.Step.ToSection(clips), decide("/clip-1_2"))
    }

    @Test fun `вкладки ленты клипов — главная раздела, клип под ними — клип (4_39)`() {
        assertEquals(BackPolicy.Step.Close, decide("/clips_trends"))
        assertEquals(BackPolicy.Step.Close, decide("/clips/subscriptions"))
        assertEquals(BackPolicy.Step.ToSection(clips), decide("/clips_trends/clip-1_2"))
        assertEquals(BackPolicy.Step.ToSection(clips), decide("/clips/subscriptions/clip-1_2"))
        assertTrue(BackPolicy.isPlayerPage("/clips_trends/clip-1_2"))
        assertTrue(BackPolicy.isPlayerPage("/clips_trends"))
    }
}
