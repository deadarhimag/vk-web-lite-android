package com.example.vkweb.sync

/**
 * Какие экраны приложения сейчас видны и играет ли музыка. Нужно, чтобы:
 *  - фоновая проверка сообщений ([MessageSyncService]) не запускала второе скрытое окно поверх открытого приложения;
 *  - WebView не усыплялись ([com.example.vkweb.web.IdlePolicy]), пока они нужны другому экрану: усыпление таймеров
 *    общее для всех WebView процесса.
 */
object AppVisibility {

    @Volatile var messagesOpen: Boolean = false

    @Volatile var mainOpen: Boolean = false

    @Volatile var musicPlaying: Boolean = false

    /** Идёт фоновая проверка сообщений: её окно живёт на тех же общих таймерах WebView. */
    @Volatile var syncRunning: Boolean = false

    /** Открыто окно кэша музыки ([com.example.vkweb.media.MusicCacheWorker]): тоже на общих таймерах WebView. */
    @Volatile var cacheRunning: Boolean = false

    /** Открытая на экране переписка (её отправляет сам экран), null — переписка не открыта (4.17). */
    @Volatile var openChatPeer: String? = null

    /** Открыт хотя бы один экран с содержимым VK. */
    val anyOpen: Boolean get() = messagesOpen || mainOpen
}
