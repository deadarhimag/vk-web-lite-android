package com.example.vkweb.media

import org.junit.Assert.*
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.nio.file.Files

class DashAudioDownloaderTest {
    private val base = "https://vd1.okcdn.ru/manifest"
    private val xml = """<MPD><Period><AdaptationSet mimeType="audio/mp4"><Representation><SegmentList><Initialization sourceURL="init"/><SegmentURL media="one"/><SegmentURL media="two"/></SegmentList></Representation></AdaptationSet></Period></MPD>"""
    private class Fake(url: String, val data: ByteArray, val status: Int = 200, val contentRange: String? = null, val mime: String = "", val declaredLength: Long = data.size.toLong()) : HttpURLConnection(URL(url)) {
        var closed = false
        override fun getInputStream() = ByteArrayInputStream(data)
        override fun getResponseCode() = status
        override fun getContentType() = mime
        override fun getContentLengthLong() = declaredLength
        override fun getHeaderField(name: String?) = if (name == "Content-Range") contentRange else null
        override fun connect() {}
        override fun disconnect() { closed = true }
        override fun usingProxy() = false
    }
    private fun target() = File(Files.createTempDirectory("dash-test").toFile().apply { deleteOnExit() }, "audio.bin")
    @Test fun concatenatesInitializationAndAllFragmentsInOrder() {
        val requested = ArrayList<String>(); val connections = ArrayList<Fake>()
        val target = target()
        DashAudioDownloader { url, range ->
            assertNull(range); requested += url
            Fake(url, url.substringAfterLast('/').toByteArray()).also { connections += it }
        }.save(xml, base, target)
        assertEquals("initonetwo", target.readText()); assertEquals(3, requested.size); assertTrue(connections.all { it.closed })
    }
    @Test fun segmentBaseUsesWholeFileGet() {
        val target = target(); var requestedRange: LongRange? = 1L..2L
        DashAudioDownloader { url, range -> requestedRange = range; Fake(url, "complete audio".toByteArray()) }
            .save("<MPD><Period><AdaptationSet contentType='audio'><Representation><BaseURL>track.mp4</BaseURL><SegmentBase indexRange='10-20'/></Representation></AdaptationSet></Period></MPD>", base, target)
        assertNull(requestedRange); assertEquals("complete audio", target.readText())
    }
    @Test fun honorsValidatedByteRanges() {
        val target = target()
        DashAudioDownloader { url, range ->
            assertEquals(2L..4L, range); Fake(url, byteArrayOf(2, 3, 4), 206, "bytes 2-4/10")
        }.save("<MPD><Period><AdaptationSet contentType='audio'><Representation><BaseURL>track.mp4</BaseURL><SegmentList><SegmentURL mediaRange='2-4'/></SegmentList></Representation></AdaptationSet></Period></MPD>", base, target)
        assertArrayEquals(byteArrayOf(2, 3, 4), target.readBytes())
    }
    @Test fun wrongRangeResponseDeletesPartialOutput() {
        val target = target()
        try {
            DashAudioDownloader { url, _ -> Fake(url, byteArrayOf(1), 200) }.save(xml.replace("media=\"one\"", "media=\"one\" mediaRange=\"0-1\""), base, target)
            fail("Expected range failure")
        } catch (error: DashAudioPlan.Failure) { assertEquals("range_response", error.reason) }
        assertFalse(target.exists())
    }
    @Test fun emptyResourceDeletesPartialOutput() {
        val target = target()
        try {
            DashAudioDownloader { url, _ -> Fake(url, byteArrayOf()) }.save(xml, base, target); fail("Expected empty failure")
        } catch (error: DashAudioPlan.Failure) { assertEquals("truncated_resource", error.reason) }
        assertFalse(target.exists())
    }
    @Test fun manifestDetectionFeedsDashDownloaderInsteadOfSavingXmlAsAudio() {
        val target = target(); val root = target.parentFile!!
        val transfer = HlsPlaylistDownloader { url, range -> assertNull(range); Fake(url, "audio".toByteArray()) }
            .saveResponse(Fake(base, "<MPD><Period><AdaptationSet contentType='audio'><Representation><BaseURL>track.mp4</BaseURL></Representation></AdaptationSet></Period></MPD>".toByteArray(), mime = "application/dash+xml"), target, root, true)
        assertEquals("DASH", transfer.format); assertEquals("audio", target.readText()); assertFalse(transfer.isHls)
    }
    @Test fun truncatedDashResourceIsNotKeptAsACompleteFile() {
        val target = target()
        try {
            DashAudioDownloader { url, _ -> Fake(url, byteArrayOf(1, 2), declaredLength = 10) }.save(xml, base, target)
            fail("Expected truncated DASH resource")
        } catch (_: java.io.EOFException) {}
        assertFalse(target.exists())
    }
}
