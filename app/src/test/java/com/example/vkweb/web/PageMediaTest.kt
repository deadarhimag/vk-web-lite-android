package com.example.vkweb.web

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PageMediaTest {

    @Test fun `файлы видео — vkvd и vd на okcdn`() {
        assertTrue(PageMedia.isVideoFile("vkvd560.okcdn.ru"))
        assertTrue(PageMedia.isVideoFile("vd748.okcdn.ru"))
        assertTrue(PageMedia.isVideoFile("VKVD303.okcdn.ru"))
    }

    @Test fun `картинки, API и чужие — нет`() {
        assertFalse(PageMedia.isVideoFile("iv.okcdn.ru"))
        assertFalse(PageMedia.isVideoFile("api.ok.ru"))
        assertFalse(PageMedia.isVideoFile("vkvd1.okcdn.ru.evil.com"))
        assertFalse(PageMedia.isVideoFile("cs1-2v4.vkuseraudio.net"))
        assertFalse(PageMedia.isVideoFile(null))
    }

    // 4.30: события плеера скрытой страницы, вызванные блокировкой файлов видео (отчёт 4.29).
    private val clipError = "клип: error {пауза,готов=0,сеть=3,поз=0/?,перемотка до=0,ошибка=4,источник=vkvd728.okcdn.ru,controls=false,376x579}"

    @Test fun `ошибка плеера с сервера видео, пока файлы не грузятся, — вызвана блокировкой`() {
        assertTrue(PageMedia.isSelfInflicted(clipError, blockedNow = true))
        assertTrue(PageMedia.isSelfInflicted(clipError.replace("error", "loadstart").replace("vkvd728", "vd409"), blockedNow = true))
        assertTrue(PageMedia.isSelfInflicted(clipError.replace("клип", "видео"), blockedNow = true))
    }

    @Test fun `та же ошибка у видимой страницы — настоящая`() {
        assertFalse(PageMedia.isSelfInflicted(clipError, blockedNow = false))
    }

    @Test fun `другие источники и другие записи — не трогаются`() {
        assertFalse(PageMedia.isSelfInflicted(clipError.replace("vkvd728.okcdn.ru", "blob"), blockedNow = true))
        assertFalse(PageMedia.isSelfInflicted(clipError.replace("vkvd728.okcdn.ru", "iv.okcdn.ru"), blockedNow = true))
        // Сводка «через 2000 мс … главный {…}» — диагностика страницы, не событие плеера.
        assertFalse(PageMedia.isSelfInflicted("клип: через 2000 мс кнопок запуска=0, главный {пауза,источник=vkvd1.okcdn.ru,controls=false,1x1}", blockedNow = true))
        assertFalse(PageMedia.isSelfInflicted("клип: ответ video.getPlayerConfig: поля=config", blockedNow = true))
    }

    // 4.88: звук треков — с тех же серверов vd###.okcdn.ru, что и видео.
    private val track = "/expires/1792603039145/clientType/58/srcIp/1.2.3.4/type/2/mid/1/id/2/ms/5.6.7.8/srcAg/CHROME_ANDROID/fn/track.m3u8"

    @Test fun `музыка страницы — файлы грузятся и под своим экраном`() {
        PageMedia.audioPlaying = false
        val now = 1_000_000_000L
        assertTrue("раздел музыки", PageMedia.audioMayLoad("/audio", "/video.m3u8", now))
        assertTrue("полный список", PageMedia.audioMayLoad("/audios30804332", "/", now))
        PageMedia.noteAudioCommand(now)
        assertTrue("сразу после команды плеера", PageMedia.audioMayLoad("/groups", "/", now + 5_000))
        assertFalse("команда давно", PageMedia.audioMayLoad("/groups", "/", now + PageMedia.AUDIO_COMMAND_GRACE_MS + 1))
        try {
            PageMedia.audioPlaying = true
            val later = now + 60_000
            assertTrue("играет — поток трека на другом разделе", PageMedia.audioMayLoad("/groups", track, later))
            assertFalse("играет — превью роликов раздела всё равно нет", PageMedia.audioMayLoad("/groups", "/video.m3u8", later))
            assertFalse(PageMedia.audioMayLoad("/clip-1_2", "/", later))
        } finally {
            PageMedia.audioPlaying = false
        }
        assertFalse("не играет и не музыка — правило работает", PageMedia.audioMayLoad("/groups", track, now + 60_000))
    }
}
