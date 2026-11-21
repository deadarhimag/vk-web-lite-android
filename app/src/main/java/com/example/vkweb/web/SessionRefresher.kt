package com.example.vkweb.web

import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.webkit.WebView
import com.example.vkweb.config.AppConfig
import com.example.vkweb.config.Tab
import com.example.vkweb.diag.AppLog
import com.example.vkweb.diag.LogFormat
import com.example.vkweb.diag.LogTag

/**
 * Фоновое обновление веб-сессии VK. Нужно, чтобы свои экраны работали, не держа открытой страницу:
 * ответы API подписаны токенами и ключами, которые создают скрипты VK и которые со временем протухают.
 * Этот компонент изредка заново загружает страницу под своим экраном — её скрипты обновляют куки и подписи,
 * а свой экран заодно получает свежие данные. Между обновлениями страница не трогается.
 *
 * Обновление запускается:
 *  - по расписанию, но не по фиксированному сроку, а заранее перед истечением сессии: сколько она живёт,
 *    приложение измеряет само ([SessionLifetime]) и обновляет за 80 % этого срока. Только пока приложение
 *    на экране ([start]/[stop]): в фоне страницы не грузятся, батарея не тратится. Настройка задаёт лишь
 *    осторожное начальное значение, пока измерений нет, и может вовсе выключить плановое обновление;
 *  - по требованию, когда своему экрану не пришли свежие данные ([requestRefresh]) — это и есть признак,
 *    что сессия устарела. Чаще, чем [AppConfig.SESSION_REFRESH_MIN_INTERVAL_MS], не обновляет.
 *
 * Грузится та же страница, что открыта под своим экраном ([refreshUrl]), а не другая: раньше это была страница
 * друзей, и после обновления свой экран музыки или страницы человека оставался без своей страницы — догрузка,
 * включение трека и запасной показ страницы VK вели не туда. Пока играет музыка, страница не перезагружается:
 * это оборвало бы воспроизведение.
 *
 * Что считать «свежим» и сколько живёт сессия, точно не известно (зависит от VK), поэтому величины
 * заданы осторожно и вынесены в [AppConfig]/настройки: интервал подбирается на практике, а не зашит жёстко.
 * Загрузку ведёт сам [WebView] владельца (главный экран): отдельный невидимый WebView не создаётся,
 * чтобы не иметь второй сессии и не расходовать память.
 */
class SessionRefresher(
    private val webView: WebView,
    /** Открыта ли сейчас страница VK у пользователя (тогда фоновое обновление не нужно и не мешаем). */
    private val isPageVisibleToUser: () -> Boolean,
    private val isOnline: () -> Boolean,
    /** Играет ли музыка: перезагрузка страницы её бы оборвала. */
    private val isMusicPlaying: () -> Boolean,
    /** Сохранённые измерения срока жизни сессии (между запусками). */
    savedLifetime: List<Long> = emptyList(),
    /** Измерения изменились — сохранить их. */
    private val onLifetimeChanged: (List<Long>) -> Unit = {},
    /** Какую страницу загрузить (открытую под своим экраном); null — открыта не страница VK. */
    private val refreshUrl: () -> String?,
    /** Обновление закончилось: адрес мог смениться (VK перенаправил), свои экраны нужно сверить с ним. */
    private val onFinished: () -> Unit = {},
) {
    /**
     * Начальный срок жизни сессии (настройка «Фоновое обновление»); 0 — плановое обновление выключено, остаётся
     * только обновление по требованию. Дальше срок уточняется измерениями ([lifetime]).
     */
    var intervalMs: Long = AppConfig.SESSION_REFRESH_DEFAULT_INTERVAL_MIN * 60_000L
        set(value) {
            if (field == value) return
            field = value
            lifetime = SessionLifetime.fromState(lifetime.toState(), value)
            if (started) reschedule()
        }

    /** Измеренный срок жизни сессии (переживает перезапуск через [onLifetimeChanged]). */
    private var lifetime = SessionLifetime.fromState(savedLifetime, intervalMs)

    private val handler = Handler(Looper.getMainLooper())
    private var started = false
    private var refreshing = false
    private var lastRefresh = 0L
    /** Что запустило текущее обновление — для журнала. */
    private var trigger = ""
    /** Когда было последнее успешное обновление: по этой разнице видно, как долго держится сессия. */
    private var lastSuccess = 0L
    private val scheduled = Runnable { refresh(scheduledRun = true) }
    private val timeout = Runnable { finish(success = false, stopLoading = true) }

    /** Когда сессию в последний раз успешно обновили (для отсчёта её срока жизни). */
    private var lastSuccessAt = 0L

    /** Начать плановые обновления (приложение на экране). */
    fun start() {
        started = true
        reschedule()
    }

    /**
     * Остановить (приложение ушло в фон): лишних загрузок в фоне не делаем. Начатая загрузка не обрывается —
     * это обычная загрузка страницы под своим экраном, а оборванная оставила бы его без данных.
     */
    fun stop() {
        started = false
        handler.removeCallbacks(scheduled)
        if (refreshing) {
            AppLog.d(LogTag.SESSION, "приложение ушло в фон во время обновления")
            finish(success = false, stopLoading = false)
        }
    }

    /**
     * Свой экран не дождался свежих данных — вероятно, устарела сессия. Обновить, если не слишком часто.
     * Возврат true — обновление запущено (экрану стоит подождать и запросить данные снова).
     */
    fun requestRefresh(): Boolean {
        val reason = blockedReason()
        if (reason != null) {
            AppLog.i(LogTag.SESSION, "обновление по требованию не запущено", "причина" to reason)
            return false
        }
        refresh(scheduledRun = false)
        return refreshing
    }

    /** Почему сейчас нельзя обновлять (null — можно). Нужно и для логики, и для журнала. */
    private fun blockedReason(): String? = when {
        refreshing -> "уже идёт"
        // Приложение в фоне: перезагрузка страницы по таймеру своего экрана тратила бы сеть и батарею (код ревью 3.59).
        !started -> "приложение в фоне"
        !isOnline() -> "нет сети"
        isMusicPlaying() -> "играет музыка"
        isPageVisibleToUser() -> "открыта страница VK"
        lastRefresh != 0L && SystemClock.elapsedRealtime() - lastRefresh < AppConfig.SESSION_REFRESH_MIN_INTERVAL_MS -> "слишком часто"
        else -> null
    }

    private fun refresh(scheduledRun: Boolean) {
        if (scheduledRun) {
            if (!started) return
            val reason = blockedReason()
            if (reason != null) {
                AppLog.d(LogTag.SESSION, "плановое обновление отложено", "причина" to reason)
                reschedule()
                return
            }
        }
        val url = refreshUrl() ?: Tab.FRIENDS.url
        trigger = if (scheduledRun) "по расписанию" else "по требованию"
        refreshing = true
        lastRefresh = SystemClock.elapsedRealtime()
        AppLog.i(
            LogTag.SESSION, "обновление запущено", "причина" to trigger, "адрес" to LogFormat.url(url),
            "после прошлого успеха, мин" to minutesSince(lastSuccess),
        )
        handler.removeCallbacks(scheduled)
        webView.loadUrl(url)
        handler.removeCallbacks(timeout)
        handler.postDelayed(timeout, AppConfig.SESSION_REFRESH_TIMEOUT_MS)
    }

    /** Страница фонового обновления догрузилась настолько, что сессия обновлена. */
    fun onRefreshLoaded(url: String?) {
        if (!refreshing) return
        // Ждём именно адрес обновления, а не промежуточные переходы (например, редирект на вход).
        if (url != null && !url.contains("/login") && !url.contains("id.vk")) {
            // Загрузку не останавливаем: это страница своего экрана, её данные ещё догружаются.
            finish(success = true, stopLoading = false)
        } else {
            // Редирект на вход — признак, что сессия VK закончилась совсем, а не просто устарели подписи.
            AppLog.w(LogTag.SESSION, "обновление привело на страницу входа", "адрес" to LogFormat.url(url))
        }
    }

    private fun finish(success: Boolean, stopLoading: Boolean) {
        if (!refreshing) return
        val took = SystemClock.elapsedRealtime() - lastRefresh
        if (success) {
            lastSuccess = SystemClock.elapsedRealtime()
            lastSuccessAt = lastSuccess
            AppLog.i(
                LogTag.SESSION, "обновление завершено",
                "причина" to trigger, "мс" to took, "срок жизни сессии, мин" to lifetime.lifetimeMs() / 60_000,
            )
        } else {
            AppLog.w(LogTag.SESSION, "обновление не завершилось", "причина" to trigger, "мс" to took)
        }
        refreshing = false
        handler.removeCallbacks(timeout)
        if (stopLoading) webView.stopLoading()
        if (started) reschedule()
        onFinished()
    }

    private fun minutesSince(at: Long): Long? =
        if (at == 0L) null else (SystemClock.elapsedRealtime() - at) / 60_000

    private fun reschedule() {
        handler.removeCallbacks(scheduled)
        if (!started || intervalMs <= 0) return
        // Обновляем заранее перед истечением измеренного срока сессии, а не раз в фиксированный интервал.
        val delay = if (lastSuccessAt == 0L) lifetime.lifetimeMs() * SessionLifetime.SAFETY_FRACTION / 100
        else lifetime.delayUntilDueMs(lastSuccessAt, SystemClock.elapsedRealtime())
        handler.postDelayed(scheduled, delay.coerceAtLeast(AppConfig.SESSION_REFRESH_MIN_INTERVAL_MS))
    }

    /**
     * Появился признак, что сессия протухла (ответ 401/403 на запрос данных или свой экран не дождался данных):
     * время, которое она прожила с прошлого успеха, — измерение её срока жизни. По нему уточняется, когда обновлять.
     */
    fun onSessionExpiredSignal() {
        if (lastSuccessAt == 0L) return
        val before = lifetime.lifetimeMs()
        lifetime.observeExpiry(lastSuccessAt, SystemClock.elapsedRealtime())
        // Одно измерение на цикл: страница при протухшей сессии отвечает 401 на десяток запросов подряд, и без этого
        // вся история из пяти наблюдений заполнилась бы одним событием.
        lastSuccessAt = 0L
        val after = lifetime.lifetimeMs()
        if (after != before) {
            AppLog.i(LogTag.SESSION, "уточнён срок жизни сессии", "было, мин" to before / 60_000, "стало, мин" to after / 60_000)
            onLifetimeChanged(lifetime.toState())
            if (started) reschedule()
        }
    }

    val isRefreshing: Boolean get() = refreshing
}
