package com.example.vkweb.web

/** A crash burst cannot create an unbounded WebView restart loop. */
class WindowRecoveryBudget {
    private val attempts = ArrayDeque<Long>()
    fun nextDelay(now: Long): Long? {
        while (attempts.isNotEmpty() && now - attempts.first() >= 60_000L) attempts.removeFirst()
        if (attempts.size >= 2) return null
        attempts.addLast(now)
        return if (attempts.size == 1) 1000L else 3000L
    }
}
