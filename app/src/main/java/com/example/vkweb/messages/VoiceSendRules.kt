package com.example.vkweb.messages

enum class VoiceSendPhase { UPLOADING, SENDING, ACCEPTED, REVIEW, FAILED }

/** Local status metadata only. It never acts as a queue or retries an audio message. */
data class VoiceSendEntry(
    val id: String,
    val peer: String,
    val durationMs: Long,
    val createdAt: Long,
    val phase: VoiceSendPhase = VoiceSendPhase.UPLOADING,
    val documentId: String = "",
) {
    val active: Boolean get() = phase == VoiceSendPhase.UPLOADING || phase == VoiceSendPhase.SENDING
}

object VoiceSendRules {
    fun reconcileHistory(entries: List<VoiceSendEntry>, peer: String, history: List<ChatMessage>) = reconcile(
        entries, peer, history.filter { it.isConfirmed && it.out }.flatMap { it.voices }.map { it.id }.toSet(),
    )

    fun restore(entries: List<VoiceSendEntry>) = entries.map { if (it.active) it.copy(phase = VoiceSendPhase.REVIEW) else it }

    fun sending(entries: List<VoiceSendEntry>, id: String, documentId: String) = entries.map {
        if (it.id == id && it.active) it.copy(phase = VoiceSendPhase.SENDING, documentId = documentId) else it
    }

    fun finish(entries: List<VoiceSendEntry>, id: String, ok: Boolean, uncertain: Boolean, documentId: String = "") = entries.map {
        if (it.id != id || !it.active) it else it.copy(
            phase = if (ok) VoiceSendPhase.ACCEPTED else if (uncertain) VoiceSendPhase.REVIEW else VoiceSendPhase.FAILED,
            documentId = documentId.ifEmpty { it.documentId },
        )
    }

    /** Only the exact uploaded document in this conversation can replace the local card. */
    fun reconcile(entries: List<VoiceSendEntry>, peer: String, documentIds: Set<String>) = entries.filterNot {
        it.peer == peer && it.documentId.isNotEmpty() && it.documentId in documentIds &&
            (it.phase == VoiceSendPhase.ACCEPTED || it.phase == VoiceSendPhase.REVIEW)
    }
}
