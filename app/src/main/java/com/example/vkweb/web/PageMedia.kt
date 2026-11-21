package com.example.vkweb.web

/**
 * Видео скрытой страницы VK (4.22). Отчёт 4.19: под своим экраном сообществ страница /groups скачала 10 МБ роликов
 * (превью-автовоспроизведение), а страница клипа под своим проигрывателем — ещё 11 МБ того же клипа, который
 * играл проигрыватель приложения: одно видео шло дважды. Пока страница скрыта своим экраном или окно не на экране,
 * файлы видео для неё не загружаются; свои проигрыватели качают сами, мимо страницы. Звук (музыка страницы) — не видео:
 * с 4.88 он идёт с тех же серверов, и для него есть исключение ([audioMayLoad]). Без Android: проверяется [PageMediaTest].
 */
object PageMedia {

    /** Серверы файлов видео VK: vkvd123.okcdn.ru, vd12.okcdn.ru (картинки — iv.okcdn.ru — не они). */
    private val VIDEO_FILE_HOST = Regex("^(vkvd|vd)\\d+\\.okcdn\\.ru$")

    fun isVideoFile(host: String?): Boolean = host != null && VIDEO_FILE_HOST.matches(host.lowercase())

    /**
     * Серверы пользовательских картинок VK: sun9-63.vkuserphoto.ru, sun1-27.userapi.com (5.00.12). Только картинки -
     * ни скриптов, ни данных страницы на них нет, поэтому под своим экраном фото их запросы страницы можно не пускать.
     */
    fun isUserPhoto(host: String?): Boolean {
        val h = host?.lowercase()?.trimEnd('.') ?: return false
        return USER_PHOTO_DOMAINS.any { h == it || h.endsWith(".$it") }
    }

    private val USER_PHOTO_DOMAINS = setOf("vkuserphoto.ru", "userapi.com")

    // ------------------------------------------------------------ музыка страницы на тех же серверах (4.88)
    // Отчёт 4.88: VK стал отдавать звук треков с vd###.okcdn.ru (audio_streams в ответе API: aac, dash, flac, HLS — в том
    // числе с путём video.m3u8), то есть с тех же серверов, что и видео. Правило «видео скрытой страницы не грузится»
    // стало глушить музыку: под своим экраном музыки трек «включался» и стоял на позиции 0 (готовность 0). По адресу
    // звук от видео не отличить, поэтому файлы с этих серверов пропускаются, пока страница занята музыкой.

    /** Музыка страницы играет (MainActivity по сообщению страницы о состоянии). */
    @Volatile var audioPlaying: Boolean = false

    /** Когда приложение дало странице команду плеера (включить трек, пауза/продолжить, перемотка), мс монотонного времени. */
    @Volatile private var audioCommandAt: Long = Long.MIN_VALUE / 2

    /** Сразу после команды плеера звук ещё не играет, а файлы уже нужны (первый кусок трека, продолжение после паузы). */
    const val AUDIO_COMMAND_GRACE_MS = 20_000L

    private fun nowMs(): Long = System.nanoTime() / 1_000_000

    fun noteAudioCommand(now: Long = nowMs()) {
        audioCommandAt = now
    }

    /** Страница — раздел музыки (/audio, /audios123, /music…): ролики там — не то, ради чего правило. */
    fun isMusicPath(path: String?): Boolean = path != null && (path.startsWith("/audio") || path.startsWith("/music"))

    /**
     * Звук трека в адресе пути (/expires/…/clientType/…/), а не в параметрах: так страница грузит трек и следующий за ним,
     * когда сама уже на другом разделе. Превью роликов (параметрами: «/?…», «/video.m3u8?…») под это не подходят.
     */
    private fun isPathStream(path: String?): Boolean = path != null && path.startsWith("/expires/")

    /**
     * Файл с сервера видео скрытой страницы можно грузить: страница в разделе музыки, только что получила команду
     * плеера или играет музыку (тогда — только поток трека, а не превью роликов раздела, на котором она сейчас).
     */
    fun audioMayLoad(pagePath: String?, requestPath: String?, now: Long = nowMs()): Boolean =
        isMusicPath(pagePath) ||
            now - audioCommandAt in 0..AUDIO_COMMAND_GRACE_MS ||
            (audioPlaying && isPathStream(requestPath))

    /**
     * Файлы видео главной страницы сейчас не грузятся (4.30): ставит MainActivity.syncPageMedia, читает журнал страницы
     * ([com.example.vkweb.diag.PageLog]). Из разных потоков — поэтому @Volatile.
     */
    @Volatile var blocked: Boolean = false

    /** Событие плеера страницы из assets/vk_lite/45_video.js: «клип: error {…,источник=vkvd12.okcdn.ru,…}». */
    private val PLAYER_EVENT = Regex("^(клип|видео): (\\w+) \\{.*,источник=([a-z0-9.-]+),")

    /**
     * Событие плеера страницы, которое вызвано самой блокировкой: файл ролика с сервера видео, пока файлы не грузятся.
     * Отчёт 4.29: под своим проигрывателем клипов каждый ролик ленты страницы давал «loadstart» и «error 4» — 100 строк
     * из 1575 и все 55 предупреждений журнала, и настоящие предупреждения тонули в них.
     */
    fun isSelfInflicted(message: String, blockedNow: Boolean = blocked): Boolean {
        if (!blockedNow) return false
        val m = PLAYER_EVENT.find(message) ?: return false
        return isVideoFile(m.groupValues[3])
    }
}
