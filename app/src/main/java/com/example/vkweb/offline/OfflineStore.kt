package com.example.vkweb.offline

import com.example.vkweb.Background
import android.content.Context
import android.os.Handler
import android.os.Looper
import com.example.vkweb.diag.AppLog
import com.example.vkweb.diag.LogTag
import com.example.vkweb.media.MusicPayload
import com.example.vkweb.media.MusicTrack
import com.example.vkweb.media.PlaylistEntry
import com.example.vkweb.media.PlaylistsPayload
import com.example.vkweb.messages.ChatCard
import com.example.vkweb.messages.ChatMessage
import com.example.vkweb.messages.ChatPhoto
import com.example.vkweb.messages.ChatVideo
import com.example.vkweb.messages.ChatVoice
import com.example.vkweb.messages.DialogEntry
import com.example.vkweb.messages.ReaderPayload
import com.example.vkweb.outbox.OutboxItem
import com.example.vkweb.owner.OwnerPayload
import com.example.vkweb.people.PeopleKind
import com.example.vkweb.people.PeoplePayload
import com.example.vkweb.sync.SyncState
import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.security.MessageDigest

/**
 * Сохранённые данные для работы без сети: список диалогов, переписки, «Мои треки», друзья и сообщества,
 * страницы людей и сообществ. Удаляются по категориям ([StorageCategory]) из настроек.
 * Каждый раз, когда страница присылает свежие данные, они перезаписываются здесь,
 * поэтому без сети показывается последнее, что приложение видело.
 * Файлы лежат во внутренней памяти приложения (не в общей) и удаляются вместе с ним.
 */
class OfflineStore private constructor(context: Context) {

    private val root = File(context.filesDir, DIR)
    private val histories = File(root, "history")
    private val owners = File(root, "owners")
    private val io = Background.serial("offline-store")
    private val main = Handler(Looper.getMainLooper())

    /**
     * Что последним записано в каждый файл (хэш текста). Страница присылает одно и то же часто
     * (повторная отправка, догрузка без новых строк) — такие записи пропускаются. Только из потока [io].
     */
    private val lastWritten = HashMap<String, String>()

    init {
        // Папки создаются в потоке записи (он один, поэтому раньше любой записи), а не в главном при первом обращении.
        io.execute {
            histories.mkdirs()
            owners.mkdirs()
        }
    }

    // ------------------------------------------------------------ диалоги

    fun saveDialogs(items: List<DialogEntry>) = write(File(root, DIALOGS_FILE)) {
        JSONArray().apply { items.forEach { put(it.toJson()) } }.toString()
    }

    fun loadDialogs(result: (List<DialogEntry>?) -> Unit) = read(File(root, DIALOGS_FILE), result) { raw ->
        val array = JSONArray(raw)
        (0 until array.length()).mapNotNull { array.optJSONObject(it)?.toDialog() }
    }

    // ------------------------------------------------------------ переписки

    fun saveHistory(peerId: String, title: String, items: List<ChatMessage>) = write(historyFile(peerId)) {
        trimHistoriesSometimes()
        JSONObject()
            .put("title", title)
            .put("items", JSONArray().apply { items.takeLast(MAX_SAVED_MESSAGES).forEach { put(it.toJson()) } })
            .toString()
    }

    fun loadHistory(peerId: String, result: (SavedHistory?) -> Unit) = read(historyFile(peerId), result) { raw ->
        val json = JSONObject(raw)
        val array = json.optJSONArray("items") ?: JSONArray()
        SavedHistory(
            json.optString("title"), (0 until array.length()).mapNotNull { array.optJSONObject(it)?.toMessage() },
        )
    }

    data class SavedHistory(val title: String, val items: List<ChatMessage>)

    // ------------------------------------------------------------ музыка

    fun saveMusic(payload: MusicPayload) = write(File(root, MUSIC_FILE)) {
        JSONObject()
            .put("hasMore", payload.hasMore)
            .put("kind", if (payload.isMyTracks) "my" else "list")
            .put("key", payload.listKey)
            .put("items", JSONArray().apply { payload.tracks.forEach { put(it.toJson()) } })
            .toString()
    }

    fun loadMusic(result: (MusicPayload?) -> Unit) = read(File(root, MUSIC_FILE), result) { raw ->
        MusicPayload.fromJson(JSONObject(raw)).takeIf { it.tracks.isNotEmpty() }
    }

    // ------------------------------------------------------------ плейлисты

    fun savePlaylists(items: List<PlaylistEntry>) = write(File(root, PLAYLISTS_FILE)) {
        JSONObject().put("items", JSONArray().apply { items.forEach { put(it.toJson()) } }).toString()
    }

    fun loadPlaylists(result: (List<PlaylistEntry>?) -> Unit) = read(File(root, PLAYLISTS_FILE), result) { raw ->
        PlaylistsPayload.parseItems(JSONObject(raw).optJSONArray("items")).takeIf { it.isNotEmpty() }
    }

    // ------------------------------------------------------------ очередь неотправленных и проверка сообщений

    fun saveOutbox(items: List<OutboxItem>, completed: ((Boolean) -> Unit)? = null) =
        write(File(root, OUTBOX_FILE), completed) { OutboxItem.listToJson(items) }

    fun loadOutbox(result: (List<OutboxItem>?) -> Unit) = read(File(root, OUTBOX_FILE), result) { OutboxItem.listFromJson(it) }

    fun saveSyncState(state: SyncState) = write(File(root, SYNC_FILE)) { state.toJson() }

    fun loadSyncState(result: (SyncState?) -> Unit) = read(File(root, SYNC_FILE), result) { SyncState.fromJson(it) }

    // ------------------------------------------------------------ друзья и сообщества

    /** Сохраняется только свой список (не друзья другого человека). */
    fun savePeople(payload: PeoplePayload) {
        if (!payload.own) return
        write(peopleFile(payload.kind)) { payload.toJson().toString() }
    }

    fun loadPeople(kind: PeopleKind, result: (PeoplePayload?) -> Unit) = read(peopleFile(kind), result) { raw ->
        PeoplePayload.fromJson(JSONObject(raw))?.takeIf { it.kind == kind && !it.isEmpty }
    }

    private fun peopleFile(kind: PeopleKind) = File(root, "people_${kind.wire}.json")

    // ------------------------------------------------------------ страницы людей и сообществ

    /**
     * Страница сохраняется под двумя именами: по id владельца (адреса /id1, /club1) и по адресу
     * (короткое имя /durov) — чтобы найти её без сети при любом из них. Только первые записи стены.
     */
    fun saveOwner(payload: OwnerPayload) {
        // Лента новостей (3.77) — без шапки, но тоже сохраняется: открывается сразу и без сети.
        if (payload.header == null && !com.example.vkweb.owner.OwnerPath.isFeed(payload.key)) return
        val text = { payload.toJson(MAX_SAVED_POSTS).toString() }
        write(ownerFile("id:" + payload.ownerId)) { trimOwners(); text() }
        write(ownerFile("path:" + payload.key.lowercase())) { text() }
    }

    /** Сохранённая страница по адресу или, если он вида /id1, по id. */
    fun loadOwner(path: String, ownerId: Long?, result: (OwnerPayload?) -> Unit) {
        // Какой из двух файлов есть — выясняется в потоке записи-чтения, а не в главном (обращение к диску).
        val byPath = ownerFile("path:" + path.trimEnd('/').lowercase())
        val byId = ownerId?.let { ownerFile("id:$it") }
        read({ if (byPath.isFile || byId == null) byPath else byId }, result) { raw ->
            OwnerPayload.fromJson(JSONObject(raw))?.takeIf { it.header != null || com.example.vkweb.owner.OwnerPath.isFeed(it.key) }
        }
    }

    private fun ownerFile(key: String) = File(owners, sha1(key) + ".json")

    private fun trimOwners() {
        val files = owners.listFiles()?.sortedByDescending { it.lastModified() } ?: return
        files.drop(MAX_SAVED_OWNER_FILES).forEach { it.delete() }
    }

    // ------------------------------------------------------------ обслуживание

    /** Сколько занимает каждая категория этого хранилища; [result] — в главном потоке. */
    fun measure(result: (Map<StorageCategory, Long>) -> Unit) {
        io.execute {
            val sizes = StorageCategory.entries.associateWith { category -> filesOf(category).sumOf(::sizeOf) }
            main.post { result(sizes) }
        }
    }

    /**
     * Удаляет сохранённое категории (в той же очереди, что и записи: запись, начатая раньше, не вернёт
     * удалённое). Картинки и кэш страниц хранятся не здесь — см. [Storage].
     */
    fun clear(category: StorageCategory, done: () -> Unit = {}) {
        io.execute {
            AppLog.i(LogTag.STORE, "удаление сохранённого", "категория" to category.name)
            filesOf(category).forEach { it.deleteRecursively() }
            lastWritten.clear()
            histories.mkdirs()
            owners.mkdirs()
            main.post(done)
        }
    }

    /** Файлы и папки категории в этом хранилище (пусто — категория хранится не здесь). */
    private fun filesOf(category: StorageCategory): List<File> = when (category) {
        StorageCategory.MESSAGES -> listOf(File(root, DIALOGS_FILE), File(root, SYNC_FILE), histories)
        StorageCategory.OUTBOX -> listOf(File(root, OUTBOX_FILE))
        StorageCategory.MUSIC -> listOf(File(root, MUSIC_FILE), File(root, PLAYLISTS_FILE))
        StorageCategory.PEOPLE -> PeopleKind.entries.map(::peopleFile)
        StorageCategory.PAGES -> listOf(owners)
        StorageCategory.MUSIC_FILES, StorageCategory.IMAGES, StorageCategory.WEB_CACHE, StorageCategory.DEV_DUMP,
        StorageCategory.EVENT_LOG -> emptyList()
    }

    /**
     * Что сохранено и как давно обновлялось — строки для отчёта (по одной на категорию). Читает только сведения о
     * файлах; вызывать не из главного потока.
     */
    fun describe(now: Long = System.currentTimeMillis(), title: (StorageCategory) -> String): List<String> =
        listOf(StorageCategory.MESSAGES, StorageCategory.OUTBOX, StorageCategory.MUSIC, StorageCategory.PEOPLE, StorageCategory.PAGES)
            .map { category ->
                val files = filesOf(category).flatMap { it.walkTopDown().filter { f -> f.isFile } }
                CacheReport.line(title(category), files.map { CacheReport.Entry(it.length(), it.lastModified()) }, now)
            }

    private fun historyFile(peerId: String) = File(histories, sha1(peerId) + ".json")

    /** Сколько переписок записано с последней уборки (поток [io]). */
    private var historyWrites = 0

    /**
     * Уборка — чтение и сортировка всей папки — не на каждую запись (переписка приходит порциями по нескольку раз
     * в секунду), а раз в [TRIM_EVERY] записей и при первой записи после запуска.
     */
    private fun trimHistoriesSometimes() {
        if (historyWrites++ % TRIM_EVERY == 0) trimHistories()
    }

    /** Храним переписки только с последними [MAX_SAVED_CHATS] собеседниками. */
    private fun trimHistories() {
        val files = histories.listFiles()?.sortedByDescending { it.lastModified() } ?: return
        files.drop(MAX_SAVED_CHATS).forEach { it.delete() }
    }

    private fun write(file: File, completed: ((Boolean) -> Unit)? = null, produce: () -> String) {
        io.execute {
            var successful = false
            try {
                val text = produce()
                val hash = DurableTextFile.fingerprint(text)
                // Первая запись файла после запуска: «что записано раньше» в памяти неизвестно — сверяемся с самим
                // файлом, а не пишем заново то, что там уже лежит (каждый вход в переписку перезаписывал её).
                if (lastWritten[file.path] == null && file.isFile) {
                    val onDisk = try { file.readText() } catch (e: IOException) { null }
                    if (onDisk != null && onDisk == text) lastWritten[file.path] = hash
                }
                if (lastWritten[file.path] == hash && file.isFile) {
                    // Время файла — «когда пользовались»: по нему при чистке остаются свежие. Без отметки часто
                    // открываемая, но не меняющаяся переписка удалялась первой (код ревью 3.59).
                    file.setLastModified(System.currentTimeMillis())
                    AppLog.d(LogTag.STORE, "запись пропущена: данные не изменились", "файл" to file.name)
                    successful = true
                    return@execute
                }
                DurableTextFile.write(file, text)
                lastWritten[file.path] = hash
                successful = true
                AppLog.d(LogTag.STORE, "сохранено", "файл" to file.name, "байт" to text.length)
            } catch (e: IOException) {
                // Нет места или файл занят — следующее сохранение перезапишет.
                AppLog.w(LogTag.STORE, "не удалось сохранить", "файл" to file.name, "ошибка" to e.javaClass.simpleName)
            } catch (e: JSONException) {
                // Данные не сериализуются — пропускаем.
                AppLog.w(LogTag.STORE, "данные не сериализуются", "файл" to file.name, "ошибка" to e.javaClass.simpleName)
            } finally {
                val saved = successful
                completed?.let { callback -> main.post { callback(saved) } }
            }
        }
    }

    private fun <T> read(file: File, result: (T?) -> Unit, parse: (String) -> T?) = read({ file }, result, parse)

    /** Файл выбирается ([choose]) уже в потоке [io]: выбор может требовать обращения к диску. */
    private fun <T> read(choose: () -> File, result: (T?) -> Unit, parse: (String) -> T?) {
        io.execute {
            val file = choose()
            val value = try {
                if (file.isFile) parse(file.readText()) else null
            } catch (e: IOException) {
                AppLog.w(LogTag.STORE, "не удалось прочитать сохранённое", "файл" to file.name, "ошибка" to e.javaClass.simpleName)
                null
            } catch (e: JSONException) {
                // Файл повреждён или формат устарел: показано будет только свежее. Текст ошибки JSON содержит сам файл
                // (переписку) — в журнал только вид ошибки (код ревью 3.59).
                AppLog.w(LogTag.STORE, "сохранённое повреждено", "файл" to file.name, "ошибка" to e.javaClass.simpleName)
                null
            }
            // Что прочитано и насколько оно старое: если показывается старое, по журналу видно, когда оно обновлялось.
            if (value != null) {
                AppLog.d(
                    LogTag.STORE, "загружено из хранилища",
                    "файл" to file.name, "байт" to file.length(), "возраст" to CacheReport.age(System.currentTimeMillis() - file.lastModified()),
                )
            } else if (!file.isFile) {
                AppLog.d(LogTag.STORE, "сохранённого нет", "файл" to file.name)
            }
            main.post { result(value) }
        }
    }

    companion object {
        private const val DIR = "offline"
        private const val DIALOGS_FILE = "dialogs.json"
        private const val OUTBOX_FILE = "outbox.json"
        private const val SYNC_FILE = "sync_state.json"
        private const val MUSIC_FILE = "music.json"
        private const val PLAYLISTS_FILE = "playlists.json"
        private const val MAX_SAVED_CHATS = 200
        private const val MAX_SAVED_MESSAGES = 1000
        /** 100 страниц × 2 имени. */
        private const val MAX_SAVED_OWNER_FILES = 200
        private const val MAX_SAVED_POSTS = 50
        private const val TRIM_EVERY = 20

        @Volatile private var instance: OfflineStore? = null

        fun get(context: Context): OfflineStore =
            instance ?: synchronized(this) { instance ?: OfflineStore(context.applicationContext).also { instance = it } }

        /** Размер файла или папки со всем содержимым. */
        fun sizeOf(file: File): Long = if (file.isDirectory) file.walkBottomUp().filter { it.isFile }.sumOf { it.length() }
            else if (file.isFile) file.length() else 0L

        fun sha1(value: String): String =
            MessageDigest.getInstance("SHA-1").digest(value.toByteArray()).joinToString("") { "%02x".format(it) }

        // Сериализация. Поля те же, что присылает страница, поэтому разбор переиспользуется.

        private fun DialogEntry.toJson() = JSONObject()
            .put("peerId", peerId).put("title", title).put("snippet", snippet).put("time", time)
            .put("unread", unread).put("unreadCount", unreadCount).put("online", online).put("avatar", avatar).put("muted", muted)
            .put("folders", JSONArray(folderIds)).put("canWrite", canWrite)

        private fun JSONObject.toDialog(): DialogEntry? {
            val peer = optString("peerId")
            if (peer.isEmpty()) return null
            return DialogEntry(
                peerId = peer, title = optString("title"), snippet = optString("snippet"), time = optString("time"),
                unread = optBoolean("unread"), unreadCount = optInt("unreadCount"), online = false, avatar = optString("avatar"), muted = optBoolean("muted"),
                folderIds = optJSONArray("folders")?.let { a -> (0 until minOf(a.length(), 20)).map { a.optLong(it) }.filter { it > 0 } }.orEmpty(),
                canWrite = optBoolean("canWrite", true),
            )
        }

        private fun ChatMessage.toJson() = JSONObject()
            .put("id", id).put("out", out).put("text", text).put("time", time).put("sender", sender).put("senderId", senderId).put("senderAvatar", senderAvatar).put("date", dateSec)
            .putAttachments(photos, videos, cards, voices)
            .put("reply", reply?.let { JSONObject().put("cmid", it.cmid).put("sender", it.sender).put("text", it.text) } ?: JSONObject.NULL)
            .put("reactions", JSONArray().apply { reactions.forEach { put(JSONObject().put("id", it.id).put("count", it.count).put("mine", it.mine)) } })
            .put("forwards", JSONArray().apply {
                forwards.forEach {
                    put(JSONObject().put("sender", it.sender).put("text", it.text).put("time", it.time).put("depth", it.depth)
                        .putAttachments(it.photos, it.videos, it.cards, it.voices))
                }
            })

        /** Вложения — одинаково у сообщения и у пересланного в нём (разбор — [ReaderPayload], как для страницы). */
        private fun JSONObject.putAttachments(
            photos: List<ChatPhoto>, videos: List<ChatVideo>, cards: List<ChatCard>, voices: List<ChatVoice>,
        ): JSONObject = this
            .put("photos", JSONArray().apply {
                photos.forEach { put(JSONObject().put("url", it.url).put("width", it.width).put("height", it.height).put("id", it.id).put("sticker", it.sticker)) }
            })
            .put("voices", JSONArray().apply {
                voices.forEach { put(JSONObject().put("url", it.url).put("duration", it.durationSec).put("id", it.id)) }
            })
            .put("cards", JSONArray().apply {
                cards.forEach {
                    put(JSONObject().put("kind", it.kind).put("title", it.title).put("subtitle", it.subtitle).put("url", it.url).put("path", it.path)
                        .put("cover", it.cover).put("id", it.trackId).put("key", it.trackKey))
                }
            })
            .put("videos", JSONArray().apply {
                videos.forEach {
                    put(JSONObject().put("id", it.id).put("title", it.title).put("cover", it.coverUrl).put("duration", it.durationSec).put("key", it.accessKey).put("clip", it.isClip))
                }
            })

        private fun JSONObject.toMessage(): ChatMessage? {
            val id = optString("id")
            if (id.isEmpty()) return null
            return ChatMessage(
                id = id, out = optBoolean("out"), text = optString("text"), time = optString("time"), dateSec = optLong("date"),
                sender = optString("sender"), senderId = optLong("senderId"),
                senderAvatar = com.example.vkweb.config.SafeUrls.image(optString("senderAvatar"), 2000), photos = ReaderPayload.parsePhotos(optJSONArray("photos")),
                videos = ReaderPayload.parseVideos(optJSONArray("videos")),
                cards = ReaderPayload.parseCards(optJSONArray("cards")),
                voices = ReaderPayload.parseVoices(optJSONArray("voices")),
                forwards = ReaderPayload.parseForwards(optJSONArray("forwards")),
                reply = ReaderPayload.parseReply(optJSONObject("reply")),
                reactions = ReaderPayload.parseReactions(optJSONArray("reactions")),
            )
        }

        private fun MusicTrack.toJson() = JSONObject()
            .put("id", id).put("title", title).put("artist", artist).put("album", album).put("duration", durationSec).put("cover", coverUrl)
            .put("coverLarge", coverLargeUrl)
    }
}
