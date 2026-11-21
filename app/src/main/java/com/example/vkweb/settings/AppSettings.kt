package com.example.vkweb.settings

import android.content.Context
import android.content.SharedPreferences
import androidx.preference.PreferenceManager
import com.example.vkweb.R
import com.example.vkweb.config.AppConfig
import com.example.vkweb.config.HiddenSection
import com.example.vkweb.config.MenuItems
import com.example.vkweb.config.Tab
import com.example.vkweb.config.ThemeMode
import com.example.vkweb.diag.LogLevel
import com.example.vkweb.diag.LogSetting

/**
 * Типизированный доступ ко всем настройкам. Экран настроек (preferences.xml) пишет
 * в те же SharedPreferences по ключам из [Keys]; значения по умолчанию совпадают с XML.
 */
class AppSettings(context: Context) {

    private val prefs: SharedPreferences =
        PreferenceManager.getDefaultSharedPreferences(context.applicationContext)

    init {
        // Записывает значения по умолчанию из XML при первом запуске (повторно ничего не меняет).
        PreferenceManager.setDefaultValues(context.applicationContext, R.xml.preferences, false)
    }

    // Оформление
    val themeMode: ThemeMode get() = ThemeMode.fromName(prefs.getString(Keys.THEME_MODE, null))
    /** Цвета темы из обоев (Material You, Android 12+); по умолчанию — свои цвета приложения. */
    val dynamicColors: Boolean get() = prefs.getBoolean(Keys.DYNAMIC_COLORS, false)
    val textZoom: Int
        get() = AppConfig.textZoom(prefs.getInt(Keys.TEXT_ZOOM, AppConfig.DEFAULT_TEXT_ZOOM))

    // Навигация

    /** Включённые пункты бокового меню (имена из [MenuItems.ALL]); по умолчанию все, минимум один. */
    val enabledMenuItems: Set<String>
        get() = MenuItems.fromDisabled(prefs.getStringSet(Keys.MENU_ITEMS_OFF, null))

    /** Экран при запуске: выбранный пункт меню, а если он выключен, первый включённый. */
    val startItem: String
        get() = MenuItems.start(prefs.getString(Keys.START_SECTION, null), enabledMenuItems)

    /** Стартовый раздел меню; если запуск с открытого раздела VK ([startSection]), то первый включённый обычный раздел. */
    val startTab: Tab
        get() = MenuItems.tab(startItem)
            ?: Tab.entries.firstOrNull { it != Tab.MESSAGES && it !in hiddenTabs }
            ?: Tab.DEFAULT

    /** Запуск с открытого раздела VK (лента, маркет и т. п.); null — запуск с обычного раздела. */
    val startSection: HiddenSection? get() = MenuItems.section(startItem)

    /** Разделы, выключенные в меню. */
    val hiddenTabs: Set<Tab> get() = Tab.entries.filter { it.name !in enabledMenuItems }.toSet()
    val drawerSwipe: Boolean get() = prefs.getBoolean(Keys.DRAWER_SWIPE, true)

    /** Меню уже открывали хоть раз (4.1): до этого на панели есть кнопка ☰. Не настройка, а память приложения. */
    var menuDiscovered: Boolean
        get() = prefs.getBoolean(Keys.MENU_DISCOVERED, false)
        set(value) {
            prefs.edit().putBoolean(Keys.MENU_DISCOVERED, value).apply()
        }
    val pinNavOnWideScreens: Boolean get() = prefs.getBoolean(Keys.PIN_NAV_WIDE, false)

    /** Короткие подписи по умолчанию; явно сохранённый компактный режим имеет приоритет. */
    val navLabels: Boolean get() = prefs.getBoolean(Keys.NAV_LABELS, true)

    /** Вид меню разделов (4.64): автоматически (по навигации Android и ширине), нижняя панель или боковое меню. */
    val navStyleChoice: com.example.vkweb.ui.NavStyle.Choice
        get() = com.example.vkweb.ui.NavStyle.Choice.fromName(prefs.getString(Keys.NAV_STYLE, null))
    /** Две колонки в «Сообщениях» на широком экране (3.94). */
    val messagesTwoPane: Boolean get() = prefs.getBoolean(Keys.MESSAGES_TWO_PANE, true)

    // Содержимое

    /**
     * Разделы VK, закрытые для переходов: выключенные в меню и те, у которых пункта меню нет (истории, платежи,
     * каталог сообществ, настройки рекламы) — они закрыты всегда.
     */
    val hiddenSections: Set<HiddenSection>
        get() = enabledMenuItems.let { enabled ->
            HiddenSection.entries.filter { it.menuPath == null || it.name !in enabled }.toSet()
        }

    /** Включённые разделы VK с пунктом меню — в порядке перечня. */
    val openedMenuSections: List<HiddenSection>
        get() = enabledMenuItems.let { enabled -> HiddenSection.entries.filter { it.menuPath != null && it.name in enabled } }

    /** Клипы выключены в меню: раздел клипов закрыт, подборки клипов на страницах прячутся. Отдельный клип открывается всегда. */
    val hideClips: Boolean get() = Tab.CLIPS.name !in enabledMenuItems

    // Конфиденциальность (всё включено по умолчанию)
    val hideAds: Boolean get() = prefs.getBoolean(Keys.HIDE_ADS, true)
    val blockTrackers: Boolean get() = prefs.getBoolean(Keys.BLOCK_TRACKERS, true)

    /** Не выдавать VK сведения об устройстве (User-Agent, экран, железо, режимы). Применяется при запуске. */
    val hideDeviceInfo: Boolean get() = prefs.getBoolean(Keys.HIDE_DEVICE_INFO, true)

    // Экспериментально
    /** Свой интерфейс сообщений. Включён по умолчанию; при сбое экран сам откроет страницу VK. */
    val nativeMessages: Boolean get() = prefs.getBoolean(Keys.NATIVE_MESSAGES, true)
    val nativeMusic: Boolean get() = prefs.getBoolean(Keys.NATIVE_MUSIC, true)
    /** Свои списки «Друзья» и «Сообщества» поверх страниц VK. */
    val nativePeople: Boolean get() = prefs.getBoolean(Keys.NATIVE_PEOPLE, true)
    /** Свои альбомы и фото поверх страниц /albums, /album, /photos (5.00.12). */
    val nativePhotos: Boolean get() = prefs.getBoolean(Keys.NATIVE_PHOTOS, true)
    /** Свои экраны страниц людей и сообществ (шапка и стена). */
    val nativeOwner: Boolean get() = prefs.getBoolean(Keys.NATIVE_OWNER, true)
    /** Свой каталог VK Видео (3.78). */
    val nativeVideo: Boolean get() = prefs.getBoolean(Keys.NATIVE_VIDEO, true)
    /** Своя лента новостей (3.77) — тем же экраном, что стена сообщества. */
    val nativeFeed: Boolean get() = prefs.getBoolean(Keys.NATIVE_FEED, true)
    /** Свой проигрыватель ленты клипов (3.76). */
    val nativeClips: Boolean get() = prefs.getBoolean(Keys.NATIVE_CLIPS, true)
    /** Как часто проверять новые сообщения в фоне, минуты; 0 — не проверять. */
    val messageSyncMinutes: Int
        get() = intOption(Keys.MESSAGE_SYNC_MIN, AppConfig.MESSAGE_SYNC_OPTIONS_MIN, AppConfig.MESSAGE_SYNC_DEFAULT_MIN)

    /** Показывать ли уведомления о новых сообщениях (нужно ещё и системное разрешение). */
    val notifyMessages: Boolean get() = prefs.getBoolean(Keys.NOTIFY_MESSAGES, true)

    /** Текст сообщений в уведомлениях (4.47); выключено — только имя и «Новое сообщение». У диалога может быть своё. */
    val notifyPreview: Boolean get() = prefs.getBoolean(Keys.NOTIFY_PREVIEW, true)

    /** Недавние переписки ярлыками на значке приложения (4.57). */
    val recentChatShortcuts: Boolean get() = prefs.getBoolean(Keys.RECENT_CHAT_SHORTCUTS, true)

    /** Как часто обновлять открытый экран сообщений, секунды; 0 — не обновлять. */
    val messagesRefreshSeconds: Int
        get() = intOption(Keys.MESSAGES_REFRESH_SEC, AppConfig.MESSAGES_REFRESH_OPTIONS_SEC, AppConfig.MESSAGES_REFRESH_DEFAULT_SEC)

    /** Уровень журнала событий; null — выключен. */
    val logLevel: LogLevel? get() = LogSetting.level(prefs.getString(Keys.LOG_LEVEL, null))

    /** Собирать обезличенные ответы страниц для разработки новых экранов. */
    val collectDump: Boolean get() = prefs.getBoolean(Keys.COLLECT_DUMP, false)
    val imageCacheMb: Int
        get() = prefs.getString(Keys.IMAGE_CACHE_MB, null)?.toIntOrNull()
            ?.takeIf { it in AppConfig.IMAGE_CACHE_OPTIONS_MB } ?: AppConfig.IMAGE_CACHE_DEFAULT_MB.toInt()

    // Воспроизведение
    val backgroundPlayback: Boolean get() = prefs.getBoolean(Keys.BACKGROUND_PLAYBACK, true)
    /**
     * Сохранять играющие треки (mp3) в папку приложения для повторного и офлайн-воспроизведения. Сохраняется трек,
     * как только он заиграл (4.99.3: порога прослушивания нет).
     */
    val cacheMusic: Boolean get() = prefs.getBoolean(Keys.CACHE_MUSIC, false)
    /** «Моя музыка» целиком - в кэш сама, при входе в раздел (5.00.12: отдельно от [cacheMusic], по умолчанию выключено). */
    val cacheMyMusic: Boolean get() = cacheMusic && prefs.getBoolean(Keys.CACHE_MY_MUSIC, false)
    /** Плановый интервал фонового обновления сессии, мс; 0 — выключено. */
    val sessionRefreshIntervalMs: Long
        get() = intOption(
            Keys.SESSION_REFRESH_MIN, AppConfig.SESSION_REFRESH_OPTIONS_MIN, AppConfig.SESSION_REFRESH_DEFAULT_INTERVAL_MIN,
        ) * 60_000L

    /**
     * Измеренные сроки жизни веб-сессии (мс), по которым [com.example.vkweb.web.SessionLifetime] уточняет, когда
     * обновлять. Это не настройка пользователя, а память приложения; хранится здесь же.
     */
    var sessionLifetimeSamples: List<Long>
        get() = prefs.getString(Keys.SESSION_LIFETIME, null)
            ?.split(',')?.mapNotNull { it.trim().toLongOrNull() }.orEmpty()
        set(value) {
            prefs.edit().putString(Keys.SESSION_LIFETIME, value.joinToString(",")).apply()
        }

    /**
     * Качество видео по умолчанию (3.86): высота кадра — потолок (самое высокое не выше, см. VideoQuality.pick) или
     * 0 — «Авто» (поток, подстраивающийся под сеть).
     */
    val videoQuality: Int
        get() = prefs.getString(Keys.VIDEO_QUALITY, null)?.toIntOrNull()
            ?.takeIf { it == com.example.vkweb.video.VideoQuality.AUTO || it in com.example.vkweb.video.VideoQuality.OPTIONS }
            ?: com.example.vkweb.video.VideoQuality.DEFAULT

    /**
     * Горизонтальное видео — альбомно само, даже при заблокированном повороте (3.88). С 3.89 выключено по умолчанию:
     * поворот — по настройке Android, как у приложений обычно.
     */
    val videoAutoRotate: Boolean get() = prefs.getBoolean(Keys.VIDEO_AUTO_ROTATE, false)

    /** Непрочитанные переписки — заранее и без отметки о прочтении (4.13): открыть потом можно и без сети. */
    val prefetchUnread: Boolean get() = prefs.getBoolean(Keys.PREFETCH_UNREAD, true)

    /** Горизонтальный свайп по своим экранам разделов — к соседнему разделу меню (4.13). */
    val sectionSwipe: Boolean get() = prefs.getBoolean(Keys.SECTION_SWIPE, true)

    /** Громкость жестом по видео (4.9): палец вверх — громче, вниз — тише. */
    val videoVolumeGesture: Boolean get() = prefs.getBoolean(Keys.VIDEO_VOLUME_GESTURE, true)

    val pausedTimeoutMs: Long
        get() {
            val minutes = prefs.getString(Keys.PAUSED_TIMEOUT_MIN, null)?.toIntOrNull()
                ?.takeIf { it in AppConfig.PAUSED_TIMEOUT_OPTIONS_MIN }
                ?: AppConfig.DEFAULT_PAUSED_TIMEOUT_MIN
            return minutes * 60_000L
        }

    /** Число из списка допустимых значений настройки; ничего не выбрано или значение не из списка — [default]. */
    private fun intOption(key: String, options: List<Int>, default: Int): Int =
        prefs.getString(key, null)?.toIntOrNull()?.takeIf { it in options } ?: default

    /** Фото в записях открываются страницей VK (лайки, комментарии), а не просмотром картинки. */
    val photoOpensPage: Boolean get() = prefs.getBoolean(Keys.PHOTO_OPENS_PAGE, false)

    object Keys {
        const val THEME_MODE = "theme_mode"
        const val DYNAMIC_COLORS = "dynamic_colors"
        const val TEXT_ZOOM = "text_zoom"
        const val START_SECTION = "start_section"
        const val DRAWER_SWIPE = "menu_edge_swipe"
        const val MENU_DISCOVERED = "menu_discovered"
        const val PIN_NAV_WIDE = "pin_nav_wide"
        const val NAV_STYLE = "nav_style"
        const val NAV_LABELS = "nav_labels"
        const val MESSAGES_TWO_PANE = "messages_two_pane"
        const val HIDE_ADS = "hide_ads"
        const val BLOCK_TRACKERS = "block_trackers"
        const val HIDE_DEVICE_INFO = "hide_device_info"
        // Прежний экспериментальный переключатель был выключен по умолчанию; новый ключ — чтобы
        // свой интерфейс включился у всех, а не только у тех, кто менял настройку.
        const val NATIVE_MESSAGES = "native_messages"
        const val IMAGE_CACHE_MB = "image_cache_mb"
        /** Раздел с сохранёнными данными: пункты добавляются из [com.example.vkweb.offline.StorageCategory]. */
        const val STORAGE = "storage"
        const val NATIVE_MUSIC = "native_music"
        const val NATIVE_PEOPLE = "native_people"
        const val NATIVE_PHOTOS = "native_photos"
        const val NATIVE_OWNER = "native_owner"
        const val NATIVE_CLIPS = "native_clips"
        const val NATIVE_FEED = "native_feed"
        const val NATIVE_VIDEO = "native_video"
        const val PHOTO_OPENS_PAGE = "photo_opens_page"
        const val COLLECT_DUMP = "collect_dump"
        const val MESSAGE_SYNC_MIN = "message_sync_min"
        const val NOTIFY_MESSAGES = "notify_messages"
        const val NOTIFY_PREVIEW = "notify_preview"
        const val RECENT_CHAT_SHORTCUTS = "recent_chat_shortcuts"
        /** Пункт-переход: системные настройки канала сообщений (звук, вибрация). */
        const val NOTIFY_SYSTEM = "notify_system"
        /** Пункт-переход: диалоги со своей настройкой уведомлений. */
        const val DIALOG_NOTIFY = "dialog_notify"
        const val MESSAGES_REFRESH_SEC = "messages_refresh_sec"
        const val BATTERY_SETTINGS = "battery_settings"
        const val LOG_LEVEL = "log_level"
        const val LOG_SHARE = "log_share"
        const val LOG_SAVE = "log_save"
        const val DUMP_SHARE = "dump_share"
        const val DUMP_EXPORT = "dump_export"
        /** Пункт списка «Пункты бокового меню» (сам не хранит значение). */
        const val MENU_ITEMS = "menu_items"
        /** Выключенные пункты бокового меню (набор имён): всё, чего здесь нет, включено. */
        const val MENU_ITEMS_OFF = "menu_items_off"
        const val BACKGROUND_PLAYBACK = "background_playback"
        const val CACHE_MUSIC = "cache_music"
        const val CACHE_MY_MUSIC = "cache_my_music"
        const val PAUSED_TIMEOUT_MIN = "paused_timeout_min"
        const val VIDEO_QUALITY = "video_quality"
        const val VIDEO_AUTO_ROTATE = "video_auto_rotate"
        const val VIDEO_VOLUME_GESTURE = "video_volume_gesture"
        const val PREFETCH_UNREAD = "messages_prefetch_unread"
        const val SECTION_SWIPE = "section_swipe"
        const val SESSION_REFRESH_MIN = "session_refresh_min"
        const val SESSION_LIFETIME = "session_lifetime_samples"

        // Кнопки-действия (без хранимого значения)
        const val SIGN_OUT = "sign_out"
        const val VERSION = "version"
        /** «Сторонние компоненты» (4.95): уведомление FFmpegKit и полный текст LGPL-3.0. */
        const val THIRD_PARTY = "third_party"
        /** «Открыть папку приложения» (4.99): кэш музыки и картинок, собранное для разработки. */
        const val APP_FOLDER = "app_folder"
        /** Раздел «Для разработки» открыт семью нажатиями на «Версию» (3.94); до того скрыт. */
        const val DEV_MODE = "dev_mode"
        /** Пункт-действие: системные настройки ссылок приложения. */
        const val OPEN_LINKS = "open_links"
        const val DEV_CATEGORY = "dev"
    }
}
