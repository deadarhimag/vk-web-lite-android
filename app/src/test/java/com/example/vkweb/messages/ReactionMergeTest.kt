package com.example.vkweb.messages

import org.junit.Assert.assertEquals
import org.junit.Test

/** Реакции (4.61): свежая версия сообщения приносит свежие реакции, и при урезанной версии без фото — тоже. */
class ReactionMergeTest {

    private fun msg(reactions: List<ChatReaction>, photos: List<ChatPhoto> = emptyList(), preview: Boolean = false) =
        ChatMessage(id = "c7", out = false, text = "т", time = "12:00", photos = photos, reactions = reactions, preview = preview)

    @Test fun `свежие реакции заменяют прежние`() {
        val known = msg(listOf(ChatReaction(1, 1, false)))
        val incoming = msg(listOf(ChatReaction(1, 2, true)))
        assertEquals(incoming.reactions, HistoryMerge.richer(known, incoming).reactions)
    }

    @Test fun `урезанная версия без фото — фото прежние, реакции свежие`() {
        val photo = ChatPhoto("https://sun9-1.userapi.com/a.jpg")
        val known = msg(emptyList(), listOf(photo))
        val incoming = msg(listOf(ChatReaction(3, 1, false)))
        val merged = HistoryMerge.richer(known, incoming)
        assertEquals(listOf(photo), merged.photos)
        assertEquals(incoming.reactions, merged.reactions)
    }

    @Test fun `предпросмотр не трогает реакции`() {
        val known = msg(listOf(ChatReaction(1, 5, false)))
        assertEquals(known.reactions, HistoryMerge.richer(known, msg(emptyList(), preview = true)).reactions)
    }
}
