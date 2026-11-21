package com.example.vkweb.messages

// Данные экранов сообщений. Без зависимостей от Android, поэтому проверяются обычными тестами.

/** Один диалог в списке. */
data class DialogEntry(
    val peerId: String,
    val title: String,
    val snippet: String,
    val time: String,
    val unread: Boolean,
    val online: Boolean,
    /** Аватар (https-адрес VK) или пусто — тогда кружок с инициалами. */
    val avatar: String = "",
    /** Уведомления беседы отключены (push_settings, 3.72): о новых в ней фоновая проверка не сообщает, как и VK. */
    val muted: Boolean = false,
    /** Папки мессенджера, в которых беседа (folder_ids, 3.79). */
    val folderIds: List<Long> = emptyList(),
    /** Сколько непрочитанных (3.94); 0 при [unread] — число неизвестно (отмечено непрочитанным вручную): точка. */
    val unreadCount: Int = 0,
    /**
     * Писать в диалог можно (can_write, 5.00.12). false - только чтение (рассылки, каналы, закрытые беседы): у его
     * уведомления нет «Ответить».
     */
    val canWrite: Boolean = true,
)

/** Папка мессенджера VK (3.79): [type] — unread, channels, business или пусто (своя); [peers] — явно включённые беседы. */
data class ChatFolder(val id: Long, val name: String, val type: String, val peers: Set<String>)

/** Какие диалоги в папке. Без зависимостей от Android: проверяется обычными тестами. */
object DialogFolders {
    /** null — «Все». «Непрочитанные» — по признаку непрочитанности; остальные — по folder_ids беседы или списку папки. */
    fun filter(items: List<DialogEntry>, folder: ChatFolder?): List<DialogEntry> = when {
        folder == null -> items
        folder.type == "unread" -> items.filter { it.unread }
        else -> items.filter { folder.id in it.folderIds || it.peerId in folder.peers }
    }

    /** Папки, которые есть смысл показывать: с именем; «Каналы» — только если в списке есть их беседы. */
    fun visible(folders: List<ChatFolder>, items: List<DialogEntry>): List<ChatFolder> =
        folders.filter { f -> f.name.isNotBlank() && (f.type == "unread" || filter(items, f).isNotEmpty()) }

    /**
     * Список папок — строкой для хранения (3.93): папки приходят от страницы позже списка диалогов, и без сохранённых
     * полоса папок появлялась только через секунды после открытия, сдвигая список.
     */
    fun encode(folders: List<ChatFolder>): String = org.json.JSONArray().apply {
        folders.take(MAX_FOLDERS).forEach { f ->
            put(org.json.JSONObject().put("id", f.id).put("name", f.name).put("type", f.type).put("peers", org.json.JSONArray(f.peers.take(MAX_PEERS))))
        }
    }.toString()

    fun decode(raw: String?): List<ChatFolder> {
        if (raw.isNullOrEmpty()) return emptyList()
        val a = try { org.json.JSONArray(raw) } catch (e: org.json.JSONException) { return emptyList() }
        val out = ArrayList<ChatFolder>()
        for (i in 0 until minOf(a.length(), MAX_FOLDERS)) {
            val o = a.optJSONObject(i) ?: continue
            val id = o.optLong("id")
            if (id <= 0) continue
            val peers = o.optJSONArray("peers")?.let { p -> (0 until minOf(p.length(), MAX_PEERS)).map { p.optString(it) }.filter { it.isNotEmpty() }.toSet() }.orEmpty()
            out += ChatFolder(id, o.optString("name").take(60), o.optString("type").take(20), peers)
        }
        return out
    }

    private const val MAX_FOLDERS = 30
    private const val MAX_PEERS = 500
}

/**
 * Где сообщение на пути к собеседнику. Всё, что пришло со страницы, — [SENT]; остальное — своя очередь
 * неотправленных ([com.example.vkweb.outbox.OutboxItem]), показанная в переписке с пометкой.
 */
enum class MessageStatus {
    SENT,
    /** Ждёт отправки (нет сети или страница ещё не готова). */
    QUEUED,
    /** Отложенное (4.17): уйдёт в своё время, [ChatMessage.time] — когда. */
    SCHEDULED,
    /** Отправляется: кнопка нажата, ждём подтверждения. */
    SENDING,
    /** Отправка могла пройти, но подтверждения нет: решает человек. */
    UNCONFIRMED,
    /** Не удалось отправить. */
    FAILED,
}

/** Реакция на сообщение (4.61): номер VK, сколько поставили, есть ли среди них своя. */
data class ChatReaction(val id: Int, val count: Int, val mine: Boolean)

/** Одно сообщение в переписке. [sender] — имя автора во входящих сообщениях бесед, иначе пусто. */
data class ChatMessage(
    val id: String,
    val out: Boolean,
    val text: String,
    val time: String,
    val sender: String = "",
    /**
     * Номер автора входящего сообщения в беседе (4.31; у людей — положительный, у сообществ — отрицательный); 0 —
     * неизвестно (личная переписка, своё, сохранённое до 4.31). По нему — список участников из шапки беседы ([ChatPeople]).
     */
    val senderId: Long = 0,
    /** Фото автора входящего сообщения в беседе (4.53; https на серверах картинок VK); пусто — кружок с инициалами. */
    val senderAvatar: String = "",
    /** Фото во вложениях (исходное разрешение). */
    val photos: List<ChatPhoto> = emptyList(),
    val status: MessageStatus = MessageStatus.SENT,
    /**
     * Пришло только из списка диалогов (последнее сообщение беседы): там бывает урезано то, что есть в истории.
     * Такая версия не заменяет уже известную ([HistoryMerge.richer]) и на диск не пишется как признак.
     */
    val preview: Boolean = false,
    /** Видео во вложениях: показываются карточкой, нажатие открывает видео. */
    val videos: List<ChatVideo> = emptyList(),
    /** Ссылки, файлы, записи и клипы во вложениях: карточки, которые открываются. */
    val cards: List<ChatCard> = emptyList(),
    /** Голосовые сообщения: проигрываются в самом пузыре. */
    val voices: List<ChatVoice> = emptyList(),
    /** Пересланные сообщения: блоком «Переслано» под текстом, со своим автором, текстом и вложениями. */
    val forwards: List<ChatForward> = emptyList(),
    /** Ответ на сообщение: цитата над текстом, нажатие ведёт к тому сообщению. */
    val reply: ChatReply? = null,
    /** Реакции (4.61): по убыванию числа; картинки — [ReactionAssets] по номеру. */
    val reactions: List<ChatReaction> = emptyList(),
    /**
     * Когда отправлено (unix-время, секунды); 0 — неизвестно (сохранённое до 3.94, прочитанное из вёрстки). По нему —
     * разделители дней в переписке и время «14:32» под сообщением ([ChatDays]).
     */
    val dateSec: Long = 0,
    /** Автоматическая сверка не может однозначно подтвердить эту локальную отправку. */
    val deliveryNeedsReview: Boolean = false,
) {
    /**
     * Сообщение принято сервером: у него настоящий номер в беседе («c» + число). Черновые (отправленные только
     * что или увиденные в вёрстке) такого номера не имеют — по нему и отличаем доставленное от «кажется, ушло».
     */
    val isConfirmed: Boolean get() = id.startsWith("c") && id.drop(1).toLongOrNull() != null

    /** Своё сообщение из очереди неотправленных (его нет на сервере). */
    val isPending: Boolean get() = status != MessageStatus.SENT
}

/**
 * Фото во вложении: адрес в исходном разрешении и размеры (0 — неизвестны).
 * Размеры нужны, чтобы место под фото было нужной высоты ещё до загрузки и список не прыгал.
 */
/** Фото во вложении; [sticker] — стикер или граффити: показывается маленьким, без рамки (3.63). */
data class ChatPhoto(val url: String, val width: Int = 0, val height: Int = 0, val id: String = "", val sticker: Boolean = false) {
    /** Высота при заданной ширине; без размеров — пропорция 4:3. */
    fun heightFor(widthPx: Int, minPx: Int, maxPx: Int): Int {
        val h = if (width > 0 && height > 0) (widthPx.toLong() * height / width).toInt() else widthPx * 3 / 4
        return h.coerceIn(minPx, maxPx)
    }
}

/**
 * Видео во вложении сообщения. [id] — «владелец_номер», [accessKey] нужен для закрытых видео. [durationSec] — 0, если неизвестна.
 */
data class ChatVideo(
    val id: String,
    val title: String,
    val coverUrl: String,
    val durationSec: Int = 0,
    val accessKey: String = "",
    /** Клип (короткое видео): открывается страницей клипа, а не обычного видео. */
    val isClip: Boolean = false,
) {
    /** Страница видео или клипа на сайте (путь): открывается в главном окне. */
    val pagePath: String get() = (if (isClip) "/clip$id" else "/video$id") + if (accessKey.isNotEmpty()) "?access_key=$accessKey" else ""
}

/**
 * Вложение-карточка: ссылка, статья, файл, запись на стене или клип. Либо [url] — внешний адрес (ссылка, файл), либо
 * [path] — страница на сайте VK (запись, клип): она открывается в главном окне, где уже выполнен вход.
 */
data class ChatCard(
    val kind: String,
    val title: String,
    val subtitle: String = "",
    val url: String = "",
    val path: String = "",
    /** Обложка (https) — у музыки; пусто, если её нет. */
    val cover: String = "",
    /** Номер трека «владелец_номер» — у музыки: по нему трек включается в музыке VK; пусто — поиск по названию. */
    val trackId: String = "",
    /** Ключ доступа трека (нужен закрытым трекам); пусто, если не нужен. */
    val trackKey: String = "",
) {
    companion object {
        val KINDS = setOf("link", "article", "doc", "wall", "clip", "audio", "video_message", "call")

        /** Виды без адреса: музыка включается по номеру или названию, звонок — только сведения (кто, итог, длительность). */
        val WITHOUT_ADDRESS = setOf("audio", "call")
    }
}

/** Голосовое сообщение: [url] — запись (https), [durationSec] — длительность, [id] — «владелец_номер» (может быть пустым). */
data class ChatVoice(val url: String, val durationSec: Int = 0, val id: String = "")

/**
 * Пересланное сообщение внутри сообщения. [depth] — уровень вложенности (1 — переслано в этом сообщении, 2 — переслано
 * внутри пересланного…): вложенные идут в списке следом за своим и показываются с отступом. [sender] — имя автора
 * (пусто, если страница его не знает), [time] — когда оно было написано.
 */
data class ChatForward(
    val sender: String,
    val text: String,
    val time: String = "",
    val depth: Int = 1,
    val photos: List<ChatPhoto> = emptyList(),
    val videos: List<ChatVideo> = emptyList(),
    val cards: List<ChatCard> = emptyList(),
    val voices: List<ChatVoice> = emptyList(),
) {
    val isEmpty: Boolean get() = text.isEmpty() && photos.isEmpty() && videos.isEmpty() && cards.isEmpty() && voices.isEmpty()
}

/**
 * Цитата ответа: на какое сообщение этой переписки ответили ([cmid] — номер в беседе, по нему в списке ищется «c$cmid»),
 * его автор (пусто, если страница его не знает) и начало текста.
 */
data class ChatReply(val cmid: Long, val sender: String, val text: String) {
    /** Id сообщения в списке переписки. */
    val messageId: String get() = "c$cmid"
}
