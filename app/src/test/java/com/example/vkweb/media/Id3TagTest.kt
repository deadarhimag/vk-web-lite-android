package com.example.vkweb.media

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File

class Id3TagTest {

    /** Кадр MPEG-1 Layer III, 128 кбит/с, 44,1 кГц. */
    private val mp3Frame = byteArrayOf(0xff.toByte(), 0xfb.toByte(), 0x90.toByte(), 0x64, 0, 0, 0, 0, 0, 0)

    private fun tagged(title: String, artwork: ByteArray?, audio: ByteArray): ByteArray {
        val out = ByteArrayOutputStream()
        Id3Tag.write(out, title, "Исполнитель", "Альбом", artwork)
        out.write(audio)
        return out.toByteArray()
    }

    @Test
    fun copyWithoutTagLeavesOnlyAudio() {
        val audio = mp3Frame + ByteArray(500) { it.toByte() }
        val source = tagged("Старый", byteArrayOf(1, 2, 3), audio)
        val out = ByteArrayOutputStream()
        Id3Tag.copyWithoutTag(ByteArrayInputStream(source), out)
        assertArrayEquals(audio, out.toByteArray())
    }

    @Test
    fun copyWithoutTagKeepsUntaggedAudioIntact() {
        val audio = mp3Frame + ByteArray(30) { 7 }
        val out = ByteArrayOutputStream()
        Id3Tag.copyWithoutTag(ByteArrayInputStream(audio), out)
        assertArrayEquals(audio, out.toByteArray())
    }

    @Test
    fun copyWithoutTagHandlesTinyInput() {
        val out = ByteArrayOutputStream()
        Id3Tag.copyWithoutTag(ByteArrayInputStream(byteArrayOf(1, 2)), out)
        assertArrayEquals(byteArrayOf(1, 2), out.toByteArray())
    }

    @Test
    fun tagSizeIncludesHeader() {
        val bytes = tagged("Песня", null, mp3Frame)
        assertEquals(bytes.size - mp3Frame.size, Id3Tag.tagSize(bytes))
        assertEquals(-1, Id3Tag.tagSize(mp3Frame))
    }

    @Test
    fun recognisesMp3ButNotAacOrMp4() {
        assertTrue(Id3Tag.looksLikeMp3(mp3Frame))
        assertTrue(Id3Tag.looksLikeMp3("ID3".toByteArray() + ByteArray(7)))
        // ADTS AAC: FF F1 — слой 00.
        assertFalse(Id3Tag.looksLikeMp3(byteArrayOf(0xff.toByte(), 0xf1.toByte(), 0x50, 0x80.toByte())))
        assertFalse(Id3Tag.looksLikeMp3(byteArrayOf(0, 0, 0, 0x20) + "ftypM4A ".toByteArray()))
        assertFalse(Id3Tag.looksLikeMp3(byteArrayOf(0xff.toByte())))
    }

    @Test
    fun readsBackPictureWrittenByUs() {
        val picture = ByteArray(300) { (it * 3).toByte() }
        val file = File.createTempFile("id3", ".mp3").apply { deleteOnExit() }
        file.writeBytes(tagged("С обложкой", picture, mp3Frame))
        assertArrayEquals(picture, Id3Tag.readPicture(file))
        assertTrue(Id3Tag.fileLooksLikeMp3(file))
    }

    @Test
    fun readsBackTitleAndArtist() {
        val file = File.createTempFile("id3", ".mp3").apply { deleteOnExit() }
        file.writeBytes(tagged("Песня ✓", byteArrayOf(9), mp3Frame))
        assertEquals("Исполнитель" to "Песня ✓", Id3Tag.readTitleArtist(file))
        file.writeBytes(mp3Frame)
        assertEquals("" to "", Id3Tag.readTitleArtist(file))
    }

    @Test
    fun noPictureWithoutApic() {
        val file = File.createTempFile("id3", ".mp3").apply { deleteOnExit() }
        file.writeBytes(tagged("Без обложки", null, mp3Frame))
        assertNull(Id3Tag.readPicture(file))
        file.writeBytes(mp3Frame)
        assertNull(Id3Tag.readPicture(file))
    }
}
