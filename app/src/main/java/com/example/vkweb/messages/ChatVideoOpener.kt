package com.example.vkweb.messages

import android.app.Activity
import android.os.Handler
import com.example.vkweb.diag.AppLog
import com.example.vkweb.diag.LogTag
import com.example.vkweb.media.ChatMusic
import com.example.vkweb.ui.VideoPlayerActivity

/**
 * Видео и клипы из переписки (с 4.71 — отдельно от MessagesActivity): играют здесь же, в своём проигрывателе — страница
 * отдаёт их файлы (video.get), без перехода в главное окно. Нет связи со страницей или файлы не пришли за
 * [SOURCES_TIMEOUT_MS] — открывается страница видео, как раньше.
 */
class ChatVideoOpener(
    private val activity: Activity,
    private val handler: Handler,
    private val links: MessageLinks,
    /** Можно попросить файлы у страницы (свой экран, мост работает, есть сеть). */
    private val canFetch: () -> Boolean,
    /** Попросить файлы ролика; итог — [onSources]. */
    private val requestFiles: (id: String, accessKey: String) -> Unit,
    private val showProgress: (Boolean) -> Unit,
    /** Перед видео: замолчать музыке, которую играет страница (запасной путь полосы трека). */
    private val stopPageAudio: () -> Unit,
) {
    /** Ролик, файлы которого запрошены; null — ничего не ждём. */
    private var requested: ChatVideo? = null

    private val timeout = Runnable {
        val video = requested ?: return@Runnable
        requested = null
        showProgress(false)
        AppLog.w(LogTag.MESSAGES, "файлы видео не пришли вовремя, открывается страница", "номер" to video.id)
        links.openVideo(video)
    }

    /** Нажали видео или клип в сообщении. */
    fun onClicked(video: ChatVideo) {
        if (!canFetch()) {
            links.openVideo(video)
            return
        }
        requested = video
        showProgress(true)
        requestFiles(video.id, video.accessKey)
        handler.removeCallbacks(timeout)
        handler.postDelayed(timeout, SOURCES_TIMEOUT_MS)
    }

    fun onSources(payload: ReaderPayload.Video) {
        val video = requested?.takeIf { it.id == payload.id } ?: return
        requested = null
        handler.removeCallbacks(timeout)
        showProgress(false)
        if (!payload.ok || !payload.hasSource) {
            AppLog.w(LogTag.MESSAGES, "файлов видео нет, открывается страница", "причина" to payload.reason)
            links.openVideo(video)
            return
        }
        AppLog.i(LogTag.MESSAGES, "файлы видео получены", "как" to payload.reason, "mp4" to payload.mp4.isNotEmpty(), "поток" to payload.hls.isNotEmpty())
        // Музыка и голосовое не должны звучать поверх видео: музыка из переписки — на паузу, страница — стоп.
        stopPageAudio()
        ChatMusic.onPlayerCommand(com.example.vkweb.media.PlayerCommand.PAUSE)
        // Трансляция — только поток; запись — mp4 (быстрее начинает), иначе поток.
        val file = if (payload.live) payload.hls else payload.mp4.ifEmpty { payload.hls }
        VideoPlayerActivity.open(
            activity, file, payload.player, video.pagePath, payload.title.ifEmpty { video.title }, loop = video.isClip,
            files = if (payload.live) emptyMap() else payload.files, hls = payload.hls, fromChat = true,
        )
    }

    /**
     * Ушли из переписки или с экрана, пока ждали файлы: видео уже не нужно — ответ, пришедший позже, не откроет окно
     * поверх других приложений.
     */
    fun cancel() {
        requested = null
        handler.removeCallbacks(timeout)
    }

    private companion object {
        const val SOURCES_TIMEOUT_MS = 8_000L
    }
}
