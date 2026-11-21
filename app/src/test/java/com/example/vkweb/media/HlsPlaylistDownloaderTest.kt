package com.example.vkweb.media

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.File
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URL
import java.nio.file.Files

class HlsPlaylistDownloaderTest {

    private class FakeConnection(
        url: String,
        private val body: ByteArray,
        private val type: String = "",
        private val code: Int = 200,
        private val headers: Map<String, String> = emptyMap(),
        private val declaredLength: Long = -1L,
    ) : HttpURLConnection(URL(url)) {
        override fun getInputStream(): InputStream = ByteArrayInputStream(body)
        override fun getContentType(): String = type
        override fun getResponseCode(): Int = code
        override fun getContentLengthLong(): Long = declaredLength
        override fun getHeaderField(name: String?): String? = headers[name]
        override fun connect() {}
        override fun disconnect() {}
        override fun usingProxy() = false
    }

    private val base = "https://cs1.vkuseraudio.ru/audio/"
    private val ts = ByteArray(376).also { it[0] = 0x47; it[188] = 0x47 }
    private val requested = ArrayList<Pair<String, LongRange?>>()

    private fun downloader(resources: Map<String, ByteArray>) = HlsPlaylistDownloader { url, range ->
        requested += url to range
        val body = resources[url.substringBefore('?')] ?: throw IllegalStateException("unexpected $url")
        if (range != null) {
            val part = body.copyOfRange(range.first.toInt(), range.last.toInt() + 1)
            FakeConnection(url, part, code = 206, headers = mapOf("Content-Range" to "bytes ${range.first}-${range.last}/${body.size}"))
        } else FakeConnection(url, body)
    }

    private fun tempDir(): File = Files.createTempDirectory("hls-test").toFile().apply { deleteOnExit() }

    private fun save(playlist: String, resources: Map<String, ByteArray>, url: String = base + "index.m3u8"): HlsPlaylistDownloader.Transfer {
        val root = tempDir()
        return downloader(resources).saveResponse(
            FakeConnection(url, playlist.toByteArray(), "application/vnd.apple.mpegurl"), File(root, "direct.bin"), root, allowHls = true,
        )
    }

    @Test
    fun segmentsWithoutExtensionAreNamedByContent() {
        val playlist = "#EXTM3U\n#EXT-X-TARGETDURATION:10\n#EXTINF:10,\nseg1?x=1\n#EXTINF:10,\nseg2\n#EXT-X-ENDLIST\n"
        val transfer = save(playlist, mapOf(base + "seg1" to ts, base + "seg2" to ts))
        assertTrue(transfer.isHls)
        val local = transfer.playlistOrAudio.readLines().filter { !it.startsWith("#") }
        assertEquals(listOf("resource-00001.ts", "resource-00002.ts"), local)
        local.forEach { assertTrue(File(transfer.temporaryDirectory, it).isFile) }
        transfer.temporaryDirectory!!.deleteRecursively()
    }

    @Test
    fun masterPicksHighestBandwidthAndKeyBecomesLocal() {
        val master = "#EXTM3U\n#EXT-X-STREAM-INF:BANDWIDTH=64000\nlow.m3u8\n#EXT-X-STREAM-INF:BANDWIDTH=256000\nhigh.m3u8\n"
        val media = "#EXTM3U\n#EXT-X-KEY:METHOD=AES-128,URI=\"key.pub\",IV=0x1\n#EXTINF:10,\na.ts\n#EXT-X-ENDLIST\n"
        val transfer = save(master, mapOf(base + "high.m3u8" to media.toByteArray(), base + "key.pub" to ByteArray(16) { 1 }, base + "a.ts" to ts))
        val text = transfer.playlistOrAudio.readText()
        assertTrue(text, text.contains("URI=\"resource-00001.key\""))
        assertTrue(text.contains("resource-00002.ts"))
        assertFalse(requested.any { it.first.endsWith("low.m3u8") })
        transfer.temporaryDirectory!!.deleteRecursively()
    }

    @Test
    fun byteRangeWithoutOffsetContinuesPreviousRange() {
        val body = ts + ts
        val playlist = "#EXTM3U\n#EXTINF:5,\n#EXT-X-BYTERANGE:376@0\nall.ts\n#EXTINF:5,\n#EXT-X-BYTERANGE:376\nall.ts\n#EXT-X-ENDLIST\n"
        val transfer = save(playlist, mapOf(base + "all.ts" to body))
        assertEquals(listOf(0L..375L, 376L..751L), requested.map { it.second })
        assertFalse(transfer.playlistOrAudio.readText().contains("BYTERANGE"))
        transfer.temporaryDirectory!!.deleteRecursively()
    }

    @Test
    fun liveAndForeignPlaylistsRejected() {
        try {
            save("#EXTM3U\n#EXTINF:10,\na.ts\n", mapOf(base + "a.ts" to ts))
            fail("live playlist accepted")
        } catch (expected: IllegalStateException) { }
        try {
            save("#EXTM3U\n#EXTINF:10,\nhttps://evil.example/a.ts\n#EXT-X-ENDLIST\n", emptyMap())
            fail("foreign segment accepted")
        } catch (expected: IllegalArgumentException) { }
    }

    @Test
    fun directAudioIsSavedAsIs() {
        val root = tempDir()
        val mp3 = byteArrayOf(0xff.toByte(), 0xfb.toByte(), 0x90.toByte(), 0x64) + ByteArray(100)
        val transfer = downloader(emptyMap()).saveResponse(FakeConnection(base + "t.mp3", mp3, "audio/mpeg"), File(root, "direct.bin"), root, allowHls = true)
        assertFalse(transfer.isHls)
        assertEquals(mp3.size.toLong(), transfer.playlistOrAudio.length())
    }

    @Test
    fun extensionsForFfmpeg() {
        val k = HlsPlaylistDownloader.Kind.SEGMENT
        assertEquals("ts", HlsPlaylistDownloader.extensionFor(k, base + "x", "", ts, ts.size))
        val adts = byteArrayOf(0xff.toByte(), 0xf1.toByte(), 0x50, 0x80.toByte())
        assertEquals("aac", HlsPlaylistDownloader.extensionFor(k, base + "x", "", adts, adts.size))
        val moof = byteArrayOf(0, 0, 0, 8) + "moof".toByteArray()
        assertEquals("m4s", HlsPlaylistDownloader.extensionFor(k, base + "x", "", moof, moof.size))
        assertEquals("mp4", HlsPlaylistDownloader.extensionFor(HlsPlaylistDownloader.Kind.INIT, base + "x", "", moof, moof.size))
        assertEquals("key", HlsPlaylistDownloader.extensionFor(HlsPlaylistDownloader.Kind.KEY, base + "k.bin", "", ts, ts.size))
        val unknown = byteArrayOf(1, 2, 3)
        assertEquals("aac", HlsPlaylistDownloader.extensionFor(k, base + "x.bin", "audio/aac", unknown, 3))
        assertEquals("ts", HlsPlaylistDownloader.extensionFor(k, base + "x.bin", "", unknown, 3))
    }
    @Test fun incompleteDirectAudioIsRejectedBeforePublication() {
        val root = tempDir()
        try {
            downloader(emptyMap()).saveResponse(FakeConnection(base + "a.mp3", byteArrayOf(1, 2, 3), "audio/mpeg", declaredLength = 10), File(root, "direct.bin"), root, true)
            fail("Expected truncated response")
        } catch (_: java.io.EOFException) {}
    }
    @Test fun incompleteHlsResourceIsRejectedAndStagingIsCleaned() {
        val root = tempDir()
        try {
            HlsPlaylistDownloader { url, _ -> FakeConnection(url, ts, declaredLength = 1000) }.saveResponse(
                FakeConnection(base + "list.m3u8", "#EXTM3U\n#EXTINF:1,\na.ts\n#EXT-X-ENDLIST\n".toByteArray(), "application/vnd.apple.mpegurl"),
                File(root, "direct.bin"), root, true,
            )
            fail("Expected truncated resource")
        } catch (_: java.io.EOFException) {}
        assertTrue(root.listFiles().orEmpty().none { it.name.startsWith("vk-hls-") })
    }
}
