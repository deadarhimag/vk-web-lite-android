package com.example.vkweb.overlay

import android.net.Uri
import android.os.SystemClock
import androidx.annotation.StringRes
import com.example.vkweb.R
import com.example.vkweb.config.AppConfig
import com.example.vkweb.config.Tab
import com.example.vkweb.diag.AppLog
import com.example.vkweb.diag.LogTag
import com.example.vkweb.ui.EmptyState
import com.example.vkweb.ui.OverlayPanel

/**
 * Свой экран поверх страницы VK (музыка, друзья и сообщества, страницы людей и сообществ).
 * Страница остаётся под экраном и присылает ему данные; здесь — общее поведение всех таких экранов:
 *  - показывать ли экран (открыта его страница, включён в настройках, пользователь не выбрал страницу VK);
 *  - при открытии — сохранённое сразу, свежее от страницы встаёт поверх без скачков (это делает панель);
 *  - свежее не пришло за [AppConfig.NATIVE_SCREEN_TIMEOUT_MS]: показать нечего — выбор «Подождать ещё» / «Страница VK»
 *    (4.51; не выбрали за [CHOICE_TIMEOUT_MS] — страница VK, как раньше);
 *    показано сохранённое — плашка «обновить не удалось» (сохранённое лучше пустой страницы);
 *  - кнопка «Страница VK / Свой экран» и «Назад» со страницы VK обратно к своему экрану;
 *  - без сети — сохранённое с плашкой, без экрана ошибки.
 * Наследник знает свою страницу и как попросить данные; показ — у [panel].
 */
abstract class PageOverlay(
    protected val host: OverlayHost,
    @StringRes private val fallbackMessage: Int,
) {
    /** На этой странице пользователь выбрал страницу VK (или данные не пришли вовремя). */
    private var showsPage = false

    /** Свежее не пришло вовремя, показано сохранённое. */
    private var stale = false
    @StringRes private var updateError: Int? = null

    /** Уже пробовали обновить сессию для этой страницы: второй раз не зацикливаемся. */
    private var triedRefresh = false

    /** Когда экран открылся и ждёт свежего: по разнице видно, как быстро приходят данные (0 — уже пришли). */
    private var waitingSince = 0L

    private val fallback = Runnable { onFreshTimeout() }

    /** Выбор «подождать / страница VK» показан, а человек ничего не нажал: страница VK (4.51). */
    private val autoPage = Runnable { openPageInstead(auto = true) }

    protected abstract val panel: OverlayPanel

    /** Открыта страница этого экрана. */
    protected abstract val isOnPage: Boolean

    /** Экран включён в настройках. */
    protected abstract val isEnabled: Boolean

    /** Текст плашки без сети. */
    @StringRes protected open val offlineBanner: Int = R.string.offline_banner

    /** Название экрана в журнале событий. */
    protected open val logName: String get() = javaClass.simpleName.removeSuffix("Overlay").lowercase()

    /** Запись журнала от имени этого экрана. */
    protected fun logInfo(message: String, vararg fields: Pair<String, Any?>) =
        AppLog.i(LogTag.SCREEN, "$logName: $message", *fields)

    protected fun logDebug(message: String, vararg fields: Pair<String, Any?>) =
        AppLog.d(LogTag.SCREEN, "$logName: $message", *fields)

    protected fun logWarn(message: String, vararg fields: Pair<String, Any?>) =
        AppLog.w(LogTag.SCREEN, "$logName: $message", *fields)

    /** Заголовок для панели, если экран знает его лучше страницы (имя человека). */
    open val title: String? get() = null

    /** Показать панель для текущей страницы. @return true — открылась заново: нужны данные. */
    protected abstract fun showPanel(): Boolean

    /** Попросить страницу прислать данные заново. */
    protected abstract fun requestFresh()

    protected open fun requestUpdate() = requestFresh()

    protected open fun onUpdateTimeout(): Boolean = false

    /** Показать сохранённое, пока свежее не пришло. */
    protected abstract fun loadSaved()

    /** Сменилась страница в WebView. */
    abstract fun onUrlChanged(uri: Uri, isVideoSite: Boolean)

    /** Выбор раздела в меню: экран можно показать сразу, не дожидаясь загрузки страницы. */
    open fun onSectionSelected(tab: Tab) = Unit

    val isShown: Boolean get() = panel.isShown

    /** У этой страницы есть свой экран (кнопка «Страница VK / Свой экран» видна). */
    val isAvailable: Boolean get() = isOnPage && isEnabled

    /** Показывает экран или страницу — по странице, настройке и выбору пользователя. */
    fun update() {
        // «Обновить» / «Повторить» на пустом экране (4.51).
        panel.frame.onRetry = ::retry
        if (isAvailable && !showsPage) {
            if (!host.isOnline) host.hideLoadError()
            if (showPanel()) {
                waitingSince = SystemClock.elapsedRealtime()
                logInfo("открыт", "сеть" to host.isOnline, "данные уже есть" to panel.hasData)
                requestFresh()
                if (!panel.hasData) loadSaved()
                armFallback()
            }
            panel.setBanner(currentBanner())
        } else {
            host.cancel(fallback)
            host.cancel(autoPage)
            panel.frame.pullDone()
            panel.hide()
        }
        host.onOverlayChanged()
    }

    /**
     * Кнопка на пустом экране или «Подождать ещё»: попросить свежее и ждать заново. @return false — без сети запрос не
     * начат (пустой экран остаётся, плашка «нет сети» уже на нём).
     */
    private fun retry(): Boolean {
        host.cancel(autoPage)
        if (!host.isOnline) {
            host.showToast(if (panel.frame.isPulling) R.string.pull_offline else R.string.empty_still_offline)
            return false
        }
        logInfo("свежее запрошено с пустого экрана")
        waitingSince = SystemClock.elapsedRealtime()
        stale = false
        updateError = null
        armFallback()
        panel.setBanner(currentBanner())
        requestUpdate()
        return true
    }

    /** Вместо своего экрана — страница VK: по кнопке на выборе или сама, если выбора не сделали. */
    private fun openPageInstead(auto: Boolean) {
        host.cancel(autoPage)
        if (!isShown || panel.hasData) return
        logWarn(if (auto) "выбор не сделан, открыта страница VK" else "выбрана страница VK вместо пустого экрана")
        showsPage = true
        update()
        if (auto) host.showToast(fallbackMessage)
    }

    /** Кнопка на панели: свой экран ↔ страница VK. */
    fun togglePage() {
        logInfo("переключение", "на страницу VK" to isShown)
        showsPage = isShown
        update()
    }

    /** «Назад» на странице VK, выбранной вместо своего экрана: вернуться к экрану. */
    open fun handleBack(): Boolean {
        if (!isAvailable || !showsPage) return false
        logInfo("возврат к своему экрану кнопкой «Назад»")
        showsPage = false
        update()
        return true
    }

    /** Сеть пропала: ждать данные от страницы бессмысленно. */
    fun onOffline() {
        panel.frame.pullDone()
        if (!isShown) return
        logInfo("сеть пропала, показано сохранённое", "данные есть" to panel.hasData)
        host.cancel(fallback)
        panel.setBanner(offlineBanner)
    }

    /**
     * Сеть появилась. @return true — экран показывает сохранённое (или пуст): страницу под ним нужно
     * перезагрузить, чтобы пришло свежее. Если свежее уже на экране, перезагружать незачем.
     */
    fun onOnline(): Boolean {
        if (!isShown) return false
        val needsFresh = !panel.hasData || panel.isShowingSaved
        logInfo("сеть появилась", "нужно свежее" to needsFresh)
        if (needsFresh) {
            waitingSince = SystemClock.elapsedRealtime()
            armFallback()
        }
        panel.setBanner(currentBanner())
        return needsFresh
    }

    /** Страница не загрузилась. @return true — экран показывает сохранённое, экран ошибки не нужен. */
    fun onMainFrameError(): Boolean {
        if (!isShown || !panel.hasData) return false
        panel.setBanner(currentBanner())
        return true
    }

    /** Сохранённое удалили в настройках: забыть показанное и попросить у страницы свежее. */
    fun onStorageCleared() {
        logInfo("сохранённое удалено, экран очищен", "открыт" to isShown)
        stale = false
        panel.clear()
        if (!isShown) return
        requestFresh()
        armFallback()
        panel.setBanner(currentBanner())
    }

    /** Новая страница: прежний выбор «страница VK» и «не удалось обновить» к ней не относятся. */
    protected fun onPageChanged() {
        showsPage = false
        stale = false
        updateError = null
        triedRefresh = false
    }

    /** Свежие данные пришли: ждать больше нечего, плашки не нужны. */
    protected fun onFreshData(snapshot: Boolean = false) {
        if (waitingSince != 0L) {
            logInfo(
                if (snapshot) "получен снимок страницы" else "свежие данные получены",
                "мс" to SystemClock.elapsedRealtime() - waitingSince,
                "после обновления сессии" to triedRefresh.takeIf { it },
            )
            waitingSince = 0L
        }
        host.cancel(fallback)
        host.cancel(autoPage)
        panel.frame.pullDone()
        stale = false
        updateError = null
        triedRefresh = false
        panel.setBanner(currentBanner())
    }

    protected fun onUpdateFailed(@StringRes message: Int) {
        host.cancel(fallback)
        host.cancel(autoPage)
        waitingSince = 0L
        panel.frame.pullDone()
        updateError = message
        if (!panel.hasData) {
            panel.frame.showEmpty(
                EmptyState.Content(
                    message, R.drawable.ic_cloud_off, R.string.empty_timeout_body,
                    action = panel.frame.retryAction(R.string.empty_wait_more),
                ),
            )
        }
        panel.setBanner(currentBanner())
    }

    private fun currentBanner(): Int? = when {
        !host.isOnline -> offlineBanner
        updateError != null -> updateError
        stale -> R.string.stale_banner
        else -> null
    }

    private fun armFallback() {
        host.cancel(fallback)
        if (host.isOnline) host.postDelayed(fallback, AppConfig.NATIVE_SCREEN_TIMEOUT_MS)
    }

    private fun onFreshTimeout() {
        if (onUpdateTimeout()) return
        // Потягивание (4.52): индикатор крутится не дольше одного ожидания; дальше — плашка или выбор, как всегда.
        if (panel.frame.isPulling) {
            panel.frame.pullDone()
            host.showToast(R.string.pull_failed)
        }
        if (!isShown || !host.isOnline) return
        // Данные не пришли — возможно, устарела сессия. Один раз пробуем тихо обновить её в фоне
        // и ждём данные ещё раз; на сайт или плашку уходим, только если и это не помогло.
        logWarn(
            "свежие данные не пришли вовремя", "мс" to AppConfig.NATIVE_SCREEN_TIMEOUT_MS,
            "данные есть" to panel.hasData, "сохранённое" to panel.isShowingSaved, "сессию уже обновляли" to triedRefresh,
        )
        if (!triedRefresh && host.refreshSession()) {
            triedRefresh = true
            logInfo("запрошено обновление сессии, ждём данные ещё раз")
            requestFresh()
            armFallback()
            return
        }
        when {
            !panel.hasData -> {
                // 4.51: не молча на страницу VK — выбор; не выбрали за CHOICE_TIMEOUT_MS — страница, как раньше.
                logWarn("показать нечего — предложен выбор: подождать или страница VK")
                panel.frame.showEmpty(
                    EmptyState.Content(
                        R.string.empty_timeout_title, R.drawable.ic_cloud_off, R.string.empty_timeout_body,
                        // Как «Обновить»: ждём заново (с индикатором); без сети — выбор остаётся.
                        action = panel.frame.retryAction(R.string.empty_wait_more),
                        secondary = EmptyState.Action(R.string.empty_open_page) { openPageInstead(auto = false) },
                    ),
                )
                host.cancel(autoPage)
                host.postDelayed(autoPage, CHOICE_TIMEOUT_MS)
            }
            panel.isShowingSaved -> {
                logWarn("оставлено сохранённое с пометкой «обновить не удалось»")
                stale = true
                panel.setBanner(currentBanner())
            }
        }
    }

    private companion object {
        /** Сколько ждать выбора «подождать / страница VK», прежде чем открыть страницу самим (4.51). */
        const val CHOICE_TIMEOUT_MS = 10_000L
    }
}
