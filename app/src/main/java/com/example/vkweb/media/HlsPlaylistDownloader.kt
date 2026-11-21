package com.example.vkweb.media

import java.io.BufferedInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.net.HttpURLConnection
import java.net.URI
import com.example.vkweb.config.SafeUrls

/**
 * Скачивает конечный HLS-плейлист со всеми сегментами, ключами и init-сегментом в локальную папку и переписывает его на
 * локальные файлы — для сборки mp3 (FFmpeg). Каждый адрес — только https на серверах звука VK ([SafeUrls.audio]).
 *
 * 4.95 (код ревью): файлы называются по содержимому (ts, aac, mp3, m4s, mp4), а не по адресу: FFmpeg открывает сегмент
 * HLS только с известным расширением, а у сегмента без расширения в адресе получалось «.bin».
 */
// DASH MPD is delegated to DashAudioDownloader; its complete audio input follows the same MP3 conversion path.
internal class HlsPlaylistDownloader(
    private val open: (String, LongRange?) -> HttpURLConnection,
) {
    data class Transfer(val playlistOrAudio: File, val isHls: Boolean, val temporaryDirectory: File?, val format: String = "")

    private var resourceNumber = 0
    private var totalBytes = 0L
    private val visitedPlaylists = mutableSetOf<String>()
    private val previousRangeEnd = mutableMapOf<String, Long>()

    fun saveResponse(connection: HttpURLConnection, directFile: File, temporaryRoot: File, allowHls: Boolean): Transfer {
        val path = runCatching { URI(connection.url.toString()).path.orEmpty() }.getOrDefault("")
        val mime = connection.contentType.orEmpty().substringBefore(';').trim().lowercase()
        val input = BufferedInputStream(connection.inputStream)
        input.mark(SNIFF_BYTES)
        val prefix = ByteArray(SNIFF_BYTES).let { buffer ->
            val length = input.read(buffer).coerceAtLeast(0)
            buffer.copyOf(length)
        }
        input.reset()
        val startsWithPlaylist = String(prefix, Charsets.UTF_8).trimStart('\uFEFF', ' ', '\t', '\r', '\n').startsWith("#EXTM3U")
        val hls = path.endsWith(".m3u8", ignoreCase = true) || mime.contains("mpegurl") || startsWithPlaylist
        val dash = mime.contains("dash+xml") || Regex("<(?:[A-Za-z_][\\w.-]*:)?MPD(?:\\s|>)")
            .containsMatchIn(String(prefix, Charsets.UTF_8))
        if (dash) {
            if (!allowHls) { input.close(); throw DashAudioPlan.Failure("conversion_disabled") }
            val xml = input.use(::readTextLimited)
            DashAudioDownloader(open).save(xml, connection.url.toString(), directFile)
            return Transfer(directFile, false, null, "DASH")
        }
        if (!hls) {
            var copied = 0L
            copyLimited(input, directFile, MAX_AUDIO_BYTES) { copied = it }
            if (connection.contentLengthLong >= 0 && copied != connection.contentLengthLong) {
                throw java.io.EOFException("Truncated audio response")
            }
            return Transfer(directFile, false, null)
        }
        if (!allowHls) {
            input.close()
            throw IllegalStateException("HLS source requires MP3 conversion")
        }

        val root = File.createTempFile("vk-hls-", ".dir", temporaryRoot).apply {
            if (!delete() || !mkdirs()) throw IllegalStateException("Cannot create HLS staging directory")
        }
        try {
            val playlist = File(root, "playlist.m3u8")
            val firstText = input.use(::readTextLimited)
            writeFinitePlaylist(firstText, connection.url.toString(), playlist, root, depth = 0)
            if (resourceNumber == 0) throw IllegalStateException("HLS playlist has no media segments")
            return Transfer(playlist, true, root)
        } catch (error: Exception) {
            root.deleteRecursively()
            throw error
        }
    }

    private fun writeFinitePlaylist(
        text: String,
        baseUrl: String,
        output: File,
        root: File,
        depth: Int,
    ) {
        if (depth > MAX_PLAYLIST_DEPTH) throw IllegalStateException("HLS playlist nesting is too deep")
        val canonicalUrl = URI(baseUrl).normalize().toString()
        if (!visitedPlaylists.add(canonicalUrl)) throw IllegalStateException("HLS playlist loop detected")
        val lines = text.removePrefix("\uFEFF").lineSequence().map { it.trim() }.filter(String::isNotEmpty).toList()
        if (lines.firstOrNull() != "#EXTM3U") throw IllegalStateException("Invalid HLS playlist")

        val audioRendition = lines.asSequence()
            .filter { it.startsWith("#EXT-X-MEDIA:") }
            .mapNotNull { line ->
                val attrs = attributes(line.substringAfter(':'))
                if (attrs["TYPE"] != "AUDIO") null else attrs["URI"]?.let { uri ->
                    Triple(attrs["DEFAULT"] == "YES", attrs["LANGUAGE"].orEmpty(), uri)
                }
            }
            .sortedByDescending { it.first }
            .firstOrNull()
        val variants = mutableListOf<Pair<Long, String>>()
        for (index in lines.indices) {
            val line = lines[index]
            if (!line.startsWith("#EXT-X-STREAM-INF:")) continue
            val next = (index + 1 until lines.size).firstOrNull { !lines[it].startsWith("#") }?.let(lines::get) ?: continue
            val bandwidth = attributes(line.substringAfter(':'))["BANDWIDTH"]?.toLongOrNull() ?: 0L
            variants += bandwidth to next
        }
        if (audioRendition != null || variants.isNotEmpty()) {
            val selected = audioRendition?.third ?: variants.maxByOrNull { it.first }?.second
                ?: throw IllegalStateException("HLS master playlist has no playable stream")
            val childUrl = resolveSafe(baseUrl, selected)
            val child = open(childUrl, null)
            val (childText, childFinalUrl) = try {
                child.inputStream.use { stream -> readTextLimited(stream) } to child.url.toString()
            } finally {
                child.disconnect()
            }
            writeFinitePlaylist(childText, childFinalUrl, output, root, depth + 1)
            return
        }

        if (lines.none { it == "#EXT-X-ENDLIST" }) throw IllegalStateException("Live HLS cannot be cached as a complete track")
        val rewritten = ArrayList<String>(lines.size)
        var pendingRange: String? = null
        for (line in lines) {
            when {
                line.startsWith("#EXT-X-BYTERANGE:") -> pendingRange = line.substringAfter(':')
                line.startsWith("#EXT-X-PART:") || line.startsWith("#EXT-X-PRELOAD-HINT:") ->
                    throw IllegalStateException("Unsupported partial HLS segment")
                line.startsWith("#EXT-X-MAP:") -> {
                    val attrs = attributes(line.substringAfter(':'))
                    rewritten += rewriteUriAttribute(line, baseUrl, root, Kind.INIT, attrs["BYTERANGE"])
                }
                line.startsWith("#EXT-X-KEY:") -> rewritten += rewriteUriAttribute(line, baseUrl, root, Kind.KEY)
                line.startsWith("#") -> rewritten += line
                else -> {
                    val url = resolveSafe(baseUrl, line)
                    rewritten += downloadResource(url, root, Kind.SEGMENT, pendingRange?.let { parseRange(url, it) }).name
                    pendingRange = null
                }
            }
        }
        if (rewritten.none { !it.startsWith("#") }) throw IllegalStateException("HLS playlist has no media segments")
        output.writeText(rewritten.joinToString("\n", postfix = "\n"), Charsets.UTF_8)
    }

    private fun rewriteUriAttribute(line: String, baseUrl: String, root: File, kind: Kind, byteRange: String? = null): String {
        val colon = line.indexOf(':')
        if (colon < 0) return line
        val matches = ATTRIBUTES.findAll(line.substring(colon + 1)).toList()
        val uriAttribute = matches.firstOrNull { it.groupValues[1].equals("URI", ignoreCase = true) } ?: return line
        val uriValue = uriAttribute.groupValues[3].ifEmpty { uriAttribute.groupValues[4].trim() }
        val remote = resolveSafe(baseUrl, uriValue)
        val local = downloadResource(remote, root, kind, byteRange?.let { parseRange(remote, it) })
        val attributes = matches.mapNotNull { match ->
            val key = match.groupValues[1]
            when {
                byteRange != null && key.equals("BYTERANGE", ignoreCase = true) -> null
                key.equals("URI", ignoreCase = true) -> "$key=\"${local.name}\""
                else -> match.value
            }
        }
        return line.substring(0, colon + 1) + attributes.joinToString(",")
    }

    private fun downloadResource(url: String, root: File, kind: Kind, byteRange: LongRange? = null): File {
        if (++resourceNumber > MAX_RESOURCES) throw IllegalStateException("HLS playlist has too many resources")
        val connection = open(url, byteRange)
        try {
            if (byteRange != null) {
                val expectedHeader = "bytes ${byteRange.first}-${byteRange.last}/"
                if (connection.responseCode != HttpURLConnection.HTTP_PARTIAL ||
                    !connection.getHeaderField("Content-Range").orEmpty().startsWith(expectedHeader)
                ) throw IllegalStateException("HLS server did not honor the requested byte range")
            }
            val partial = File(root, "resource-${resourceNumber.toString().padStart(5, '0')}.part")
            val expectedBytes = byteRange?.let { it.last - it.first + 1L }
            copyLimited(connection.inputStream, partial, minOf(MAX_HLS_BYTES - totalBytes, expectedBytes ?: Long.MAX_VALUE)) { bytes -> totalBytes += bytes }
            if (connection.contentLengthLong >= 0 && partial.length() != connection.contentLengthLong) {
                throw java.io.EOFException("Truncated HLS resource")
            }
            if (expectedBytes != null && partial.length() != expectedBytes) throw IllegalStateException("Truncated HLS byte range")
            if (partial.length() == 0L) throw IllegalStateException("Empty HLS resource")
            val prefix = ByteArray(SNIFF_SEGMENT_BYTES)
            val count = partial.inputStream().use { input -> input.read(prefix).coerceAtLeast(0) }
            val extension = extensionFor(kind, connection.url.toString(), connection.contentType.orEmpty(), prefix, count)
            val file = File(root, partial.name.removeSuffix(".part") + "." + extension)
            if (!partial.renameTo(file)) throw IllegalStateException("Cannot name HLS resource")
            return file
        } finally {
            connection.disconnect()
        }
    }

    private fun parseRange(url: String, value: String): LongRange {
        val match = BYTE_RANGE.matchEntire(value.trim()) ?: throw IllegalStateException("Invalid HLS byte range")
        val length = match.groupValues[1].toLongOrNull()?.takeIf { it > 0L }
            ?: throw IllegalStateException("Invalid HLS byte range length")
        val start = match.groupValues[2].takeIf { it.isNotEmpty() }?.toLongOrNull() ?: previousRangeEnd[url]
            ?: throw IllegalStateException("HLS byte range has no start offset")
        val end = start + length - 1L
        if (end < start) throw IllegalStateException("HLS byte range overflow")
        previousRangeEnd[url] = end + 1L
        return start..end
    }

    private fun resolveSafe(baseUrl: String, relative: String): String {
        val value = runCatching { URI(baseUrl).resolve(relative).toString() }.getOrDefault("")
        if (SafeUrls.audio(value).isEmpty()) {
            throw IllegalArgumentException("Untrusted HLS resource")
        }
        return value
    }

    private fun attributes(value: String): Map<String, String> = ATTRIBUTES.findAll(value).associate {
        it.groupValues[1].uppercase() to it.groupValues[3].ifEmpty { it.groupValues[4].trim() }
    }

    private fun readTextLimited(input: java.io.InputStream): String {
        val output = ByteArrayOutputStream()
        val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
        while (true) {
            val count = input.read(buffer)
            if (count < 0) break
            if (output.size() + count > MAX_PLAYLIST_BYTES) throw IllegalStateException("HLS playlist is too large")
            output.write(buffer, 0, count)
        }
        return output.toString(Charsets.UTF_8.name())
    }

    private fun copyLimited(
        input: java.io.InputStream,
        file: File,
        limit: Long,
        onBytes: (Long) -> Unit = {},
    ) {
        if (limit <= 0) throw IllegalStateException("Audio download exceeds the cache limit")
        var copied = 0L
        input.use { source -> file.outputStream().use { target ->
            val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
            while (true) {
                val count = source.read(buffer)
                if (count < 0) break
                copied += count
                if (copied > limit) throw IllegalStateException("Audio download exceeds the cache limit")
                target.write(buffer, 0, count)
            }
        } }
        onBytes(copied)
    }

    /** Что за ресурс плейлиста: от этого зависит, какое расширение ждёт FFmpeg. */
    internal enum class Kind { SEGMENT, INIT, KEY }

    internal companion object {
        /**
         * Расширение файла ресурса для FFmpeg: ключ — «key»; сегмент и init — по содержимому, затем по типу ответа,
         * затем по адресу (если оно из списка, который принимает демультиплексор HLS), иначе «ts».
         */
        fun extensionFor(kind: Kind, url: String, contentType: String, prefix: ByteArray, count: Int): String {
            if (kind == Kind.KEY) return "key"
            val sniffed = sniff(prefix, count)
            if (sniffed != null) return if (sniffed == "mp4" && kind == Kind.SEGMENT) "m4s" else sniffed
            val mime = contentType.substringBefore(';').trim().lowercase()
            MIME_EXTENSIONS[mime]?.let { return if (it == "mp4" && kind == Kind.SEGMENT) "m4s" else it }
            val fromUrl = runCatching { URI(url).path.orEmpty().substringAfterLast('/').substringAfterLast('.', "") }
                .getOrDefault("").lowercase()
            if (fromUrl in SEGMENT_EXTENSIONS) return fromUrl
            return if (kind == Kind.INIT) "mp4" else "ts"
        }

        /** Формат по первым байтам: MPEG-TS, mp3, AAC (ADTS), фрагмент MP4; null — не распознан. */
        fun sniff(prefix: ByteArray, count: Int): String? {
            if (count >= 1 && prefix[0] == TS_SYNC && (count < TS_PACKET + 1 || prefix[TS_PACKET] == TS_SYNC)) return "ts"
            if (count >= 8) {
                val box = String(prefix, 4, 4, Charsets.ISO_8859_1)
                if (box in MP4_BOXES) return "mp4"
            }
            if (Id3Tag.looksLikeMp3(prefix, count) && !(count >= 3 && prefix[0] == 'I'.code.toByte())) return "mp3"
            if (count >= 2 && prefix[0].toInt() and 0xff == 0xff && prefix[1].toInt() and 0xf6 == 0xf0) return "aac"
            // ID3 в начале — так начинаются сегменты «packed audio» (AAC или mp3 с меткой времени).
            if (count >= 3 && prefix[0] == 'I'.code.toByte() && prefix[1] == 'D'.code.toByte() && prefix[2] == '3'.code.toByte()) {
                val tag = Id3Tag.tagSize(prefix, count)
                if (tag in 0 until count - 2) {
                    val rest = prefix.copyOfRange(tag, count)
                    if (Id3Tag.looksLikeMp3(rest)) return "mp3"
                }
                return "aac"
            }
            return null
        }

        private const val TS_SYNC: Byte = 0x47
        private const val TS_PACKET = 188
        private const val SNIFF_SEGMENT_BYTES = 1024
        private val MP4_BOXES = setOf("ftyp", "styp", "moof", "moov", "sidx", "free", "mdat")
        private val MIME_EXTENSIONS = mapOf(
            "video/mp2t" to "ts", "video/mpeg" to "ts", "audio/mp2t" to "ts",
            "audio/aac" to "aac", "audio/aacp" to "aac", "audio/x-aac" to "aac",
            "audio/mpeg" to "mp3", "audio/mp3" to "mp3",
            "audio/mp4" to "mp4", "video/mp4" to "mp4", "video/iso.segment" to "mp4",
        )
        /** Расширения, с которыми демультиплексор HLS FFmpeg открывает сегменты (его allowed_extensions). */
        private val SEGMENT_EXTENSIONS = setOf(
            "3gp", "aac", "ac3", "eac3", "flac", "m4a", "m4s", "m4v", "mov", "mp2", "mp3", "mp4", "mpeg", "mpegts", "ogg", "oga", "ts", "wav",
        )
        const val SNIFF_BYTES = 4096
        const val MAX_PLAYLIST_BYTES = 2 * 1024 * 1024
        const val MAX_AUDIO_BYTES = 300L * 1024 * 1024
        const val MAX_HLS_BYTES = 300L * 1024 * 1024
        const val MAX_RESOURCES = 10_000
        const val MAX_PLAYLIST_DEPTH = 4
        val BYTE_RANGE = Regex("(\\d+)(?:@(\\d+))?")
        val ATTRIBUTES = Regex("([A-Z0-9-]+)\\s*=\\s*(\\\"([^\\\"]*)\\\"|([^,]*))", RegexOption.IGNORE_CASE)
    }
}
