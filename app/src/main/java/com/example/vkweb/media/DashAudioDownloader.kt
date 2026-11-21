package com.example.vkweb.media

import java.io.File
import java.net.HttpURLConnection

/** Downloads a validated finite audio plan as one local input for FFmpeg. FFmpeg never accesses the network. */
internal class DashAudioDownloader(private val open: (String, LongRange?) -> HttpURLConnection) {
    fun save(xml: String, url: String, target: File) {
        val plan = DashAudioPlan.parse(xml, url)
        var total = 0L
        try {
            target.outputStream().buffered().use { output ->
                plan.resources.forEach { resource ->
                    val connection = open(resource.url, resource.range)
                    try {
                        val range = resource.range
                        val expected = range?.let { it.last - it.first + 1L }
                        if (range != null) {
                            if (connection.responseCode != 206 ||
                                !connection.getHeaderField("Content-Range").orEmpty().startsWith("bytes ${range.first}-${range.last}/")) {
                                throw DashAudioPlan.Failure("range_response")
                            }
                        }
                        if (connection.contentLengthLong > MAX_BYTES - total) throw DashAudioPlan.Failure("audio_limit")
                        var copied = 0L
                        connection.inputStream.use { input ->
                            val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
                            while (true) {
                                val count = input.read(buffer)
                                if (count < 0) break
                                if (count.toLong() > MAX_BYTES - total || expected != null && count.toLong() > expected - copied) {
                                    throw DashAudioPlan.Failure("audio_limit")
                                }
                                output.write(buffer, 0, count); total += count; copied += count
                            }
                        }
                        if (connection.contentLengthLong >= 0 && copied != connection.contentLengthLong) {
                            throw java.io.EOFException("Truncated DASH resource")
                        }
                        if (copied == 0L || expected != null && copied != expected) throw DashAudioPlan.Failure("truncated_resource")
                    } finally { connection.disconnect() }
                }
            }
        } catch (error: Exception) {
            target.delete()
            throw error
        }
    }
    private companion object { const val MAX_BYTES = 300L * 1024 * 1024 }
}
