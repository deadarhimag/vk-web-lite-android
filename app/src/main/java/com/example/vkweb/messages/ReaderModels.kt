package com.example.vkweb.messages

import com.example.vkweb.config.SafeUrls
import com.example.vkweb.config.VkIds
import org.json.JSONException
import org.json.JSONObject

/** Что пришло со страницы. */
sealed interface ReaderPayload {
    data class Dialogs(val items: List<DialogEntry>, val folders: List<ChatFolder> = emptyList()) : ReaderPayload
    data class History(
        val peerId: String,
        val title: String,
        val items: List<ChatMessage>,
        /** До какого номера в беседе собеседник прочитал свои сообщения (out_read_cmid); 0 — не известно (3.72). */
        val outReadCmid: Long = 0,
        /** Писать в беседу можно (can_write; например, сообщество закрыло сообщения — нельзя). */
        val canWrite: Boolean = true,
        val online: Boolean = false,
        val mobile: Boolean = false,
        /** Когда собеседник был в сети, секунды Unix; 0 — скрыто или не известно. */
        val lastSeen: Long = 0,
    ) : ReaderPayload
    data class Sent(val ok: Boolean) : ReaderPayload

    /**
     * Сервер принял сообщение (успешный ответ `messages.send`, перехваченный страницей): сообщение из очереди с таким
     * текстом доставлено — ждать, пока оно появится в истории, не нужно.
     */
    data class Delivered(val peerId: String, val text: String, val cmid: Long = 0) : ReaderPayload

    /** Ход отправки вложений в окне чтения: stage — files | uploading | error, detail — причина ошибки. */
    data class Attach(val stage: String, val detail: String) : ReaderPayload

    /**
     * Музыка из сообщения, которую играет сама страница мессенджера: state — playing | paused | ended | stopped | failed |
     * notfound (вложения нет в вёрстке — тогда трек включается в разделе музыки), позиция и длительность — в секундах.
     */
    data class Audio(val state: String, val title: String, val positionSec: Int, val durationSec: Int, val detail: String) : ReaderPayload

    /** Ответ на сообщение отправлен ([ok]) или нет ([reason] — для журнала и подсказки). */
    data class Replied(val ok: Boolean, val reason: String) : ReaderPayload

    /** Недавние стикеры страницы (3.73): номер и картинка (https на серверах VK). */
    data class Stickers(val items: List<Sticker>, val reason: String) : ReaderPayload {
        data class Sticker(val id: Long, val url: String)
    }

    /** Итог отправки стикера. */
    data class StickerSent(val ok: Boolean, val reason: String) : ReaderPayload

    /** Подсказки стикеров для набранного [query] (3.92); пусто — подсказать нечего. */
    data class StickerSuggest(val query: String, val items: List<Stickers.Sticker>) : ReaderPayload

    /** Сообщение переслано в переписку [target] ([ok]) или нет ([reason]). */
    data class Forwarded(val ok: Boolean, val target: String, val reason: String) : ReaderPayload

    /** В переписке [peer] печатают (4.60): имена известных; [voice] — записывают голосовое. */
    data class Typing(val peer: String, val names: List<String>, val voice: Boolean) : ReaderPayload

    /** Картинки реакций (4.61): номер → https-адрес. */
    data class ReactionAssets(val images: Map<Int, String>) : ReaderPayload

    /** Реакция поставлена или снята (4.61) — или нет, с причиной. */
    data class Reacted(val ok: Boolean, val cmid: Long, val reason: String) : ReaderPayload

    /** Голосовое отправлено (4.59) или нет — с причиной. */
    data class VoiceSent(val ok: Boolean, val reason: String, val uncertain: Boolean = false, val operation: String = "", val documentId: String = "") : ReaderPayload
    data class VoiceStage(val operation: String, val documentId: String) : ReaderPayload

    /**
     * Файлы видео или клипа [id] для проигрывателя приложения: [mp4] (до 720p), [hls] — поток, [player] — встраиваемый
     * проигрыватель VK; все — https на серверах VK. [ok] = false — файлов нет ([reason]): видео открывается страницей.
     */
    data class Video(
        val id: String, val ok: Boolean, val mp4: String, val hls: String, val player: String,
        val title: String, val live: Boolean, val reason: String,
        /** Файлы по высоте кадра (3.86): выбор качества в проигрывателе. */
        val files: Map<Int, String> = emptyMap(),
    ) : ReaderPayload {
        val hasSource: Boolean get() = mp4.isNotEmpty() || hls.isNotEmpty() || player.isNotEmpty()
    }

    /**
     * Адрес звука трека [id] из сообщения ([url] — https на серверах звука VK): трек играет само приложение. [how] — как
     * нашли (сообщение, номер, поиск); [ok] = false — не нашли ([reason]): тогда трек нажимается на странице, как раньше.
     */
    data class AudioUrl(val id: String, val ok: Boolean, val url: String, val title: String, val durationSec: Int, val how: String, val reason: String) : ReaderPayload

    /** Очередь музыки переписки: адреса потоков треков (по порядку), [wanted] — нажатый; [how] — как нашли нажатый. */
    data class AudioQueue(val wanted: String, val items: List<QueueItem>, val how: String, val reason: String) : ReaderPayload

    data class QueueItem(val id: String, val url: String, val title: String, val artist: String, val durationSec: Int)

    /** Итог действия с беседой из списка диалогов (4.0). */
    data class ConvAction(val action: String, val peer: String, val ok: Boolean, val reason: String) : ReaderPayload

    /** Сообщение удалено (у себя или у всех) — или не удалось (3.94). */
    data class Deleted(val ok: Boolean, val cmid: Long, val reason: String) : ReaderPayload

    /** Текст своего сообщения изменён — или не удалось (3.94). */
    data class Edited(val ok: Boolean, val cmid: Long, val text: String, val reason: String) : ReaderPayload

    /** Участники беседы (4.32): ответ на запрос из шапки переписки; ok=false — VK не отдал (нет доступа, ошибка). */
    data class Members(val peerId: String, val ok: Boolean, val people: List<ChatPeople.Person>) : ReaderPayload

    /** Своя страница — для шапки бокового меню (3.94). */
    data class Self(val profile: com.example.vkweb.account.SelfProfile) : ReaderPayload

    /** Старых сообщений больше нет: переписка загружена с самого начала. */
    data object OlderDone : ReaderPayload

    /** Данные прочитать не удалось: reason — login | empty | unknown. */
    data class Unavailable(val reason: String) : ReaderPayload

    companion object {
        private const val MAX_TEXT = 4000
        private const val MAX_UNREAD = 99_999
        /** Дата сообщения не позже 2100 года: мусор в поле не превращается в «день» разделителя. */
        private const val MAX_DATE_SEC = 4_102_444_800L
        private const val MAX_DIALOGS = 300
        private const val MAX_MESSAGES = 2000
        private const val MAX_PHOTOS = 10

        /** Фото: объект {url,width,height,id} или просто адрес (так хранилось в старом сохранённом). */
        fun parsePhotos(array: org.json.JSONArray?): List<ChatPhoto> = parseList(array, MAX_PHOTOS) { o, i ->
            val url = httpsOrEmpty(o?.optString("url") ?: array?.optString(i).orEmpty())
            if (url.isEmpty()) null else ChatPhoto(url, o?.optInt("width") ?: 0, o?.optInt("height") ?: 0, vkId(o?.optString("id")), o?.optBoolean("sticker") == true)
        }

        /** Массив объектов страницы: не больше [max] первых, [build] вернул null — элемент отброшен (данные чужие). Общий цикл всех разборов ниже. */
        private inline fun <T> parseList(array: org.json.JSONArray?, max: Int, build: (org.json.JSONObject?, Int) -> T?): List<T> {
            if (array == null) return emptyList()
            val result = ArrayList<T>()
            for (i in 0 until minOf(array.length(), max)) build(array.optJSONObject(i), i)?.let { result += it }
            return result
        }


        /** Номер объекта VK «владелец_номер» или пустая строка. */
        private fun vkId(value: String?): String = value?.takeIf { VkIds.isMediaId(it) }.orEmpty()

        private const val MAX_CARDS = 6
        private const val MAX_QUEUE = 100
        private val CARD_PATH = Regex("^/(wall|clip)-?\\d+_\\d+$")

        /** Карточки вложений: вид из известных, адрес только https, путь только записи или клипа. Данные чужие — всё проверяется. */
        fun parseCards(array: org.json.JSONArray?): List<ChatCard> = parseList(array, MAX_CARDS) { o, _ ->
            val kind = o?.optString("kind").orEmpty()
            val url = httpsOrEmpty(o?.optString("url").orEmpty())
            val path = o?.optString("path")?.takeIf { CARD_PATH.matches(it) }.orEmpty()
            // Музыка (включается по номеру или названию) и звонок (только сведения) — без адреса; остальным адрес или путь обязательны.
            if (o == null || kind !in ChatCard.KINDS || (url.isEmpty() && path.isEmpty() && kind !in ChatCard.WITHOUT_ADDRESS)) null
            else ChatCard(
                kind, o.optString("title").trim().take(200), o.optString("subtitle").trim().take(120), url, path,
                cover = httpsOrEmpty(o.optString("cover")),
                trackId = if (kind == "audio") vkId(o.optString("id")) else "",
                trackKey = if (kind == "audio") o.optString("key").takeIf { ACCESS_KEY.matches(it) }.orEmpty() else "",
            )
        }

        private const val MAX_VOICES = 4

        fun parseVoices(array: org.json.JSONArray?): List<ChatVoice> = parseList(array, MAX_VOICES) { o, _ ->
            val url = httpsOrEmpty(o?.optString("url").orEmpty())
            if (o == null || url.isEmpty()) null else ChatVoice(url, o.optInt("duration").coerceIn(0, 3_600), vkId(o.optString("id")))
        }

        /** Цитата ответа: только с номером сообщения (по нему переход), текст и автор обрезаются. */
        /** Реакции (4.61): номер больше 0, число больше 0, не больше 8. */
        fun parseReactions(a: org.json.JSONArray?): List<ChatReaction> {
            if (a == null) return emptyList()
            val out = ArrayList<ChatReaction>()
            for (i in 0 until minOf(a.length(), 8)) {
                val o = a.optJSONObject(i) ?: continue
                val id = o.optInt("id")
                val count = o.optInt("count")
                if (id > 0 && count > 0) out += ChatReaction(id, count.coerceAtMost(9_999_999), o.optBoolean("mine"))
            }
            return out
        }

        fun parseReply(o: org.json.JSONObject?): ChatReply? {
            val cmid = o?.optLong("cmid") ?: return null
            if (cmid <= 0) return null
            return ChatReply(cmid, o.optString("sender").trim().take(200), o.optString("text").trim().take(300))
        }

        private const val MAX_FORWARDS = 10
        private const val MAX_FORWARD_DEPTH = 3

        /** Пересланные: автор, текст, время, уровень и вложения (те же проверки, что у самого сообщения). */
        fun parseForwards(array: org.json.JSONArray?): List<ChatForward> = parseList(array, MAX_FORWARDS) { o, _ ->
            if (o == null) return@parseList null
            ChatForward(
                sender = o.optString("sender").trim().take(200),
                text = o.optString("text").trim().take(MAX_TEXT),
                time = o.optString("time").trim().take(32),
                depth = o.optInt("depth", 1).coerceIn(1, MAX_FORWARD_DEPTH),
                photos = parsePhotos(o.optJSONArray("photos")),
                videos = parseVideos(o.optJSONArray("videos")),
                cards = parseCards(o.optJSONArray("cards")),
                voices = parseVoices(o.optJSONArray("voices")),
            ).takeIf { !it.isEmpty || it.sender.isNotEmpty() }
        }

        private const val MAX_VIDEOS = 5
        private val ACCESS_KEY = Regex("^[0-9A-Za-z]{1,40}$")

        fun parseVideos(array: org.json.JSONArray?): List<ChatVideo> = parseList(array, MAX_VIDEOS) { o, _ ->
            val id = vkId(o?.optString("id"))
            if (o == null || id.isEmpty()) null else ChatVideo(
                id = id,
                title = o.optString("title").trim().take(200),
                coverUrl = httpsOrEmpty(o.optString("cover")),
                durationSec = o.optInt("duration").coerceIn(0, 86_400 * 5),
                accessKey = o.optString("key").takeIf { ACCESS_KEY.matches(it) }.orEmpty(),
                isClip = o.optBoolean("clip"),
            )
        }

        /** Адрес файла видео: https и сервер VK или его сети доставки (как у картинок) — чужой адрес не проигрывается. */
        fun videoUrlOrEmpty(value: String): String = SafeUrls.video(value, URL_MAX)

        /** Адрес звука трека: https на серверах звука VK, не заглушка «трек недоступен». */
        fun audioUrlOrEmpty(value: String): String =
            SafeUrls.audio(value, URL_MAX).takeUnless { it.contains("audio_api_unavailable") }.orEmpty()

        /** Адреса в данных переписки короче, чем в лентах: длиннее — не адрес файла. */
        private const val URL_MAX = 2000
        /** Форматы картинок реакций, которые Android картинкой не покажет (4.61). */
        private val UNDRAWABLE = setOf("json", "lottie", "tgs")

        private fun httpsOrEmpty(value: String) = SafeUrls.https(value, URL_MAX)

        /** Разбор сообщения от страницы. Данные чужие, поэтому всё проверяется и режется. */
        fun parse(raw: String?): ReaderPayload? {
            if (raw.isNullOrEmpty()) return null
            return try {
                val json = JSONObject(raw)
                when (json.optString("type")) {
                    "dialogs" -> Dialogs(json.dialogs(), json.folders())
                    "history" -> History(
                        peerId = json.optString("peerId").take(64),
                        title = json.optString("title").trim().take(200),
                        items = json.messages(),
                        outReadCmid = json.optLong("outRead").coerceAtLeast(0),
                        canWrite = json.optBoolean("canWrite", true),
                        online = json.optBoolean("online"),
                        mobile = json.optBoolean("mobile"),
                        lastSeen = json.optLong("lastSeen").coerceAtLeast(0),
                    )
                    "sent" -> Sent(json.optBoolean("ok"))
                    "convaction" -> ConvAction(
                        json.optString("action").take(16), json.optString("peer").take(16),
                        json.optBoolean("ok"), json.optString("reason").take(200),
                    )
                    "deleted" -> Deleted(json.optBoolean("ok"), json.optLong("cmid").coerceAtLeast(0), json.optString("reason").take(200))
                    "edited" -> Edited(
                        json.optBoolean("ok"), json.optLong("cmid").coerceAtLeast(0),
                        json.optString("text").take(MAX_TEXT), json.optString("reason").take(200),
                    )
                    "self" -> com.example.vkweb.account.SelfProfile.fromJson(json)?.let { Self(it) }
                    "members" -> Members(
                        json.optString("peerId").take(32), json.optBoolean("ok"),
                        ChatPeople.parse(json.optJSONArray("items")),
                    )
                    "delivered" -> Delivered(json.optString("peerId").take(32), json.optString("text").take(10_000), json.optLong("cmid").coerceAtLeast(0))
                    "attach" -> Attach(json.optString("stage").take(20), json.optString("detail").take(200))
                    "unavailable" -> Unavailable(json.optString("reason").ifEmpty { "unknown" })
                    "replied" -> Replied(json.optBoolean("ok"), json.optString("reason").take(200))
                    "stickers" -> Stickers(json.stickers(), json.optString("reason").take(200))
                    "stickersent" -> StickerSent(json.optBoolean("ok"), json.optString("reason").take(200))
                    "stickersuggest" -> StickerSuggest(json.optString("query").take(80), json.stickers().take(STICKER_SUGGEST_MAX))
                    "typing" -> Typing(
                        json.optString("peer").takeIf { VkIds.isPeer(it) }.orEmpty(),
                        json.optJSONArray("names")?.let { a -> (0 until minOf(a.length(), 10)).map { a.optString(it).trim().take(100) } }.orEmpty(),
                        json.optBoolean("voice"),
                    )
                    "reactionAssets" -> ReactionAssets(
                        json.optJSONArray("items")?.let { a ->
                            (0 until minOf(a.length(), 64)).mapNotNull { i ->
                                val o = a.optJSONObject(i) ?: return@mapNotNull null
                                val url = SafeUrls.image(o.optString("image"), URL_MAX)
                                // Анимации (json, lottie) картинкой не показать — такие не берём: лучше без реакции, чем пустой
                                // кружок. SVG (так их отдаёт VK, отчёт 4.62) с 4.63 рисует ImageCache.
                                val ext = url.substringBefore('?').substringAfterLast('.', "").lowercase()
                                if (o.optInt("id") > 0 && url.isNotEmpty() && ext !in UNDRAWABLE) o.optInt("id") to url else null
                            }.toMap()
                        }.orEmpty(),
                    )
                    "reacted" -> Reacted(json.optBoolean("ok"), json.optLong("cmid"), json.optString("reason").take(200))
                    "voiceSent" -> VoiceSent(json.optBoolean("ok"), json.optString("reason").take(200), json.optBoolean("uncertain"), json.optString("operation").take(64), vkId(json.optString("documentId")))
                    "voiceStage" -> VoiceStage(json.optString("operation").take(64), vkId(json.optString("documentId")))
                    "forwarded" -> Forwarded(json.optBoolean("ok"), json.optString("target").takeIf { VkIds.isPeer(it) }.orEmpty(), json.optString("reason").take(200))
                    "video" -> Video(
                        id = vkId(json.optString("id")),
                        ok = json.optBoolean("ok"),
                        mp4 = videoUrlOrEmpty(json.optString("mp4")),
                        hls = videoUrlOrEmpty(json.optString("hls")),
                        player = videoUrlOrEmpty(json.optString("player")),
                        title = json.optString("title").trim().take(200),
                        live = json.optBoolean("live"),
                        reason = json.optString("reason").take(200),
                        files = com.example.vkweb.video.VideoQuality.parse(json.optJSONObject("files"), ::videoUrlOrEmpty),
                    )
                    "audiourl" -> {
                        val url = audioUrlOrEmpty(json.optString("url"))
                        AudioUrl(
                            id = json.optString("id").take(40), ok = json.optBoolean("ok") && url.isNotEmpty(), url = url,
                            title = json.optString("title").trim().take(200), durationSec = json.optInt("duration").coerceIn(0, 86_400),
                            how = json.optString("how").take(20), reason = json.optString("reason").take(200),
                        )
                    }
                    "audioqueue" -> AudioQueue(
                        wanted = vkId(json.optString("wanted")),
                        items = parseList(json.optJSONArray("items"), MAX_QUEUE) { o, _ ->
                            val url = audioUrlOrEmpty(o?.optString("url").orEmpty())
                            val id = o?.optString("id").orEmpty().let { if (it.isEmpty()) "" else vkId(it) }
                            if (o == null || url.isEmpty()) null else QueueItem(
                                id = id, url = url, title = o.optString("title").trim().take(200),
                                artist = o.optString("artist").trim().take(200), durationSec = o.optInt("duration").coerceIn(0, 86_400),
                            )
                        },
                        how = json.optString("how").take(20),
                        reason = json.optString("reason").take(200),
                    )
                    "older" -> if (json.optBoolean("done")) OlderDone else null
                    "audio" -> Audio(
                        state = json.optString("state").take(20),
                        title = json.optString("title").trim().take(200),
                        positionSec = json.optInt("position").coerceIn(0, 86_400),
                        durationSec = json.optInt("duration").coerceIn(0, 86_400),
                        detail = json.optString("detail").take(200),
                    )
                    else -> null
                }
            } catch (e: JSONException) {
                null
            }
        }

        private const val STICKER_SUGGEST_MAX = 8

        private fun JSONObject.stickers(): List<Stickers.Sticker> {
            val array = optJSONArray("items") ?: return emptyList()
            val out = ArrayList<Stickers.Sticker>()
            for (i in 0 until minOf(array.length(), MAX_STICKERS)) {
                val o = array.optJSONObject(i) ?: continue
                val id = o.optLong("id")
                val url = httpsOrEmpty(o.optString("url"))
                if (id > 0 && url.isNotEmpty() && SafeUrls.image(url, URL_MAX).isNotEmpty()) out += Stickers.Sticker(id, url)
            }
            return out
        }

        private const val MAX_STICKERS = 60

        private fun JSONObject.folders(): List<ChatFolder> {
            val array = optJSONArray("folders") ?: return emptyList()
            val out = ArrayList<ChatFolder>()
            for (i in 0 until minOf(array.length(), 20)) {
                val f = array.optJSONObject(i) ?: continue
                val id = f.optLong("id")
                val name = f.optString("name").trim().take(60)
                if (id <= 0 || name.isEmpty()) continue
                val peers = f.optJSONArray("peers")?.let { a -> (0 until minOf(a.length(), 500)).map { a.optLong(it).toString() } }.orEmpty().toSet()
                out += ChatFolder(id, name, f.optString("type").take(20), peers)
            }
            return out
        }

        private fun JSONObject.dialogs(): List<DialogEntry> {
            val array = optJSONArray("items") ?: return emptyList()
            val result = ArrayList<DialogEntry>(minOf(array.length(), MAX_DIALOGS))
            for (i in 0 until minOf(array.length(), MAX_DIALOGS)) {
                val o = array.optJSONObject(i) ?: continue
                val peer = o.optString("peerId").take(64)
                if (peer.isEmpty()) continue
                result += DialogEntry(
                    peerId = peer,
                    title = o.optString("title").trim().take(200).ifEmpty { "Диалог $peer" },
                    snippet = o.optString("snippet").trim().take(300),
                    time = o.optString("time").trim().take(32),
                    unread = o.optBoolean("unread"),
                    unreadCount = if (o.optBoolean("unread")) o.optInt("unreadCount").coerceIn(0, MAX_UNREAD) else 0,
                    online = o.optBoolean("online"),
                    avatar = httpsOrEmpty(o.optString("avatar")),
                    muted = o.optBoolean("muted"),
                    folderIds = o.optJSONArray("folders")?.let { a -> (0 until minOf(a.length(), 20)).map { a.optLong(it) }.filter { it > 0 } }.orEmpty(),
                    canWrite = o.optBoolean("canWrite", true),
                )
            }
            return result
        }

        private fun JSONObject.messages(): List<ChatMessage> {
            val array = optJSONArray("items") ?: return emptyList()
            // Страница присылает последние сообщения по порядку; если их больше лимита — берём самые новые.
            val start = maxOf(0, array.length() - MAX_MESSAGES)
            val result = ArrayList<ChatMessage>(array.length() - start)
            for (i in start until array.length()) {
                val o = array.optJSONObject(i) ?: continue
                val body = o.optString("text").trim().take(MAX_TEXT)
                val photos = parsePhotos(o.optJSONArray("photos"))
                val videos = parseVideos(o.optJSONArray("videos"))
                val cards = parseCards(o.optJSONArray("cards"))
                val voices = parseVoices(o.optJSONArray("voices"))
                val forwards = parseForwards(o.optJSONArray("forwards"))
                if (body.isEmpty() && photos.isEmpty() && videos.isEmpty() && cards.isEmpty() && voices.isEmpty() && forwards.isEmpty()) continue
                result += ChatMessage(
                    id = o.optString("id").take(64).ifEmpty { "i$i" },
                    out = o.optBoolean("out"),
                    text = body,
                    time = o.optString("time").trim().take(32),
                    dateSec = o.optLong("date").coerceIn(0, MAX_DATE_SEC),
                    sender = o.optString("sender").trim().take(200),
                    senderId = o.optLong("senderId"),
                    senderAvatar = SafeUrls.image(o.optString("senderAvatar"), URL_MAX),
                    photos = photos,
                    preview = o.optBoolean("preview"),
                    videos = videos,
                    cards = cards,
                    voices = voices,
                    forwards = forwards,
                    reply = parseReply(o.optJSONObject("reply")),
                    reactions = parseReactions(o.optJSONArray("reactions")),
                )
            }
            return result
        }
    }
}
