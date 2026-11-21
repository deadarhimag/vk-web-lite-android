package com.example.vkweb.media

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class CacheBatchTest {

    @Test
    fun reportsOnceWhenAllTracksSettled() {
        var reports = 0
        val batch = CacheBatch(4) { reports++ }
        batch.onSaved()
        batch.onAlready()
        batch.onFailed()
        assertEquals(0, reports)
        batch.onCancelled()
        assertEquals(1, reports)
        batch.onSaved() // лишнее после итога не считается
        assertEquals(1, reports)
        assertEquals(1, batch.saved)
        assertEquals(4, batch.done)
        assertTrue(batch.isFinished)
    }

    @Test
    fun failedInBulk() {
        var last: CacheBatch? = null
        val batch = CacheBatch(50) { last = it }
        repeat(20) { batch.onSaved() }
        batch.onFailed(30)
        assertEquals(30, last?.failed)
    }

    @Test
    fun emptyBatchFinishesImmediately() {
        var reports = 0
        CacheBatch(0) { reports++ }
        assertEquals(1, reports)
    }
}
