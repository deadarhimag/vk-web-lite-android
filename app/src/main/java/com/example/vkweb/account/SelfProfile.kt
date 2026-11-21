package com.example.vkweb.account

import com.example.vkweb.config.SafeUrls
import android.content.Context
import org.json.JSONObject

/**
 * Своя страница (3.94): имя и фото вошедшего — в шапке бокового меню вместо названия приложения, нажатие ведёт на
 * свою страницу. Приходит от окна чтения сообщений (users.get), хранится до выхода из аккаунта.
 */
data class SelfProfile(val id: Long, val name: String, val photo: String) {

    /** Путь своей страницы на сайте. */
    val path: String get() = "/id$id"

    fun toJson(): JSONObject = JSONObject().put("id", id).put("name", name).put("photo", photo)

    companion object {
        private const val PREFS = "account"
        private const val KEY = "self_profile"
        private const val MAX_NAME = 120
        private const val MAX_URL = 2000

        /** Данные чужие (страница): номер положительный, имя непустое, фото — только https. */
        fun from(id: Long, name: String, photo: String): SelfProfile? {
            val cleanName = name.replace(Regex("\\s+"), " ").trim().take(MAX_NAME)
            if (id <= 0 || cleanName.isEmpty()) return null
            val cleanPhoto = SafeUrls.https(photo, MAX_URL)
            return SelfProfile(id, cleanName, cleanPhoto)
        }

        fun fromJson(o: JSONObject?): SelfProfile? =
            o?.let { from(it.optLong("id"), it.optString("name"), it.optString("photo")) }

        fun load(context: Context): SelfProfile? {
            val raw = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KEY, null) ?: return null
            return try {
                fromJson(JSONObject(raw))
            } catch (e: org.json.JSONException) {
                null
            }
        }

        /** @return true, если сохранённое изменилось (шапку меню стоит перерисовать). */
        fun save(context: Context, profile: SelfProfile): Boolean {
            if (load(context) == profile) return false
            context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putString(KEY, profile.toJson().toString()).apply()
            return true
        }

        /** Выход из аккаунта. */
        fun clear(context: Context) {
            context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().remove(KEY).apply()
        }
    }
}
