package com.example.vkweb.media

import java.io.ByteArrayOutputStream
import java.io.File
import java.io.InputStream
import java.io.OutputStream
import java.io.PushbackInputStream

/**
 * ID3v2.3 для сохранённых mp3 (4.95: вынесено из MusicDownloadStarter, чтобы проверять тестами): запись тегов
 * названия, исполнителя, альбома и обложки, снятие прежнего тега и распознавание mp3 по первым байтам.
 * Без зависимостей от Android.
 */
object Id3Tag {

    /** Сколько первых байт нужно [looksLikeMp3]. */
    const val SNIFF_BYTES = 10

    /** Теги трека: пустые поля не пишутся; [artwork] — JPEG. */
    fun write(output: OutputStream, title: String, artist: String, album: String, artwork: ByteArray?) {
        val frames = ByteArrayOutputStream()
        textFrame(frames, "TIT2", title)
        textFrame(frames, "TPE1", artist)
        textFrame(frames, "TALB", album)
        if (artwork != null && artwork.isNotEmpty()) {
            val picture = ByteArrayOutputStream().apply {
                write(0) // ISO-8859-1
                write(JPEG_MIME.toByteArray(Charsets.ISO_8859_1))
                write(0)
                write(FRONT_COVER)
                write(0) // пустое описание
                write(artwork)
            }.toByteArray()
            frame(frames, "APIC", picture)
        }
        val body = frames.toByteArray()
        output.write(byteArrayOf('I'.code.toByte(), 'D'.code.toByte(), '3'.code.toByte(), 3, 0, 0))
        output.write(syncSafe(body.size))
        output.write(body)
    }

    /** Аудио из [input] без ID3v2 в начале (если он есть): новые теги остаются единственными. */
    fun copyWithoutTag(input: InputStream, output: OutputStream) {
        val source = PushbackInputStream(input, HEADER)
        val header = ByteArray(HEADER)
        val count = readUpTo(source, header)
        val size = tagSize(header, count)
        if (size < 0) {
            if (count > 0) source.unread(header, 0, count)
            source.copyTo(output)
            return
        }
        // Заголовок (10 байт) уже прочитан: пропускается остальное.
        var remaining = size.toLong() - count
        while (remaining > 0) {
            val skipped = source.skip(remaining)
            if (skipped > 0) remaining -= skipped else {
                if (source.read() < 0) throw IllegalStateException("Truncated source tags")
                remaining--
            }
        }
        source.copyTo(output)
    }

    /**
     * Размер ID3v2 вместе с заголовком (и подвалом, если он есть), байт; -1 — тега нет. [count] — сколько байт
     * [header] прочитано.
     */
    fun tagSize(header: ByteArray, count: Int = header.size): Int {
        if (count < HEADER || header[0] != 'I'.code.toByte() || header[1] != 'D'.code.toByte() || header[2] != '3'.code.toByte()) return -1
        if ((6..9).any { header[it].toInt() and 0x80 != 0 }) return -1
        val body = ((header[6].toInt() and 0x7f) shl 21) or ((header[7].toInt() and 0x7f) shl 14) or
            ((header[8].toInt() and 0x7f) shl 7) or (header[9].toInt() and 0x7f)
        val footer = if (header[5].toInt() and 0x10 != 0) HEADER else 0
        return HEADER + body + footer
    }

    /**
     * Похоже на mp3: ID3v2 в начале или заголовок кадра MPEG Layer III. ADTS (AAC) и контейнеры MP4 — нет: у ADTS
     * поле «слой» равно 00, а у MP4 нет синхрослова.
     */
    fun looksLikeMp3(prefix: ByteArray, count: Int = prefix.size): Boolean {
        if (count >= 3 && prefix[0] == 'I'.code.toByte() && prefix[1] == 'D'.code.toByte() && prefix[2] == '3'.code.toByte()) return true
        if (count < 3) return false
        val b0 = prefix[0].toInt() and 0xff
        val b1 = prefix[1].toInt() and 0xff
        val b2 = prefix[2].toInt() and 0xff
        if (b0 != 0xff || b1 and 0xe0 != 0xe0) return false
        val version = (b1 shr 3) and 0x3
        val layer = (b1 shr 1) and 0x3
        val bitrate = (b2 shr 4) and 0xf
        val sampleRate = (b2 shr 2) and 0x3
        return version != 0x1 && layer == 0x1 && bitrate != 0x0 && bitrate != 0xf && sampleRate != 0x3
    }

    /** Первые байты файла — для [looksLikeMp3]. */
    fun fileLooksLikeMp3(file: File): Boolean {
        if (!file.isFile) return false
        val prefix = ByteArray(SNIFF_BYTES)
        val count = file.inputStream().use { readUpTo(it, prefix) }
        return looksLikeMp3(prefix, count)
    }

    /**
     * Обложка (APIC) из тега ID3v2.3, который пишет [write]; null — тега или обложки нет.
     */
    fun readPicture(file: File): ByteArray? = readFrames(file)["APIC"]?.let { pictureData(it, 0, it.size) }

    /** (исполнитель, название) из тега ID3v2.3 файла; пустые строки — нет тега или поля. */
    fun readTitleArtist(file: File): Pair<String, String> {
        val frames = readFrames(file)
        return text(frames["TPE1"]) to text(frames["TIT2"])
    }

    /** Кадры тега ID3v2.3 в начале файла: идентификатор → данные (первый кадр каждого вида). */
    private fun readFrames(file: File): Map<String, ByteArray> = runCatching {
        file.inputStream().buffered().use { input ->
            val header = ByteArray(HEADER)
            val count = readUpTo(input, header)
            val total = tagSize(header, count)
            if (total < 0 || header[3].toInt() != 3 || total > MAX_TAG_BYTES) return emptyMap()
            val body = ByteArray(total - HEADER)
            val read = readUpTo(input, body)
            val frames = LinkedHashMap<String, ByteArray>()
            var at = 0
            while (at + FRAME_HEADER <= read) {
                val id = String(body, at, 4, Charsets.ISO_8859_1)
                if (id[0] == '\u0000') break
                val size = ((body[at + 4].toInt() and 0xff) shl 24) or ((body[at + 5].toInt() and 0xff) shl 16) or
                    ((body[at + 6].toInt() and 0xff) shl 8) or (body[at + 7].toInt() and 0xff)
                val dataStart = at + FRAME_HEADER
                if (size <= 0 || dataStart + size > read) break
                if (id !in frames) frames[id] = body.copyOfRange(dataStart, dataStart + size)
                at = dataStart + size
            }
            frames
        }
    }.getOrDefault(emptyMap())

    private fun text(frame: ByteArray?): String {
        if (frame == null || frame.isEmpty()) return ""
        val charset = when (frame[0].toInt()) {
            0 -> Charsets.ISO_8859_1
            1 -> Charsets.UTF_16
            2 -> Charsets.UTF_16BE
            else -> Charsets.UTF_8
        }
        return String(frame, 1, frame.size - 1, charset).trimEnd('\u0000').trim()
    }

    private fun pictureData(body: ByteArray, start: Int, end: Int): ByteArray? {
        var at = start
        val encoding = body[at++].toInt()
        while (at < end && body[at].toInt() != 0) at++ // MIME
        at++
        at++ // тип картинки
        // Описание: в однобайтовой кодировке — до 0, в UTF-16 — до 00 00 на чётной позиции.
        if (encoding == 0 || encoding == 3) {
            while (at < end && body[at].toInt() != 0) at++
            at++
        } else {
            while (at + 1 < end && !(body[at].toInt() == 0 && body[at + 1].toInt() == 0)) at += 2
            at += 2
        }
        return if (at < end) body.copyOfRange(at, end) else null
    }

    private fun textFrame(output: OutputStream, id: String, value: String) {
        if (value.isBlank()) return
        // UTF-16 с BOM (Java пишет BE с FE FF) — кодировка 1 в ID3v2.3.
        val text = value.replace('\u0000', ' ').toByteArray(Charsets.UTF_16)
        frame(output, id, byteArrayOf(1) + text)
    }

    private fun frame(output: OutputStream, id: String, data: ByteArray) {
        output.write(id.toByteArray(Charsets.ISO_8859_1))
        output.write(byteArrayOf(
            ((data.size ushr 24) and 0xff).toByte(), ((data.size ushr 16) and 0xff).toByte(),
            ((data.size ushr 8) and 0xff).toByte(), (data.size and 0xff).toByte(), 0, 0,
        ))
        output.write(data)
    }

    private fun syncSafe(value: Int) = byteArrayOf(
        ((value shr 21) and 0x7f).toByte(), ((value shr 14) and 0x7f).toByte(),
        ((value shr 7) and 0x7f).toByte(), (value and 0x7f).toByte(),
    )

    private fun readUpTo(input: InputStream, buffer: ByteArray): Int {
        var count = 0
        while (count < buffer.size) {
            val read = input.read(buffer, count, buffer.size - count)
            if (read < 0) break
            count += read
        }
        return count
    }

    private const val HEADER = 10
    private const val FRAME_HEADER = 10
    private const val FRONT_COVER = 3
    private const val JPEG_MIME = "image/jpeg"
    private const val MAX_TAG_BYTES = 16 * 1024 * 1024
}
