package com.example.vkweb.ui

import android.view.View
import android.view.inputmethod.EditorInfo
import android.widget.EditText
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import com.example.vkweb.R
import com.example.vkweb.media.MediaState
import com.example.vkweb.media.MusicPayload
import com.example.vkweb.media.MusicSearch
import com.example.vkweb.media.MusicSection
import com.example.vkweb.media.MusicTrack
import com.example.vkweb.media.PlayerCommand
import com.example.vkweb.media.PlaylistEntry
import com.example.vkweb.media.PlaylistsPayload
import com.example.vkweb.offline.ImageCache
import com.google.android.material.button.MaterialButtonToggleGroup
import com.google.android.material.chip.Chip
import com.google.android.material.dialog.MaterialAlertDialogBuilder

/**
 * Свой экран музыки поверх страницы (layout/panel_music.xml). Сверху переключатель «Музыка / Плейлисты»:
 *  - «Музыка» — «Мои треки»;
 *  - «Плейлисты» — список плейлистов; нажатие на плейлист открывает его треки (кнопка «к плейлистам» — назад).
 * Над переключателем — поиск (4.79): результаты поиска самой страницы заменяют списки, пока поиск не закрыт («назад» в
 * строке поиска). Долгое нажатие на трек — «Треки исполнителя»: тот же поиск, но в списке только треки исполнителя.
 * Нажатие на трек — команда странице; звук играет сама страница. Прокрутка до конца просит догрузку.
 * Сохранённое показывается сразу; свежее встаёт поверх него без скачков ([ListUpdates]).
 */
class MusicPanel(
    root: View,
    /** [from] — из какого списка: [FROM_MY], [FROM_PLAYLIST] или [FROM_SEARCH]. */
    onPlay: (track: MusicTrack, from: String) -> Unit,
    onLoadMore: (target: String) -> Unit,
    onPlayerCommand: (PlayerCommand) -> Unit,
    onOpenPlayer: () -> Unit,
    /** Человек переключил «Музыка / Плейлисты». */
    private val onSection: (MusicSection) -> Unit,
    /** Человек нажал на плейлист в списке. */
    private val onOpenPlaylist: (PlaylistEntry) -> Unit,
    /** Человек вернулся от треков плейлиста к списку плейлистов. */
    private val onClosePlaylist: () -> Unit,
    /** «Добавить к себе» у трека плейлиста (4.45). */
    onAddTrack: (MusicTrack) -> Unit = {},
    /** Кэш (4.97): трек — в хранилище («Музыка/VkWebLite») или из него; весь список; остановить; идёт ли сейчас. */
    private val caching: Caching = Caching(),
    /** «Добавить к себе» открытый плейлист (4.45). */
    private val onFollowPlaylist: (PlaylistEntry) -> Unit = {},
    /** Человек ищет (4.79): [artist] — поиск по исполнителю (в списке только его треки), null — обычный запрос. */
    private val onSearch: (query: String, artist: String?) -> Unit = { _, _ -> },
    /** Поиск закрыт: вернуть списки, которые были до него. */
    private val onCloseSearch: () -> Unit = {},
    /** Переключатель «Только этот исполнитель». */
    private val onArtistOnly: (Boolean) -> Unit = {},
) : OverlayPanel {

    /** Мини-плеер внизу: что играет и пауза; нажатие открывает плеер с перемоткой. */
    private val bar: View = root.findViewById(R.id.music_player_bar)
    private val barCover: ImageView = root.findViewById(R.id.music_player_cover)
    private val barTitle: TextView = root.findViewById(R.id.music_player_title)
    private val barArtist: TextView = root.findViewById(R.id.music_player_artist)
    private val barToggle: ImageButton = root.findViewById(R.id.music_player_toggle)
    private val saveStatus: View = root.findViewById(R.id.music_save_status)
    private val saveStatusText: TextView = root.findViewById(R.id.music_save_status_text)
    private val saveStop: View = root.findViewById(R.id.music_save_stop)
    private val saveDetails: View = root.findViewById(R.id.music_save_details)
    private val saveDismiss: View = root.findViewById(R.id.music_save_dismiss)
    private var saveSummary: String? = null
    private var unsavedTracks: List<MusicTrack> = emptyList()
    private var saving = false
    private var dismissSaveResult = true
    private var progressListening = false
    private var stopRequested = false
    private val saveProgress: (com.example.vkweb.media.MusicDownloadStarter.Progress) -> Unit = { renderSaveProgress(it) }

    private fun listenToSaveProgress(listen: Boolean) {
        if (listen == progressListening) return
        progressListening = listen
        if (listen) {
            com.example.vkweb.media.MusicDownloadStarter.addProgressListener(saveProgress)
            renderSaveProgress(com.example.vkweb.media.MusicDownloadStarter.progress())
        } else com.example.vkweb.media.MusicDownloadStarter.removeProgressListener(saveProgress)
    }

    private fun renderSaveProgress(progress: com.example.vkweb.media.MusicDownloadStarter.Progress) {
        val active = progress.queued > 0
        if (active && !saving) { saveSummary = null; unsavedTracks = emptyList(); dismissSaveResult = false; stopRequested = false }
        saving = active
        saveStatus.visibility = if (active || !dismissSaveResult) View.VISIBLE else View.GONE
        saveStatusText.text = if (active) saveStatusText.context.getString(R.string.music_save_progress, progress.saved, progress.queued)
            else saveSummary ?: saveStatusText.context.getString(R.string.music_save_finished)
        if (active && stopRequested) saveStatusText.append("\n" + saveStatusText.context.getString(R.string.music_save_stopping))
        saveStop.isEnabled = !stopRequested
        saveStop.visibility = if (active) View.VISIBLE else View.GONE
        saveDetails.visibility = if (!active && unsavedTracks.isNotEmpty()) View.VISIBLE else View.GONE
        saveDismiss.visibility = if (active) View.GONE else View.VISIBLE
    }

    /** Batch completion includes real totals; the global live counter resets when the queue becomes empty. */
    fun showCacheResult(text: String, tracks: List<MusicTrack> = emptyList()) {
        saveSummary = text
        unsavedTracks = tracks.filterNot { com.example.vkweb.media.MusicCache.isCached(it.id) }
        dismissSaveResult = false
        val progress = com.example.vkweb.media.MusicDownloadStarter.progress()
        saving = progress.queued > 0
        renderSaveProgress(progress)
    }
    private val images = ImageCache.get(root.context)
    private val barCoverPx = Views.dp(root, BAR_COVER_DP)
    private var barPlaying = false

    override val frame = PanelFrame(root)
    private val switch: MaterialButtonToggleGroup = root.findViewById(R.id.music_switch)
    private val header: View = root.findViewById(R.id.music_playlist_header)
    private val headerTitle: TextView = root.findViewById(R.id.music_playlist_title)
    private val followButton: View = root.findViewById(R.id.music_playlist_follow)

    /** Открытый плейлист можно добавить к себе (чужой, ещё не добавлен) — по сведениям страницы. */
    private var canFollow = false

    private val searchInput: EditText = root.findViewById(R.id.music_search_input)
    private val searchBack: View = root.findViewById(R.id.music_search_back)
    private val artistOnly: Chip = root.findViewById(R.id.music_search_artist_only)

    private val my = TrackListView(
        root.findViewById<RecyclerView>(R.id.music_list), { onPlay(it, FROM_MY) }, { onLoadMore(FROM_MY) }, "мои треки",
        onLongPress = { showTrackMenu(it, FROM_MY) },
    )
    private val opened = TrackListView(
        root.findViewById<RecyclerView>(R.id.playlist_tracks_list), { onPlay(it, FROM_PLAYLIST) }, { onLoadMore(FROM_PLAYLIST) }, "треки плейлиста",
        onAdd = onAddTrack, onLongPress = { showTrackMenu(it, FROM_PLAYLIST) },
    )
    private val found = TrackListView(
        root.findViewById<RecyclerView>(R.id.music_search_list), { onPlay(it, FROM_SEARCH) }, { onLoadMore(FROM_SEARCH) }, "найденные треки",
        onAdd = onAddTrack, onLongPress = { showTrackMenu(it, FROM_SEARCH) },
    )

    /** Открытый поиск: запрос, результаты которого показаны (null — поиск закрыт). */
    var searchQuery: String? = null
        private set

    /** Поиск по исполнителю: его имя (null — обычный запрос). */
    var searchArtist: String? = null
        private set

    /** Ждать результатов поиска больше нечего: пустой список — «ничего не найдено», а не загрузка. */
    private var searchSettled = false

    /** Переключатель «Только …» ставится программно (новый поиск): не считать это выбором человека. */
    private var syncingChip = false

    val isSearching: Boolean get() = searchQuery != null

    /** Включён «Только этот исполнитель» (у поиска по исполнителю). */
    val isArtistOnly: Boolean get() = searchArtist != null && artistOnly.isChecked

    private val playlists = PlaylistListView(
        root.findViewById<RecyclerView>(R.id.playlists_list), { open(it) }, { onLoadMore("playlists") },
    )

    /** Что показано: «Мои треки» или плейлисты. */
    var section: MusicSection = MusicSection.MUSIC
        private set

    /** Открытый плейлист (его треки показаны вместо списка плейлистов); null — список плейлистов. */
    var openedPlaylist: PlaylistEntry? = null
        private set

    /** Идёт программная смена переключателя: не считать её выбором человека. */
    private var syncingSwitch = false

    /** Треки, которые видит человек: «Мои», открытого плейлиста или найденные. */
    val tracks: List<MusicTrack>
        get() = when {
            isSearching -> found.current
            section == MusicSection.PLAYLISTS && openedPlaylist != null -> opened.current
            else -> my.current
        }

    override val isShown: Boolean get() = frame.isShown

    /** Есть ли данные у того списка, который сейчас на экране. */
    override val hasData: Boolean
        get() = when {
            isSearching -> found.current.isNotEmpty()
            section == MusicSection.MUSIC -> my.current.isNotEmpty()
            openedPlaylist != null -> opened.current.isNotEmpty()
            else -> playlists.current.isNotEmpty()
        }

    override val isShowingSaved: Boolean
        get() = when {
            isSearching -> false
            section == MusicSection.MUSIC -> my.isShowingSaved
            openedPlaylist != null -> opened.isShowingSaved
            else -> playlists.isShowingSaved
        }

    init {
        saveStop.setOnClickListener {
            stopRequested = true
            caching.onStop()
            renderSaveProgress(com.example.vkweb.media.MusicDownloadStarter.progress())
        }
        saveDismiss.setOnClickListener { dismissSaveResult = true; renderSaveProgress(com.example.vkweb.media.MusicDownloadStarter.progress()) }
        saveDetails.setOnClickListener {
            val missing = unsavedTracks.filterNot { com.example.vkweb.media.MusicCache.isCached(it.id) }
            MaterialAlertDialogBuilder(root.context).setTitle(R.string.music_save_remaining)
                .setMessage(if (missing.isEmpty()) root.context.getString(R.string.music_cache_list_done_already)
                    else missing.joinToString("\n") { listOf(it.artist, it.title).filter(String::isNotBlank).joinToString(" — ") })
                .setPositiveButton(R.string.close, null).show()
        }
        root.addOnAttachStateChangeListener(object : View.OnAttachStateChangeListener {
            override fun onViewAttachedToWindow(view: View) { listenToSaveProgress(true) }
            override fun onViewDetachedFromWindow(view: View) { listenToSaveProgress(false) }
        })
        if (root.isAttachedToWindow) listenToSaveProgress(true)
        // Значки «в хранилище»: слушатель держит панель слабой ссылкой и снимает себя сам, когда экрана уже нет.
        val self = java.lang.ref.WeakReference(this)
        lateinit var cacheListener: () -> Unit
        cacheListener = { self.get()?.onCacheChanged() ?: com.example.vkweb.media.MusicCache.removeListener(cacheListener) }
        com.example.vkweb.media.MusicCache.addListener(cacheListener)
        bar.setOnClickListener { onOpenPlayer() }
        barToggle.setOnClickListener { onPlayerCommand(if (barPlaying) PlayerCommand.PAUSE else PlayerCommand.PLAY) }
        switch.addOnButtonCheckedListener { _, checkedId, isChecked ->
            if (!isChecked || syncingSwitch) return@addOnButtonCheckedListener
            val chosen = if (checkedId == R.id.music_switch_playlists) MusicSection.PLAYLISTS else MusicSection.MUSIC
            if (chosen == section) return@addOnButtonCheckedListener
            section = chosen
            applyVisibility()
            onSection(chosen)
        }
        root.findViewById<View>(R.id.music_playlist_back).setOnClickListener { closePlaylist() }
        followButton.setOnClickListener {
            val entry = openedPlaylist ?: return@setOnClickListener
            setFollowable(false) // повторное нажатие до ответа не уходит
            onFollowPlaylist(entry)
        }
        searchInput.setOnEditorActionListener { _, actionId, event ->
            val enter = event != null && event.keyCode == android.view.KeyEvent.KEYCODE_ENTER && event.action == android.view.KeyEvent.ACTION_DOWN
            if (actionId != EditorInfo.IME_ACTION_SEARCH && !enter) return@setOnEditorActionListener false
            val query = MusicSearch.clean(searchInput.text?.toString())
            if (query.isNotEmpty()) {
                Views.hideKeyboard(searchInput)
                onSearch(query, null)
            }
            true
        }
        searchBack.setOnClickListener { onCloseSearch() }
        artistOnly.setOnCheckedChangeListener { _, checked -> if (searchArtist != null && !syncingChip) onArtistOnly(checked) }
        applyVisibility()
    }

    // ------------------------------------------------------------ поиск (4.79)

    /**
     * Открыть поиск [query]: результаты заменяют списки, пока не придут свежие. [artist] — поиск по исполнителю: в списке
     * только его треки (переключатель «Только …» включён).
     */
    fun enterSearch(query: String, artist: String?) {
        searchQuery = query
        searchArtist = artist
        searchSettled = false
        found.clear()
        if (searchInput.text?.toString() != query) searchInput.setText(query)
        searchInput.clearFocus()
        if (artist != null) {
            artistOnly.text = artistOnly.context.getString(R.string.music_search_artist_only, artist)
            syncingChip = true
            artistOnly.isChecked = true
            syncingChip = false
        }
        applyVisibility()
    }

    /** Найденные треки в том порядке, в каком они на экране (4.88: очередь своего проигрывателя). */
    val foundTracks: List<MusicTrack> get() = found.current

    /**
     * Снять «Только …» без отклика [onArtistOnly] (4.88): у исполнителя ничего не отобралось, а VK что-то нашёл —
     * показывается всё найденное, переключатель честно выключен (включить снова можно).
     */
    fun dropArtistOnly() {
        if (!artistOnly.isChecked) return
        syncingChip = true
        artistOnly.isChecked = false
        syncingChip = false
    }

    /** Поиск закрыт: снова «Мои треки» или плейлисты — как было до него. */
    fun exitSearch() {
        if (!isSearching) return
        searchQuery = null
        searchArtist = null
        searchSettled = false
        found.clear()
        searchInput.setText("")
        searchInput.clearFocus()
        Views.hideKeyboard(searchInput)
        applyVisibility()
    }

    /** Найденные треки (уже отобранные по исполнителю, если нужно). */
    fun updateFound(payload: MusicPayload): List<MusicTrack> {
        val shown = found.update(payload)
        if (frame.isShown) refreshChrome()
        return shown
    }

    /** Список найденного заново (переключили «Только исполнитель»): прежний не сливается с новым. */
    fun replaceFound(payload: MusicPayload): List<MusicTrack> {
        found.clear()
        return updateFound(payload)
    }

    /** Ждать больше нечего: пустой список найденного — «ничего не найдено». */
    fun settleSearch() {
        if (searchSettled) return
        searchSettled = true
        if (frame.isShown) refreshChrome()
    }

    /**
     * Долгое нажатие на трек: «Треки исполнителя: …» — по пункту на каждого исполнителя трека («A feat. B» — два).
     * Нет исполнителя — меню нет.
     */
    private fun showTrackMenu(track: MusicTrack, from: String) {
        val artists = MusicSearch.artistsOf(track.artist)
        val context = searchInput.context
        val labels = mutableListOf<String>()
        val actions = mutableListOf<() -> Unit>()
        if (com.example.vkweb.media.MusicCache.isCached(track.id)) {
            labels += context.getString(R.string.music_cache_remove)
            actions += { caching.onRemove(track) }
        } else {
            labels += context.getString(R.string.music_cache_track)
            actions += { caching.onTrack(track) }
        }
        val list = listFor(from)
        val left = list.count { !com.example.vkweb.media.MusicCache.isCached(it.id) }
        if (left > 0) {
            labels += context.getString(
                if (from == FROM_PLAYLIST) R.string.music_cache_playlist else R.string.music_cache_list, left,
            )
            actions += { caching.onList(from) }
        }
        if (caching.isActive()) {
            labels += context.getString(R.string.music_cache_stop)
            actions += { caching.onStop() }
        }
        labels += context.getString(R.string.music_cache_open_folder)
        actions += { caching.onOpenFolder() }
        artists.forEach { artist ->
            labels += context.getString(R.string.music_artist_tracks, artist)
            actions += { findArtist(artist) }
        }
        MaterialAlertDialogBuilder(context)
            .setTitle(listOf(track.artist, track.title).filter { it.isNotEmpty() }.joinToString(" — "))
            .setItems(labels.toTypedArray()) { _, which -> actions.getOrNull(which)?.invoke() }
            .show()
    }

    /** Выбор исполнителя: «Треки исполнителя: …» по пункту на каждого; [title] — заголовок меню (null — без него). */
    private fun artistMenu(artists: List<String>, title: String?) {
        val context = searchInput.context
        MaterialAlertDialogBuilder(context)
            .setTitle(title)
            .setItems(artists.map { context.getString(R.string.music_artist_tracks, it) }.toTypedArray()) { _, which ->
                findArtist(artists[which])
            }
            .show()
    }

    /** Треки исполнителя [artist]: поиск по его имени. */
    fun findArtist(artist: String) {
        val name = MusicSearch.clean(artist)
        if (name.isNotEmpty()) onSearch(name, name)
    }

    /**
     * Исполнитель из плеера: один — сразу его треки; несколько («A feat. B») — выбор, чьи.
     */
    fun chooseArtist(artist: String) {
        val artists = MusicSearch.artistsOf(artist)
        when (artists.size) {
            0 -> Unit
            1 -> findArtist(artists[0])
            else -> artistMenu(artists, null)
        }
    }

    fun show() {
        frame.show()
        renderSaveProgress(com.example.vkweb.media.MusicDownloadStarter.progress())
        refreshChrome()
    }

    override fun hide() = frame.hide()

    override fun clear() {
        saveSummary = null
        unsavedTracks = emptyList()
        dismissSaveResult = true
        renderSaveProgress(com.example.vkweb.media.MusicDownloadStarter.progress())
        my.clear()
        opened.clear()
        found.clear()
        playlists.clear()
        if (frame.isShown) refreshChrome()
    }

    override fun setBanner(text: Int?) {
        frame.setBanner(text)
        if (text != null && !hasData) frame.showNothingSaved()
    }

    // ------------------------------------------------------------ разделы и плейлисты

    /** Открыть плейлист: его треки заменяют список плейлистов, пока не придут свежие. */
    fun open(entry: PlaylistEntry) {
        openedPlaylist = entry
        canFollow = false
        opened.clear()
        section = MusicSection.PLAYLISTS
        applyVisibility()
        onOpenPlaylist(entry)
    }

    /** Показать плейлист, который страница открыла сама: без вызова [onOpenPlaylist] (переход уже идёт). */
    fun showOpenedByPage(entry: PlaylistEntry) {
        openedPlaylist = entry
        canFollow = false
        opened.clear()
        section = MusicSection.PLAYLISTS
        applyVisibility()
    }

    /** От треков плейлиста назад к списку плейлистов. */
    fun closePlaylist() {
        if (openedPlaylist == null) return
        openedPlaylist = null
        opened.clear()
        applyVisibility()
        onClosePlaylist()
    }

    private fun applyVisibility() {
        val searching = isSearching
        val music = section == MusicSection.MUSIC
        val inPlaylist = !music && openedPlaylist != null
        my.isVisible = music && !searching
        playlists.isVisible = !music && !inPlaylist && !searching
        opened.isVisible = inPlaylist && !searching
        found.isVisible = searching
        switch.visibility = if (searching) View.GONE else View.VISIBLE
        searchBack.visibility = if (searching) View.VISIBLE else View.GONE
        artistOnly.visibility = if (searching && searchArtist != null) View.VISIBLE else View.GONE
        header.visibility = if (inPlaylist && !searching) View.VISIBLE else View.GONE
        followButton.visibility = if (inPlaylist && canFollow && !searching) View.VISIBLE else View.GONE
        if (inPlaylist) {
            headerTitle.text = openedPlaylist?.title?.ifEmpty { null } ?: header.context.getString(R.string.playlist_default_title)
        }
        val checked = if (music) R.id.music_switch_music else R.id.music_switch_playlists
        if (switch.checkedButtonId != checked) {
            syncingSwitch = true
            switch.check(checked)
            syncingSwitch = false
        }
        if (frame.isShown) refreshChrome()
    }

    /** Индикатор, «пусто» или содержимое — по тому списку, который на экране. */
    private fun refreshChrome() {
        val music = section == MusicSection.MUSIC
        val inPlaylist = !music && openedPlaylist != null
        val empty: Boolean
        val loaded: Boolean
        val emptyText: Int
        var emptyBody: Int? = null
        val searching = isSearching
        when {
            searching -> {
                empty = found.current.isEmpty(); loaded = searchSettled
                if (isArtistOnly) {
                    emptyText = R.string.music_search_artist_empty; emptyBody = R.string.music_search_artist_empty_body
                } else {
                    emptyText = R.string.music_search_empty
                }
            }
            music -> { empty = my.current.isEmpty(); loaded = my.isLoaded; emptyText = R.string.music_empty }
            inPlaylist -> { empty = opened.current.isEmpty(); loaded = opened.isLoaded; emptyText = R.string.playlist_empty }
            else -> {
                empty = playlists.current.isEmpty(); loaded = playlists.isLoaded
                emptyText = R.string.playlists_none_title; emptyBody = R.string.playlists_none_body
            }
        }
        when {
            !empty -> frame.showContent()
            loaded -> frame.showEmptyList(emptyText, R.drawable.ic_section_music, emptyBody)
            else -> frame.showLoading()
        }
    }

    // ------------------------------------------------------------ данные страницы

    /** Свежие «Мои треки». @return показанный теперь список (список адаптера обновляется позже). */
    fun updateMy(payload: MusicPayload): List<MusicTrack> {
        val shown = my.update(payload)
        if (frame.isShown) refreshChrome()
        return shown
    }

    /** Свежие треки открытого плейлиста. */
    fun updateOpened(payload: MusicPayload): List<MusicTrack> {
        val shown = opened.update(payload)
        // Чей плейлист и добавлен ли — от страницы; сведения о другом плейлисте кнопку не трогают.
        payload.playlist?.takeIf { it.id == openedPlaylist?.id }?.let { setFollowable(it.canFollow) }
        if (frame.isShown) refreshChrome()
        return shown
    }

    /** Свежий список плейлистов. */
    fun updatePlaylists(payload: PlaylistsPayload): List<PlaylistEntry> {
        val shown = playlists.update(payload)
        if (frame.isShown) refreshChrome()
        return shown
    }

    /** Кнопка «Добавить к себе» у открытого плейлиста (4.45). */
    fun setFollowable(value: Boolean) {
        canFollow = value
        followButton.visibility = if (value && section == MusicSection.PLAYLISTS && openedPlaylist != null) View.VISIBLE else View.GONE
    }

    /** «Добавить к себе» у трека плейлиста: кнопка прячется сразу или возвращается после ошибки. */
    fun setTrackAdded(id: String, added: Boolean) {
        opened.setAdded(id, added)
        found.setAdded(id, added)
    }

    /** Сохранённые «Мои треки»: если свежие уже показаны — только дополняют снизу недостающим. */
    fun showSaved(payload: MusicPayload) {
        my.showSaved(payload)
        if (frame.isShown) refreshChrome()
    }

    fun showSavedPlaylists(items: List<PlaylistEntry>) {
        playlists.showSaved(items)
        if (frame.isShown) refreshChrome()
    }

    /** Трек из показанных списков, который играет страница (по названию и исполнителю): для выделения и обложки. */
    fun trackFor(title: String, artist: String): MusicTrack? {
        val lists = when {
            isSearching -> listOf(found, my, opened)
            section == MusicSection.PLAYLISTS && openedPlaylist != null -> listOf(opened, my, found)
            else -> listOf(my, opened, found)
        }
        return lists.firstNotNullOfOrNull { it.trackFor(title, artist) }
    }

    /** Список, из которого включают с [from] ([FROM_MY], [FROM_PLAYLIST], [FROM_SEARCH]): очередь своего проигрывателя. */
    fun listFor(from: String): List<MusicTrack> = when (from) {
        FROM_PLAYLIST -> opened.current
        FROM_SEARCH -> found.current
        else -> my.current
    }

    /** Кэш изменился: значки «в хранилище» у треков. */
    private fun onCacheChanged() {
        my.refreshCached()
        opened.refreshCached()
        found.refreshCached()
    }

    /** Действия кэша из меню трека (4.97). */
    class Caching(
        val onTrack: (MusicTrack) -> Unit = {},
        val onRemove: (MusicTrack) -> Unit = {},
        val onList: (String) -> Unit = {},
        val onStop: () -> Unit = {},
        val isActive: () -> Boolean = { false },
        /** Открыть папку музыки в «Файлах» (4.99). */
        val onOpenFolder: () -> Unit = {},
    )

    fun trackForId(id: String): MusicTrack? {
        val lists = when {
            isSearching -> listOf(found, my, opened)
            section == MusicSection.PLAYLISTS && openedPlaylist != null -> listOf(opened, my, found)
            else -> listOf(my, opened, found)
        }
        return lists.firstNotNullOfOrNull { it.trackForId(id) }
    }

    /** Состояние плеера страницы: выделить текущий трек (и значок при воспроизведении), показать мини-плеер. */
    fun setNowPlaying(state: MediaState) {
        val match = state.trackId.takeIf { it.isNotEmpty() }?.let(::trackForId)
            ?: trackFor(state.title, state.artist)
        val currentId = if (!state.ended) match?.id else null
        my.setCurrentTrack(currentId, state.playing && currentId != null)
        opened.setCurrentTrack(currentId, state.playing && currentId != null)
        found.setCurrentTrack(currentId, state.playing && currentId != null)
        val visible = state.title.isNotEmpty() && !state.ended
        bar.visibility = if (visible) View.VISIBLE else View.GONE
        // Плашка «нет сети» встаёт над мини-плеером, а не поверх него.
        frame.liftBanner(if (visible) Views.dp(bar, BAR_HEIGHT_DP) else 0)
        if (!visible) return
        barPlaying = state.playing
        barTitle.text = state.title
        barArtist.text = state.artist
        barArtist.visibility = if (state.artist.isEmpty()) View.GONE else View.VISIBLE
        barToggle.setImageResource(if (state.playing) R.drawable.ic_player_pause else R.drawable.ic_player_play)
        barToggle.contentDescription = bar.context.getString(if (state.playing) R.string.player_pause else R.string.player_play)
        images.load(barCover, match?.coverUrl, barCoverPx) { Views.musicPlaceholder(bar) }
    }

    companion object {
        /** Откуда включают трек (и что догружать) — так их понимает страница (assets/vk_lite/50_music.js). */
        const val FROM_MY = "my"
        const val FROM_PLAYLIST = "playlist"
        const val FROM_SEARCH = "search"
        private const val BAR_HEIGHT_DP = 64
        private const val BAR_COVER_DP = 40
    }
}
