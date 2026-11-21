package com.example.vkweb.media

import org.junit.Assert.*
import org.junit.Test

class StreamLifetimeTest {
    @Test fun missingAndExpiredAddressIsNotReusable() {
        assertEquals(0L, StreamLifetime.limit(0, 1000))
        assertEquals(0L, StreamLifetime.limit(999, 1000))
        assertFalse(StreamLifetime.isFresh(1000, 1000))
    }
    @Test fun validServerDeadlineIsPreserved() {
        assertEquals(2000L, StreamLifetime.limit(2000, 1000))
        assertTrue(StreamLifetime.isFresh(2000, 1000))
    }
    @Test fun excessivelyLongAddressLifetimeIsLimited() {
        assertEquals(1000 + StreamLifetime.MAX_AGE_MS, StreamLifetime.limit(Long.MAX_VALUE, 1000))
        assertFalse(StreamLifetime.isFresh(Long.MAX_VALUE, 1000))
    }
    @Test fun badClockCannotTurnUnknownAddressIntoFreshOne() {
        assertEquals(0L, StreamLifetime.limit(1000, -1))
        assertFalse(StreamLifetime.isFresh(1000, -1))
    }
    @Test fun deadlineArithmeticDoesNotOverflow() {
        assertEquals(Long.MAX_VALUE, StreamLifetime.limit(Long.MAX_VALUE, Long.MAX_VALUE - 10))
        assertTrue(StreamLifetime.isFresh(Long.MAX_VALUE, Long.MAX_VALUE - 10))
    }
}
