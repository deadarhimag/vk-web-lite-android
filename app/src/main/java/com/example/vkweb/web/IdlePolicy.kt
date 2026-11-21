package com.example.vkweb.web

/**
 * Когда можно усыпить все WebView процесса. Без зависимостей от Android: проверяется обычными тестами.
 *
 * Остановка JS-таймеров ([android.webkit.WebView.pauseTimers]) касается всех окон процесса сразу. Если главное окно
 * усыпит их, пока открыт экран сообщений, его окно чтения замрёт: история переписки перестанет приходить. Поэтому
 * усыплять можно, только когда не виден ни один экран и не играет музыка (её ведёт JS страницы).
 */
object IdlePolicy {

    fun canPauseWeb(
        mainOpen: Boolean,
        messagesOpen: Boolean,
        musicPlaying: Boolean,
        /** Идёт фоновая проверка сообщений (невидимое окно HeadlessSync): её таймеры усыплять нельзя. */
        syncRunning: Boolean = false,
        /** Открыто окно кэша музыки (4.99.7): ищет потоки и в фоне. */
        cacheRunning: Boolean = false,
    ): Boolean = !mainOpen && !messagesOpen && !musicPlaying && !syncRunning && !cacheRunning
}
