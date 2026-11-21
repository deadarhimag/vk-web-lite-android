package com.example.vkweb.media

/** The lifetime of a signed network address is independent of the lifetime of an offline MP3. */
object StreamLifetime {
    const val MAX_AGE_MS = 5 * 60_000L
    fun limit(proposed: Long, now: Long): Long {
        if (now < 0 || proposed <= now) return 0L
        val maximum = if (now > Long.MAX_VALUE - MAX_AGE_MS) Long.MAX_VALUE else now + MAX_AGE_MS
        return minOf(proposed, maximum)
    }
    fun isFresh(until: Long, now: Long): Boolean = now >= 0 && until > now && until - now <= MAX_AGE_MS
}
