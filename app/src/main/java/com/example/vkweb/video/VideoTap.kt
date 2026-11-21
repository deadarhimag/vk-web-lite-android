package com.example.vkweb.video

/**
 * Двойное касание по видео (3.87; с 4.8 — отдельно от VideoPlayerActivity): левая треть — назад, правая — вперёд,
 * середина — пауза или игра. Без Android: проверяется [VideoTapTest].
 */
object VideoTap {

    enum class Zone { BACK, PLAY_PAUSE, FORWARD }

    /** Где коснулись: [x] — от левого края, [width] — ширина видео. */
    fun zone(x: Float, width: Int): Zone {
        val third = width / 3f
        return when {
            x < third -> Zone.BACK
            x > width - third -> Zone.FORWARD
            else -> Zone.PLAY_PAUSE
        }
    }

    /** Куда перемотать: на [deltaMs] от [positionMs], не раньше начала и не позже конца [durationMs]. */
    fun target(positionMs: Long, deltaMs: Long, durationMs: Long): Long =
        (positionMs + deltaMs).coerceIn(0, durationMs.coerceAtLeast(0))
}
