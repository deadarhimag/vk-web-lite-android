package com.example.vkweb.config

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Номера VK — одно правило для всего приложения (код ревью 4.43). */
class VkIdsTest {

    @Test fun `номер переписки`() {
        for (v in listOf("1", "-1", "2000000012", "442473503")) assertTrue(v, VkIds.isPeer(v))
        for (v in listOf(null, "", "1_2", "abc", "1234567890123", "--1")) assertFalse(v.toString(), VkIds.isPeer(v))
    }

    @Test fun `номер ролика, трека, фото`() {
        for (v in listOf("1_2", "-212788102_456239591")) assertTrue(v, VkIds.isMediaId(v))
        for (v in listOf(null, "", "1", "1_", "_2", "1_2_3", "x_2")) assertFalse(v.toString(), VkIds.isMediaId(v))
    }

    @Test fun `номер для комментариев - ролик или запись стены с буквой w`() {
        for (v in listOf("1_2", "-5_1", "w-42_7", "w1_2")) assertTrue(v, VkIds.isCommentTarget(v))
        for (v in listOf(null, "", "w", "w1", "wx-1_2", "ww1_2", "w-1_2_3", "1_2w")) assertFalse(v.toString(), VkIds.isCommentTarget(v))
        assertFalse("у ролика буквы нет", VkIds.isMediaId("w-42_7"))
    }

    @Test fun `беседа и человек`() {
        assertTrue(VkIds.isChat(2_000_000_012L))
        assertTrue(VkIds.isChat("2000000001"))
        assertFalse(VkIds.isChat("5"))
        assertFalse(VkIds.isChat(null as String?))
        assertTrue(VkIds.isUser(5))
        assertFalse(VkIds.isUser(-5))
        assertFalse(VkIds.isUser(2_000_000_012L))
    }
}
