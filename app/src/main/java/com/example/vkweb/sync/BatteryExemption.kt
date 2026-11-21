package com.example.vkweb.sync

import android.annotation.SuppressLint
import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.PowerManager
import android.provider.Settings
import android.widget.Toast
import com.example.vkweb.R
import com.example.vkweb.diag.AppLog
import com.example.vkweb.diag.LogTag
import com.example.vkweb.settings.AppSettings
import com.google.android.material.dialog.MaterialAlertDialogBuilder

/**
 * Исключение из оптимизации батареи (3.66). Без него система откладывает фоновую проверку сообщений у приложения,
 * которым какое-то время не пользовались, — в отчёте 3.65 на 6 часов: сообщение пришло утром, уведомления не было.
 * У VK нет для этого клиента настоящих push-уведомлений, поэтому проверка по расписанию — единственный путь.
 */
object BatteryExemption {

    private const val PREFS = "sync_runtime"
    private const val KEY_ASKED = "battery_asked"

    fun isExempt(context: Context): Boolean =
        context.getSystemService(PowerManager::class.java)?.isIgnoringBatteryOptimizations(context.packageName) == true

    /** Один раз спросить, если уведомления о сообщениях включены, а система может откладывать проверку. */
    fun maybeAsk(activity: Activity) {
        val settings = AppSettings(activity)
        if (settings.messageSyncMinutes <= 0 || !settings.notifyMessages || isExempt(activity)) return
        val prefs = activity.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        if (prefs.getBoolean(KEY_ASKED, false)) return
        prefs.edit().putBoolean(KEY_ASKED, true).apply()
        MaterialAlertDialogBuilder(activity)
            .setTitle(R.string.battery_ask_title)
            .setMessage(R.string.battery_ask_message)
            .setPositiveButton(R.string.battery_ask_allow) { _, _ -> request(activity) }
            .setNegativeButton(R.string.battery_ask_later, null)
            .show()
    }

    /** Системный вопрос «разрешить работу без ограничений» для этого приложения; нет его — общий список. */
    @SuppressLint("BatteryLife")
    fun request(activity: Activity) {
        if (isExempt(activity)) {
            openList(activity)
            return
        }
        try {
            activity.startActivity(Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:" + activity.packageName)))
            AppLog.i(LogTag.SYNC, "запрошена работа без ограничений батареи")
        } catch (e: ActivityNotFoundException) {
            openList(activity)
        } catch (e: SecurityException) {
            openList(activity)
        }
    }

    private fun openList(activity: Activity) {
        try {
            activity.startActivity(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS))
        } catch (e: ActivityNotFoundException) {
            Toast.makeText(activity, R.string.pref_battery_unavailable, Toast.LENGTH_SHORT).show()
        }
    }
}
