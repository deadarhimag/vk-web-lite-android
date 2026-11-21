package com.example.vkweb.messages

import com.example.vkweb.offline.ImageKey
import com.example.vkweb.offline.ListMerge

/**
 * Слияние того, что уже на экране (сохранённое или прошлая порция), с новой порцией со страницы.
 * Страница присылает данные частями: сначала, бывает, только последнее сообщение, потом историю.
 * Если просто заменять список, он то сжимается, то растёт — «прыгает», и пропадает уже показанное.
 */
object HistoryMerge {

    /**
     * Переписка: объединяем по id, новое заменяет старое. Сообщения из API («c» + номер в беседе)
     * идут по номеру; черновые (отправленные или увиденные в вёрстке, без номера) — в конце,
     * в порядке новой порции. Старые черновые не переносим: если они ещё актуальны, они есть в новой.
     */
    fun messages(shown: List<ChatMessage>, incoming: List<ChatMessage>, limit: Int = 2000): List<ChatMessage> {
        val byId = LinkedHashMap<String, ChatMessage>()
        shown.filter { cmid(it) != null }.forEach { byId[it.id] = it }
        incoming.forEach { byId[it.id] = richer(byId[it.id], it) }
        // Номер разбирается один раз на сообщение: сортировка вызывала бы разбор при каждом сравнении.
        val withNumber = byId.values.map { it to cmid(it) }
        val numberedPairs = withNumber.filter { it.second != null }
        val provisional = withNumber.filter { it.second == null }.map { it.first }
        val incomingIds = incoming.map { it.id }.toHashSet()
        // Черновое, которое по тексту повторяет настоящее сообщение, не показываем: страница угадывает «новые» по
        // вёрстке, и при несовпадении форматов последние сообщения появлялись в конце переписки дублями.
        val numberedSorted = numberedPairs.sortedBy { it.second }.map { it.first }
        val candidates = provisional.filter { it.id in incomingIds }
        val drafts = if (candidates.isEmpty()) {
            candidates
        } else {
            // Черновые — догадки по вёрстке о последних сообщениях, повторить они могут только последние настоящие.
            // Раньше сверка шла со всей историей и нормализовала текст каждого сообщения при каждом слиянии: на
            // переписке в сотни сообщений это были ~100 мс в главном потоке (подвисание в отчёте).
            val recent = numberedSorted.takeLast(RECENT_FOR_DRAFTS).map { matchText(it.text) }.filter { it.isNotEmpty() }
            candidates.filter { !duplicatesKnown(it.text, recent) }
        }
        return (numberedSorted + drafts).takeLast(limit)
    }

    /**
     * То же сообщение из новой порции: [incoming] главнее, но не беднее. Последнее сообщение страница сначала присылает
     * из списка диалогов — без имени автора и часто без вложений, а полную версию позже. Заменять целиком значило,
     * что при каждом входе показанное сообщение «беднело» и снова «обогащалось» (и перезаписывалось на диск).
     * Текст и признак «своё» берутся из новой порции (сообщение могли исправить), а пустое не затирает известное.
     */
    fun richer(known: ChatMessage?, incoming: ChatMessage): ChatMessage {
        if (known == null || known.id != incoming.id) return incoming
        // Предпросмотр из списка диалогов не заменяет известное — ни показанное, ни сохранённое: в нём могут
        // отличаться и непустые поля (другой размер фото, подпись вместо урезанного ответа).
        if (incoming.preview) return known
        // Урезанная версия без фото (в её тексте вместо картинки может быть подпись «[Фото]»): известное остаётся целиком.
        if (incoming.photos.isEmpty() && known.photos.isNotEmpty()) {
            return incoming.copy(
                text = known.text, time = incoming.time.ifEmpty { known.time },
                dateSec = if (incoming.dateSec > 0) incoming.dateSec else known.dateSec,
                sender = incoming.sender.ifEmpty { known.sender }, photos = known.photos,
                videos = if (incoming.videos.isEmpty()) known.videos else incoming.videos,
                cards = if (incoming.cards.isEmpty()) known.cards else incoming.cards,
                voices = if (incoming.voices.isEmpty()) known.voices else incoming.voices,
                forwards = mergedForwards(known.forwards, incoming.forwards),
                reply = mergedReply(known.reply, incoming.reply),
            )
        }
        return incoming.copy(
            text = incoming.text.ifEmpty { known.text },
            time = incoming.time.ifEmpty { known.time },
            dateSec = if (incoming.dateSec > 0) incoming.dateSec else known.dateSec,
            sender = incoming.sender.ifEmpty { known.sender },
            photos = when {
                incoming.photos.isEmpty() -> known.photos
                // Те же фото, другая подпись в адресе (VK подписывает адрес заново в каждом ответе): адреса остаются
                // известные — иначе сообщение каждый раз «менялось», пузырь пересоздавал картинки, а файл переписки
                // перезаписывался тем же размером.
                samePhotos(known.photos, incoming.photos) -> known.photos
                else -> incoming.photos
            },
            videos = when {
                incoming.videos.isEmpty() -> known.videos
                // Те же ролики остаются известными, кроме случая, когда обложка (или признак клипа) появилась только теперь.
                sameVideos(known.videos, incoming.videos) && !videosImproved(known.videos, incoming.videos) -> known.videos
                else -> incoming.videos
            },
            cards = when {
                incoming.cards.isEmpty() -> known.cards
                sameCards(known.cards, incoming.cards) && !cardsImproved(known.cards, incoming.cards) -> known.cards
                else -> incoming.cards
            },
            voices = when {
                incoming.voices.isEmpty() -> known.voices
                sameVoices(known.voices, incoming.voices) -> known.voices
                else -> incoming.voices
            },
            forwards = mergedForwards(known.forwards, incoming.forwards),
            reply = mergedReply(known.reply, incoming.reply),
        )
    }

    /** Цитата ответа: урезанная версия приходит без неё — известная остаётся; имя автора, которого не знали, дописывается. */
    fun mergedReply(known: ChatReply?, incoming: ChatReply?): ChatReply? = when {
        incoming == null -> known
        known != null && known.cmid == incoming.cmid && incoming.sender.isEmpty() -> incoming.copy(sender = known.sender)
        else -> incoming
    }

    /**
     * Пересланные: урезанная версия (из списка диалогов) приходит без них — известные остаются; те же самые (VK
     * подписывает адреса картинок заново в каждом ответе) — тоже известные, иначе сообщение «менялось» при каждом ответе.
     * Имя автора, которого страница ещё не знала, дописывается.
     */
    fun mergedForwards(known: List<ChatForward>, incoming: List<ChatForward>): List<ChatForward> = when {
        incoming.isEmpty() -> known
        sameForwards(known, incoming) ->
            if (known.indices.any { known[it].sender.isEmpty() && incoming[it].sender.isNotEmpty() }) incoming else known
        else -> incoming
    }

    fun sameForwards(a: List<ChatForward>, b: List<ChatForward>): Boolean =
        a.size == b.size && a.indices.all { i ->
            val x = a[i]
            val y = b[i]
            x.text == y.text && x.depth == y.depth && x.time == y.time && (x.sender == y.sender || x.sender.isEmpty() || y.sender.isEmpty()) &&
                samePhotos(x.photos, y.photos) && sameVideos(x.videos, y.videos) && sameCards(x.cards, y.cards) &&
                sameVoices(x.voices, y.voices)
        }

    /** Те же голосовые: по номеру, а без него — по пути записи (подпись и сервер в адресе меняются). */
    fun sameVoices(a: List<ChatVoice>, b: List<ChatVoice>): Boolean {
        return ListMerge.sameKeys(a, b) { v -> v.id.ifEmpty { ImageKey.identity(v.url) } }
    }

    /** Те же карточки: совпадают вид и адрес (название и подпись могли прийти иначе). */
    fun sameCards(a: List<ChatCard>, b: List<ChatCard>): Boolean {
        return ListMerge.sameKeys(a, b) { c -> c.kind + "|" + c.url + "|" + c.path + "|" + c.trackId }
    }

    /** Карточка музыки получила то, чего не было: обложку или номер трека (в сохранённом они ещё не хранились). */
    fun cardsImproved(known: List<ChatCard>, incoming: List<ChatCard>): Boolean =
        known.size == incoming.size && known.indices.any { i ->
            (known[i].cover.isEmpty() && incoming[i].cover.isNotEmpty()) || (known[i].trackId.isEmpty() && incoming[i].trackId.isNotEmpty())
        }

    /**
     * Новая версия принесла то, чего не было: обложку там, где её не было, или признак клипа. Обложка у ролика бывает
     * только в вёрстке мессенджера и появляется позже ответа API, поэтому первое сообщение приходит без неё.
     */
    fun videosImproved(known: List<ChatVideo>, incoming: List<ChatVideo>): Boolean {
        val old = known.associateBy { it.id }
        return incoming.any { v ->
            val was = old[v.id] ?: return@any false
            (was.coverUrl.isEmpty() && v.coverUrl.isNotEmpty()) || (!was.isClip && v.isClip)
        }
    }

    /** Те же видео: совпадают номера (в любом порядке); название и обложка могли прийти в другом виде. */
    fun sameVideos(a: List<ChatVideo>, b: List<ChatVideo>): Boolean = ListMerge.sameKeys(a, b) { it.id }

    /**
     * Те же фото: совпадают пути картинок ([ImageKey.identity]) — без сервера, подписи и размера, в любом порядке.
     * Одна картинка приходит с разной подписью и разным набором размеров, и по полному адресу (даже без подписи) сообщение
     * считалось изменённым при каждом обновлении переписки.
     */
    fun samePhotos(a: List<ChatPhoto>, b: List<ChatPhoto>): Boolean {
        if (a.size != b.size) return false
        // Номер фото в VK надёжнее всего: у разных размеров одного снимка бывают разные пути картинок.
        if (a.all { it.id.isNotEmpty() } && b.all { it.id.isNotEmpty() }) return ListMerge.sameKeys(a, b) { it.id }
        return ListMerge.sameKeys(a, b) { ImageKey.identity(it.url) }
    }

    /**
     * Название беседы для сохранения: настоящее имя, а не заглушка, которую страница присылает, пока имя не пришло
     * («id12345», «Сообщество 5», «Беседа»). Иначе название в файле менялось между порциями, и файл перезаписывался.
     */
    fun chatTitle(fromPage: String, known: String): String {
        val page = fromPage.trim()
        val shown = known.trim()
        return listOf(page, shown).firstOrNull { it.isNotEmpty() && !isPlaceholderTitle(it) } ?: page.ifEmpty { shown }
    }

    fun isPlaceholderTitle(title: String): Boolean = PLACEHOLDER_TITLE.matches(title.trim())

    private val PLACEHOLDER_TITLE = Regex("""^(id\d+|Сообщество \d+|Беседа)$""")

    /**
     * Какие поля у одних и тех же сообщений изменились между [before] и [after] — для журнала: если список «дышит»
     * при каждом входе, по отчёту видно, что именно меняется. Не больше трёх сообщений.
     */
    fun describeChanges(before: List<ChatMessage>, after: List<ChatMessage>): String {
        val old = before.associateBy { it.id }
        val parts = ArrayList<String>()
        for (m in after) {
            val o = old[m.id] ?: continue
            val fields = buildList {
                if (o.text != m.text) add("текст")
                if (o.time != m.time) add("время")
                if (o.sender != m.sender) add("автор")
                if (o.out != m.out) add("свой")
                if (o.photos != m.photos) add("фото ${o.photos.size}→${m.photos.size}")
                if (o.forwards != m.forwards) add("пересланные ${o.forwards.size}→${m.forwards.size}")
                if (o.status != m.status) add("статус")
            }
            if (fields.isNotEmpty()) parts += "${m.id}: ${fields.joinToString("+")}"
            if (parts.size >= 3) break
        }
        return parts.joinToString("; ")
    }

    /**
     * Что сохранять на диск: только подтверждённые сервером сообщения (с номером в беседе). Черновые — догадки по вёрстке
     * и неотправленное — живут только на экране: сохранённые, они при следующем открытии сначала показывались, а потом
     * пропадали из-за первой неполной порции (список «скакал» при каждом входе).
     */
    fun persistable(messages: List<ChatMessage>): List<ChatMessage> = messages.filter { it.isConfirmed }

    /** Текст для сравнения: без регистра, пробелов и знаков (как в assets/vk_reader.js). */
    fun matchText(value: String): String = value.lowercase().filter { it in 'a'..'z' || it in '0'..'9' || it in 'а'..'я' || it == 'ё' }

    /** Совпадает ли текст черновика с одним из известных: равен, или одно содержит другое (от трёх знаков). */
    fun duplicatesKnown(text: String, knownTexts: List<String>): Boolean {
        val d = matchText(text)
        if (d.isEmpty()) return false
        return knownTexts.any { k -> k == d || (k.length >= 3 && d.contains(k)) || (d.length >= 3 && k.contains(d)) }
    }

    /** Диалоги для сохранения: свежий порядок, затем недостающие из известного. */
    fun dialogs(shown: List<DialogEntry>, incoming: List<DialogEntry>): List<DialogEntry> {
        val known = shown.associateBy { it.peerId }
        return ListMerge.freshFirst(shown, incoming.map { keepRealTitle(known[it.peerId], it) }) { it.peerId }
    }

    /**
     * Страница подписывает собеседника «id12345», «Сообщество 5» или «Беседа», пока не пришли профили (в ответе их может
     * не быть). Такая подпись не должна затирать настоящее имя, которое уже известно (показанное или сохранённое).
     */
    fun keepRealTitle(known: DialogEntry?, incoming: DialogEntry): DialogEntry {
        if (known == null || known.title.isBlank() || isPlaceholderTitle(known.title)) return incoming
        return if (incoming.title.isBlank() || isPlaceholderTitle(incoming.title)) incoming.copy(title = known.title) else incoming
    }

    /**
     * Тот же ли набор диалогов: порядок и «в сети» не в счёт (экран показывает устойчивый порядок, а не настоящий,
     * и «в сети» мигает сам по себе) — иначе автообновление считало бы каждую перезагрузку «с новым».
     */
    fun sameDialogs(a: List<DialogEntry>, b: List<DialogEntry>): Boolean {
        if (a.size != b.size) return false
        fun key(d: DialogEntry) = listOf(d.peerId, d.title, d.snippet, d.time, d.unread.toString())
        return a.map(::key).toSet() == b.map(::key).toSet()
    }

    /** Сколько последних настоящих сообщений сверять с черновыми. */
    private const val RECENT_FOR_DRAFTS = 60

    /** Номер сообщения в беседе для id вида «c123», иначе null (черновое). */
    private fun cmid(message: ChatMessage): Long? =
        if (message.id.startsWith("c")) message.id.substring(1).toLongOrNull() else null
}
