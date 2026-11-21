package com.example.vkweb.offline

import android.content.Context

/**
 * Небольшое значение (словарь, список) строкой в своём файле SharedPreferences (4.67, код ревью 4.64): общее у черновиков
 * (messages/Drafts.kt), мест в видео (video/VideoResume.kt) и прочитанного в сообществах (people/GroupSeen.kt). Раньше у
 * каждого были свои одинаковые чтение, сравнение, запись и очистка. Правила самого значения (сколько хранить, что
 * забывать) — в их чистых объектах, проверяемых тестами.
 */
class PrefsValue<T>(
    context: Context,
    private val file: String,
    private val key: String,
    decode: (String?) -> T,
    private val encode: (T) -> String,
) {
    private val prefs = context.applicationContext.getSharedPreferences(file, Context.MODE_PRIVATE)

    /** Текущее значение (читается один раз при создании). */
    var value: T = decode(prefs.getString(key, null))
        private set

    /** Записать [next], если оно отличается от текущего. @return true — изменилось. */
    fun update(next: T): Boolean {
        if (next == value) return false
        value = next
        prefs.edit().putString(key, encode(next)).apply()
        return true
    }

    companion object {
        /** Стереть файл [file] (выход из аккаунта, «Очистить сохранённое»). */
        fun clear(context: Context, file: String) {
            context.applicationContext.getSharedPreferences(file, Context.MODE_PRIVATE).edit().clear().apply()
        }
    }
}
