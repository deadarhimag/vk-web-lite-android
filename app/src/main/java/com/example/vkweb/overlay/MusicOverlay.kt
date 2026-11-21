package com.example.vkweb.overlay

import android.net.Uri
import android.os.SystemClock
import android.view.View
import com.example.vkweb.R
import com.example.vkweb.config.Tab
import com.example.vkweb.media.MediaState
import com.example.vkweb.media.MusicAction
import com.example.vkweb.media.MusicPayload
import com.example.vkweb.media.MusicRouting
import com.example.vkweb.media.MusicSearch
import com.example.vkweb.media.MusicDownload
import com.example.vkweb.media.MusicDownloadStarter
import com.example.vkweb.media.MusicCache
import com.example.vkweb.media.InAppQueue
import com.example.vkweb.media.MusicSection
import com.example.vkweb.media.MusicTrack
import com.example.vkweb.media.PlaylistEntry
import com.example.vkweb.media.PlaylistsPayload
import com.example.vkweb.ui.MusicPanel
import com.example.vkweb.ui.PlayerSheet
import com.example.vkweb.web.PageBridge

/**
 * Свой экран музыки поверх страниц /audio: «Мои треки» и «Плейлисты» (переключатель сверху). «Мои треки» и список
 * плейлистов сохраняются для работы без сети.
 *
 * Страница музыки одна, а экранов два: что на ней сейчас («Мои треки», плейлист), она сообщает сама, а какой список
 * показать человеку, решает [MusicRouting]. Переходы между разделами делает сама страница (как нажатие ссылки),
 * поэтому музыка при переключении не прерывается.
 *
 * Поиск (4.79): ищет сама страница музыки (её поиск), результаты показываются своим списком вместо «Моих треков» и
 * плейлистов. Поиск по исполнителю — из долгого нажатия на трек, из плеера и из переписки ([searchFromChat]).
 */
class MusicOverlay(host: OverlayHost, root: View) : PageOverlay(host, R.string.music_fallback) {

    /** Для своего проигрывателя найденных треков (4.88). */
    private val context = root.context

    override val panel: MusicPanel = MusicPanel(
        root = root,
        onPlay = ::play,
        onLoadMore = { target -> host.bridge.musicMore(target) },
        onPlayerCommand = ::command,
        onOpenPlayer = { player.show() },
        onSection = ::onSection,
        onOpenPlaylist = ::onPlaylistOpened,
        onClosePlaylist = { logInfo("плейлист закрыт: список плейлистов") },
        onAddTrack = ::addTrack,
        caching = MusicPanel.Caching(
            onTrack = ::cacheTrack,
            onRemove = ::removeCached,
            onList = ::cacheList,
            onStop = ::stopCaching,
            onOpenFolder = { if (!com.example.vkweb.files.AppFolder.openMusicDownloads(context)) host.showToast(R.string.app_folder_unavailable) },
            isActive = { MusicDownloadStarter.queued > 0 || com.example.vkweb.media.MusicCacheWorker.isBusy },
        ),
        onFollowPlaylist = ::followPlaylist,
        onSearch = { query, artist -> search(query, artist) },
        onCloseSearch = ::closeSearch,
        onArtistOnly = ::onArtistOnly,
    )
    private var onMusicPage = false
    /** Что играет сейчас: выделяется в списке и после его обновления. */
    private var nowPlaying = MediaState.IDLE

    /** Когда пришло текущее состояние: от него досчитывается позиция трека. */
    private var nowPlayingAt = 0L

    /** Плеер с перемоткой: открывается из мини-плеера внизу списка. */
    private val player: PlayerSheet = PlayerSheet(
        root.context, onCommand = ::command, onSeek = ::seek,
        onAdd = { id -> addTrackId(id, fromPlayer = true) },
        onArtist = { artist -> panel.chooseArtist(artist) },
    )

    // ------------------------------------------------------------ состояние поиска (4.79)

    /** Последние данные страницы поиска: по ним список строится заново, когда переключили «Только исполнитель». */
    private var lastFound: MusicPayload? = null

    /** Когда начат поиск: пустой ответ в первые [SEARCH_SETTLE_MS] — ещё не «ничего не найдено». */
    private var searchStartedAt = 0L

    /** Страница уже показывала этот поиск: ушла с него сама — поиск на экране закрывается. */
    private var searchOnPage = false

    /** Запрос поиска, на котором сейчас страница (по её адресу); null — она не на поиске. */
    private var pageQuery: String? = null

    /** Поиск из переписки ждёт страницу музыки: команда уйдёт, когда она откроется ([searchFromChat]). */
    private var pendingPageSearch: String? = null

    private val searchSettle = Runnable { settleSearch() }
    private val sendPendingSearch = Runnable {
        val query = pendingPageSearch ?: return@Runnable
        pendingPageSearch = null
        if (panel.searchQuery == query) {
            // 4.88: ожидание результатов — с отправки команды, а не с нажатия в переписке. Пока открывался раздел музыки
            // (холодная загрузка, медленная сеть), семь секунд ожидания проходили впустую, и экран успевал сказать
            // «ничего не найдено» до того, как страница вообще начала искать.
            searchStartedAt = SystemClock.elapsedRealtime()
            host.cancel(searchSettle)
            host.postDelayed(searchSettle, SEARCH_SETTLE_MS)
            host.bridge.musicSearch(query)
        }
    }

    init {
        host.bridge.on(MusicPayload.TYPE, MusicPayload::fromJson, ::onPayload)
        host.bridge.on(PlaylistsPayload.TYPE, PlaylistsPayload::fromJson, ::onPlaylists)
        host.bridge.on(MusicAction.TYPE, MusicAction::fromJson, ::onAction)
    }

    // ------------------------------------------------------------ кэш по просьбе (4.97)

    private val mainHandler = android.os.Handler(android.os.Looper.getMainLooper())

    private fun notice(text: String) = mainHandler.post { com.example.vkweb.ui.Notice.show(context, text) }

    private fun cacheResult(text: String, tracks: List<MusicTrack> = emptyList(), generation: Long) = mainHandler.post {
        if (generation != MusicCache.currentGeneration()) return@post
        panel.showCacheResult(text, tracks)
        com.example.vkweb.ui.Notice.show(context, text)
    }

    private fun label(track: MusicTrack) = listOf(track.artist, track.title).filter { it.isNotBlank() }.joinToString(" — ")

    /** Просьба об одном треке: итог — полосой внизу экрана. */
    private fun singleBatch(label: String, generation: Long = MusicCache.currentGeneration()) = com.example.vkweb.media.CacheBatch(1, label) { b ->
        val text = when {
            b.saved > 0 || b.already > 0 -> context.getString(R.string.music_cache_saved, label)
            b.failed > 0 && MusicDownloadStarter.takeMp3Unavailable() -> context.getString(R.string.music_mp3_requires_64bit)
            b.failed > 0 -> context.getString(R.string.music_cache_failed, label)
            else -> null
        }
        text?.let { cacheResult(it, generation = generation) }
    }

    /**
     * «Кэшировать трек»: трек сохраняется в хранилище телефона («Музыка/VkWebLite») и дальше играет оттуда. Адрес потока
     * известен — сразу; иначе его ищет отдельная страница кэша ([com.example.vkweb.media.MusicCacheWorker]).
     */
    private fun cacheTrack(track: MusicTrack) {
        if (MusicCache.isCached(track.id)) {
            notice(context.getString(R.string.music_cache_saved, label(track)))
            return
        }
        val starter = MusicDownloadStarter(context)
        when {
            track.currentStreamUrl().isNotEmpty() && host.isOnline -> {
                notice(context.getString(R.string.music_cache_started, label(track)))
                starter.cache(MusicDownload.of(track, pageUrl = pageUrlFor(track) ?: com.example.vkweb.media.MusicRecovery.DEFAULT_PAGE), singleBatch(label(track)))
            }
            !host.isOnline -> host.showToast(R.string.music_cache_offline)
            else -> {
                // 4.99.7: поток ищет отдельная страница кэша — плеер и страница человека не трогаются.
                val page = pageUrlFor(track)
                if (page == null) {
                    host.showToast(R.string.music_download_unavailable)
                    return
                }
                logInfo("кэш: поток ищет страница кэша", "номер" to track.id)
                notice(context.getString(R.string.music_cache_started, label(track)))
                com.example.vkweb.media.MusicCacheWorker.enqueue(context, listOf(track), page, singleBatch(label(track)))
            }
        }
    }

    /** Страница VK, где есть трек: «Мои треки» или открытый плейлист; null — найденное поиском (его страницы нет). */
    private fun pageUrlFor(track: MusicTrack): String? = when {
        panel.listFor(MusicPanel.FROM_MY).any { it.id == track.id } -> com.example.vkweb.media.MusicCacheWorker.MY_PAGE
        panel.listFor(MusicPanel.FROM_PLAYLIST).any { it.id == track.id } -> playlistPage()
        else -> null
    }

    /** Адрес открытого плейлиста (полный). */
    private fun playlistPage(): String? = panel.openedPlaylist?.url?.takeIf { it.isNotEmpty() }?.let {
        if (it.startsWith("https://")) it else "https://m.vk.ru" + (if (it.startsWith("/")) it else "/$it")
    }

    /** «Удалить из кэша»: файл удаляется и из «Музыки» (это он и есть). */
    private fun removeCached(track: MusicTrack) {
        MusicDownloadStarter(context).remove(track.id) { ok ->
            host.showToast(if (ok) R.string.music_cache_removed else R.string.music_cache_remove_failed)
        }
    }

    /**
     * «Кэшировать весь список» (Мои треки, плейлист, найденное): все показанные треки, которых ещё нет в кэше. С адресом
     * потока — сразу; остальные — через отдельную страницу кэша ([com.example.vkweb.media.MusicCacheWorker], 4.99.7).
     */
    private fun cacheList(from: String) {
        val tracks = panel.listFor(from).filterNot { MusicCache.isCached(it.id) }
        if (tracks.isEmpty()) {
            host.showToast(R.string.music_cache_list_done_already)
            return
        }
        if (!host.isOnline) {
            host.showToast(R.string.music_cache_offline)
            return
        }
        logInfo("кэш списка", "откуда" to from, "треков" to tracks.size)
        notice(context.resources.getQuantityString(R.plurals.music_cache_list_started, tracks.size, tracks.size))
        val page = if (from == MusicPanel.FROM_PLAYLIST) playlistPage() else if (from == MusicPanel.FROM_MY) com.example.vkweb.media.MusicCacheWorker.MY_PAGE else null
        val generation = MusicCache.currentGeneration()
        enqueue(tracks, page, com.example.vkweb.media.CacheBatch(tracks.size) { b -> cacheResult(listSummary(b), tracks, generation) })
    }

    /** Треки — в кэш: с адресом потока сразу, остальные — страница кэша [page] (null — потока не найти: «не удалось»). */
    private fun enqueue(tracks: List<MusicTrack>, page: String?, batch: com.example.vkweb.media.CacheBatch) {
        val starter = MusicDownloadStarter(context)
        val (direct, unresolved) = tracks.partition { it.currentStreamUrl().isNotEmpty() }
        direct.forEach { starter.cache(MusicDownload.of(it, pageUrl = page ?: com.example.vkweb.media.MusicRecovery.DEFAULT_PAGE), batch) }
        if (unresolved.isEmpty()) return
        if (page == null) batch.onFailed(unresolved.size)
        else com.example.vkweb.media.MusicCacheWorker.enqueue(context, unresolved, page, batch)
    }

    /** Треки «Моей музыки», уже поставленные в кэш в этом запуске (повторные данные страницы их не ставят снова). */
    private val autoCached = HashSet<String>()

    /**
     * «Моя музыка» — вся в кэш (4.99.4), при включённом «Кэшировать всю «Мою музыку»» (5.00.12: отдельная настройка,
     * по умолчанию выключена - раньше скачивание само начиналось при входе в раздел): каждый показанный трек, которого ещё нет
     * в кэше, — по мере того как страница отдаёт список (и при «добавить к себе»). Потоки ищет страница кэша — музыку
     * человека это не прерывает (4.99.7). Итог — в журнал, ход — в уведомлении кэширования.
     */
    private fun autoCacheMy(tracks: List<MusicTrack>) {
        if (!host.settings.cacheMyMusic || !host.isOnline) return
        val fresh = tracks.filter { !MusicCache.isCached(it.id) && autoCached.add(it.id) }
        if (fresh.isEmpty()) return
        val generation = MusicCache.currentGeneration()
        logInfo("«Моя музыка» в кэш", "треков" to fresh.size)
        enqueue(fresh, com.example.vkweb.media.MusicCacheWorker.MY_PAGE, com.example.vkweb.media.CacheBatch(fresh.size) { b ->
            mainHandler.post {
                logInfo("«Моя музыка» в кэш: итог", "сохранено" to b.saved, "уже было" to b.already, "не удалось" to b.failed, "остановлено" to b.cancelled)
                if (generation == MusicCache.currentGeneration()) panel.showCacheResult(listSummary(b), fresh)
            }
        })
    }

    private fun listSummary(b: com.example.vkweb.media.CacheBatch): String {
        val parts = mutableListOf(context.getString(R.string.music_cache_summary_saved, b.saved + b.already, b.total))
        if (b.failed > 0) parts += context.getString(R.string.music_cache_summary_failed, b.failed)
        if (b.cancelled > 0) parts += context.getString(R.string.music_cache_summary_cancelled, b.cancelled)
        return parts.joinToString(", ")
    }

    /** «Остановить кэширование»: очередь кэша и страница кэша снимают всё неначатое (MusicCacheWorker слушает отмену). */
    private fun stopCaching() {
        MusicDownloadStarter.cancelQueued()
        host.showToast(R.string.music_cache_stopped)
    }

    override val logName: String get() = "музыка"
    override val isOnPage: Boolean get() = onMusicPage
    override val isEnabled: Boolean get() = host.settings.nativeMusic
    override val offlineBanner: Int = R.string.offline_music_banner

    override fun showPanel(): Boolean {
        if (panel.isShown) return false
        panel.show()
        return true
    }

    /** Данные просим у страницы по тому, что на экране: свои треки, плейлисты или открытый плейлист. */
    override fun requestFresh() {
        val query = panel.searchQuery
        when {
            // Тот же поиск: страница уже на нём — только пришлёт результаты заново.
            query != null -> host.bridge.musicSearch(query)
            panel.section == MusicSection.MUSIC -> host.bridge.music(PageBridge.ACTION_SYNC)
            panel.openedPlaylist != null -> host.bridge.music(PageBridge.ACTION_SYNC)
            else -> host.bridge.playlists()
        }
    }

    override fun loadSaved() {
        // Результаты поиска не сохраняются.
        if (panel.isSearching) return
        if (panel.section == MusicSection.PLAYLISTS) {
            // Треки плейлистов не сохраняются; сохранён список плейлистов.
            if (panel.openedPlaylist == null) loadSavedPlaylists()
            return
        }
        host.store.loadMusic { saved ->
            logInfo(if (saved != null) "показано сохранённое" else "сохранённого нет", "треков" to saved?.tracks?.size)
            if (saved != null) panel.showSaved(saved)
        }
    }

    private fun loadSavedPlaylists() = host.store.loadPlaylists { saved ->
        logInfo(if (saved != null) "показаны сохранённые плейлисты" else "сохранённых плейлистов нет", "плейлистов" to saved?.size)
        if (saved != null) panel.showSavedPlaylists(saved)
    }

    override fun onSectionSelected(tab: Tab) {
        if (tab != Tab.MUSIC) return
        // Раздел выбран в меню: страница открывает главную музыки — поиск закрывается.
        if (panel.isSearching) {
            logInfo("поиск закрыт: выбран раздел")
            forgetSearch()
        }
        onMusicPage = true
        onPageChanged()
        update()
    }

    override fun onUrlChanged(uri: Uri, isVideoSite: Boolean) {
        val music = !isVideoSite && Tab.MUSIC.matches(uri.path.orEmpty())
        if (music != onMusicPage) {
            onMusicPage = music
            onPageChanged()
        }
        pageQuery = if (music) MusicSearch.queryOf(uri.path, uri.encodedQuery) else null
        if (music) followPageSearch(pageQuery)
        // Поиск из переписки: страница музыки открылась — даём ей прийти в себя и ищем (см. [searchFromChat]).
        if (music && pendingPageSearch != null) {
            host.cancel(sendPendingSearch)
            host.postDelayed(sendPendingSearch, PAGE_SEARCH_DELAY_MS)
        }
        update()
    }

    /**
     * Страница музыки сменила адрес: [query] — запрос её поиска (null — это не поиск). Страница на поиске, которого
     * экран не показывает (поиск из переписки, поиск страницы для трека из переписки), — экран показывает его результаты;
     * страница ушла с поиска, который экран показывал, — поиск закрывается.
     */
    private fun followPageSearch(query: String?) {
        val shown = panel.searchQuery
        when {
            query == null -> if (shown != null && searchOnPage) {
                logInfo("поиск закрыт: страница ушла с поиска")
                forgetSearch()
            }
            shown != null && MusicSearch.sameQuery(shown, query) -> searchOnPage = true
            // Свой поиск ещё не дошёл до страницы, а она показывает прежний (человек быстро искал дважды): ждём свой.
            shown != null && !searchOnPage -> Unit
            // Поиск страницы, о котором экран не знал (трек из переписки ищется поиском страницы): показать его результаты.
            else -> {
                logInfo("поиск открыт страницей")
                startSearch(query, null)
                searchOnPage = true
            }
        }
    }

    /** «Назад» внутри экрана: из поиска — к спискам; от треков плейлиста — к списку плейлистов (а не со всего экрана). */
    override fun handleBack(): Boolean {
        // Открыто из переписки: «Назад» не закрывает поиск — главное окно вернёт в переписку (музыка играет дальше).
        if (isShown && panel.isSearching && !host.returnsToChat) {
            closeSearch()
            return true
        }
        if (isShown && panel.section == MusicSection.PLAYLISTS && panel.openedPlaylist != null) {
            panel.closePlaylist()
            return true
        }
        return super.handleBack()
    }

    // ------------------------------------------------------------ переключатель и плейлисты

    private fun onSection(section: MusicSection) {
        logInfo("раздел", "выбран" to section.name, "сеть" to host.isOnline)
        if (!host.isOnline) {
            if (!panel.hasData) loadSaved()
            return
        }
        when (section) {
            // Страница возвращается на главную музыки (в плейлисте она осталась бы), и «Мои треки» приходят заново.
            MusicSection.MUSIC -> {
                host.bridge.musicHome()
                host.bridge.music(PageBridge.ACTION_SYNC)
            }
            MusicSection.PLAYLISTS -> {
                val opened = panel.openedPlaylist
                if (opened != null) host.bridge.openPlaylist(opened.id, opened.url) else host.bridge.playlists()
                if (opened == null && !panel.hasData) loadSavedPlaylists()
            }
        }
    }

    private fun onPlaylistOpened(entry: PlaylistEntry) {
        logInfo("плейлист открыт", "номер" to entry.id, "треков" to entry.count)
        if (!host.isOnline) {
            host.showToast(R.string.offline_cannot_play)
            return
        }
        host.bridge.openPlaylist(entry.id, entry.url)
    }

    private fun onPlaylists(payload: PlaylistsPayload) {
        logDebug(
            "плейлисты от страницы", "плейлистов" to payload.items.size, "нет" to payload.none,
            "весь список" to payload.complete,
        )
        val shown = panel.updatePlaylists(payload)
        if (shown.isNotEmpty()) host.store.savePlaylists(shown)
        // Данные ждёт только вкладка «Плейлисты» со списком: «страница не отвечает» относится к ней.
        if (panel.section == MusicSection.PLAYLISTS && panel.openedPlaylist == null && !panel.isSearching && (payload.items.isNotEmpty() || payload.none)) {
            onFreshData()
        }
    }

    // ------------------------------------------------------------ треки

    private fun onPayload(payload: MusicPayload) {
        val opened = panel.openedPlaylist
        val target = MusicRouting.target(payload.page, panel.section, opened?.id, payload.listKey, panel.searchQuery, payload.query)
        logDebug(
            "данные от страницы", "список" to payload.listKey, "страница" to payload.page, "куда" to target.name,
            "треков" to payload.tracks.size, "есть ещё" to payload.hasMore, "весь список" to payload.complete,
        )
        when (target) {
            MusicRouting.Target.MY -> onMyTracks(payload)
            MusicRouting.Target.OPEN_NEW -> {
                if (payload.tracks.isEmpty()) return
                panel.showOpenedByPage(entryFor(payload))
                onOpenedTracks(payload)
            }
            MusicRouting.Target.OPENED -> onOpenedTracks(payload)
            MusicRouting.Target.SEARCH -> onFound(payload)
            MusicRouting.Target.IGNORE -> Unit
        }
    }

    /** Плейлист, который страница открыла сама: номер из адреса, название — из страницы. */
    private fun entryFor(payload: MusicPayload) = PlaylistEntry(
        id = MusicRouting.playlistIdOf(payload.listKey) ?: "0_0",
        title = payload.title,
        subtitle = "",
        coverUrl = "",
        count = 0,
        url = payload.listKey,
    )

    private fun onMyTracks(payload: MusicPayload) {
        // Пока открыт поиск, «Мои треки» обновляются за ним, невидимо: ожидание и плашки относятся к поиску.
        val visible = panel.section == MusicSection.MUSIC && !panel.isSearching
        val before = panel.tracks.size
        // Список адаптера обновится позже (разница считается в фоне): дальше работаем с тем, что вернула панель.
        val shown = panel.updateMy(payload)
        // Список стал короче — это заметно человеку; в журнал, чтобы по отчёту было видно, почему.
        if (visible && shown.size < before) {
            logInfo("список сократился", "было" to before, "стало" to shown.size, "весь список" to payload.complete)
        }
        show(nowPlaying)
        if (payload.tracks.isEmpty()) return
        autoCacheMy(shown)
        if (visible) onFreshData()
        // Сохраняем то, что на экране: настоящий порядок, затем треки, до которых страница не дошла.
        // Раньше сохранялся panel.tracks — то есть прошлая порция: список адаптера к этому моменту ещё не обновлён.
        if (payload.isMyTracks) host.store.saveMusic(payload.copy(tracks = shown))
    }

    private fun onOpenedTracks(payload: MusicPayload) {
        panel.updateOpened(payload)
        show(nowPlaying)
        if (payload.tracks.isNotEmpty() && panel.section == MusicSection.PLAYLISTS && !panel.isSearching) onFreshData()
    }

    /** Что играет страница (главное окно присылает каждое её сообщение). Пока играет свой проигрыватель — он на экране. */
    fun setNowPlaying(state: MediaState) {
        pageState = state
        // Пока звучит свой проигрыватель, прослушивание считает он ([onOwnPlayback]).
        if (ownPlayback == null) {
            val known = panel.trackForId(state.trackId)
            val download = state.audioUrl.takeIf { it.isNotEmpty() && state.trackId.isNotEmpty() }?.let {
                MusicDownload(
                    state.trackId, it, state.title.ifBlank { known?.title.orEmpty() }, state.artist.ifBlank { known?.artist.orEmpty() },
                    known?.album.orEmpty(), known?.tagCoverUrl?.ifEmpty { state.cover } ?: state.cover,
                )
            }
            noteListening(state.trackId, state.playing, download)
        }
        // Свой проигрыватель уступил странице (ChatMusic.yieldToPage не оповещает слушателей): снова музыка страницы.
        ownPlayback?.let { if (!com.example.vkweb.media.ChatMusic.isActive) {
            ownPlayback = null
            com.example.vkweb.media.ChatMusic.removeListener(it)
        } }
        if (ownPlayback == null) show(state)
    }

    private fun show(state: MediaState) {
        // Повторная отрисовка с тем же состоянием (после обновления списка) не сбрасывает отсчёт позиции.
        if (state !== nowPlaying) nowPlayingAt = SystemClock.elapsedRealtime()
        nowPlaying = state
        panel.setNowPlaying(state)
        player.update(state, nowPlayingAt, coverFor(state))
    }

    private fun play(track: MusicTrack, from: String) {
        val cached = MusicCache.isCached(track.id)
        logInfo(
            "нажатие на трек", "номер" to track.id, "список" to from, "сеть" to host.isOnline,
            "свой проигрыватель" to (cached || track.currentStreamUrl().isNotEmpty()), "из кэша" to cached,
        )
        val chatMusic = com.example.vkweb.media.ChatMusic
        // Тот же трек уже звучит своим проигрывателем — пауза/продолжить.
        if (chatMusic.current?.id == track.id) {
            chatMusic.toggle()
            followOwnPlayback()
            return
        }
        // 4.99.6 (отчёт 22.10 23:33): нажатие на трек, который сейчас у плеера страницы, — пауза/продолжить. Раньше
        // команда «включить» уходила снова: VK ставил паузу, а проверка включения тут же нажимала «играть» — трек
        // нельзя было остановить нажатием.
        if (ownPlayback == null && pageState.trackId.isNotEmpty() && pageState.trackId == track.id) {
            logInfo("нажатие на играющий трек: пауза/продолжить", "играет" to pageState.playing)
            host.bridge.send(if (pageState.playing) com.example.vkweb.media.PlayerCommand.PAUSE else com.example.vkweb.media.PlayerCommand.PLAY)
            return
        }
        // 4.95: трек из кэша играет приложение — из файла, без сети. 4.88: у найденного поиском есть адрес звука, а нажать
        // на него на странице нельзя — тоже играет приложение (очередь — соседние треки на экране, по порядку).
        if (cached || track.currentStreamUrl().isNotEmpty() && host.isOnline) {
            playInApp(track, from)
            return
        }
        if (!host.isOnline) {
            host.showToast(R.string.offline_cannot_play)
            return
        }
        host.bridge.playTrack(track.id, from)
    }

    // ------------------------------------------------------------ свой проигрыватель найденного (4.88)

    /** Последнее состояние музыки страницы: на экран — когда свой проигрыватель замолчит. */
    private var pageState = MediaState.IDLE

    /** Слушатель своего проигрывателя, пока он играет найденное отсюда; null — на экране музыка страницы. */
    private var ownPlayback: ((com.example.vkweb.media.ChatMusic.Snapshot) -> Unit)? = null

    /**
     * Свой проигрыватель с очередью из списка, откуда нажали ([InAppQueue]): соседние треки из кэша и с адресом потока.
     * Своя очередь доиграла — следующий трек списка включается как обычное нажатие (страницей, если приложению его
     * не сыграть): музыка не обрывается на треке из кэша.
     */
    private fun playInApp(track: MusicTrack, from: String) {
        val online = host.isOnline
        val urlFor: (MusicTrack) -> String = { t -> MusicCache.uriFor(t.id) ?: if (online) t.currentStreamUrl() else "" }
        val built = InAppQueue.build(panel.listFor(from), track.id, urlFor)
            ?: InAppQueue.build(listOf(track), track.id, urlFor)
            ?: return host.bridge.playTrack(track.id, from)
        logInfo("трек играет приложение", "в очереди" to built.tracks.size, "с" to built.start + 1)
        val self = java.lang.ref.WeakReference(this)
        com.example.vkweb.media.ChatMusic.play(context, built.tracks, built.start) { lastId ->
            self.get()?.continueAfter(lastId, from)
        }
        followOwnPlayback()
    }

    /** Своя очередь доиграла: следующий трек списка [from] после [lastId] (если он есть). */
    private fun continueAfter(lastId: String, from: String) {
        val next = InAppQueue.nextAfter(panel.listFor(from), lastId) ?: return
        logInfo("своя очередь доиграла: следующий трек списка", "номер" to next.id)
        play(next, from)
    }

    // ------------------------------------------------------------ кэш того, что играет (4.95, 4.99.3)

    /** Трек, уже отправленный в кэш в этом звучании: повторные состояния (пауза, перемотка) его не дёргают. */
    private var cachedNow = ""

    /**
     * Играющий трек (страница или свой проигрыватель) — сразу в кэш, если включён «Кэшировать музыку» и известен адрес
     * потока (4.99.3: без порога прослушивания — кэшируется всё, что заиграло). Адрес может прийти не с первым
     * состоянием (плейлист HLS страница грузит чуть позже): тогда — с тем, где он появился.
     */
    private fun noteListening(trackId: String, playing: Boolean, download: MusicDownload?) {
        if (!host.settings.cacheMusic || trackId.isEmpty() || !playing || download == null) return
        if (trackId == cachedNow || MusicCache.isCached(trackId)) return
        cachedNow = trackId
        logInfo("трек играет: в кэш", "номер" to download.id)
        MusicDownloadStarter(context).cache(download)
    }

    /** Кнопки мини-плеера и плеера: тому, что сейчас на экране. */
    private fun command(command: com.example.vkweb.media.PlayerCommand) {
        if (ownPlayback != null && com.example.vkweb.media.ChatMusic.isActive) com.example.vkweb.media.ChatMusic.onPlayerCommand(command)
        else host.bridge.send(command)
    }

    private fun seek(seconds: Double) {
        if (ownPlayback != null && com.example.vkweb.media.ChatMusic.isActive) com.example.vkweb.media.ChatMusic.seekTo((seconds * 1000).toInt())
        else host.bridge.seek(seconds)
    }

    /**
     * Следить за своим проигрывателем: его трек — в мини-плеере и выделен в списке. Слушатель держит экран слабой
     * ссылкой (проигрыватель живёт дольше окна) и снимает себя сам, когда музыка кончилась или экрана уже нет.
     */
    private fun followOwnPlayback() {
        if (ownPlayback != null) return
        val self = java.lang.ref.WeakReference(this)
        lateinit var listener: (com.example.vkweb.media.ChatMusic.Snapshot) -> Unit
        listener = { snapshot ->
            val overlay = self.get()
            if (overlay == null || overlay.ownPlayback !== listener) {
                com.example.vkweb.media.ChatMusic.removeListener(listener)
            } else {
                overlay.onOwnPlayback(snapshot, listener)
            }
        }
        ownPlayback = listener
        com.example.vkweb.media.ChatMusic.addListener(listener)
    }

    private fun onOwnPlayback(snapshot: com.example.vkweb.media.ChatMusic.Snapshot, listener: (com.example.vkweb.media.ChatMusic.Snapshot) -> Unit) {
        val track = snapshot.track
        if (track == null) {
            // Свой проигрыватель замолчал (кончилась очередь, заиграла страница): снова музыка страницы.
            ownPlayback = null
            com.example.vkweb.media.ChatMusic.removeListener(listener)
            show(pageState)
            return
        }
        val state = snapshot.state
        // Трек не из кэша (адрес https) — прослушанный попадёт в кэш, как и музыка страницы.
        val download = track.url.takeIf { it.startsWith("https://") }?.let { url ->
            val known = panel.trackForId(track.id)
            MusicDownload(track.id, url, track.title, track.artist, known?.album.orEmpty(), known?.tagCoverUrl ?: track.cover)
        }
        noteListening(track.id, state.playing, download)
        show(
            MediaState(
                playing = state.playing || state.loading,
                title = track.title,
                artist = track.artist,
                position = state.positionMs / 1000.0,
                duration = if (state.durationMs > 0) state.durationMs / 1000.0 else track.durationSec.toDouble(),
                cover = MediaState.coverOrEmpty(track.cover),
                trackId = track.id,
            )
        )
    }

    // ------------------------------------------------------------ поиск (4.79)

    /**
     * Поиск [query] (из строки поиска; [artist] — по исполнителю: в списке только его треки). Ищет страница, результаты
     * приходят обычными данными музыки (page = «search»).
     */
    fun search(query: String, artist: String? = null) {
        val q = MusicSearch.clean(query)
        if (q.isEmpty()) return
        if (!host.isOnline) {
            host.showToast(R.string.music_search_offline)
            return
        }
        // В журнал — только вид поиска и длина: что ищет человек, в отчёт не попадает.
        logInfo("поиск", "по исполнителю" to (artist != null), "символов" to q.length)
        player.dismiss()
        startSearch(q, artist)
        // Страница уже на этом поиске (повторный поиск того же): адрес не сменится, но поиск — её.
        searchOnPage = pageQuery?.let { MusicSearch.sameQuery(it, q) } == true
        pendingPageSearch = null
        host.cancel(sendPendingSearch)
        host.bridge.musicSearch(q)
    }

    /**
     * Треки исполнителя из переписки. Экран сразу в поиске. [onMusicPage] — страница уже на музыке: ищет сразу (музыка
     * не прерывается); иначе главное окно открывает раздел музыки, и команда уходит, когда страница откроется.
     */
    fun searchFromChat(artist: String, onMusicPage: Boolean) {
        val name = MusicSearch.clean(artist)
        if (name.isEmpty()) return
        logInfo("треки исполнителя из переписки", "страница музыки уже открыта" to onMusicPage, "символов" to name.length)
        player.dismiss()
        startSearch(name, name)
        searchOnPage = onMusicPage && pageQuery?.let { MusicSearch.sameQuery(it, name) } == true
        host.cancel(sendPendingSearch)
        if (onMusicPage) {
            pendingPageSearch = null
            host.bridge.musicSearch(name)
        } else {
            pendingPageSearch = name
            // Раздел музыки ещё открывается: обычное ожидание начнётся с отправки команды (sendPendingSearch). Здесь —
            // только предел на случай, если страница музыки так и не откроется.
            host.cancel(searchSettle)
            host.postDelayed(searchSettle, PENDING_SEARCH_SETTLE_MS)
        }
    }

    /** В этом поиске «Только исполнитель» уже снят сам (4.88): включил человек снова — его выбор, не снимаем. */
    private var artistFallbackDone = false

    private fun startSearch(query: String, artist: String?) {
        lastFound = null
        artistFallbackDone = false
        searchStartedAt = SystemClock.elapsedRealtime()
        panel.enterSearch(query, artist)
        host.cancel(searchSettle)
        host.postDelayed(searchSettle, SEARCH_SETTLE_MS)
        host.onOverlayChanged()
    }

    /** Закрыть поиск: снова «Мои треки» или плейлисты, страница возвращается к ним. */
    private fun closeSearch() {
        if (!panel.isSearching) return
        logInfo("поиск закрыт")
        forgetSearch()
        onSection(panel.section)
        if (!panel.hasData) loadSaved()
    }

    /** Поиск забыт без команд странице (она уже ушла с него сама или уходит по выбору раздела). */
    private fun forgetSearch() {
        host.cancel(searchSettle)
        host.cancel(sendPendingSearch)
        pendingPageSearch = null
        lastFound = null
        searchOnPage = false
        panel.exitSearch()
        host.onOverlayChanged()
    }

    private fun onFound(payload: MusicPayload) {
        lastFound = payload
        var shown = foundFor(payload)
        // 4.88: «Только исполнитель» отбросил всё, что нашёл VK (имя у треков записано иначе, чем у исполнителя в
        // плеере или меню) — экран показывал пустой список, хотя треки есть. Теперь — всё найденное, переключатель
        // выключен, и об этом коротко сказано.
        val waitedSoFar = SystemClock.elapsedRealtime() - searchStartedAt
        // Только по окончательному ответу (весь список или ожидание вышло): первыми могут прийти подборки страницы.
        val settled = payload.complete || waitedSoFar >= SEARCH_SETTLE_MS
        if (settled && !artistFallbackDone && panel.isArtistOnly && shown.tracks.isEmpty() && payload.tracks.isNotEmpty()) {
            artistFallbackDone = true
            logInfo("поиск по исполнителю: его имя не нашлось у треков — показано всё найденное", "треков" to payload.tracks.size)
            panel.dropArtistOnly()
            host.showToast(R.string.music_search_artist_fallback)
            shown = payload
        }
        val waited = SystemClock.elapsedRealtime() - searchStartedAt
        logDebug("найдено", "треков" to payload.tracks.size, "показано" to shown.tracks.size, "есть ещё" to payload.hasMore, "мс" to waited)
        // Пустой ответ сразу после начала поиска — страница ещё ищет: «ничего не найдено» показывать рано.
        if (payload.tracks.isEmpty() && !payload.complete && waited < SEARCH_SETTLE_MS) return
        panel.updateFound(shown)
        if (payload.tracks.isNotEmpty() || waited >= SEARCH_SETTLE_MS) settleSearch()
        show(nowPlaying)
    }

    /** Ответ страницы, отобранный по исполнителю (если включено «Только …»). */
    private fun foundFor(payload: MusicPayload): MusicPayload {
        val artist = panel.searchArtist?.takeIf { panel.isArtistOnly } ?: return payload
        return payload.copy(tracks = MusicSearch.byArtist(payload.tracks, artist))
    }

    private fun onArtistOnly(checked: Boolean) {
        logInfo("поиск: только исполнитель", "включено" to checked)
        val last = lastFound ?: return
        panel.replaceFound(foundFor(last))
        show(nowPlaying)
    }

    /** Ждать результатов больше нечего: пустой список — «ничего не найдено»; потягивание и ожидание закончены. */
    private fun settleSearch() {
        host.cancel(searchSettle)
        if (!panel.isSearching) return
        lastFound?.let { if (panel.tracks.isEmpty()) panel.updateFound(foundFor(it)) }
        panel.settleSearch()
        onFreshData()
    }

    // ------------------------------------------------------------ «добавить к себе» (4.45)

    private fun addTrack(track: MusicTrack) = addTrackId(track.id, fromPlayer = false)

    private fun addTrackId(id: String, fromPlayer: Boolean) {
        logInfo("добавить трек к себе", "номер" to id, "откуда" to if (fromPlayer) "плеер" else "список", "сеть" to host.isOnline)
        if (!host.isOnline) {
            panel.setTrackAdded(id, false)
            player.onAddResult(id, false)
            host.showToast(R.string.offline_cannot_play)
            return
        }
        // Кнопка у этого трека в списке плейлиста тоже прячется (и наоборот — из списка прячется в плеере).
        panel.setTrackAdded(id, true)
        host.bridge.musicAdd(id)
    }

    private fun followPlaylist(entry: PlaylistEntry) {
        logInfo("добавить плейлист к себе", "номер" to entry.id, "сеть" to host.isOnline)
        if (!host.isOnline) {
            panel.setFollowable(true)
            host.showToast(R.string.offline_cannot_play)
            return
        }
        host.bridge.musicFollow(entry.id, entry.url)
    }

    private fun onAction(result: MusicAction) {
        logInfo("добавление к себе", "что" to result.action, "принято" to result.ok, "причина" to result.reason.ifEmpty { null })
        when (result.action) {
            MusicAction.ADD -> {
                panel.setTrackAdded(result.id, result.ok)
                player.onAddResult(result.id, result.ok)
                host.showToast(if (result.ok) R.string.music_added_track else R.string.music_add_failed)
                // Добавленный в «Мою музыку» — в кэш (4.99.4).
                if (result.ok) panel.trackForId(result.id)?.let { autoCacheMy(listOf(it)) }
            }
            MusicAction.FOLLOW -> {
                if (!result.ok) panel.setFollowable(panel.openedPlaylist?.id == result.id)
                host.showToast(if (result.ok) R.string.music_followed_playlist else R.string.music_add_failed)
            }
        }
    }

    private companion object {
        /** Сколько ждать результатов поиска, прежде чем пустой список станет «ничего не найдено». */
        const val SEARCH_SETTLE_MS = 7_000L

        /** Страница музыки открылась ради поиска из переписки: сколько дать ей прийти в себя перед командой. */
        const val PAGE_SEARCH_DELAY_MS = 1_200L
        /** Поиск из переписки ждёт открытия раздела музыки не дольше этого (4.88). */
        const val PENDING_SEARCH_SETTLE_MS = 25_000L
    }

    /** Обложка играющего трека: из списка треков раздела, иначе — что дала страница (4.0). */
    fun coverFor(state: MediaState): String? =
        panel.trackFor(state.title, state.artist)?.coverUrl?.takeIf { it.isNotEmpty() } ?: state.cover.ifEmpty { null }
}
