package com.example.vkweb.settings

import android.Manifest
import com.example.vkweb.ui.Notice
import android.content.ActivityNotFoundException
import android.content.Intent
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.webkit.CookieManager
import android.webkit.WebStorage
import androidx.activity.result.contract.ActivityResultContracts
import androidx.annotation.StringRes
import androidx.preference.ListPreference
import androidx.preference.MultiSelectListPreference
import androidx.preference.Preference
import androidx.preference.PreferenceCategory
import androidx.preference.PreferenceFragmentCompat
import androidx.preference.SeekBarPreference
import com.example.vkweb.R
import com.example.vkweb.config.AppConfig
import com.example.vkweb.config.MenuItems
import com.example.vkweb.config.Tab
import com.example.vkweb.config.ThemeMode
import com.example.vkweb.diag.AppLog
import com.example.vkweb.diag.LogTag
import com.example.vkweb.offline.Storage
import com.example.vkweb.settings.AppSettings.Keys
import com.example.vkweb.sync.MessageSyncScheduler
import com.example.vkweb.ui.SectionLabels
import com.google.android.material.color.DynamicColors
import com.google.android.material.dialog.MaterialAlertDialogBuilder

/**
 * Настройки, сгруппированные по разделам (res/xml/preferences.xml).
 * Варианты списков берутся из тех же enum, что использует приложение, — дублирования нет.
 */
class SettingsFragment : PreferenceFragmentCompat(),
    SharedPreferences.OnSharedPreferenceChangeListener,
    StorageSection.Listener {

    private var storageSection: StorageSection? = null
    private var dumpSection: DumpSection? = null
    private var logSection: LogSection? = null
    /** Раздел «Уведомления» (4.47). */
    private val notifySection = NotifySection(this)

    /** Разрешение на уведомления (Android 13+): без него проверка сообщений идёт, но показать нечего. */
    private val notificationPermission = registerForActivityResult(ActivityResultContracts.RequestPermission()) { }

    /** «Сохранить отчёт в файл…»: пользователь сам выбирает, куда записать файл. */
    private val createLogFile = registerForActivityResult(ActivityResultContracts.CreateDocument(LogSection.MIME)) { uri ->
        logSection?.onDocumentCreated(uri)
    }

    /** «Сохранить собранное…»: пользователь сам выбирает, куда записать файл. */
    private val createDumpFile = registerForActivityResult(ActivityResultContracts.CreateDocument(DUMP_MIME)) { uri ->
        dumpSection?.onDocumentCreated(uri)
    }

    override fun onCreatePreferences(savedInstanceState: Bundle?, rootKey: String?) {
        setPreferencesFromResource(R.xml.preferences, rootKey)
        setupThemeList()
        setupNavStyleList()
        setupDynamicColors()
        setupTextZoom()
        setupMenuItemsList()
        setupImageCacheList()
        setupPausedTimeoutList()
        setupVideoQualityList()
        setupBatteryShortcut()
        notifySection.setup()
        setupNumberList(
            Keys.SESSION_REFRESH_MIN, AppConfig.SESSION_REFRESH_OPTIONS_MIN, AppConfig.SESSION_REFRESH_DEFAULT_INTERVAL_MIN,
            R.string.pref_session_refresh_off, R.string.pref_session_refresh_entry,
        )
        setupNumberList(
            Keys.MESSAGE_SYNC_MIN, AppConfig.MESSAGE_SYNC_OPTIONS_MIN, AppConfig.MESSAGE_SYNC_DEFAULT_MIN,
            R.string.pref_message_sync_off, R.string.pref_message_sync_entry,
        )
        setupNumberList(
            Keys.MESSAGES_REFRESH_SEC, AppConfig.MESSAGES_REFRESH_OPTIONS_SEC, AppConfig.MESSAGES_REFRESH_DEFAULT_SEC,
            R.string.pref_messages_refresh_off, R.string.pref_messages_refresh_entry,
        )
        setupActions()
        setupDevSection()
        // Первый экран — только список разделов: тяжёлое (размеры хранилища, журнал) — на экране своего раздела.
        if (rootKey == null) return
        storageSection = findPreference<PreferenceCategory>(Keys.STORAGE)?.let { StorageSection(requireContext(), it, this) }
        dumpSection = findPreference<Preference>(Keys.DUMP_EXPORT)?.let { export ->
            findPreference<Preference>(Keys.DUMP_SHARE)?.let { share -> DumpSection(requireContext(), export, share, createDumpFile) }
        }
        logSection = findPreference<ListPreference>(Keys.LOG_LEVEL)?.let { level ->
            val share = findPreference<Preference>(Keys.LOG_SHARE)
            val save = findPreference<Preference>(Keys.LOG_SAVE)
            if (share != null && save != null) LogSection(requireContext(), level, share, save, createLogFile) else null
        }
        findPreference<Preference>(Keys.VERSION)?.summary = appVersion()
    }

    override fun onResume() {
        super.onResume()
        settingsActivity()?.setScreenTitle(preferenceScreen?.title)
        preferenceManager.sharedPreferences?.registerOnSharedPreferenceChangeListener(this)
        storageSection?.refresh()
        dumpSection?.refresh()
        notifySection.refresh()
    }

    override fun onDestroy() {
        storageSection?.detach()
        dumpSection?.detach()
        logSection?.detach()
        super.onDestroy()
    }

    // ------------------------------------------------------------ StorageSection.Listener

    override fun onWebCacheClearRequested() {
        settingsActivity()?.requestClearCache()
    }

    override fun onStorageCleared() {
        settingsActivity()?.reportStorageCleared()
        dumpSection?.refresh()
    }

    override fun onPause() {
        preferenceManager.sharedPreferences?.unregisterOnSharedPreferenceChangeListener(this)
        super.onPause()
    }

    override fun onSharedPreferenceChanged(prefs: SharedPreferences?, key: String?) {
        // Тема меняется сразу: экран настроек пересоздаётся без потерь,
        // главный экран подхватит её, когда это не прервёт музыку.
        if (key == Keys.THEME_MODE || key == Keys.DYNAMIC_COLORS) requireActivity().recreate()
        if (key == null) return
        if (key == Keys.LOG_LEVEL) logSection?.onLevelChanged(prefs?.getString(key, null))
        else AppLog.i(LogTag.APP, "настройка изменена", "имя" to key, "значение" to prefs?.all?.get(key))
        if (key == Keys.MESSAGE_SYNC_MIN || key == Keys.NOTIFY_MESSAGES) onMessageSettingChanged(key)
        notifySection.onChanged(key)
        // 4.57: выключили «Недавние чаты на значке» — имена с рабочего стола уходят сразу.
        if (key == Keys.RECENT_CHAT_SHORTCUTS && !AppSettings(requireContext()).recentChatShortcuts) {
            com.example.vkweb.ui.AppShortcuts.clearRecent(requireContext())
        }
    }

    /**
     * «Цвета обоев» (4.23) — только там, где они есть (Android 12+ и прошивки с поддержкой): на старых переключатель
     * был виден, включался, но ничего не менял.
     */
    private fun setupDynamicColors() {
        findPreference<Preference>(Keys.DYNAMIC_COLORS)?.isVisible = DynamicColors.isDynamicColorAvailable()
    }

    /**
     * Размер текста — шагами по 10 % (4.23): шаг держит сам ползунок ([StepSeekBarPreference], 4.24). Здесь —
     * только неровное значение, сохранённое до 4.23 (113 %): оно подравнивается, чтобы ползунок встал на шаг.
     */
    private fun setupTextZoom() = findPreference<SeekBarPreference>(Keys.TEXT_ZOOM)?.apply {
        val snapped = AppConfig.textZoom(value)
        if (snapped != value) value = snapped
    }

    private fun setupThemeList() = findPreference<ListPreference>(Keys.THEME_MODE)?.apply {
        entries = ThemeMode.entries.map { getString(themeLabel(it)) }.toTypedArray()
        entryValues = ThemeMode.entries.map { it.name }.toTypedArray()
    }

    /** «Вид меню» (4.64): варианты — из [com.example.vkweb.ui.NavStyle.Choice]. */
    private fun setupNavStyleList() = findPreference<ListPreference>(Keys.NAV_STYLE)?.apply {
        val choices = com.example.vkweb.ui.NavStyle.Choice.entries
        entries = choices.map { getString(navStyleLabel(it)) }.toTypedArray()
        entryValues = choices.map { it.name }.toTypedArray()
    }

    private fun navStyleLabel(choice: com.example.vkweb.ui.NavStyle.Choice) = when (choice) {
        com.example.vkweb.ui.NavStyle.Choice.AUTO -> R.string.nav_style_auto
        com.example.vkweb.ui.NavStyle.Choice.BOTTOM -> R.string.nav_style_bottom
        com.example.vkweb.ui.NavStyle.Choice.DRAWER -> R.string.nav_style_drawer
    }

    private fun menuItemLabel(name: String): String =
        MenuItems.tab(name)?.let { getString(it.titleRes) }
            ?: MenuItems.section(name)?.let { getString(SectionLabels.of(it)) }
            ?: name

    /**
     * Пункты бокового меню: по умолчанию включены все, выключить можно любые, но один остаётся всегда — пустой набор
     * не сохраняется. Список «Раздел при запуске» всегда состоит из включённых пунктов.
     */
    private fun setupMenuItemsList() {
        val menu = findPreference<MultiSelectListPreference>(Keys.MENU_ITEMS) ?: return
        menu.entries = MenuItems.ALL.map(::menuItemLabel).toTypedArray()
        menu.entryValues = MenuItems.ALL.toTypedArray()
        val prefs = preferenceManager.sharedPreferences
        menu.values = MenuItems.fromDisabled(prefs?.getStringSet(Keys.MENU_ITEMS_OFF, null))
        menu.setSummaryProvider(Preference.SummaryProvider<MultiSelectListPreference> { pref -> menuSummary(pref.values) })
        menu.setOnPreferenceChangeListener { _, newValue ->
            val chosen = (newValue as? Set<*>)?.filterIsInstance<String>()?.filter { it in MenuItems.ALL }?.toSet().orEmpty()
            if (chosen.isEmpty()) {
                Notice.show(requireContext(), R.string.pref_menu_items_min_one)
                false
            } else {
                // Хранятся выключенные пункты: новый пункт меню включён у всех сразу.
                prefs?.edit()?.putStringSet(Keys.MENU_ITEMS_OFF, MenuItems.disabledOf(chosen))?.apply()
                setupStartSectionList(chosen)
                true
            }
        }
        setupStartSectionList(MenuItems.enabled(menu.values))
    }

    private fun menuSummary(values: Set<String>?): String {
        val enabled = MenuItems.enabled(values)
        val off = MenuItems.ALL.filter { it !in enabled }
        return when {
            off.isEmpty() -> getString(R.string.pref_menu_items_all)
            off.size <= MENU_OFF_LISTED -> getString(R.string.pref_menu_items_off, off.joinToString(", ", transform = ::menuItemLabel))
            else -> getString(R.string.pref_menu_items_some, enabled.size, MenuItems.ALL.size)
        }
    }

    private fun setupStartSectionList(enabled: Set<String>) = findPreference<ListPreference>(Keys.START_SECTION)?.apply {
        val names = MenuItems.ALL.filter { it in enabled }
        entries = names.map(::menuItemLabel).toTypedArray()
        entryValues = names.toTypedArray()
        val current = MenuItems.start(value, enabled)
        if (value != current) value = current
        summaryProvider = Preference.SummaryProvider<ListPreference> { it.entry }
    }

    private fun setupImageCacheList() = findPreference<ListPreference>(Keys.IMAGE_CACHE_MB)?.apply {
        entries = AppConfig.IMAGE_CACHE_OPTIONS_MB.map { mb ->
            if (mb >= 1000) getString(R.string.pref_image_cache_gb, mb / 1000f) else getString(
                R.string.pref_image_cache_mb, mb,
            )
        }.toTypedArray()
        entryValues = AppConfig.IMAGE_CACHE_OPTIONS_MB.map { it.toString() }.toTypedArray()
    }

    /** Качество видео: «Авто» и высоты; подпись — выбранное («Не выше 720p»). */
    private fun setupVideoQualityList() = findPreference<ListPreference>(Keys.VIDEO_QUALITY)?.apply {
        val values = listOf(com.example.vkweb.video.VideoQuality.AUTO) + com.example.vkweb.video.VideoQuality.OPTIONS
        entries = values.map {
            if (it == com.example.vkweb.video.VideoQuality.AUTO) getString(R.string.pref_video_quality_auto)
            else getString(R.string.pref_video_quality_entry, it)
        }.toTypedArray()
        entryValues = values.map { it.toString() }.toTypedArray()
        if (value == null) value = com.example.vkweb.video.VideoQuality.DEFAULT.toString()
    }

    private fun setupPausedTimeoutList() = findPreference<ListPreference>(Keys.PAUSED_TIMEOUT_MIN)?.apply {
        entries = AppConfig.PAUSED_TIMEOUT_OPTIONS_MIN.map { getString(R.string.pref_paused_timeout_entry, it) }.toTypedArray()
        entryValues = AppConfig.PAUSED_TIMEOUT_OPTIONS_MIN.map { it.toString() }.toTypedArray()
    }

    /**
     * Список чисел: ноль — «выключено», остальные — «каждые N …». Как и у остальных списков, в подписи — выбранное
     * значение; пока ничего не выбрано, берётся значение по умолчанию.
     */
    private fun setupNumberList(key: String, options: List<Int>, default: Int, @StringRes zeroRes: Int, @StringRes entryRes: Int) =
        findPreference<ListPreference>(key)?.apply {
            entries = options.map { if (it == 0) getString(zeroRes) else getString(entryRes, it) }.toTypedArray()
            entryValues = options.map { it.toString() }.toTypedArray()
            if (value == null) value = default.toString()
            summaryProvider = Preference.SummaryProvider<ListPreference> { it.entry }
        }

    /** Расписание и разрешение на уведомления — сразу при выборе, не дожидаясь возврата на главный экран. */
    private fun onMessageSettingChanged(key: String) {
        val settings = AppSettings(requireContext())
        if (key == Keys.MESSAGE_SYNC_MIN) MessageSyncScheduler.applyPeriodic(
            requireContext(), settings.messageSyncMinutes,
        )
        val wantsNotifications = settings.notifyMessages && settings.messageSyncMinutes > 0
        if (wantsNotifications && Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) askNotificationPermission()
    }

    private fun askNotificationPermission() {
        val granted = requireContext().checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED
        if (!granted) notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
    }

    /** Список приложений с ограничениями батареи: выбрать это приложение и разрешить работу без ограничений. */
    private fun setupBatteryShortcut() {
        findPreference<Preference>(Keys.BATTERY_SETTINGS)?.setOnPreferenceClickListener {
            // Сразу вопрос системы для этого приложения (3.66), а не общий список, где его ещё надо найти.
            com.example.vkweb.sync.BatteryExemption.request(requireActivity())
            true
        }
    }

    private fun setupActions() {
        findPreference<Preference>(Keys.SIGN_OUT)?.setOnPreferenceClickListener {
            confirmSignOut()
            true
        }
        findPreference<Preference>(Keys.OPEN_LINKS)?.setOnPreferenceClickListener {
            openLinkSettings()
            true
        }
    }

    /**
     * Android 12+ — экран «Открывать по умолчанию» этого приложения (там «Добавить ссылку» → vk.com); раньше — сведения
     * о приложении, где тот же пункт.
     */
    private fun openLinkSettings() {
        val uri = android.net.Uri.parse("package:" + requireContext().packageName)
        val intents = listOfNotNull(
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) Intent(Settings.ACTION_APP_OPEN_BY_DEFAULT_SETTINGS, uri) else null,
            Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, uri),
        )
        for (intent in intents) {
            try {
                startActivity(intent)
                return
            } catch (e: ActivityNotFoundException) {
                AppLog.w(LogTag.APP, "системный экран ссылок недоступен", "действие" to intent.action)
            }
        }
    }

    private fun confirmSignOut() {
        MaterialAlertDialogBuilder(requireContext())
            .setTitle(R.string.pref_sign_out_confirm_title)
            .setMessage(R.string.pref_sign_out_confirm_body)
            .setNegativeButton(R.string.cancel, null)
            .setPositiveButton(R.string.pref_sign_out_confirm) { _, _ -> signOut() }
            .show()
    }

    /** Данные входа — cookie и хранилище сайта; они общие для всех WebView приложения. */
    private fun signOut() {
        CookieManager.getInstance().removeAllCookies(null)
        CookieManager.getInstance().flush()
        WebStorage.getInstance().deleteAllData()
        // Сохранённые переписки, списки и фото — тоже данные аккаунта.
        Storage(requireContext()).clearAll()
        // И всё, что осталось от аккаунта вне хранилища (код ревью 3.59): уведомление с именем и текстом, фоновые
        // задания (иначе фоновая проверка записала бы список переписок обратно), отчёт о сбое и переданные файлы.
        val context = requireContext()
        com.example.vkweb.sync.MessageNotifier(context).cancel()
        context.getSystemService(android.app.job.JobScheduler::class.java)?.cancelAll()
        com.example.vkweb.crash.CrashLog.clear(context)
        com.example.vkweb.account.SelfProfile.clear(context)
        com.example.vkweb.messages.Drafts.clear(context)
        com.example.vkweb.messages.VoiceSendStore.clear(context)
        com.example.vkweb.messages.PendingReads(context).clear()
        // 4.47: свои настройки уведомлений диалогов — с именами собеседников, новому входу не нужны.
        com.example.vkweb.sync.DialogNotifyStore(context).clear()
        // 4.57: недавние переписки на значке — с именами собеседников.
        com.example.vkweb.ui.AppShortcuts.clearRecent(context)
        com.example.vkweb.people.GroupSeen.clear(context)
        com.example.vkweb.video.VideoResume.clear(context)
        java.io.File(context.cacheDir, "shared").deleteRecursively()
        settingsActivity()?.apply {
            requestClearCache()
            reportStorageCleared()
            reportSignedOut()
        }
        Notice.show(requireContext(), R.string.pref_sign_out_done)
    }

    private fun settingsActivity() = activity as? SettingsActivity

    private var versionTaps = 0

    /**
     * «Для разработки» (сбор данных страниц) нужен единицам: скрыт, пока не включён сбор или раздел не открыт семью
     * нажатиями на «Версию», как в Android.
     */
    private fun setupDevSection() {
        val prefs = preferenceManager.sharedPreferences
        val shown = prefs?.getBoolean(Keys.DEV_MODE, false) == true || prefs?.getBoolean(Keys.COLLECT_DUMP, false) == true
        findPreference<PreferenceCategory>(Keys.DEV_CATEGORY)?.isVisible = shown
        findPreference<Preference>(Keys.THIRD_PARTY)?.setOnPreferenceClickListener { showThirdParty(); true }
        findPreference<Preference>(Keys.APP_FOLDER)?.setOnPreferenceClickListener {
            if (!com.example.vkweb.files.AppFolder.open(requireContext())) Notice.show(requireContext(), R.string.app_folder_unavailable)
            true
        }
        findPreference<Preference>(Keys.VERSION)?.setOnPreferenceClickListener {
            versionTaps++
            if (versionTaps >= DEV_TAPS) {
                versionTaps = 0
                val already = findPreference<PreferenceCategory>(Keys.DEV_CATEGORY)?.isVisible == true
                prefs?.edit()?.putBoolean(Keys.DEV_MODE, true)?.apply()
                findPreference<PreferenceCategory>(Keys.DEV_CATEGORY)?.isVisible = true
                Notice.show(requireContext(), if (already) R.string.pref_dev_already else R.string.pref_dev_unlocked)
            }
            true
        }
    }

    /**
     * Уведомление о сторонних компонентах и полный текст LGPL-3.0 (с GPL-3.0, на которую он ссылается) — из APK
     * (assets/licenses): LGPL требует передать текст лицензии вместе с программой, ссылки недостаточно.
     */
    private fun showThirdParty() {
        val context = requireContext()
        val text = runCatching {
            listOf(THIRD_PARTY_NOTICES, LGPL_TEXT).joinToString("\n\n────────\n\n") { path ->
                context.assets.open(path).bufferedReader(Charsets.UTF_8).use { it.readText() }.trim()
            }
        }.getOrElse { getString(R.string.pref_third_party_summary) }
        MaterialAlertDialogBuilder(context)
            .setTitle(R.string.pref_third_party)
            .setMessage(text)
            .setPositiveButton(android.R.string.ok, null)
            .show()
    }

    private fun appVersion(): String {
        val context = requireContext()
        return context.packageManager.getPackageInfo(context.packageName, 0).versionName.orEmpty()
    }

    private fun themeLabel(mode: ThemeMode) = when (mode) {
        ThemeMode.DARK -> R.string.theme_dark
        ThemeMode.LIGHT -> R.string.theme_light
        ThemeMode.SYSTEM -> R.string.theme_system
        ThemeMode.BLACK -> R.string.theme_black
    }

    private companion object {
        private const val THIRD_PARTY_NOTICES = "licenses/THIRD_PARTY_NOTICES.txt"
        private const val LGPL_TEXT = "licenses/LGPL-3.0.txt"
        /** Сколько выключенных пунктов меню перечислять в подписи; больше — только число включённых. */
        private const val MENU_OFF_LISTED = 3

        /** Сколько нажатий на «Версию» открывают раздел для разработки. */
        private const val DEV_TAPS = 7

        const val DUMP_MIME = "application/json"
    }
}
