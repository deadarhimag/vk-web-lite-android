package com.example.vkweb.messages

import android.app.Activity
import android.view.View
import android.widget.RadioGroup
import android.widget.TextView
import com.example.vkweb.R
import com.example.vkweb.diag.AppLog
import com.example.vkweb.diag.LogTag
import com.example.vkweb.settings.AppSettings
import com.example.vkweb.sync.DialogNotify
import com.example.vkweb.sync.DialogNotifyStore
import com.example.vkweb.sync.MessageNotifier
import com.example.vkweb.sync.NotifyMode
import com.example.vkweb.text.RuPlural
import com.example.vkweb.ui.Notice
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.materialswitch.MaterialSwitch

/**
 * Уведомления отдельного диалога на этом телефоне (4.47): из меню диалога в списке, из меню открытой переписки и из
 * настроек («Настройка диалогов»). Сеть не нужна: настройка — только в приложении ([DialogNotifyStore]).
 */
object DialogNotifyDialog {

    /**
     * @param mutedInVk беседа заглушена в VK (подпись «Как в VK (там выключены)»); null — неизвестно (из настроек).
     * @param onChanged настройка сохранена.
     */
    fun show(activity: Activity, peer: String, title: String, mutedInVk: Boolean?, onChanged: () -> Unit = {}) {
        if (peer.isEmpty()) return
        val store = DialogNotifyStore(activity)
        val current = store.get(peer)
        val view = activity.layoutInflater.inflate(R.layout.dialog_notify, null)
        val modes = view.findViewById<RadioGroup>(R.id.notify_modes)
        val hideText = view.findViewById<MaterialSwitch>(R.id.notify_hide_text)
        if (mutedInVk == true) view.findViewById<TextView>(R.id.notify_mode_default).setText(R.string.notify_mode_default_muted)
        modes.check(idOf(current.mode))
        hideText.isChecked = current.hideText
        warning(activity)?.let { text ->
            view.findViewById<TextView>(R.id.notify_warning).apply {
                setText(text)
                visibility = View.VISIBLE
            }
        }
        MaterialAlertDialogBuilder(activity)
            .setTitle(title.ifBlank { activity.getString(R.string.chat_notify) })
            .setView(view)
            .setNegativeButton(R.string.cancel, null)
            .setPositiveButton(R.string.done) { _, _ ->
                val rule = DialogNotify(modeOf(modes.checkedRadioButtonId), hideText.isChecked, title)
                if (rule == current.copy(title = title)) return@setPositiveButton
                store.set(peer, rule)
                // 4.48: уведомления по диалогам — выключили этот, его уведомление уходит сразу, остальные остаются.
                if (rule.mode == NotifyMode.OFF) MessageNotifier(activity).cancelDialog(peer)
                // Без имени и номера диалога: в журнале нет ни того, ни другого (README, «Журнал событий»).
                AppLog.i(LogTag.MESSAGES, "уведомления диалога изменены", "режим" to rule.mode.key, "без текста" to rule.hideText)
                onChanged()
            }
            .show()
    }

    /** Диалоги со своей настройкой (из настроек приложения): нажатие — изменить, «Сбросить все» — вернуть «как в VK». */
    fun showList(activity: Activity, onChanged: () -> Unit) {
        val store = DialogNotifyStore(activity)
        val rules = store.all().entries.sortedBy { it.value.title.lowercase() }
        val builder = MaterialAlertDialogBuilder(activity).setTitle(R.string.dialog_notify_list_title)
        if (rules.isEmpty()) {
            builder.setMessage(R.string.dialog_notify_empty).setPositiveButton(R.string.done, null).show()
            return
        }
        val labels = rules.map { (_, r) -> "${r.title.ifBlank { "—" }} — ${describe(activity, r)}" }.toTypedArray()
        builder
            .setItems(labels) { _, which ->
                val (peer, rule) = rules[which]
                show(activity, peer, rule.title, mutedInVk = null) {
                    onChanged()
                    showList(activity, onChanged)
                }
            }
            .setNegativeButton(R.string.done, null)
            .setNeutralButton(R.string.dialog_notify_reset_all) { _, _ -> confirmReset(activity, onChanged) }
            .show()
    }

    /** «Своя настройка: 3 диалога» или «все как в VK» — подпись пункта в настройках. */
    fun summary(activity: Activity): String {
        val n = DialogNotifyStore(activity).all().size
        return if (n == 0) activity.getString(R.string.pref_dialog_notify_none)
        else activity.getString(R.string.pref_dialog_notify_some, RuPlural.count(n.toLong(), "диалог", "диалога", "диалогов"))
    }

    private fun confirmReset(activity: Activity, onChanged: () -> Unit) {
        MaterialAlertDialogBuilder(activity)
            .setMessage(R.string.dialog_notify_reset_confirm)
            .setNegativeButton(R.string.cancel, null)
            .setPositiveButton(R.string.dialog_notify_reset_all) { _, _ ->
                DialogNotifyStore(activity).clear()
                AppLog.i(LogTag.MESSAGES, "уведомления всех диалогов — как в VK")
                Notice.show(activity, R.string.dialog_notify_reset_done)
                onChanged()
            }
            .show()
    }

    /** «без звука · текст скрыт». */
    private fun describe(activity: Activity, r: DialogNotify): String {
        val mode = activity.getString(
            when (r.mode) {
                NotifyMode.DEFAULT -> R.string.notify_short_default
                NotifyMode.ALWAYS -> R.string.notify_short_always
                NotifyMode.SILENT -> R.string.notify_short_silent
                NotifyMode.OFF -> R.string.notify_short_off
            },
        )
        return if (r.hideText && r.mode != NotifyMode.OFF) "$mode · ${activity.getString(R.string.notify_short_hidden)}" else mode
    }

    /** Почему настройка диалога сейчас ничего не изменит; null — всё работает. */
    private fun warning(activity: Activity): Int? {
        val settings = AppSettings(activity)
        return when {
            !settings.notifyMessages -> R.string.notify_global_off
            settings.messageSyncMinutes <= 0 -> R.string.notify_sync_off
            else -> null
        }
    }

    private fun idOf(mode: NotifyMode): Int = when (mode) {
        NotifyMode.DEFAULT -> R.id.notify_mode_default
        NotifyMode.ALWAYS -> R.id.notify_mode_always
        NotifyMode.SILENT -> R.id.notify_mode_silent
        NotifyMode.OFF -> R.id.notify_mode_off
    }

    private fun modeOf(id: Int): NotifyMode = when (id) {
        R.id.notify_mode_always -> NotifyMode.ALWAYS
        R.id.notify_mode_silent -> NotifyMode.SILENT
        R.id.notify_mode_off -> NotifyMode.OFF
        else -> NotifyMode.DEFAULT
    }
}
