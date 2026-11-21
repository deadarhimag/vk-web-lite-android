package com.example.vkweb.messages

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class AttachmentPolicyTest {
    private val mb = 1024L * 1024

    @Test fun `обычный набор проходит`() {
        assertNull(AttachmentPolicy.check(listOf(2 * mb, 500)))
        assertNull(AttachmentPolicy.check(listOf(-1L)))
    }

    @Test fun `слишком много, слишком большой, всего слишком много, пустой файл`() {
        assertEquals(AttachmentPolicy.Problem.TOO_MANY, AttachmentPolicy.check(List(11) { 1L }))
        assertEquals(AttachmentPolicy.Problem.FILE_TOO_BIG, AttachmentPolicy.check(listOf(13 * mb)))
        assertEquals(AttachmentPolicy.Problem.TOTAL_TOO_BIG, AttachmentPolicy.check(listOf(11 * mb, 11 * mb, 11 * mb)))
        assertEquals(AttachmentPolicy.Problem.EMPTY_FILE, AttachmentPolicy.check(listOf(0L, 10)))
    }

    @Test fun `куски кратны трём и покрывают файл`() {
        assertEquals(0, AttachmentPolicy.CHUNK_BYTES % 3)
        assertEquals(1, AttachmentPolicy.chunkCount(1))
        assertEquals(1, AttachmentPolicy.chunkCount(AttachmentPolicy.CHUNK_BYTES.toLong()))
        assertEquals(2, AttachmentPolicy.chunkCount(AttachmentPolicy.CHUNK_BYTES + 1L))
        assertEquals(64, AttachmentPolicy.chunkCount(12 * mb))
    }

    @Test fun `размер словами`() {
        assertEquals("12 Б", AttachmentPolicy.size(12))
        assertEquals("320 КБ", AttachmentPolicy.size(320 * 1024))
        assertEquals("1,5 МБ", AttachmentPolicy.size(1572864))
        assertEquals("", AttachmentPolicy.size(-1))
    }
}
