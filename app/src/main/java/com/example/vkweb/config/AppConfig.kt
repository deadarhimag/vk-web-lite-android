package com.example.vkweb.config

/**
 * Все настройки приложения в одном месте.
 * Файл не зависит от Android, поэтому его логику можно проверять обычными unit-тестами.
 */
object AppConfig {

    /** Домены VK: навигация по ним остаётся внутри приложения (включая поддомены). */
    val VK_DOMAINS: Set<String> = setOf(
        "vk.com", "vk.ru", "vk.me", "vkuser.net", "userapi.com", "vk-cdn.net",
        // Раздел «Видео» VK открывается на отдельном домене (m.vk.com/video → m.vkvideo.ru).
        "vkvideo.ru",
        // Картинки и аватары.
        "vkuserphoto.ru",
    )

    /**
     * Сети доставки картинок вне доменов VK, откуда VK отдаёт обложки видео и клипов (инфраструктура VK Видео общая с ОК).
     * Только для загрузки картинок в свои экраны ([com.example.vkweb.offline.ImageCache]); навигации и скриптов не касается.
     */
    val IMAGE_CDN_DOMAINS: Set<String> = setOf("mycdn.me", "okcdn.ru")

    /** Сеть доставки видео VK (файлы роликов). */
    val VIDEO_CDN_DOMAINS: Set<String> = setOf("vkuservideo.net")

    /**
     * Хост файла видео: как у картинок и ещё сеть доставки видео VK ([VIDEO_CDN_DOMAINS]).
     *
     * Те же списки (VK, картинки, видео, звук) есть в скриптах страниц — assets/shared/net_tap.js (HOSTS): меняете здесь —
     * меняйте и там; разницу ловит HostListsTest (4.65).
     */
    fun isVideoHost(host: String?): Boolean {
        val h = host?.lowercase()?.trimEnd('.') ?: return false
        return isImageHost(h) || VIDEO_CDN_DOMAINS.any { h == it || h.endsWith(".$it") }
    }

    /**
     * Серверы звука VK: поток трека из ответа API, который играет само приложение. 4.88: VK отдаёт звук и с okcdn.ru
     * (audio_streams: mp3, aac, HLS) — так играет и сама страница.
     */
    val AUDIO_DOMAINS: Set<String> = setOf("vkuseraudio.ru", "vkuseraudio.net", "vkuseraudio.com", "okcdn.ru")

    /** Хост, с которого можно проигрывать звук трека: серверы звука VK и сами домены VK (с поддоменами). */
    fun isAudioHost(host: String?): Boolean {
        val h = host?.lowercase()?.trimEnd('.') ?: return false
        return (VK_DOMAINS + AUDIO_DOMAINS).any { h == it || h.endsWith(".$it") }
    }

    /** Хост, с которого можно загружать картинки: домены VK и сети доставки картинок VK Видео (с поддоменами). */
    fun isImageHost(host: String?): Boolean {
        val h = host?.lowercase()?.trimEnd('.') ?: return false
        return (VK_DOMAINS + IMAGE_CDN_DOMAINS).any { h == it || h.endsWith(".$it") }
    }

    /** Сторонние домены входа (VK ID умеет входить через ОК и Mail). */
    val AUTH_DOMAINS: Set<String> = setOf("ok.ru", "mail.ru")

    /** Адрес сайта, на котором работает приложение (VK сам приводит vk.com ↔ vk.ru). */
    const val SITE_ORIGIN = "https://m.vk.com"

    /** Хосты основного сайта, для которых действуют правила [HiddenSection] и [CLIP_SECTIONS]. Точное совпадение. */
    val MAIN_SITE_HOSTS: Set<String> = setOf(
        "vk.com", "m.vk.com", "www.vk.com", "vk.ru", "m.vk.ru", "www.vk.ru"
    )

    /**
     * Хосты VK Видео. Здесь своя главная страница («/» — это каталог видео, а не лента),
     * поэтому из правил скрытия действуют только клипы.
     */
    val VIDEO_SITE_HOSTS: Set<String> = setOf("vkvideo.ru", "m.vkvideo.ru", "www.vkvideo.ru")

    /** Кэш картинок для работы без сети: варианты размера (МБ) и значение по умолчанию. */
    val IMAGE_CACHE_OPTIONS_MB: List<Int> = listOf(250, 500, 1000, 2000, 5000)
    const val IMAGE_CACHE_DEFAULT_MB = 1000L

    /**
     * Раздел клипов: скрывается, если включено «Скрывать клипы» (по умолчанию). Путь без query.
     * Отдельный клип (/clip-1_2), присланный в сообщении, стоящий в ленте или на стене группы, открывается всегда.
     */
    val CLIP_SECTIONS: List<Regex> = listOf(
        Regex("^/(clips|clip)(/.*)?$"),
        Regex("^/clips-?\\d+(_\\d+)?(/.*)?$"),   // /clips-123 (клипы автора)
    )

    /**
     * Рекламные сети: запросы отбрасываются, если включено «Скрывать рекламу» (по умолчанию).
     * Совпадение по домену и всем поддоменам.
     */
    val AD_DOMAINS: Set<String> = setOf(
        "ad.mail.ru", "mradx.net", "target.my.com",        // myTarget — рекламная сеть VK (mradx.net: r, r0 … — все хосты)
        "ads.vk.com", "ad.vk.com",
        "adfox.ru", "adriver.ru", "an.yandex.ru", "yandexadexchange.net",
        "doubleclick.net", "googlesyndication.com", "googleadservices.com", "adservice.google.com",
    )

    /** Счётчики и аналитика: запросы отбрасываются, если включено «Блокировать трекеры» (по умолчанию). */
    val TRACKER_DOMAINS: Set<String> = setOf(
        "top-fwz1.mail.ru", "top.mail.ru", "rs.mail.ru",   // Top@Mail.ru
        "stats.vk-portal.net",                             // статистика VK
        "mc.yandex.ru",                                    // Яндекс Метрика
        "counter.yadro.ru", "tns-counter.ru", "mediascope.net",
        "google-analytics.com", "googletagmanager.com",
        "connect.facebook.net", "scorecardresearch.com",
        "apptracer.ru",                                    // отчёты VK о работе приложения и устройстве
        "tracker-api.my.com",                              // myTracker
        // 4.19 (отчёт 4.12): проверки сайта VK Видео — стоит ли на телефоне сертификат Минцифры и как идёт соединение.
        // Ничего не показывают и не нужны для видео.
        "mincifry-cert.vkvideo.ru", "r3-test.vkvideo.ru",
    )

    /** Пиксели на доменах самого VK (путь без query). Проверяются только для доменов VK. */
    val TRACKER_PATHS_ON_VK: List<Regex> = listOf(
        Regex("^/rtrg.*$"),                                // пиксель ретаргетинга VK
        Regex("^/method/statEvents\\..*$"),                  // события использования (statEvents.addWeb…)
        Regex("^/method/stats\\.track.*$"),                  // статистика посетителя (stats.trackEvents…)
        Regex("^/method/video\\.trackPlayerEvents$"),        // 4.19: события проигрывателя VK Видео (что, сколько, где остановили)
    )

    /**
     * Рекламные методы API на доменах VK (4.19, отчёт 4.12): промо и баннеры, которые страница запрашивает сама —
     * «Видео теперь здесь», полноэкранный баннер музыки. Отбрасываются, если включено «Скрывать рекламу».
     */
    val AD_PATHS_ON_VK: List<Regex> = listOf(
        Regex("^/method/video\\.getMvkPromo$"),
        Regex("^/method/audio\\.getFullscreenBanner$"),
    )

    /** Кнопки, которые скрипт нажимает сам, чтобы закрыть окна-заглушки VK и чтобы VK запомнил отказ (точный текст). */
    val PROMO_CLICK_TEXTS: List<String> = listOf("Остаться в браузере", "Продолжить в браузере")

    /**
     * Фразы, по которым блок скрывается в момент появления, ещё до отрисовки: «откройте приложение»,
     * подсказки-онбординг, баннеры партнёров. Применяется при включённом «Скрывать рекламу».
     * Ищется по тексту, а не по классам. Тексты чатов, постов и комментариев не затрагиваются.
     */
    val PROMO_HIDE_TEXTS: List<String> = listOf(
        "В приложении — ещё лучше",
        "Остаться в браузере",
        "Продолжить в браузере",
        "Открыть в приложении",
        "Скачайте приложение",
        "Установите приложение",
        "Установить ВКонтакте",
        "Видео теперь здесь",
        "Яндекс Браузер — чтобы точно быть на связи",
    )

    /** Где лежит пользовательский текст: подсказки и окна внутри него не ищем. */
    const val PROMO_IGNORE_SELECTOR: String =
        "[class*=\"ConvoHistory\"],[class*=\"MessageText\"],[class*=\"ConvoLastMessage\"]," +
            "[class*=\"wall_post\"],[class*=\"PostText\"],[class*=\"Comment\"],textarea,[contenteditable]"

    /** Схемы, которые разрешено передавать другим приложениям. */
    val EXTERNAL_SCHEMES: Set<String> = setOf("https", "http", "mailto", "tel", "intent", "market")

    /** Домены, в страницы которых встраивается скрипт и где доступен JS-мост. */
    val SCRIPT_DOMAINS: Set<String> = setOf("vk.com", "vk.ru", "vkvideo.ru")

    /** Правила origin для WebView (формат androidx.webkit). */
    val SCRIPT_ORIGIN_RULES: Set<String> =
        SCRIPT_DOMAINS.flatMap { listOf("https://$it", "https://*.$it") }.toSet()

    /** Имя JS-объекта, через который страница отправляет сообщения приложению. */
    const val JS_BRIDGE_NAME = "vkLiteBridge"

    /**
     * Кнопки плеера на сайте: запасной путь, если страница не регистрирует Media Session.
     * Селекторы — предположение, проверяйте через chrome://inspect.
     */
    const val NEXT_TRACK_SELECTOR =
        "[aria-label*=\"Следующ\"],[aria-label*=\"Next\"],[data-testid*=\"next\"],.audio_page_player_next"
    const val PREV_TRACK_SELECTOR =
        "[aria-label*=\"Предыдущ\"],[aria-label*=\"Previous\"],[data-testid*=\"prev\"],.audio_page_player_prev"

    /** Защита от зацикливания перенаправлений на домашнюю страницу. */
    const val HOME_REDIRECT_COOLDOWN_MS = 3_000L

    /** Варианты «убирать плеер из уведомлений после паузы», минуты. */
    val PAUSED_TIMEOUT_OPTIONS_MIN: List<Int> = listOf(1, 5, 15, 30)

    /** Интервалы планового фонового обновления сессии (минуты); 0 — выключено (только по требованию). */
    val SESSION_REFRESH_OPTIONS_MIN: List<Int> = listOf(0, 10, 20, 40, 60)

    /** Как часто проверять новые сообщения в фоне (минуты); 0 — выключено. Система не позволяет чаще 15 минут. */
    val MESSAGE_SYNC_OPTIONS_MIN: List<Int> = listOf(0, 15, 30, 60)
    const val MESSAGE_SYNC_DEFAULT_MIN = 30

    /** Как часто обновлять открытый экран сообщений (секунды); 0 — выключено. */
    val MESSAGES_REFRESH_OPTIONS_SEC: List<Int> = listOf(0, 30, 60, 120)
    const val MESSAGES_REFRESH_DEFAULT_SEC = 60
    const val DEFAULT_PAUSED_TIMEOUT_MIN = 5

    /** Размер текста страниц, % (совпадает с preferences.xml). */
    const val MIN_TEXT_ZOOM = 80
    const val MAX_TEXT_ZOOM = 150
    const val DEFAULT_TEXT_ZOOM = 100
    /** Шаг размера текста (4.23): ползунок в настройках встаёт на 80, 90 … 150, а не на 117. */
    const val TEXT_ZOOM_STEP = 10

    /** Размер текста страниц из любого значения: в пределах [MIN_TEXT_ZOOM]…[MAX_TEXT_ZOOM], к ближайшему шагу. */
    fun textZoom(value: Int): Int =
        com.example.vkweb.ui.SliderSteps.snap(value, MIN_TEXT_ZOOM, MAX_TEXT_ZOOM, TEXT_ZOOM_STEP)

    /** Через сколько после ухода в фон (без музыки) усыплять WebView ради батареи. */
    const val BACKGROUND_PAUSE_DELAY_MS = 30_000L

    /**
     * Свои экраны (музыка, друзья, сообщества, страницы людей): сколько ждать данные от страницы,
     * прежде чем показать саму страницу VK.
     */
    const val NATIVE_SCREEN_TIMEOUT_MS = 12_000L

    /** Свой интерфейс сообщений: мессенджер VK запрашивает данные уже после загрузки страницы — ждём с запасом. */
    const val MESSAGES_READ_TIMEOUT_MS = 10_000L

    /**
     * Фоновое обновление сессии (web/SessionRefresher.kt): редко открывает страницу VK в невидимом
     * WebView, чтобы её скрипты обновили куки и подписи, затем останавливает загрузку. Всё остальное
     * время приложение работает в своих экранах. Величины ниже — осторожные начальные; их можно менять.
     */
    /** Не обновлять чаще этого даже при ошибках своих экранов: иначе при сбоях пошёл бы поток перезагрузок. */
    const val SESSION_REFRESH_MIN_INTERVAL_MS = 60_000L

    /** Плановое обновление: по умолчанию раз в этот срок (настройка «Фоновое обновление»). */
    const val SESSION_REFRESH_DEFAULT_INTERVAL_MIN = 20

    /** Фоновое обновление считается неудачным, если страница не загрузилась за это время. */
    const val SESSION_REFRESH_TIMEOUT_MS = 30_000L

    /** Сколько ждать следующую порцию списка, прежде чем снова разрешить догрузку при прокрутке. */
    const val LOAD_MORE_TIMEOUT_MS = 8_000L

    /** Ограничение длины названия и исполнителя, пришедших со страницы. */
    const val MAX_METADATA_LENGTH = 200
}
