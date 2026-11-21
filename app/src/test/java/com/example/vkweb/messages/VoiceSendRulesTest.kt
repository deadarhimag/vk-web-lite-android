package com.example.vkweb.messages

import org.junit.Assert.*
import org.junit.Test

class VoiceSendRulesTest {
    private fun entry(id: String = "one", peer: String = "5", phase: VoiceSendPhase = VoiceSendPhase.UPLOADING, doc: String = "") =
        VoiceSendEntry(id, peer, 12_000, 1000, phase, doc)

    @Test fun `interrupted uploads never look like confirmed failures after restart`() {
        val original = VoiceSendPhase.entries.map { entry(id = it.name, phase = it) }
        val restored = VoiceSendRules.restore(original)
        assertEquals(VoiceSendPhase.REVIEW, restored[0].phase)
        assertEquals(VoiceSendPhase.REVIEW, restored[1].phase)
        assertEquals(original.drop(2), restored.drop(2))
    }

    @Test fun `timeout preserves document identity for later history confirmation`() {
        val uploading = listOf(entry())
        val sending = VoiceSendRules.sending(uploading, "one", "5_7")
        val uncertain = VoiceSendRules.finish(sending, "one", ok = false, uncertain = true)
        assertEquals(VoiceSendPhase.REVIEW, uncertain.single().phase)
        assertEquals("5_7", uncertain.single().documentId)
    }

    @Test fun `late success cannot rewrite a timed out result or finish a new operation`() {
        val entries = listOf(entry(phase = VoiceSendPhase.REVIEW), entry(id = "new"))
        assertEquals(entries, VoiceSendRules.finish(entries, "one", ok = true, uncertain = false, documentId = "5_7"))
        assertEquals(entries, VoiceSendRules.sending(entries, "old", "5_7"))
    }

    @Test fun `other conversation or another audio never removes a local result`() {
        val entries = listOf(entry(phase = VoiceSendPhase.REVIEW, doc = "5_7"))
        assertEquals(entries, VoiceSendRules.reconcile(entries, "6", setOf("5_7")))
        assertEquals(entries, VoiceSendRules.reconcile(entries, "5", setOf("5_8")))
        assertEquals(entries, VoiceSendRules.reconcile(entries, "5", emptySet()))
    }

    @Test fun `only exact server document replaces accepted or uncertain cards`() {
        val entries = listOf(entry(phase = VoiceSendPhase.ACCEPTED, doc = "5_7"),
            entry(id = "uncertain", phase = VoiceSendPhase.REVIEW, doc = "5_8"), entry(id = "other", phase = VoiceSendPhase.REVIEW, doc = "5_9"))
        assertEquals(listOf("other"), VoiceSendRules.reconcile(entries, "5", setOf("5_7", "5_8")).map { it.id })
    }

    @Test fun `missing document identity cannot accidentally match a history entry`() {
        val entries = listOf(entry(phase = VoiceSendPhase.REVIEW))
        assertEquals(entries, VoiceSendRules.reconcile(entries, "5", setOf("", "5_7")))
    }

    @Test fun `a known failure has no automatic retry transition`() {
        val failed = VoiceSendRules.finish(listOf(entry()), "one", ok = false, uncertain = false)
        assertEquals(VoiceSendPhase.FAILED, failed.single().phase)
        assertEquals(failed, VoiceSendRules.restore(failed))
        assertEquals(failed, VoiceSendRules.sending(failed, "one", "5_7"))
    }

    @Test fun `incoming or provisional audio cannot confirm the local outgoing voice`() {
        val entries = listOf(entry(phase = VoiceSendPhase.REVIEW, doc = "5_7"))
        val incoming = ChatMessage("c5", false, "", "", voices = listOf(ChatVoice("https://example/voice", id = "5_7")))
        assertEquals(entries, VoiceSendRules.reconcileHistory(entries, "5", listOf(incoming)))
        assertEquals(entries, VoiceSendRules.reconcileHistory(entries, "5", listOf(incoming.copy(id = "local5", out = true))))
        assertTrue(VoiceSendRules.reconcileHistory(entries, "5", listOf(incoming.copy(id = "c6", out = true))).isEmpty())
    }
}
