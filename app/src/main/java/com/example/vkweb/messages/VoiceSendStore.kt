package com.example.vkweb.messages

import android.content.Context
import com.example.vkweb.config.VkIds
import org.json.JSONArray
import org.json.JSONObject

/** Small private status records; no audio, access keys or sending credentials are stored here. */
class VoiceSendStore(context: Context) {
    private val prefs = context.applicationContext.getSharedPreferences(FILE, Context.MODE_PRIVATE)

    init { change(VoiceSendRules::restore) }

    fun all(): List<VoiceSendEntry> = try {
        val array = JSONArray(prefs.getString(KEY, "[]"))
        (0 until minOf(array.length(), 100)).mapNotNull { index ->
            val value = array.optJSONObject(index) ?: return@mapNotNull null
            val id = value.optString("id").take(64)
            val peer = value.optString("peer")
            if (id.isEmpty() || !VkIds.isPeer(peer)) return@mapNotNull null
            VoiceSendEntry(id, peer, value.optLong("duration").coerceIn(0, 600_000), value.optLong("at"),
                VoiceSendPhase.entries.firstOrNull { it.name == value.optString("phase") } ?: VoiceSendPhase.REVIEW,
                value.optString("document").takeIf { VkIds.isMediaId(it) }.orEmpty())
        }
    } catch (_: org.json.JSONException) { emptyList() }

    fun change(transform: (List<VoiceSendEntry>) -> List<VoiceSendEntry>) {
        val old = all()
        val next = transform(old).takeLast(100)
        if (old == next) return
        val array = JSONArray()
        next.forEach { entry -> array.put(JSONObject().put("id", entry.id).put("peer", entry.peer)
            .put("duration", entry.durationMs).put("at", entry.createdAt).put("phase", entry.phase.name)
            .put("document", entry.documentId)) }
        prefs.edit().putString(KEY, array.toString()).apply()
    }

    companion object {
        private const val FILE = "voice_send_status"
        private const val KEY = "items"
        fun clear(context: Context) { context.applicationContext.getSharedPreferences(FILE, Context.MODE_PRIVATE).edit().clear().apply() }
    }
}
