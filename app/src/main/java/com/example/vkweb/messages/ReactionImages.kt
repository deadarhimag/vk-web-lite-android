package com.example.vkweb.messages

import android.content.Context
import org.json.JSONObject

/**
 * Картинки реакций (4.61): номер реакции VK → адрес картинки, из messages.getReactionsAssets. Хранятся на диске —
 * без сети реакции сохранённых переписок тоже видны (сами картинки — в кэше фото). Номера без картинки не
 * показываются: свою таблицу «номер → эмодзи» не держим, чтобы не показать не ту реакцию.
 */
class ReactionImages(context: Context) {

    private val prefs = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    @Volatile private var cached: Map<Int, String>? = null

    fun all(): Map<Int, String> = cached ?: read().also { cached = it }

    fun save(images: Map<Int, String>) {
        if (images.isEmpty()) return
        cached = images
        prefs.edit().putString(KEY, JSONObject(images.mapKeys { it.key.toString() }).toString()).apply()
    }

    private fun read(): Map<Int, String> = try {
        val o = JSONObject(prefs.getString(KEY, null) ?: return emptyMap())
        o.keys().asSequence().mapNotNull { k -> k.toIntOrNull()?.let { it to com.example.vkweb.config.SafeUrls.https(o.optString(k)) } }
            .filter { it.second.isNotEmpty() }.toMap()
    } catch (e: Exception) {
        emptyMap()
    }

    private companion object {
        const val PREFS = "reaction_images"
        const val KEY = "images"
    }
}
