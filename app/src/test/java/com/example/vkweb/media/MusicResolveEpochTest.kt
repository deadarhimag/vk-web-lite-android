package com.example.vkweb.media

import org.junit.Assert.*
import org.junit.Test

class MusicResolveEpochTest {
    @Test fun aResultBelongsToOneLookupAndOneRequestedTrack() {
        val gate = MusicResolveEpoch("test"); val token = gate.start(listOf("1_2"))
        assertFalse(gate.take(token, "1_3")); assertTrue(gate.take(token, "1_2")); assertFalse(gate.take(token, "1_2"))
    }
    @Test fun restartedLookupForSameTrackRejectsOldResult() {
        val gate = MusicResolveEpoch("test"); val old = gate.start(listOf("1_2")); val current = gate.start(listOf("1_2"))
        assertFalse(gate.take(old, "1_2")); assertTrue(gate.take(current, "1_2"))
    }
    @Test fun cancellationOrNavigationDiscardsRemainingResults() {
        val gate = MusicResolveEpoch("test"); val token = gate.start(listOf("1_2", "1_3")); gate.invalidate()
        assertFalse(gate.take(token, "1_2")); assertFalse(gate.take(token, "1_3"))
    }
    @Test fun malformedOrMissingOperationCannotConsumeAValidResult() {
        val gate = MusicResolveEpoch("test"); val token = gate.start(listOf("1_2"))
        assertFalse(gate.take("", "1_2")); assertFalse(gate.take("other", "1_2")); assertTrue(gate.take(token, "1_2"))
    }
    @Test fun twoTracksInOneBatchCanCompleteIndependently() {
        val gate = MusicResolveEpoch("test"); val token = gate.start(listOf("1_2", "1_3"))
        assertTrue(gate.take(token, "1_3")); assertTrue(gate.take(token, "1_2")); assertFalse(gate.take(token, "1_3"))
    }
}
