package com.example.vkweb

import android.app.Application
import com.example.vkweb.crash.CrashLog
import com.example.vkweb.diag.AppLog
import com.example.vkweb.diag.FreezeWatch
import com.example.vkweb.diag.LogTag
import com.example.vkweb.messages.ReaderScript
import com.example.vkweb.outbox.Outbox
import com.example.vkweb.settings.AppSettings
import com.example.vkweb.sync.MessageSyncScheduler
import com.example.vkweb.web.AssetText
import com.example.vkweb.web.PageScripts
import com.example.vkweb.web.PageVisibility
import com.example.vkweb.web.PrivacyShield

/** Приложение: подключает журнал событий и запись сбоев как можно раньше, до любого экрана; ставит фоновую работу. */
class VkWebApp : Application() {
    override fun onCreate() {
        super.onCreate()
        AppLog.init(this)
        AppLog.configure(AppSettings(this).logLevel)
        CrashLog.install(this)
        FreezeWatch.install(this)
        // Версия — в каждом запуске (4.11): журнал переживает обновления, и без неё не видно, какая сборка писала строки.
        val version = com.example.vkweb.diag.AppVersion.of(this)
        AppLog.i(
            LogTag.APP, "процесс запущен",
            "версия" to version.name, "сборка" to version.build, "android" to android.os.Build.VERSION.SDK_INT,
        )
        // Скрипты и стили страниц понадобятся первому же окну: читаем их из APK заранее, не в главном потоке.
        AssetText.prewarm(this) { context ->
            listOf(PageVisibility.ASSET, PrivacyShield.ASSET, ReaderScript.ASSET) + PageScripts.assetsToPrewarm(context)
        }
        scheduleBackgroundWork()
        dropLegacySettings()
        // Кэш музыки (4.95): список файлов — в память, незавершённые загрузки прошлых запусков — удалить. В фоне, не при каждом вопросе.
        com.example.vkweb.media.MusicDownloadStarter.prepare(this)
    }

    /**
     * Настройки прежних версий, которых больше нет (клипы и разделы теперь включаются пунктами меню): в отчёте они
     * выглядели действующими — «hide_clips=true» при включённых в меню клипах (отчёт 3.73).
     */
    private fun dropLegacySettings() {
        val prefs = androidx.preference.PreferenceManager.getDefaultSharedPreferences(this)
        // convert_music_to_mp3 (4.93–4.94): с 4.95 музыка всегда сохраняется в mp3, настройки нет.
        // cache_music_metered (4.95): с 4.96 кэш работает в любой сети, настройки нет.
        val legacy = listOf("hide_clips", "hidden_sections", "lite_messages", "convert_music_to_mp3", "cache_music_metered").filter { prefs.contains(it) }
        if (legacy.isEmpty()) return
        val editor = prefs.edit()
        legacy.forEach { editor.remove(it) }
        editor.apply()
        AppLog.i(LogTag.APP, "убраны настройки прежних версий", "какие" to legacy.joinToString(","))
    }

    /**
     * Фоновая работа с сообщениями. Расписание хранит система, поэтому здесь оно только приводится в соответствие
     * с настройкой (после обновления приложения или перезагрузки) и следит за очередью неотправленных:
     * когда в ней есть что отправлять, ставится одноразовое задание «отправить при появлении сети».
     */
    private fun scheduleBackgroundWork() {
        MessageSyncScheduler.applyPeriodic(this, AppSettings(this).messageSyncMinutes)
        val outbox = Outbox.get(this)
        outbox.addListener { MessageSyncScheduler.onOutboxChanged(this, outbox.items()) }
        outbox.whenReady { MessageSyncScheduler.onOutboxChanged(this, outbox.items()) }
    }
}
