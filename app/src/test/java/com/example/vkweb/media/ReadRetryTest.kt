package com.example.vkweb.media

import org.junit.Assert.*
import org.junit.Test
import java.io.EOFException
import java.net.SocketTimeoutException

class ReadRetryTest {
    @Test fun temporaryReadFailureRetriesAndCleansPartialResult() {
        var calls = 0; var cleaned = 0; var waited = 0L
        val result = ReadRetry.run({ true }, { cleaned++ }, { waited += it }) {
            calls++; if (calls < 3) throw EOFException(); "complete"
        }
        assertEquals("complete", result); assertEquals(3, calls); assertEquals(2, cleaned); assertEquals(4000L, waited)
    }
    @Test fun permanentFailureDoesNotRetry() {
        var calls = 0
        try { ReadRetry.run({ true }, {}, {}) { calls++; throw MusicRecovery.HttpFailure(403) }; fail() }
        catch (error: MusicRecovery.HttpFailure) { assertEquals(403, error.status) }
        assertEquals(1, calls)
    }
    @Test fun retriesHaveAFixedUpperBound() {
        var calls = 0
        try { ReadRetry.run({ true }, {}, {}) { calls++; throw SocketTimeoutException() }; fail() }
        catch (_: SocketTimeoutException) {}
        assertEquals(3, calls)
    }
    @Test fun retryAfterSecondsAndDateAreHonored() {
        assertEquals(7000L, ReadRetry.delay(MusicRecovery.HttpFailure(429, "7"), 0, 0))
        assertEquals(5000L, ReadRetry.retryAfter("Thu, 1 Jan 1970 00:00:05 GMT", 0))
        assertNull(ReadRetry.delay(MusicRecovery.HttpFailure(503, "60"), 0, 0))
    }
    @Test fun invalidHeaderDoesNotProduceAnUnboundedWait() {
        assertEquals(1000L, ReadRetry.delay(MusicRecovery.HttpFailure(503, "invalid"), 0, 0))
        assertNull(ReadRetry.delay(MusicRecovery.HttpFailure(503, "999999999999999999999999"), 0, 0))
    }
    @Test fun cancellationDuringWaitPreventsTheNextRequest() {
        var current = true; var calls = 0
        try { ReadRetry.run({ current }, {}, { current = false }) { calls++; throw EOFException() }; fail() }
        catch (_: ReadRetry.Cancelled) {}
        assertEquals(1, calls)
    }
    @Test fun unavailableGenerationDoesNotStartARequest() {
        var calls = 0
        try { ReadRetry.run({ false }, {}, {}) { calls++ }; fail() } catch (_: ReadRetry.Cancelled) {}
        assertEquals(0, calls)
    }
    @Test fun tlsAndLocalFileErrorsDoNotLoop() {
        assertNull(ReadRetry.delay(javax.net.ssl.SSLHandshakeException("certificate"), 0, 0))
        assertNull(ReadRetry.delay(java.io.FileNotFoundException(), 0, 0))
    }
    @Test fun perHostCooldownAlsoProtectsTheNextTrack() {
        val gate = RetryAfterGate(); gate.record("audio", "60", 1000)
        gate.record("audio", "1", 1500)
        assertEquals(59000L, gate.remaining("audio", 2000)); assertEquals(0L, gate.remaining("other", 2000))
        assertEquals(0L, gate.remaining("audio", 61000))
    }
    @Test fun cooldownStorageIsBounded() {
        val gate = RetryAfterGate(2); listOf("a", "b", "c").forEach { gate.record(it, "60", 1000) }
        assertEquals(0L, gate.remaining("a", 1000)); assertEquals(60000L, gate.remaining("c", 1000))
    }
}
