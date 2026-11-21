package com.example.vkweb.clips

import android.app.Activity
import com.example.vkweb.diag.AppLog
import com.example.vkweb.diag.LogTag
import com.example.vkweb.web.BackPolicy
import com.example.vkweb.web.PageBridge

/**
 * Связь главного окна со своим проигрывателем клипов (3.76; с 4.7 — отдельно от MainActivity): лента от страницы —
 * в [ClipsHub], открытие [ClipsActivity] на странице клипа, «ещё», «нравится» и закрытие проигрывателя.
 */
class ClipsLauncher(
    private val activity: Activity,
    private val bridge: PageBridge,
    private val host: Host,
) {
    interface Host {
        /** Настройка «Свой проигрыватель клипов». */
        val nativeClips: Boolean
        /** Свой список раздела «Клипы» есть на этой странице (вошли через /clips). */
        val sectionListAvailable: Boolean
        /** Свой список раздела «Клипы» показан. */
        val sectionListShown: Boolean
        /** На страницу клипа пришли по воле человека или приложения, а не страница сама (4.38, [com.example.vkweb.web.PageIntent]). */
        val entryWanted: Boolean
        fun currentPath(): String?
        /** Уйти из раздела клипов к самой странице (страница клипа с комментариями). */
        fun leaveSection()
        /** Проигрыватель закрыт «Назад» или крестиком: шаг назад главного окна. */
        fun back()
    }

    /** Проигрыватель открыт (или закрыт человеком на этой странице — снова не открывается, пока не уйдёт с неё). */
    private var shown = false

    // Обработчики общей ленты — постоянные объекты: при закрытии окна снимаются, только если всё ещё его (код ревью
    // 3.81: ссылка на закрытое окно держала его в памяти, а закрытие проигрывателя обращалось к уничтоженной странице).
    private val more: () -> Unit = { bridge.clips("more") }
    private val like: (String, Boolean) -> Unit = { id, on -> bridge.clipsLike(id, on) }
    private val closed: (Boolean) -> Unit = { toPage -> onClosed(toPage) }

    /** Подписаться на ленту и ответы страницы; занять общие обработчики [ClipsHub]. */
    fun install() {
        bridge.on(ClipsPayload.TYPE, ClipsPayload::fromJson, ::onFeed)
        ClipsHub.requestMore = more
        ClipsHub.onClosed = closed
        ClipsHub.requestLike = like
        bridge.on(ClipLike.TYPE, ClipLike::fromJson) { result ->
            if (!result.ok) AppLog.w(LogTag.PAGE, "клипы: «нравится» не принято", "причина" to result.reason)
            ClipsHub.onLike(result)
        }
    }

    /** Окно закрывается: отдать обработчики, если их не заняло другое окно. */
    fun release() {
        if (ClipsHub.requestMore === more) ClipsHub.requestMore = null
        if (ClipsHub.onClosed === closed) ClipsHub.onClosed = null
        if (ClipsHub.requestLike === like) ClipsHub.requestLike = null
    }

    /** Открыта страница не проигрывателя: на следующей странице клипа проигрыватель снова откроется сам. */
    fun onLeftPlayer() {
        shown = false
        skipLogged = false
    }

    private var skipLogged = false

    private fun onFeed(payload: ClipsPayload) {
        ClipsHub.update(payload)
        val path = host.currentPath()
        AppLog.d(LogTag.PAGE, "клипы: лента от страницы", "роликов" to payload.items.size, "сначала" to payload.reset, "есть ещё" to payload.hasMore)
        if (!host.nativeClips || shown || !payload.reset || payload.items.isEmpty()) return
        // Раздел клипов (вошли через /clips): общий список своим экраном, проигрыватель — по нажатию ролика (3.84).
        if (host.sectionListAvailable) return
        if (!BackPolicy.isPlayerPage(path) || path?.startsWith("/video") == true) return
        // Страница сама перелистнула на клип (её проигрыватель без файлов уходит дальше) — не открывать свой поверх
        // всего: отчёт 4.33, «самопроизвольные запуски клипов» (4.38).
        if (!host.entryWanted) {
            if (!skipLogged) AppLog.i(LogTag.PAGE, "клипы: страница сама открыла клип — свой проигрыватель не открывается")
            skipLogged = true
            return
        }
        shown = true
        bridge.clips("silence")
        val start = ClipPaths.clipId(path)
        ClipsActivity.open(activity, start)
    }

    /**
     * Проигрыватель закрыт: страница снова звучит. «Назад» или крестик — сами клипы закрываются, как «Назад»;
     * переход к странице клипа ([toPage]) — её открывает сам проигрыватель (раньше она так и оставалась без звука).
     */
    private fun onClosed(toPage: Boolean) {
        AppLog.i(LogTag.PAGE, "клипы: свой проигрыватель закрыт", "к странице клипа" to toPage, "к списку клипов" to host.sectionListShown)
        if (toPage) {
            // Страница клипа (комментарии) — сама страница, а не список раздела.
            // 4.45: человек сам ушёл к странице — проигрыватель поверх неё снова не открывается (отчёт 4.44: открылся
            // через 2 с после загрузки страницы, до комментариев было не добраться). Сбросится уходом со страниц клипов.
            shown = true
            host.leaveSection()
            bridge.clips("release")
            return
        }
        // Открыт из списка раздела — к нему же; страница под списком молчит дальше.
        if (host.sectionListShown) return
        bridge.clips("release")
        host.back()
    }
}
