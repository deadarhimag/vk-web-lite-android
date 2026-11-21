package com.example.vkweb.media

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class InAppQueueTest {

    private fun track(n: Int, url: String = "") = MusicTrack("1_$n", "t$n", "a", 100, "", streamUrl = url)

    @Test
    fun takesContiguousPlayableNeighbours() {
        val list = listOf(track(1, "u1"), track(2), track(3, "u3"), track(4, "u4"), track(5), track(6, "u6"))
        val built = InAppQueue.build(list, "1_4") { it.streamUrl }!!
        assertEquals(listOf("1_3", "1_4"), built.tracks.map { it.id })
        assertEquals(1, built.start)
    }

    @Test
    fun cachedUrlWins() {
        val list = listOf(track(1), track(2, "https://x"))
        val built = InAppQueue.build(list, "1_1") { if (it.id == "1_1") "file:///c/1_1.mp3" else it.streamUrl }!!
        assertEquals(listOf("file:///c/1_1.mp3", "https://x"), built.tracks.map { it.url })
    }

    @Test
    fun notPlayableOrMissing() {
        val list = listOf(track(1), track(2, "u"))
        assertNull(InAppQueue.build(list, "1_1") { it.streamUrl })
        assertNull(InAppQueue.build(list, "1_9") { it.streamUrl })
    }

    @Test
    fun nextAfter() {
        val list = listOf(track(1), track(2))
        assertEquals("1_2", InAppQueue.nextAfter(list, "1_1")?.id)
        assertNull(InAppQueue.nextAfter(list, "1_2"))
        assertNull(InAppQueue.nextAfter(list, "1_3"))
    }

    @Test
    fun cachedTracksPlayFromFileVoicesUntouched() {
        val tracks = listOf(
            ChatQueue.Track("1_1", "a", "b", "https://x/1.mp3"),
            ChatQueue.Track("1_2", "a", "b", "https://x/2.mp3"),
            ChatQueue.Track("1_1", "voice", "", "https://x/v.ogg", voice = true),
        )
        val local = InAppQueue.withCached(tracks) { id -> if (id == "1_1") "content://media/1" else null }
        assertEquals(listOf("content://media/1", "https://x/2.mp3", "https://x/v.ogg"), local.map { it.url })
    }
}
