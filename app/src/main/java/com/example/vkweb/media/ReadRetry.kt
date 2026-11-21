package com.example.vkweb.media

import java.io.EOFException
import java.net.ConnectException
import java.net.SocketException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter

/** Only finite GET/transfer reads use this policy. No API write or user action is retried here. */
object ReadRetry {
    class Cancelled : IllegalStateException("Read retry cancelled")
    const val MAX_WAIT_MS = 10_000L
    fun retryableStatus(status: Int) = status in setOf(408, 425, 429, 500, 502, 503, 504)
    fun retryAfter(value: String?, now: Long): Long? {
        val text = value?.trim()?.takeIf { it.isNotEmpty() } ?: return null
        if (text.all { it in '0'..'9' }) {
            val seconds = text.toLongOrNull() ?: return Long.MAX_VALUE
            return if (seconds > Long.MAX_VALUE / 1000) Long.MAX_VALUE else seconds * 1000
        }
        return runCatching {
            val time = ZonedDateTime.parse(text, DateTimeFormatter.RFC_1123_DATE_TIME).toInstant().toEpochMilli()
            (time - now).coerceAtLeast(0)
        }.getOrNull()
    }
    fun delay(error: Exception, retries: Int, now: Long): Long? {
        if (retries !in 0..1) return null
        val eligible = error is MusicRecovery.HttpFailure && retryableStatus(error.status) ||
            error is SocketTimeoutException || error is ConnectException || error is UnknownHostException ||
            error is SocketException || error is EOFException
        if (!eligible) return null
        val server = (error as? MusicRecovery.HttpFailure)?.let { retryAfter(it.retryAfter, now) } ?: 0L
        if (server > MAX_WAIT_MS) return null // Never retry before a longer server-directed delay.
        return maxOf(if (retries == 0) 1000L else 3000L, server)
    }
    fun <T> run(
        isCurrent: () -> Boolean,
        cleanup: () -> Unit,
        sleep: (Long) -> Unit = { Thread.sleep(it) },
        now: () -> Long = System::currentTimeMillis,
        action: () -> T,
    ): T {
        var retries = 0
        while (true) {
            if (!isCurrent()) throw Cancelled()
            try { return action() } catch (error: Exception) {
                val delay = delay(error, retries, now()) ?: throw error
                if (!isCurrent()) throw Cancelled()
                cleanup()
                var remaining = delay
                while (remaining > 0) {
                    if (!isCurrent()) throw Cancelled()
                    val part = minOf(remaining, 250L)
                    try { sleep(part) } catch (_: InterruptedException) {
                        Thread.currentThread().interrupt(); throw Cancelled()
                    }
                    remaining -= part
                }
                retries++
            }
        }
    }
}

/** Bounded per-host Retry-After memory also protects the next queued track from an immediate request. */
class RetryAfterGate(private val limit: Int = 32) {
    init { require(limit > 0) }
    private val deadlines = LinkedHashMap<String, Long>()
    @Synchronized fun record(host: String, value: String?, now: Long) {
        val delay = ReadRetry.retryAfter(value, now)?.takeIf { it > 0 } ?: return
        deadlines.entries.removeAll { it.value <= now }
        if (host !in deadlines && deadlines.size >= limit) deadlines.remove(deadlines.keys.first())
        val deadline = if (delay > Long.MAX_VALUE - now) Long.MAX_VALUE else now + delay
        deadlines[host] = maxOf(deadlines[host] ?: 0L, deadline)
    }
    @Synchronized fun remaining(host: String, now: Long): Long = (deadlines[host]?.minus(now) ?: 0).coerceAtLeast(0)
}
