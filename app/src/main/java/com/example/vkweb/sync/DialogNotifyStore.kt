package com.example.vkweb.sync

import android.content.Context

/**
 * Настройки уведомлений по диалогам (4.47) на диске. Отдельный файл, не файл настроек приложения: в нём имена
 * собеседников, поэтому на новый телефон он не переезжает (data_extraction_rules) и стирается при выходе из аккаунта.
 */
class DialogNotifyStore(context: Context) {

    private val prefs = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun all(): Map<String, DialogNotify> = DialogNotifications.fromJson(prefs.getString(KEY, null))

    fun get(peer: String): DialogNotify = all()[peer] ?: DialogNotify()

    fun set(peer: String, rule: DialogNotify) {
        prefs.edit().putString(KEY, DialogNotifications.toJson(DialogNotifications.with(all(), peer, rule))).apply()
    }

    /** Все диалоги — снова «как в VK». */
    fun clear() {
        prefs.edit().remove(KEY).apply()
    }

    private companion object {
        const val PREFS = "dialog_notify"
        const val KEY = "rules"
    }
}
