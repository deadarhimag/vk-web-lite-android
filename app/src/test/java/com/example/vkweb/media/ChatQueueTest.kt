package com.example.vkweb.media

import com.example.vkweb.messages.ReaderPayload
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** 3.60: музыка из переписки — очередь как в разделе «Музыка». */
class ChatQueueTest {
    private fun t(id: String) = ChatQueue.Track(id, "Название $id", "Исполнитель", "https://cs1.vkuseraudio.ru/$id.m3u8")

    @Test fun `вперёд и назад по очереди, на краях — конец`() {
        val q = ChatQueue(listOf(t("1_1"), t("1_2"), t("1_3")), 1)
        assertEquals("1_2", q.current?.id)
        assertEquals("1_3", q.next()?.id)
        assertNull(q.next())
        assertEquals("1_3", q.current?.id)
        assertEquals("1_2", q.previous()?.id)
        assertEquals("1_1", q.previous()?.id)
        assertNull(q.previous())
    }

    @Test fun `«назад» после трёх секунд — к началу трека, в начале очереди — тоже`() {
        val q = ChatQueue(listOf(t("1_1"), t("1_2")), 1)
        assertTrue(q.previousRestarts(5_000))
        assertFalse(q.previousRestarts(1_000))
        assertTrue(ChatQueue(listOf(t("1_1")), 0).previousRestarts(0))
    }

    @Test fun `свежий адрес и пропуск непроигрываемого`() {
        val q = ChatQueue(listOf(t("1_1"), t("1_2"), t("1_3")), 0)
        assertTrue(q.replaceUrl("1_2", "https://x.vkuseraudio.ru/new.m3u8"))
        assertEquals("https://x.vkuseraudio.ru/new.m3u8", q.tracks[1].url)
        assertFalse(q.replaceUrl("9_9", "https://a"))
        assertEquals("1_2", q.dropCurrent()?.id)
        assertEquals(2, q.tracks.size)
        q.next()
        assertNull(q.dropCurrent())
    }

    @Test fun `название карточки делится на исполнителя и трек`() {
        assertEquals("SISENIN" to "Блуд", ChatQueue.splitTitle("SISENIN — Блуд"))
        assertEquals("" to "Без исполнителя", ChatQueue.splitTitle("Без исполнителя"))
    }

    @Test fun `очередь от страницы — только адреса серверов звука VK`() {
        val p = ReaderPayload.parse(
            """{"type":"audioqueue","wanted":"1_2","how":"номер","items":[
              {"id":"1_1","url":"https://cs9.vkuseraudio.ru/a/index.m3u8","title":"А","artist":"Б","duration":120},
              {"id":"1_2","url":"https://evil.example/b.m3u8","title":"В"},
              {"id":"bad id","url":"https://cs9.vkuseraudio.ru/c.m3u8"}]}""",
        ) as ReaderPayload.AudioQueue
        assertEquals("1_2", p.wanted)
        assertEquals(listOf("1_1", ""), p.items.map { it.id })
        assertEquals(120, p.items[0].durationSec)
    }
}

/** 3.63: трек записи и стикеры. */
class PostAudioAndStickerTest {
    @Test fun `адреса треков записи — только звук VK, номер обязателен`() {
        val p = com.example.vkweb.owner.PostAudio.fromJson(org.json.JSONObject(
            """{"wanted":"1_2","items":[{"id":"1_2","url":"https://cs9.vkuseraudio.ru/x/index.m3u8","title":"Т","artist":"И","duration":10},
              {"id":"1_3","url":"https://evil.example/a.mp3"},{"id":"bad","url":"https://cs9.vkuseraudio.ru/y.m3u8"}]}""",
        ))!!
        assertEquals(listOf("1_2"), p.items.map { it.id })
        assertEquals("1_2", p.wanted)
    }

    @Test fun `трек в записи хранит номер и ключ, чужое отбрасывается`() {
        val e = com.example.vkweb.owner.PostExtra.fromJson(org.json.JSONObject("""{"kind":"audio","title":"А — Б","url":"","id":"-5_7","key":"ab12"}"""))!!
        assertEquals("-5_7", e.trackId)
        assertEquals("ab12", e.trackKey)
        val bad = com.example.vkweb.owner.PostExtra.fromJson(org.json.JSONObject("""{"kind":"link","title":"x","url":"https://a.b","id":"-5_7"}"""))!!
        assertEquals("", bad.trackId)
    }

    @Test fun `стикер разбирается с признаком`() {
        val photos = ReaderPayload.parsePhotos(org.json.JSONArray("""[{"url":"https://vk.com/sticker/1-5-256","width":256,"height":256,"sticker":true}]"""))
        assertTrue(photos.single().sticker)
    }
}
