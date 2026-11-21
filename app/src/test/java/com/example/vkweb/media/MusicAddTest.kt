package com.example.vkweb.media

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** «Добавить к себе» (4.45): отметка у трека, сведения об открытом плейлисте, итог действия. */
class MusicAddTest {

    private fun track(id: String, added: Boolean?) = JSONObject().put("id", id).put("title", "T").put("artist", "A")
        .put("duration", 100).apply { if (added != null) put("added", added) }

    @Test fun `трек без сведений считается добавленным — кнопки нет`() {
        val p = MusicPayload.fromJson(JSONObject().put("items", JSONArray().put(track("2_1", null)).put(track("2_2", false)).put(track("2_3", true))))
        assertEquals(listOf(true, false, true), p.tracks.map { it.added })
        assertNull("не страница плейлиста", p.playlist)
    }

    @Test fun `плейлист — кнопка только у чужого и не добавленного`() {
        fun parse(own: Any?, followed: Boolean) = MusicPayload.fromJson(
            JSONObject().put("page", "playlist").put("items", JSONArray())
                .put("playlist", JSONObject().put("id", "-9_2").put("own", own ?: JSONObject.NULL).put("followed", followed)),
        ).playlist!!
        assertTrue(parse(false, false).canFollow)
        assertFalse("уже добавлен", parse(false, true).canFollow)
        assertFalse("свой", parse(true, false).canFollow)
        assertFalse("свой номер страница не знает — не предлагать", parse(null, false).canFollow)
        assertNull(parse(null, false).own)
        assertNull(OpenedPlaylist.fromJson(JSONObject().put("id", "плохой")))
    }

    @Test fun `итог действия`() {
        val ok = MusicAction.fromJson(JSONObject().put("action", "add").put("id", "2_3").put("ok", true))!!
        assertEquals(MusicAction.ADD, ok.action)
        assertTrue(ok.ok)
        assertEquals(MusicAction.FOLLOW, MusicAction.fromJson(JSONObject().put("action", "follow").put("id", "-9_2"))!!.action)
        assertNull(MusicAction.fromJson(JSONObject().put("action", "delete").put("id", "2_3")))
        assertNull(MusicAction.fromJson(JSONObject().put("action", "add").put("id", "x")))
    }
}
