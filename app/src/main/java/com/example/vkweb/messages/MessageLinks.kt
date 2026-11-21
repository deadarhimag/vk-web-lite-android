package com.example.vkweb.messages

import android.app.Activity
import android.content.Intent
import android.net.Uri
import com.example.vkweb.MainActivity
import com.example.vkweb.diag.AppLog
import com.example.vkweb.diag.LogTag
import com.example.vkweb.web.ExternalLinks
import com.example.vkweb.web.UrlPolicy

/** Открывает то, на что нажали в переписке. «Назад» из главного окна возвращает в переписку. */
class MessageLinks(private val activity: Activity) {
    private val externalLinks by lazy { ExternalLinks(activity) }
    private val policy = UrlPolicy()
    private val trusted: (String?, String?) -> Boolean = { scheme, host -> policy.isTrustedOrigin(scheme, host) }

    fun openVideo(video: ChatVideo) {
        AppLog.i(LogTag.MESSAGES, "видео открывается на странице", "номер" to video.id)
        openSitePath(video.pagePath)
    }

    fun openCard(card: ChatCard) {
        AppLog.i(LogTag.MESSAGES, "вложение открывается", "вид" to card.kind)
        open(LinkTarget.forCard(card, trusted))
    }

    fun openLink(url: String) = open(LinkTarget.forUrl(url, trusted))

    /** Страница человека или сообщества из шапки переписки (4.31): свой экран страницы, «Назад» — в переписку. */
    fun openPage(path: String) {
        if (!path.startsWith("/") || path.startsWith("//")) return
        AppLog.i(LogTag.MESSAGES, "страница собеседника из шапки", "адрес" to path.replace(Regex("\\d{3,}"), "#"))
        openSitePath(path)
    }

    private fun open(target: LinkTarget.Target) = when (target) {
        is LinkTarget.Target.Site -> openSitePath(target.path)
        is LinkTarget.Target.External -> externalLinks.open(Uri.parse(target.url))
        is LinkTarget.Target.Track -> openTrack(target)
    }

    /** Музыка: главное окно открывает страницу музыки, и она включает трек (нет на странице — находит поиском). */
    private fun openTrack(target: LinkTarget.Target.Track) {
        AppLog.i(LogTag.MESSAGES, "музыка из переписки", "номер" to target.trackId.ifEmpty { "нет" })
        activity.startActivity(
            Intent(activity, MainActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_REORDER_TO_FRONT or Intent.FLAG_ACTIVITY_SINGLE_TOP)
                .putExtra(MainActivity.EXTRA_PLAY_TRACK, target.trackId)
                .putExtra(MainActivity.EXTRA_PLAY_TITLE, target.title)
                .putExtra(MainActivity.EXTRA_INTERNAL_TOKEN, MainActivity.INTERNAL_TOKEN),
        )
    }

    /** Треки исполнителя (4.79): поиск в своём экране музыки главного окна; «Назад» — сюда, музыка играет дальше. */
    fun openArtist(artist: String) {
        AppLog.i(LogTag.MESSAGES, "треки исполнителя из переписки")
        activity.startActivity(
            Intent(activity, MainActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_REORDER_TO_FRONT or Intent.FLAG_ACTIVITY_SINGLE_TOP)
                .putExtra(MainActivity.EXTRA_MUSIC_ARTIST, artist)
                .putExtra(MainActivity.EXTRA_INTERNAL_TOKEN, MainActivity.INTERNAL_TOKEN),
        )
    }

    /** Страница сайта открывается в главном окне; уже открытое окно выходит на передний план, а не создаётся второе. */
    private fun openSitePath(path: String) = activity.startActivity(
        Intent(activity, MainActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_REORDER_TO_FRONT or Intent.FLAG_ACTIVITY_SINGLE_TOP)
            .putExtra(MainActivity.EXTRA_OPEN_URL, path)
            .putExtra(MainActivity.EXTRA_FROM_CHAT, true)
            .putExtra(MainActivity.EXTRA_INTERNAL_TOKEN, MainActivity.INTERNAL_TOKEN),
    )
}
