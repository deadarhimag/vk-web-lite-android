package com.example.vkweb.settings

import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.Intent
import android.provider.Settings
import androidx.preference.Preference
import androidx.preference.PreferenceFragmentCompat
import com.example.vkweb.R
import com.example.vkweb.diag.AppLog
import com.example.vkweb.diag.LogTag
import com.example.vkweb.messages.DialogNotifyDialog
import com.example.vkweb.settings.AppSettings.Keys
import com.example.vkweb.sync.MessageNotifier

/**
 * Раздел «Уведомления» экрана «Сообщения» (4.47): общий переключатель, текст в уведомлениях, свои настройки диалогов и
 * переход к системным настройкам канала (звук, вибрация). Подписи живые: «не придут» без фоновой проверки, «выключены
 * в Android», сколько диалогов со своей настройкой. На других экранах настроек этих пунктов нет — раздел ничего не делает.
 */
class NotifySection(private val fragment: PreferenceFragmentCompat) {

    private val activity: Activity get() = fragment.requireActivity()

    private val master get() = fragment.findPreference<Preference>(Keys.NOTIFY_MESSAGES)
    private val dialogs get() = fragment.findPreference<Preference>(Keys.DIALOG_NOTIFY)
    private val system get() = fragment.findPreference<Preference>(Keys.NOTIFY_SYSTEM)

    fun setup() {
        dialogs?.setOnPreferenceClickListener {
            DialogNotifyDialog.showList(activity) { refresh() }
            true
        }
        system?.setOnPreferenceClickListener {
            openSystemSettings()
            true
        }
    }

    /** Подписи — при открытии экрана (могли поменять в Android) и после изменений. */
    fun refresh() {
        if (master == null) return
        val context = fragment.context ?: return
        val settings = AppSettings(context)
        master?.setSummary(
            if (settings.notifyMessages && settings.messageSyncMinutes <= 0) R.string.pref_notify_messages_no_sync
            else R.string.pref_notify_messages_summary,
        )
        dialogs?.summary = DialogNotifyDialog.summary(activity)
        val notifier = MessageNotifier(context)
        system?.setSummary(
            if (notifier.systemAllows() && notifier.channelEnabled()) R.string.pref_notify_system_summary
            else R.string.pref_notify_system_blocked,
        )
    }

    /** Настройка изменена. Выключили уведомления — показанное уходит сразу, не дожидаясь следующей проверки. */
    fun onChanged(key: String) {
        if (key != Keys.NOTIFY_MESSAGES && key != Keys.MESSAGE_SYNC_MIN) return
        val context = fragment.context ?: return
        if (key == Keys.NOTIFY_MESSAGES && !AppSettings(context).notifyMessages) MessageNotifier(context).cancel()
        refresh()
    }

    /**
     * Уведомления приложения разрешены — экран канала «Новые сообщения» (звук, вибрация, всплывающие); выключены целиком
     * (или нет разрешения Android 13+) — экран уведомлений приложения: там их включают одним переключателем.
     */
    private fun openSystemSettings() {
        val context = activity
        val notifier = MessageNotifier(context)
        notifier.ensureChannels()
        val intent = if (notifier.systemAllows()) {
            Intent(Settings.ACTION_CHANNEL_NOTIFICATION_SETTINGS)
                .putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName)
                .putExtra(Settings.EXTRA_CHANNEL_ID, MessageNotifier.CHANNEL_ID)
        } else {
            Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName)
        }
        try {
            context.startActivity(intent)
        } catch (e: ActivityNotFoundException) {
            AppLog.w(LogTag.APP, "системный экран уведомлений недоступен", "действие" to intent.action)
            context.startActivity(
                Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, android.net.Uri.parse("package:" + context.packageName)),
            )
        }
    }
}
