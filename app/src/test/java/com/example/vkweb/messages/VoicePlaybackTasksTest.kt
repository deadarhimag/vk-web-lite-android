package com.example.vkweb.messages

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class VoicePlaybackTasksTest {
    private val worker = ArrayDeque<Runnable>()
    private val main = ArrayDeque<Runnable>()
    private val events = mutableListOf<String>()

    private fun session() = VoicePlaybackTasks({ worker.addLast(it) }, { main.addLast(it) })

    @Test fun stopDoesNotWaitForReleaseAndReleasesOnceBeforePreparingNextTrack() {
        val previous = session()
        previous.command { events += "stale seek" }
        previous.close { events += "release" }
        previous.close { events += "duplicate release" }
        session().command { events += "prepare next" }

        assertTrue(events.isEmpty())
        while (worker.isNotEmpty()) worker.removeFirst().run()

        assertEquals(listOf("release", "prepare next"), events)
    }

    @Test fun resultsQueuedBeforeAndAfterStopCannotAffectNewTrack() {
        val previous = session()
        previous.result { events += "stale completion" }
        previous.close {}
        previous.result { events += "stale error" }
        session().result { events += "ready" }

        while (main.isNotEmpty()) main.removeFirst().run()

        assertEquals(listOf("ready"), events)
    }
}
