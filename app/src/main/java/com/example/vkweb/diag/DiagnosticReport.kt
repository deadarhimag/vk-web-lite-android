package com.example.vkweb.diag

import com.example.vkweb.settings.AppSettings
import com.example.vkweb.Background
import android.app.ActivityManager
import android.app.usage.UsageStatsManager
import android.content.Context
import android.content.pm.ApplicationInfo
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.webkit.WebView
import androidx.preference.PreferenceManager
import com.example.vkweb.R
import com.example.vkweb.offline.OfflineStore
import com.example.vkweb.web.HostStats
import com.example.vkweb.crash.CrashLog
import com.example.vkweb.dev.DumpRecorder
import com.example.vkweb.offline.Storage
import com.example.vkweb.offline.StorageCategory
import com.example.vkweb.sync.MessageSyncScheduler
import java.time.ZoneId
import java.util.Locale

/**
 * Диагностический отчёт одним текстом: окружение, настройки, сколько хранится, последний сбой и журнал событий.
 * Его присылают разработчику, чтобы по одному файлу понять, что происходило. Личного в отчёте нет:
 * только технические сведения; куки, токены и содержимое страниц сюда не попадают.
 * Собирается в фоне ([build]): читает диск и считает размеры.
 */
object DiagnosticReport {

    private const val EVENT_LINES_IN_REPORT = 2000
    private val worker = Background.serial("diag-report")
    private val main = Handler(Looper.getMainLooper())

    /**
     * Собирает отчёт и передаёт его в [consume] — в фоновом потоке, поэтому там же можно писать файл, не
     * загружая главный поток (отчёт бывает в сотни килобайт). Результат [consume] приходит в [done] в главном потоке.
     */
    fun <T> build(context: Context, consume: (String) -> T, done: (T) -> Unit) {
        val app = context.applicationContext
        Storage(app).measure { sizes ->
            worker.execute {
                val text = ReportFormat.assemble(app.getString(R.string.report_title), sections(app, sizes))
                val value = consume(text)
                main.post { done(value) }
            }
        }
    }

    private fun sections(context: Context, sizes: Map<StorageCategory, Long>): List<ReportFormat.Section> = listOf(
        ReportFormat.Section(context.getString(R.string.report_app), appLines(context)),
        ReportFormat.Section(context.getString(R.string.report_device), deviceLines(context)),
        ReportFormat.Section(context.getString(R.string.report_network), networkLines(context)),
        ReportFormat.Section(context.getString(R.string.report_settings), settingsLines(context)),
        ReportFormat.Section(context.getString(R.string.report_storage), storageLines(context, sizes)),
        ReportFormat.Section(context.getString(R.string.report_cache), cacheLines(context)),
        ReportFormat.Section(context.getString(R.string.report_servers), HostStats.lines().ifEmpty { listOf("запросов ещё не было") }),
        ReportFormat.Section(context.getString(R.string.report_crash), crashLines(context)),
        ReportFormat.Section(context.getString(R.string.report_events), eventLines()),
    )

    private fun appLines(context: Context): List<String> {
        val info = try {
            context.packageManager.getPackageInfo(context.packageName, 0)
        } catch (e: Exception) {
            null
        }
        val debuggable = context.applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE != 0
        return listOf(
            "версия=${info?.versionName ?: "?"}",
            // PackageInfoCompat: longVersionCode — с Android 9, а приложение работает с Android 8 (Lint NewApi, 4.68).
            "сборка=${info?.let { androidx.core.content.pm.PackageInfoCompat.getLongVersionCode(it) } ?: "?"}",
            "отладочная=$debuggable",
            "журнал=${if (AppLog.isEnabled) "включён" else "выключен"}",
        ) + backgroundLines(context)
    }

    /**
     * Почему фоновая работа может не идти: группа ожидания (App Standby) и ограничения батареи. Система откладывает
     * фоновые задания приложениям, которыми давно не пользовались, — без этих строк по отчёту не понять, виновато ли оно.
     */
    private fun backgroundLines(context: Context): List<String> {
        val usage = context.getSystemService(UsageStatsManager::class.java)
        val power = context.getSystemService(PowerManager::class.java)
        // Явная проверка версии (4.69): через takeIf Lint её не видел (NewApi), хотя вызов и раньше был только на Android 9+.
        val bucket = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P && usage != null) {
            ReportFormat.standbyBucket(usage.appStandbyBucket)
        } else {
            "?"
        }
        return listOf(
            "группа ожидания=$bucket",
            "без ограничений батареи=${power?.isIgnoringBatteryOptimizations(context.packageName) ?: "?"}",
            "расписание: ${MessageSyncScheduler.describe(context)}",
        )
    }

    private fun deviceLines(context: Context): List<String> {
        val webView = try {
            WebView.getCurrentWebViewPackage()?.let { "${it.packageName} ${it.versionName}" }
        } catch (e: Throwable) {
            null
        } ?: "?"
        val metrics = context.resources.displayMetrics
        val memory = context.getSystemService(ActivityManager::class.java)
        return listOf(
            "устройство=${Build.MANUFACTURER} ${Build.MODEL}",
            "android=${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})",
            "webview=$webView",
            "экран=${metrics.widthPixels}x${metrics.heightPixels} плотность=${metrics.density}",
            "память приложения=${memory?.memoryClass ?: "?"} МБ" + if (memory?.isLowRamDevice == true) " (слабое устройство)" else "",
            "язык=${Locale.getDefault().toLanguageTag()} пояс=${ZoneId.systemDefault()}",
        )
    }

    private fun networkLines(context: Context): List<String> {
        val connectivity = context.getSystemService(ConnectivityManager::class.java)
        val caps = connectivity?.activeNetwork?.let(connectivity::getNetworkCapabilities)
            ?: return listOf("состояние=нет сети")
        val kind = when {
            caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) -> "wifi"
            caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) -> "мобильная"
            caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) -> "ethernet"
            else -> "другая"
        }
        return listOf(
            "тип=$kind",
            "интернет=${caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)}",
            "проверена системой=${caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)}",
            "экономия трафика=${dataSaver(connectivity.restrictBackgroundStatus)}",
        )
    }

    private fun dataSaver(status: Int) = when (status) {
        ConnectivityManager.RESTRICT_BACKGROUND_STATUS_ENABLED -> "включена"
        ConnectivityManager.RESTRICT_BACKGROUND_STATUS_WHITELISTED -> "включена, приложению разрешено"
        else -> "выключена"
    }

    /** Все настройки приложения (в них нет входа и личных данных). */
    private fun settingsLines(context: Context): List<String> =
        ReportFormat.settings(PreferenceManager.getDefaultSharedPreferences(context).all)

    private fun storageLines(context: Context, sizes: Map<StorageCategory, Long>): List<String> {
        val lines = StorageCategory.entries.map { category ->
            "${context.getString(category.titleRes)}=${ReportFormat.size(sizes[category] ?: 0)}"
        }
        val dump = DumpRecorder.get(context).statsBlocking()
        return lines + "собрано записей страниц=${dump.records}" +
            "диагностика музыки: schema=1, сбор=${AppSettings(context).collectDump}" +
            "окно кэша: ${com.example.vkweb.media.MusicCacheWorker.diagnosticState}"
    }

    /** Что и как давно сохранено: если показывается старое, видно, когда оно обновлялось в последний раз. */
    private fun cacheLines(context: Context): List<String> =
        OfflineStore.get(context).describe { context.getString(it.titleRes) }

    /** Только читает отчёт: [CrashLog.hasReport] сдвигает отметку прошлого завершения, и экран сбоя не показался бы. */
    private fun crashLines(context: Context): List<String> {
        val report = CrashLog.read(context)
        if (report.isBlank()) return listOf("отчёта о сбое нет")
        return report.lineSequence().take(CRASH_LINES).toList()
    }

    private fun eventLines(): List<String> {
        val all = AppLog.readAll().lines().filter { it.isNotBlank() }
        val shown = all.takeLast(EVENT_LINES_IN_REPORT)
        val skipped = all.size - shown.size
        return if (skipped > 0) listOf("(пропущено старых записей: $skipped)") + shown else shown
    }

    private const val CRASH_LINES = 60
}
