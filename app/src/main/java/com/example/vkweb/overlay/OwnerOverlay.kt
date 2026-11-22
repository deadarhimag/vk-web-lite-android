package com.example.vkweb.overlay

import android.net.Uri
import android.view.View
import com.example.vkweb.R
import com.example.vkweb.diag.LogFormat
import com.example.vkweb.owner.LikeResult
import com.example.vkweb.owner.OwnerActions
import com.example.vkweb.owner.OwnerHeader
import com.example.vkweb.owner.OwnerPanel
import com.example.vkweb.owner.OwnerPath
import com.example.vkweb.owner.OwnerPayload
import com.example.vkweb.owner.OwnerRefreshResult
import com.example.vkweb.owner.PostPhoto
import com.example.vkweb.owner.PostShare
import com.example.vkweb.owner.WallPost
import com.example.vkweb.web.PageBridge

/**
 * Свой экран страницы человека или сообщества. Адрес /id1, /club1 — экран сразу; короткое имя (/durov)
 * может быть и разделом сайта — экран появляется, когда страница прислала данные владельца.
 */
class OwnerOverlay(host: OverlayHost, root: View) : PageOverlay(host, R.string.owner_fallback), OwnerActions {

    private val context = root.context
    /** Треки записи, которой нажали трек: по ним — очередь (названия и порядок). */
    private var audioPost: List<com.example.vkweb.owner.PostExtra> = emptyList()

    override val panel = OwnerPanel(
        root, this,
        onLoadMore = { host.bridge.owner(PageBridge.ACTION_MORE) },
        onPostsSeen = { keys -> host.bridge.ownerSeen(keys) },
    )

    /** Путь открытой страницы, если она может быть страницей владельца (/id1, /durov), иначе null. */
    private var key: String? = null

    /** Это точно страница владельца: адрес вида /id1 или страница прислала его данные. */
    private var confirmed = false

    /**
     * Страницы, которые в этом сеансе уже оказались страницами владельцев. При возврате «Назад» VK часто
     * берёт их из своего кэша без новых запросов — тогда экран показывает то, что уже было.
     */
    private val knownKeys = HashSet<String>()
    private var refreshSequence = 0L
    private var pendingRefresh: String? = null
    private var refreshFailed = false
    private var wallHeadRequested = false

    init {
        host.bridge.on(OwnerPayload.TYPE, OwnerPayload::fromJson, ::onPayload)
        host.bridge.on(OwnerRefreshResult.TYPE, OwnerRefreshResult::fromJson, ::onRefreshResult)
        host.bridge.on(LikeResult.TYPE, LikeResult::fromJson, ::onLikeResult)
        host.bridge.on(com.example.vkweb.owner.PostAudio.TYPE, com.example.vkweb.owner.PostAudio::fromJson, ::onPostAudio)
        host.bridge.on(POST_VIDEO_TYPE, com.example.vkweb.video.VideoFiles::fromJson, ::onPostVideo)
    }

    override val logName: String get() = "страница"
    override val isOnPage: Boolean get() = key != null && confirmed
    override val isEnabled: Boolean get() = host.settings.nativeOwner

    override val title: String?
        get() = when {
            !panel.isShown -> null
            OwnerPath.isFeed(panel.key) -> context.getString(R.string.feed_title)
            else -> panel.current?.header?.name?.takeIf { it.isNotBlank() }
        }

    override fun showPanel(): Boolean {
        val k = key ?: return false
        if (panel.isShown && OwnerPath.sameKey(panel.key, k)) return false
        pendingRefresh = null
        refreshFailed = false
        panel.show(k)
        return true
    }

    override fun requestFresh() = host.bridge.owner(PageBridge.ACTION_SYNC)

    override fun requestUpdate() {
        val k = key ?: return
        if (OwnerPath.isFeed(k)) return requestFresh()
        wallHeadRequested = true
        val operation = (++refreshSequence).toString()
        pendingRefresh = operation
        refreshFailed = false
        host.bridge.refreshOwner(operation, k)
    }

    override fun onUpdateTimeout(): Boolean {
        if (pendingRefresh == null) return false
        finishRefreshFailure("timeout")
        return true
    }

    private fun onRefreshResult(result: OwnerRefreshResult) {
        if (!isShown || result.operation != pendingRefresh || !OwnerPath.sameKey(key, result.key)) return
        val payload = result.payload
        if (result.reason.isNotEmpty() || payload == null) {
            finishRefreshFailure(result.reason.ifEmpty { "invalid" }, result.detail)
            return
        }
        pendingRefresh = null
        onPayload(payload)
        if (payload.header == null) onFreshData()
    }

    private fun finishRefreshFailure(reason: String, detail: String = "") {
        pendingRefresh = null
        val denied = reason == "denied"
        logWarn("сетевое обновление не выполнено", "отказ VK" to denied, "причина" to reason, "ответ" to detail.ifEmpty { null })
        // 5.00.16: VK не отказал в доступе - как до 5.00.15: страница присылает свои данные заново. Иначе (отчёт 22.11)
        // каждое обновление сообщества кончалось ошибкой, и данные страницы больше не считались свежими.
        if (!denied) {
            refreshFailed = false
            logInfo("обновление - данными страницы")
            requestFresh()
            return
        }
        // Отказ VK (нет доступа к стене) - так и сказать
        refreshFailed = true
        onUpdateFailed(R.string.owner_refresh_denied)
    }

    override fun loadSaved() {
        val k = key ?: return
        host.store.loadOwner(k, OwnerPath.ownerIdOf(k)) { saved ->
            logInfo(if (saved != null) "показано сохранённое" else "сохранённого нет", "записей" to saved?.posts?.size)
            if (saved != null && OwnerPath.sameKey(key, k)) panel.showSaved(saved)
        }
    }

    override fun onUrlChanged(uri: Uri, isVideoSite: Boolean) {
        val feed = !isVideoSite && OwnerPath.isFeed(uri.path) && host.settings.nativeFeed
        val k = if (!isVideoSite && (OwnerPath.isCandidate(uri.path) || feed)) OwnerPath.keyOf(uri.path) else null
        if (k == null || key == null || !OwnerPath.sameKey(k, key)) onOwnerPageChanged(k)
        update()
    }

    private fun onPayload(payload: OwnerPayload) {
        if (!OwnerPath.sameKey(payload.key, key)) return
        // Страница с коротким именем: свой экран — только когда известно, чья она (есть шапка).
        if (payload.header == null && !confirmed) return
        if (!confirmed) logInfo(
            "страница признана страницей человека или сообщества", "адрес" to LogFormat.url(payload.key),
        )
        confirmed = true
        logDebug(
            "данные от страницы", "шапка" to (payload.header != null), "стена получена" to payload.wallSeen,
            "записей" to payload.posts.size, "есть ещё" to payload.hasMore,
            "сообщество" to payload.header?.let { !it.isUser },
        )
        update()
        panel.update(payload)
        host.prefetchImages(payload.imageUrls())
        if (payload.header != null || OwnerPath.isFeed(payload.key)) {
            knownKeys += OwnerPath.keyOf(payload.key).lowercase()
            if (pendingRefresh == null && !refreshFailed) onFreshData(payload.snapshot || payload.needsWallHead)
            // Сохраняем то, что на экране: свежие записи и ещё не догруженные из прежнего сохранения.
            panel.current?.let(host.store::saveOwner)
        }
        if (payload.needsWallHead && isShown && host.isOnline && !wallHeadRequested) {
            logInfo("стена получена с курсором, запрошено начало стены")
            startUpdate()
        }
        host.onOverlayChanged()
    }

    /** Когда открылась страница с номером (/club1): короткое имя сразу после неё — её же перенаправление. */
    private var idKeyAt = 0L

    /**
     * Приложение само начало переход к странице с номером (4.13) — короткое имя, которое откроется следом, её же.
     * Отчёт 4.12: сообщество из списка открывалось переходом силами страницы, и VK сразу показывал /rock_music_on, минуя
     * /club1 в адресе. Прежнее правило («короткое имя сразу после страницы с номером») не срабатывало: страницы с номером
     * в адресе не было, и 3,2 с была видна настоящая страница VK, пока не пришли данные владельца.
     */
    private var expectingOwner = false

    /** Перед переходом на [url]: страница с номером (/club1, /id1, /public1) — ждать её под коротким именем. */
    fun expectOwner(url: String) {
        val path = try { Uri.parse(url).path } catch (e: Exception) { null }
        if (OwnerPath.ownerIdOf(OwnerPath.keyOf(path)) == null) {
            expectingOwner = false
            return
        }
        expectingOwner = true
        idKeyAt = android.os.SystemClock.elapsedRealtime()
    }

    private fun onOwnerPageChanged(newKey: String?) {
        val previous = key
        key = newKey
        pendingRefresh = null
        refreshFailed = false
        wallHeadRequested = false
        onPageChanged()
        val now = android.os.SystemClock.elapsedRealtime()
        // 4.0, отчёт 16:19: сообщество из списка открывается как /club1, а VK тут же перенаправляет на короткое имя
        // (/rock_music_on). Короткое имя признавалось страницей владельца только по данным — 3,5 с вместо своего экрана
        // была видна настоящая страница VK. Перенаправление сразу после страницы с номером — та же страница.
        val afterOwnerId = expectingOwner || (previous != null && OwnerPath.ownerIdOf(previous) != null)
        val redirectOfOwner = newKey != null && afterOwnerId &&
            OwnerPath.ownerIdOf(newKey) == null && now - idKeyAt < REDIRECT_WINDOW_MS
        if (redirectOfOwner) logInfo(
            "короткое имя сразу после страницы с номером — та же страница", "адрес" to LogFormat.url(newKey),
            "переход начат приложением" to expectingOwner,
        )
        // Ожидание — на один переход: следующая страница с коротким именем (раздел, другой человек) — уже не она.
        if (newKey != null) expectingOwner = false
        if (newKey != null && OwnerPath.ownerIdOf(newKey) != null) idKeyAt = now
        confirmed = newKey != null && (redirectOfOwner || OwnerPath.ownerIdOf(newKey) != null || OwnerPath.isFeed(newKey) || newKey.lowercase() in knownKeys)
        if (newKey == null || confirmed || host.isOnline) return
        // Без сети данных от страницы не будет: показываем сохранённое, если эта страница уже открывалась.
        host.store.loadOwner(newKey, null) { saved ->
            if (saved == null || !OwnerPath.sameKey(key, newKey) || confirmed) return@loadOwner
            confirmed = true
            update()
        }
    }

    // ------------------------------------------------------------ видео из записи (4.14)

    /** Ролик из записи, для которого ждём файлы: номер и страница (на неё — если файлов нет). */
    private var pendingVideo: Pair<String, String>? = null
    private val videoTimeout = Runnable {
        val (id, page) = pendingVideo ?: return@Runnable
        pendingVideo = null
        logWarn("файлы ролика из записи не пришли вовремя — открыта страница ролика", "номер" to LogFormat.url("/video$id"))
        host.links.openLink(page)
    }

    /**
     * Ролик из записи — своим проигрывателем, как в каталоге видео и в сообщениях. Отчёт 4.12: из сообщества ролик
     * открывался страницей VK Видео — играл чужим проигрывателем, а «Назад» перезагружал страницу сообщества.
     */
    private fun playPostVideo(id: String, key: String, page: String) {
        pendingVideo = id to page
        host.cancel(videoTimeout)
        host.postDelayed(videoTimeout, VIDEO_FILES_TIMEOUT_MS)
        logInfo("ролик из записи: запрошены файлы", "ключ" to key.isNotEmpty())
        host.bridge.ownerVideo(id, key)
    }

    private fun onPostVideo(files: com.example.vkweb.video.VideoFiles) {
        val (id, page) = pendingVideo?.takeIf { it.first == files.id } ?: return
        pendingVideo = null
        host.cancel(videoTimeout)
        if (files.files.isEmpty() && files.hls.isEmpty()) {
            logWarn("у ролика из записи нет файлов — открыта страница ролика")
            host.links.openLink(page)
            return
        }
        logInfo("ролик из записи открыт проигрывателем приложения", "качеств" to files.files.size, "поток" to files.hls.isNotEmpty())
        val file = files.files.maxByOrNull { it.key }?.value ?: files.hls
        com.example.vkweb.ui.VideoPlayerActivity.open(
            context, file, player = "", page = page, title = "", loop = false, files = files.files, hls = files.hls,
        )
    }

    // ------------------------------------------------------------ OwnerActions

    override fun openLink(target: String) = host.links.openLink(target)

    /**
     * Два режима (настройка «Фото в записях»): страницей VK — с лайками и комментариями, или просто картинкой. Из
     * просмотра картинки страницу фото можно открыть кнопкой.
     */
    override fun openPhoto(photo: PostPhoto, gallery: List<PostPhoto>) {
        if (photo.open.isNotEmpty()) {
            val videoId = VIDEO_PATH.matchEntire(photo.open)?.groupValues?.get(1)
            if (videoId != null && host.settings.nativeVideo && host.isOnline) {
                playPostVideo(videoId, photo.key, photo.open)
                return
            }
            host.links.openLink(photo.open)
            return
        }
        if (host.settings.photoOpensPage && photo.page.isNotEmpty()) {
            val uri = Uri.parse(photo.page)
            logInfo("фото открыто страницей", "ключ передан" to ("access_key" in uri.queryParameterNames),
                "параметров" to uri.queryParameterNames.size, "фрагмент" to (uri.fragment != null))
            host.links.openLink(photo.page)
            return
        }
        // Листаются только фото: превью видео ведут на страницу видео, в просмотр им не нужно.
        val stills = gallery.filter { it.open.isEmpty() }
        host.links.openPhotos(stills.map { it.full.ifEmpty { it.url } }, stills.indexOf(photo).coerceAtLeast(0), stills.map { it.page })
    }

    /** Запись - своим экраном с комментариями (шторка); нет окна или номера записи - страница сайта, как раньше. */
    override fun openPost(post: WallPost) {
        val fallback = { if (post.url.isNotEmpty()) host.links.openLink(post.url) }
        val activity = context as? android.app.Activity
        if (activity == null) {
            fallback()
            return
        }
        com.example.vkweb.comments.CommentsSheet.openPost(
            activity, com.example.vkweb.comments.CommentTarget.ofPost(post.key), post, this, fallback,
        )
    }

    override fun share(post: WallPost) {
        val text = PostShare.textOf(post)
        val photos = post.photos.filter { it.open.isEmpty() }.map { it.full.ifEmpty { it.url } }
        logInfo("поделиться записью", "фото" to photos.size)
        host.share(text, photos)
    }

    /** Отмечаем сразу (иначе нажатие кажется не сработавшим), сервер подтверждает или отменяет. */
    override fun like(post: WallPost) {
        if (!host.isOnline) {
            host.showToast(R.string.offline_cannot_like)
            return
        }
        val on = !post.liked
        logInfo(if (on) "лайк записи" else "лайк записи снят", "запись" to LogFormat.url(post.url))
        pendingLikes[post.key] = post
        panel.setLike(post.key, on, post.likes + if (on) 1 else -1)
        host.bridge.ownerLike(post.key, on)
    }

    /** Записи, лайк которых ждёт ответа сервера: при отказе на экране возвращается прежнее состояние. */
    private val pendingLikes = HashMap<String, WallPost>()

    private fun onLikeResult(result: LikeResult) {
        val before = pendingLikes.remove(result.key)
        if (result.ok) {
            logInfo(if (result.liked) "лайк поставлен" else "лайк снят", "лайков" to result.likes)
            panel.setLike(result.key, result.liked, result.likes)
            return
        }
        logWarn("лайк не поставлен", "причина" to result.reason)
        if (before == null) return
        panel.setLike(before.key, before.liked, before.likes)
        // Страница не дала поставить лайк сама — открываем запись: там он ставится кнопкой VK.
        host.showToast(R.string.like_failed_open_post)
        if (before.url.isNotEmpty()) host.links.openLink(before.url)
    }

    /** Трек записи: адреса треков этой записи у страницы, играет приложение (как музыка из переписки). */
    override fun playAudio(post: WallPost, track: com.example.vkweb.owner.PostExtra) {
        if (!host.isOnline) {
            host.showToast(R.string.offline_cannot_play)
            return
        }
        // Тот же трек уже звучит — пауза/продолжить.
        if (com.example.vkweb.media.ChatMusic.current?.id == track.trackId) {
            com.example.vkweb.media.ChatMusic.toggle()
            return
        }
        audioPost = post.extras.filter { it.kind == "audio" && it.trackId.isNotEmpty() }
        logInfo("трек записи", "номер" to track.trackId, "треков в записи" to audioPost.size)
        val csv = audioPost.joinToString(",") { it.trackId + if (it.trackKey.isNotEmpty()) "_" + it.trackKey else "" }
        host.bridge.ownerAudio(track.trackId, csv)
    }

    private fun onPostAudio(payload: com.example.vkweb.owner.PostAudio) {
        val byId = payload.items.associateBy { it.id }
        val tracks = audioPost.mapNotNull { e ->
            val item = byId[e.trackId] ?: return@mapNotNull null
            val (artist, name) = com.example.vkweb.media.ChatQueue.splitTitle(e.title)
            com.example.vkweb.media.ChatQueue.Track(
                id = e.trackId, title = item.title.ifEmpty { name }, artist = item.artist.ifEmpty { artist },
                url = item.url, durationSec = item.durationSec,
            )
        }
        val start = tracks.indexOfFirst { it.id == payload.wanted }
        if (start < 0) {
            logWarn("адреса трека записи нет", "причина" to payload.reason, "адресов" to payload.items.size)
            host.showToast(R.string.post_audio_unavailable)
            return
        }
        logInfo("трек записи играет приложение", "в очереди" to tracks.size)
        com.example.vkweb.media.ChatMusic.play(context, tracks, start)
    }

    override fun openAvatar(url: String) = host.links.openPhoto(url)

    override fun write(header: OwnerHeader) = host.links.openChat(header.ownerId.toString(), header.name, header.photo)

    private companion object {
        const val REDIRECT_WINDOW_MS = 3_000L
        /** Сообщение страницы с файлами ролика из записи (4.14). */
        const val POST_VIDEO_TYPE = "postvideo"
        /** Ролик из записи (не клип): /video-1_2. */
        val VIDEO_PATH = Regex("^/video(-?\\d{1,12}_\\d{1,12})$")
        const val VIDEO_FILES_TIMEOUT_MS = 5_000L
    }
}
