package com.example.vkweb.messages

import android.os.Handler
import com.example.vkweb.diag.AppLog
import com.example.vkweb.diag.LogTag

/**
 * Возврат в свой интерфейс сообщений после входа на странице VK (4.19; с 4.72 — отдельно от MessagesActivity).
 *
 * Отчёт: после входа на мгновение была видна настоящая страница VK — возврат ждал первого списка диалогов от неё, а она
 * тем временем рисовалась на экране. Теперь страница прячется, как только окно ушло со страниц входа на m.vk.com, и экран
 * открывается заново, когда она загрузилась (вход сохранён в куки). Не загрузилась за [COVER_MAX_MS] — страница снова
 * видна.
 */
class LoginReturn(
    private val handler: Handler,
    /** Показана страница VK вместо своего экрана. */
    private val onSite: () -> Boolean,
    /** …и показана она ради входа (а не из-за сбоя своего экрана). */
    private val waitingForLogin: () -> Boolean,
    private val setPageVisible: (Boolean) -> Unit,
    private val showProgress: (Boolean) -> Unit,
    /** Вход выполнен, страница загрузилась: открыть свой экран заново. */
    private val onLoggedIn: () -> Unit,
) {
    /** Страница спрятана: ждём, когда она загрузится после входа. */
    private var covered = false

    private val uncover = Runnable {
        if (!onSite() || !covered) return@Runnable
        covered = false
        setPageVisible(true)
        showProgress(false)
        AppLog.w(LogTag.MESSAGES, "после входа свой интерфейс не открылся вовремя — показана страница VK")
    }

    /** Окно чтения начало загружать [url]. */
    fun onPageStarted(url: String?) {
        if (!onSite() || !waitingForLogin() || covered || !LoginPages.isAfterLogin(url)) return
        covered = true
        setPageVisible(false)
        showProgress(true)
        AppLog.i(LogTag.MESSAGES, "вход выполнен — страница VK скрыта, открывается свой интерфейс")
        handler.postDelayed(uncover, COVER_MAX_MS)
    }

    /** Окно чтения загрузило [url]. */
    fun onPageFinished(url: String?) {
        if (!onSite() || !covered) return
        handler.removeCallbacks(uncover)
        if (LoginPages.isLogin(url)) {
            // VK вернул на вход (второй шаг, ошибка): страница снова видна.
            covered = false
            setPageVisible(true)
            showProgress(false)
            return
        }
        AppLog.i(LogTag.MESSAGES, "вход выполнен — возврат в свой интерфейс сообщений")
        onLoggedIn()
    }

    private companion object {
        const val COVER_MAX_MS = 8_000L
    }
}
