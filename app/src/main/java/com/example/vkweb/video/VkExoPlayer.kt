package com.example.vkweb.video

import android.content.Context
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer

/**
 * Проигрыватель видео и клипов (4.0): Media3 ExoPlayer вместо системного VideoView. По отчётам с телефона VideoView
 * подвешивал экран при каждом перелистывании клипа и смене качества (освобождение проигрывателя на главном потоке —
 * до 0,2 с) и при смене скорости (до 0,4 с). ExoPlayer один на экран: следующий ролик — новая запись в том же
 * проигрывателе, без пересоздания; скорость и перемотка не останавливают главный поток.
 */
@androidx.annotation.OptIn(markerClass = [UnstableApi::class])
object VkExoPlayer {

    /** Звук как у видео: система ставит музыку на паузу; отключили наушники — пауза. */
    fun create(context: Context): ExoPlayer = ExoPlayer.Builder(context)
        .setAudioAttributes(
            AudioAttributes.Builder().setUsage(C.USAGE_MEDIA).setContentType(C.AUDIO_CONTENT_TYPE_MOVIE).build(),
            true,
        )
        .setHandleAudioBecomingNoisy(true)
        .build()

    /** Запись для адреса: поток HLS (.m3u8) отмечается явно — по адресу VK его не всегда узнать. */
    fun item(url: String): MediaItem {
        val builder = MediaItem.Builder().setUri(url)
        if (isHls(url)) builder.setMimeType(MimeTypes.APPLICATION_M3U8)
        return builder.build()
    }

    fun isHls(url: String): Boolean = url.contains(".m3u8")

    /** Длительность в мс или 0, пока не известна. */
    fun durationMs(value: Long): Long = if (value == C.TIME_UNSET || value < 0) 0 else value
}
