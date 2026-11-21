package com.example.vkweb.messages

import android.content.Context

/**
 * Отложенные отметки о прочтении (4.13): переписку открыли без сети (показана сохранённая) — прочитанной на сервере она
 * станет, когда сеть вернётся и страница сообщений снова отвечает. Хранится на диске: закрытие приложения отметку не
 * теряет. Номера переписок — не личное содержимое.
 */
class PendingReads(context: Context) : ReadMarks.Pending {

    private val prefs = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    override fun all(): Set<String> = prefs.getStringSet(KEY, null)?.toSet() ?: emptySet()

    fun add(peer: String) {
        if (peer.isEmpty() || peer in all()) return
        prefs.edit().putStringSet(KEY, all() + peer).apply()
    }

    override fun remove(peer: String) {
        if (peer !in all()) return
        prefs.edit().putStringSet(KEY, all() - peer).apply()
    }

    /** Выход из аккаунта: чужие номера новому входу не нужны. */
    fun clear() {
        prefs.edit().remove(KEY).apply()
    }

    private companion object {
        const val PREFS = "pending_reads"
        const val KEY = "peers"
    }
}
