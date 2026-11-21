package com.example.vkweb.messages

import android.text.SpannableString
import android.text.Spanned
import android.text.style.ClickableSpan
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.example.vkweb.R
import com.example.vkweb.offline.ImageCache
import com.example.vkweb.ui.PhotoViewerActivity
import com.example.vkweb.ui.Views

/**
 * Переписка: входящие слева, исходящие справа. Неотправленные ([ChatMessage.isPending]) показаны справа
 * с пометкой вместо времени; нажатие на них — [onPendingClick] (отправить ещё раз, не отправлять).
 * Нажатие на доставленное сообщение открывает его действия ([onMenu]): ответить, переслать, поделиться, копировать —
 * раньше для этого нужно было зажать и подождать. Ссылки в тексте и вложения по нажатию открываются сами.
 */
class ChatAdapter(
    private val onPendingClick: (ChatMessage) -> Unit = {},
    /** Нажатие на карточку видео во вложении. */
    private val onVideoClick: (ChatVideo) -> Unit = {},
    /** Нажатие на карточку ссылки, файла, записи или клипа. */
    private val onCardClick: (ChatCard) -> Unit = {},
    /** Нажатие на ссылку в тексте сообщения. */
    private val onLinkClick: (String) -> Unit = {},
    /** Действия: нажатие на пузырь — с сообщением; долгое нажатие на вложение-карточку — с ним, на фото — с фото. */
    private val onMenu: (MenuTarget) -> Unit = {},
    /** Нажатие на цитату ответа: перейти к сообщению, на которое ответили. */
    private val onReplyClick: (ChatReply) -> Unit = {},
    /** Голосовое: играть/пауза (3.71 — как музыка: очередь, уведомление, перемотка). */
    private val onVoiceClick: (ChatVoice) -> Unit = {},
    /** Голосовое перемотали полосой в пузыре на [ms] от начала. */
    private val onVoiceSeek: (ChatVoice, Int) -> Unit = { _, _ -> },
    /** Нажатие на аватар автора в беседе (4.53): его страница. */
    private val onSenderClick: (ChatMessage) -> Unit = {},
    /** Нажатие на реакцию под сообщением (4.61): поставить такую же или снять свою. */
    private val onReactionClick: (ChatMessage, ChatReaction) -> Unit = { _, _ -> },
) : ListAdapter<ChatMessage, ChatAdapter.Holder>(DIFF) {

    /**
     * До какого номера в беседе собеседник прочитал свои сообщения (3.72): у своих доставленных — «✓✓» (прочитано)
     * или «✓» (ещё нет). 0 — не известно, отметок нет.
     */
    /** Картинки реакций по номеру (4.61); без картинки реакция не показывается. */
    var reactionImages: Map<Int, String> = emptyMap()
        set(value) {
            if (field == value) return
            field = value
            // 4.82: только строки с реакциями. Перерисовка всех строк сразу после открытия переписки (картинки реакций
            // приходят через пару секунд) сдвигала список: сообщения «улетали» вверх и съезжали обратно.
            notifyRows { it.reactions.isNotEmpty() }
        }

    var outReadCmid: Long = 0
        set(value) {
            if (field == value) return
            val old = field
            field = value
            // Только свои отправленные, у которых сменилась отметка «✓ / ✓✓», и только строка состояния (4.82):
            // раньше на первое же обновление страницы перерисовывалась вся переписка.
            val low = minOf(old, value)
            val high = maxOf(old, value)
            notifyRows(PAYLOAD_STATUS) { m ->
                val cmid = if (m.out && m.isConfirmed) m.id.drop(1).toLongOrNull() ?: 0L else 0L
                cmid > 0 && (old <= 0 || value <= 0 || cmid in (low + 1)..high)
            }
        }

    /** Перерисовать строки, подходящие под [which] ([payload] — что именно; null — целиком). */
    private fun notifyRows(payload: Any? = null, which: (ChatMessage) -> Boolean) {
        currentList.forEachIndexed { i, m -> if (which(m)) notifyItemChanged(i, payload) }
    }

    /** Для чего действия: сообщение, карточка вложения (в сообщении [message]) или фото [url]. */
    sealed interface MenuTarget {
        val message: ChatMessage
        data class Message(override val message: ChatMessage) : MenuTarget
        data class Card(override val message: ChatMessage, val card: ChatCard) : MenuTarget
        data class Photo(override val message: ChatMessage, val url: String) : MenuTarget
    }

    override fun getItemViewType(position: Int) = if (getItem(position).out) TYPE_OUT else TYPE_IN

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): Holder {
        // Свои — справа, синим; чужие — слева, серым. Отступ с противоположной стороны ограничивает ширину пузыря.
        val layout = if (viewType == TYPE_OUT) R.layout.item_message_out else R.layout.item_message_in
        val view = LayoutInflater.from(parent.context).inflate(layout, parent, false)
        return Holder(view, onPendingClick, onVideoClick, onCardClick, onLinkClick, onMenu, onReplyClick, onVoiceClick, onVoiceSeek, onSenderClick, onReactionClick, { reactionImages }) { outReadCmid }
    }

    override fun onBindViewHolder(holder: Holder, position: Int) {
        holder.bind(getItem(position))
        holder.bindGroup(groupedWithPrevious(position), groupedWithPrevious(position + 1), ChatDays.avatarSlot(currentList, position))
    }

    /** Изменилась только принадлежность к «реплике» (пришло следующее сообщение того же автора) — без перерисовки пузыря. */
    override fun onBindViewHolder(holder: Holder, position: Int, payloads: MutableList<Any>) {
        if (payloads.isNotEmpty() && payloads.all { it == PAYLOAD_GROUP || it == PAYLOAD_STATUS }) {
            if (PAYLOAD_GROUP in payloads) {
                holder.bindGroup(groupedWithPrevious(position), groupedWithPrevious(position + 1), ChatDays.avatarSlot(currentList, position))
            }
            if (PAYLOAD_STATUS in payloads) holder.bindStatus(getItem(position))
        } else {
            onBindViewHolder(holder, position)
        }
    }

    private fun groupedWithPrevious(position: Int) = ChatDays.groupedWithPrevious(currentList, position)

    /**
     * Группа соседей могла смениться у строк, которые сами не изменились (DiffUtil их не перерисует): новое сообщение
     * снизу снимает хвостик с предыдущего, догрузка сверху меняет отступ у прежнего первого. Им — лёгкая перерисовка.
     */
    override fun onCurrentListChanged(previousList: List<ChatMessage>, currentList: List<ChatMessage>) {
        if (previousList.isEmpty()) return
        val before = HashMap<String, Pair<Boolean, Boolean>>(previousList.size)
        previousList.indices.forEach { i ->
            before[previousList[i].id] = ChatDays.groupedWithPrevious(previousList, i) to ChatDays.groupedWithPrevious(previousList, i + 1)
        }
        currentList.indices.forEach { i ->
            val was = before[currentList[i].id] ?: return@forEach
            val now = ChatDays.groupedWithPrevious(currentList, i) to ChatDays.groupedWithPrevious(currentList, i + 1)
            if (was != now) notifyItemChanged(i, PAYLOAD_GROUP)
        }
    }

    class Holder(
        view: View,
        private val onPendingClick: (ChatMessage) -> Unit,
        private val onVideoClick: (ChatVideo) -> Unit,
        private val onCardClick: (ChatCard) -> Unit,
        private val onLinkClick: (String) -> Unit,
        private val onMenu: (MenuTarget) -> Unit,
        private val onReplyClick: (ChatReply) -> Unit,
        private val onVoiceClick: (ChatVoice) -> Unit,
        private val onVoiceSeek: (ChatVoice, Int) -> Unit,
        private val onSenderClick: (ChatMessage) -> Unit,
        private val onReactionClick: (ChatMessage, ChatReaction) -> Unit,
        private val reactionImages: () -> Map<Int, String>,
        /** До какого номера собеседник прочитал свои сообщения (значение адаптера на момент показа). */
        private val readUpTo: () -> Long,
    ) : RecyclerView.ViewHolder(view) {
        private val reply: View = view.findViewById(R.id.message_reply)
        private val replySender: TextView = view.findViewById(R.id.message_reply_sender)
        private val replyText: TextView = view.findViewById(R.id.message_reply_text)
        private val sender: TextView = view.findViewById(R.id.message_sender)
        private val text: TextView = view.findViewById(R.id.message_text)
        private val time: TextView = view.findViewById(R.id.message_time)
        /** Есть только у своих сообщений. */
        private val status: ImageView? = view.findViewById(R.id.message_status)
        private var current: ChatMessage? = null
        /** Фото, по которым построены картинки в пузыре: пока не изменились, пузырь их не пересоздаёт. */
        private var boundPhotos: List<ChatPhoto>? = null
        private val photos: LinearLayout = view.findViewById(R.id.message_photos)
        private val videos: LinearLayout = view.findViewById(R.id.message_videos)
        private val cards: LinearLayout = view.findViewById(R.id.message_cards)
        private var boundCards: List<ChatCard>? = null
        private var boundText: String? = null
        private var boundPending = false
        private val voices: LinearLayout = view.findViewById(R.id.message_voices)
        private var boundVoices: List<ChatVoice>? = null
        private val forwards: LinearLayout = view.findViewById(R.id.message_forwards)
        private var boundForwards: List<ChatForward>? = null
        /** Видео, по которым построены карточки: пока не изменились, пузырь их не пересоздаёт. */
        private var boundVideos: List<ChatVideo>? = null
        private val images = ImageCache.get(view.context)
        private val photoPx = Views.dp(view, PHOTO_DP)
        private val bubble: View = view.findViewById(R.id.bubble)
        private val reactions: LinearLayout = view.findViewById(R.id.message_reactions)
        /** Аватар автора в беседе (4.53); есть только у чужих сообщений. */
        private val avatar: ImageView? = view.findViewById(R.id.message_avatar)
        private val avatarPx = Views.dp(view, AVATAR_DP)
        private val touchExtraPx = Views.dp(view, (MIN_TOUCH_DP - AVATAR_DP) / 2)
        /** Цвет цитат и пересланного у чужих — акцент темы (имя автора с 4.53 — своим цветом, им их красить нельзя). */
        private val accentColor = com.google.android.material.color.MaterialColors.getColor(view, androidx.appcompat.R.attr.colorPrimary)
        /** Фон входящего пузыря тёмный — имена светлыми цветами ([AvatarPalette.nameColorFor]). */
        private val darkBubble = AvatarPalette.isDark(
            com.google.android.material.color.MaterialColors.getColor(view, com.google.android.material.R.attr.colorSurfaceContainerHigh),
        )

        init {
            // Нажатие на пузырь (и на строку рядом) — действия с сообщением; долгое — то же, по старой привычке.
            listOfNotNull(view, view.findViewById<View>(R.id.bubble)).forEach { target ->
                target.setOnClickListener { tap() }
                target.setOnLongClickListener { tap(); true }
            }
            avatar?.let { a ->
                Views.clipCircle(a)
                a.setOnClickListener { current?.let(onSenderClick) }
            }
            // Текст не перехватывает нажатие: оно уходит пузырю, кроме нажатия на ссылку (выделения больше нет —
            // текст копируется пунктом «Копировать текст»).
            text.isClickable = false
            text.isLongClickable = false
            text.setOnTouchListener(LinkTouch())
        }

        /**
         * Место в «реплике» (3.94): внутри неё — отступ меньше и имя автора не повторяется; хвостик пузыря — только у
         * последнего сообщения подряд.
         */
        fun bindGroup(withPrevious: Boolean, withNext: Boolean, slot: ChatDays.AvatarSlot = ChatDays.AvatarSlot.NONE) {
            sender.visibility = if (current?.sender.isNullOrEmpty() || withPrevious) View.GONE else View.VISIBLE
            bindAvatar(slot)
            val out = status != null
            bubble.setBackgroundResource(
                when {
                    !withNext && out -> R.drawable.bubble_out
                    !withNext -> R.drawable.bubble_in
                    out -> R.drawable.bubble_out_grouped
                    else -> R.drawable.bubble_in_grouped
                },
            )
            itemView.setPadding(
                itemView.paddingLeft, Views.dp(itemView, if (withPrevious) GROUP_GAP_DP else GROUP_START_DP),
                itemView.paddingRight, Views.dp(itemView, GROUP_GAP_DP),
            )
        }

        /**
         * Аватар автора в беседе (4.53): у последнего сообщения «реплики» — фото (только уже показанное или скачанное
         * кэшем) или кружок с инициалами; внутри реплики — пустое место той же ширины; в личной переписке — ничего.
         */
        private fun bindAvatar(slot: ChatDays.AvatarSlot) {
            val a = avatar ?: return
            val m = current
            itemView.touchDelegate = null
            when {
                m == null || slot == ChatDays.AvatarSlot.NONE -> a.visibility = View.GONE
                slot == ChatDays.AvatarSlot.SPACE -> {
                    a.visibility = View.INVISIBLE
                    a.isClickable = false
                }
                else -> {
                    a.visibility = View.VISIBLE
                    images.load(a, m.senderAvatar.ifEmpty { null }, avatarPx) { AvatarBadge(m.sender) }
                    val page = ChatPeople.pathOf(m.senderId) != null
                    a.isClickable = page
                    a.contentDescription = if (page) a.context.getString(R.string.chat_sender_open, m.sender) else m.sender
                    a.importantForAccessibility = if (page) View.IMPORTANT_FOR_ACCESSIBILITY_YES else View.IMPORTANT_FOR_ACCESSIBILITY_NO
                    // 4.54: картинка 32dp, а попадать пальцем — по 48dp (норма Android): зона нажатия шире на 8dp с каждой
                    // стороны, в пределах строки.
                    if (page) itemView.post {
                        if (a.visibility != View.VISIBLE || current !== m) return@post
                        val hit = android.graphics.Rect()
                        a.getHitRect(hit)
                        hit.inset(-touchExtraPx, -touchExtraPx)
                        itemView.touchDelegate = android.view.TouchDelegate(hit, a)
                    }
                }
            }
        }

        /**
         * Реакции под сообщением (4.61): картинка VK (16dp) и число; своя — с обводкой. Номер без картинки не
         * показывается (своей таблицы «номер → эмодзи» нет — не та реакция хуже никакой).
         */
        private fun bindReactions(item: ChatMessage) {
            reactions.removeAllViews()
            val known = reactionImages()
            val shown = item.reactions.filter { known[it.id] != null }
            reactions.visibility = if (shown.isEmpty()) View.GONE else View.VISIBLE
            val ctx = reactions.context
            val icon = Views.dp(reactions, REACTION_ICON_DP)
            val gap = Views.dp(reactions, 4)
            shown.forEach { r ->
                val chip = LinearLayout(ctx).apply {
                    orientation = LinearLayout.HORIZONTAL
                    gravity = android.view.Gravity.CENTER_VERTICAL
                    setBackgroundResource(if (r.mine) R.drawable.reaction_chip_mine else R.drawable.reaction_chip)
                    setPadding(Views.dp(this, 8), Views.dp(this, 4), Views.dp(this, 8), Views.dp(this, 4))
                    minimumHeight = Views.dp(this, 48)
                    minimumWidth = Views.dp(this, 48)
                    contentDescription = ctx.getString(if (r.mine) R.string.reaction_description_mine else R.string.reaction_description, r.count)
                    setOnClickListener { onReactionClick(item, r) }
                    layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { marginEnd = gap }
                }
                val image = ImageView(ctx).apply {
                    layoutParams = LinearLayout.LayoutParams(icon, icon)
                    importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
                }
                images.load(image, known[r.id], icon)
                val count = TextView(ctx).apply {
                    text = if (r.count > 1) r.count.toString() else ""
                    setTextAppearance(com.google.android.material.R.style.TextAppearance_Material3_LabelMedium)
                    setTextColor(com.google.android.material.color.MaterialColors.getColor(this, com.google.android.material.R.attr.colorOnSurface))
                    setPadding(if (r.count > 1) Views.dp(this, 4) else 0, 0, 0, 0)
                    importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
                }
                chip.addView(image)
                chip.addView(count)
                reactions.addView(chip)
            }
        }

        /** Неотправленное — его действия (повторить, удалить); доставленное — меню сообщения. */
        private fun tap() {
            val m = current ?: return
            if (m.isPending) onPendingClick(m) else onMenu(MenuTarget.Message(m))
        }

        fun bind(item: ChatMessage) {
            current = item
            bindStatus(item)
            sender.text = item.sender
            bindReactions(item)
            // 4.53: имя — цветом автора (как его кружок), подобранным под фон пузыря; так авторов видно сразу.
            if (item.sender.isNotEmpty()) sender.setTextColor(AvatarPalette.nameColorFor(item.sender, darkBubble))
            bindReply(item.reply)
            // Текст (и его ссылки) заново — только если он изменился: смена статуса отправки его не трогает, а перенастройка
            // выделения и ссылок на каждую перерисовку строки лишняя работа в главном потоке.
            if (item.text != boundText || item.isPending != boundPending) {
                boundText = item.text
                boundPending = item.isPending
                text.text = linked(item)
            }
            text.visibility = if (item.text.isEmpty()) View.GONE else View.VISIBLE
            // Строка перерисовывается и при смене только статуса отправки: картинки пересоздавать незачем.
            if (item.photos != boundPhotos) {
                boundPhotos = item.photos
                bindPhotos(photos, item.photos)
            }
            if (item.videos != boundVideos) {
                boundVideos = item.videos
                bindVideos(videos, item.videos)
            }
            if (item.cards != boundCards) {
                boundCards = item.cards
                bindCards(cards, item.cards)
            }
            if (item.voices != boundVoices) {
                boundVoices = item.voices
                bindVoices(voices, item.voices)
            }
            if (item.forwards != boundForwards) {
                boundForwards = item.forwards
                bindForwards(item.forwards)
            }
        }

        /** Цитата ответа: автор (или «Ответ на сообщение») и начало текста; цвета — как у пересланного. */
        private fun bindReply(r: ChatReply?) {
            reply.visibility = if (r == null) View.GONE else View.VISIBLE
            if (r == null) return
            val accent = if (status != null) text.currentTextColor else accentColor
            replySender.text = r.sender.ifEmpty { reply.context.getString(R.string.reply_to_message) }
            replySender.setTextColor(accent)
            reply.findViewById<View>(R.id.message_reply_bar).setBackgroundColor(accent)
            replyText.text = r.text
            replyText.setTextColor(text.currentTextColor)
            replyText.visibility = if (r.text.isEmpty()) View.GONE else View.VISIBLE
            reply.contentDescription = reply.context.getString(R.string.reply_open_description, r.text.ifEmpty { replySender.text })
            reply.setOnClickListener { onReplyClick(r) }
        }

        /** Долгое нажатие на вложение — только у доставленного (у неотправленного свои действия по обычному нажатию). */
        private fun longPress(build: (ChatMessage) -> MenuTarget): Boolean {
            val m = current?.takeIf { !it.isPending } ?: return false
            onMenu(build(m))
            return true
        }

        /**
         * Текст с нажимаемыми ссылками. У неотправленного сообщения ссылок нет: нажатие на него открывает действия, а
         * текст с ссылками перехватывал бы нажатие.
         */
        private fun linked(item: ChatMessage): CharSequence {
            val links = if (item.isPending) emptyList() else LinkFinder.find(item.text)
            if (links.isEmpty()) return item.text
            val span = SpannableString(item.text)
            links.forEach { link ->
                span.setSpan(object : ClickableSpan() {
                    override fun onClick(widget: View) = onLinkClick(link.url)
                }, link.start, link.end, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            }
            return span
        }

        /**
         * Пересланные: блок на каждое — полоса слева, «Переслано · Автор, время», вложения и текст; вложенные (переслано
         * внутри пересланного) — с отступом по уровню. Цвет подписи и полосы — как у имени автора в чужих сообщениях и
         * как у текста в своих (на синем фоне основной цвет не читается).
         */
        private fun bindForwards(list: List<ChatForward>) = fill(forwards, list, R.layout.item_forward) { view, forward ->
            val ctx = view.context
            val accent = if (status != null) text.currentTextColor else accentColor
            val header = view.findViewById<TextView>(R.id.forward_header)
            header.text = when {
                forward.sender.isEmpty() -> ctx.getString(R.string.forward_header)
                forward.time.isEmpty() -> ctx.getString(R.string.forward_header_from, forward.sender)
                else -> ctx.getString(R.string.forward_header_from_time, forward.sender, forward.time)
            }
            header.setTextColor(accent)
            view.findViewById<View>(R.id.forward_bar).setBackgroundColor(accent)
            (view.layoutParams as LinearLayout.LayoutParams).marginStart = Views.dp(view, FORWARD_INDENT_DP) * (forward.depth - 1)
            val body = view.findViewById<TextView>(R.id.forward_text)
            body.text = forward.text
            body.setTextColor(text.currentTextColor)
            body.visibility = if (forward.text.isEmpty()) View.GONE else View.VISIBLE
            bindPhotos(view.findViewById(R.id.forward_photos), forward.photos)
            bindVideos(view.findViewById(R.id.forward_videos), forward.videos)
            bindVoices(view.findViewById(R.id.forward_voices), forward.voices)
            bindCards(view.findViewById(R.id.forward_cards), forward.cards)
        }

        /** Голосовые сообщения: кнопка, полоса, время; играет само приложение ([VoicePlayer]), одно за раз. */
        private fun bindVoices(container: LinearLayout, list: List<ChatVoice>) = fill(container, list, R.layout.item_voice_card) { view, voice ->
            val toggle = view.findViewById<ImageView>(R.id.voice_toggle)
            val progress = view.findViewById<android.widget.SeekBar>(R.id.voice_progress)
            val time = view.findViewById<TextView>(R.id.voice_time)
            toggle.setOnClickListener { onVoiceClick(voice) }
            var dragging = false
            var shownDuration = voice.durationSec * 1000
            progress.setOnSeekBarChangeListener(object : android.widget.SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(bar: android.widget.SeekBar, value: Int, fromUser: Boolean) {
                    if (fromUser && shownDuration > 0) {
                        time.text = com.example.vkweb.ui.PlayerFormat.clockMs(value.toLong() * shownDuration / 1000, shownDuration.toLong())
                    }
                }

                override fun onStartTrackingTouch(bar: android.widget.SeekBar) { dragging = true }

                override fun onStopTrackingTouch(bar: android.widget.SeekBar) {
                    dragging = false
                    if (shownDuration > 0) onVoiceSeek(voice, (bar.progress.toLong() * shownDuration / 1000).toInt())
                }
            })
            // Карточка следит за проигрывателем: кнопка, полоса и время всегда соответствуют тому, что звучит.
            VoicePlayer.observe(view, voice.url) { state ->
                val ctx = view.context
                toggle.setImageResource(if (state.playing || state.loading) R.drawable.ic_player_pause else R.drawable.ic_player_play)
                toggle.contentDescription = ctx.getString(if (state.playing) R.string.voice_pause else R.string.voice_play)
                val duration = if (state.durationMs > 0) state.durationMs else voice.durationSec * 1000
                shownDuration = duration
                if (dragging) return@observe
                progress.progress = VoiceFormat.progress(state.positionMs, duration)
                time.text = when {
                    state.failed -> ctx.getString(R.string.voice_failed)
                    state.playing || state.positionMs > 0 -> com.example.vkweb.ui.PlayerFormat.clockMs(state.positionMs.toLong(), duration.toLong())
                    else -> VoiceFormat.time(duration)
                }
            }
        }

        /** Карточки ссылок, файлов, записей и клипов: значок, название, подпись; нажатие открывает. */
        private fun bindCards(container: LinearLayout, list: List<ChatCard>) = fill(container, list, R.layout.item_attachment_card) { view, card ->
            view.findViewById<ImageView>(R.id.card_icon).setImageResource(
                when (card.kind) {
                    "doc" -> R.drawable.ic_card_file
                    "wall" -> R.drawable.ic_card_post
                    "clip", "video_message" -> R.drawable.ic_card_clip
                    "audio" -> R.drawable.ic_card_music
                    "call" -> if (card.title.contains("идео")) R.drawable.ic_card_video_call else R.drawable.ic_card_call
                    else -> R.drawable.ic_card_link
                },
            )
            bindCover(view, card)
            view.findViewById<TextView>(R.id.card_title).text = card.title
            val subtitle = view.findViewById<TextView>(R.id.card_subtitle)
            subtitle.text = card.subtitle
            subtitle.visibility = if (card.subtitle.isEmpty() || card.subtitle == card.title) View.GONE else View.VISIBLE
            view.contentDescription = view.context.getString(R.string.card_open_description, card.title)
            // Звонок без ссылки «присоединиться» открыть некуда: нажатие — как на сам пузырь.
            val opens = card.url.isNotEmpty() || card.path.isNotEmpty() || card.kind != "call"
            view.setOnClickListener { if (opens) onCardClick(card) else tap() }
            view.setOnLongClickListener { if (opens) longPress { MenuTarget.Card(it, card) } else { tap(); true } }
        }

        /** Музыка: слева обложка (пока нет или не загрузилась — нота на заглушке), вместо маленького значка. */
        private fun bindCover(view: View, card: ChatCard) {
            val cover = view.findViewById<ImageView>(R.id.card_cover)
            val icon = view.findViewById<ImageView>(R.id.card_icon)
            val isMusic = card.kind == "audio"
            cover.visibility = if (isMusic) View.VISIBLE else View.GONE
            icon.visibility = if (isMusic) View.GONE else View.VISIBLE
            if (!isMusic) return
            Views.clipRounded(cover, Views.dp(cover, COVER_RADIUS_DP).toFloat())
            // Нота — заглушка, пока обложка не загрузилась (или её нет): с отступом, чтобы не обрезалась по краям квадрата.
            val inset = Views.dp(cover, COVER_NOTE_INSET_DP)
            images.load(cover, card.cover.ifEmpty { null }, Views.dp(cover, COVER_DP)) {
                androidx.core.content.ContextCompat.getDrawable(cover.context, R.drawable.ic_card_music)?.let {
                    android.graphics.drawable.InsetDrawable(it, inset)
                }
            }
        }

        /** Карточки видео: обложка, название, длительность; нажатие открывает видео. */
        private fun bindVideos(container: LinearLayout, list: List<ChatVideo>) = fill(container, list, R.layout.item_video_card) { card, video ->
            Views.clipRounded(card, Views.dp(container, PHOTO_RADIUS_DP).toFloat())
            val title = card.findViewById<TextView>(R.id.video_title)
            val label = video.title.ifEmpty { if (video.isClip) card.context.getString(R.string.clip_title) else "" }
            title.text = label
            title.visibility = if (label.isEmpty()) View.GONE else View.VISIBLE
            val duration = card.findViewById<TextView>(R.id.video_duration)
            duration.text = durationText(video.durationSec)
            duration.visibility = if (video.durationSec > 0) View.VISIBLE else View.GONE
            card.contentDescription = card.context.getString(R.string.video_open_description, video.title)
            card.setOnClickListener { onVideoClick(video) }
            card.setOnLongClickListener { longPress { MenuTarget.Message(it) } }
            val cover = card.findViewById<ImageView>(R.id.video_cover)
            if (video.coverUrl.isNotEmpty()) images.load(cover, video.coverUrl, photoPx)
        }

        /**
         * Список вложений одного вида в пузыре: очистить контейнер, по разметке [layout] построить по строке на элемент
         * (отступ между ними) и заполнить её [bind]. Общее для видео, карточек и голосовых — раньше был тройной повтор.
         */
        private fun <T> fill(container: LinearLayout, list: List<T>, layout: Int, bind: (View, T) -> Unit) {
            container.removeAllViews()
            container.visibility = if (list.isEmpty()) View.GONE else View.VISIBLE
            val inflater = LayoutInflater.from(container.context)
            val gap = Views.dp(container, PHOTO_GAP_DP)
            list.forEach { item ->
                val view = inflater.inflate(layout, container, false)
                (view.layoutParams as LinearLayout.LayoutParams).topMargin = gap
                bind(view, item)
                container.addView(view)
            }
        }

        /** «1:15», «1:02:30». */
        private fun durationText(seconds: Int): String = com.example.vkweb.ui.PlayerFormat.timeSec(seconds.toLong())

        /** У доставленного — время. У неотправленного — состояние словами и значок (не только цветом). */
        fun bindStatus(item: ChatMessage) {
            val context = itemView.context
            val cmid = if (item.isConfirmed) item.id.drop(1).toLongOrNull() ?: 0L else 0L
            val outReadCmid = readUpTo()
            val readMark = when {
                !item.out || item.status != MessageStatus.SENT || outReadCmid <= 0 || cmid <= 0 -> ""
                cmid <= outReadCmid -> " ✓✓"
                else -> " ✓"
            }
            val label = when (item.status) {
                MessageStatus.SENT -> ChatDays.time(item) + readMark
                MessageStatus.QUEUED -> context.getString(R.string.message_status_queued)
                MessageStatus.SCHEDULED -> context.getString(R.string.message_status_scheduled, item.time)
                MessageStatus.SENDING -> context.getString(R.string.message_status_sending)
                MessageStatus.UNCONFIRMED -> context.getString(if (item.deliveryNeedsReview) R.string.message_status_manual_check else R.string.message_status_unconfirmed)
                MessageStatus.FAILED -> context.getString(R.string.message_status_failed)
            }
            time.text = label
            time.contentDescription = when (readMark) {
                " ✓✓" -> context.getString(R.string.message_read_by_peer, ChatDays.time(item))
                " ✓" -> context.getString(R.string.message_unread_by_peer, ChatDays.time(item))
                else -> null
            }
            time.visibility = if (label.isEmpty()) View.GONE else View.VISIBLE
            val icon = when (item.status) {
                MessageStatus.SENT -> 0
                MessageStatus.QUEUED, MessageStatus.SENDING, MessageStatus.SCHEDULED -> R.drawable.ic_status_pending
                MessageStatus.UNCONFIRMED -> if (item.deliveryNeedsReview) R.drawable.ic_status_error else R.drawable.ic_status_pending
                MessageStatus.FAILED -> R.drawable.ic_status_error
            }
            status?.visibility = if (icon == 0) View.GONE else View.VISIBLE
            if (icon != 0) status?.setImageResource(icon)
        }

        /** Фото в пузыре: оригинал сохраняется в кэш, на экране — уменьшенная копия; нажатие — на весь экран. */
        private fun bindPhotos(container: LinearLayout, list: List<ChatPhoto>) {
            container.removeAllViews()
            container.visibility = if (list.isEmpty()) View.GONE else View.VISIBLE
            val gap = Views.dp(container, PHOTO_GAP_DP)
            // В просмотр — только фото (стикеры там не нужны); номер — среди фото.
            val gallery = list.filter { !it.sticker }
            val urls = gallery.map { it.url }
            val stickerPx = Views.dp(container, STICKER_DP)
            list.forEach { photo ->
                val url = photo.url
                if (photo.sticker) {
                    // Стикер и граффити — маленькой картинкой без рамки и подложки, как в VK.
                    val sticker = ImageView(container.context).apply {
                        layoutParams = LinearLayout.LayoutParams(stickerPx, stickerPx).apply { topMargin = gap }
                        scaleType = ImageView.ScaleType.FIT_CENTER
                        contentDescription = context.getString(R.string.sticker_description)
                        setOnClickListener { tap() }
                        setOnLongClickListener { tap(); true }
                    }
                    container.addView(sticker)
                    images.load(sticker, url, stickerPx)
                    return@forEach
                }
                val position = gallery.indexOf(photo)
                // Высота известна заранее (из размеров фото), поэтому при загрузке картинки список не сдвигается.
                val height = photo.heightFor(photoPx, photoPx / 3, photoPx * 4 / 3)
                val image = ImageView(container.context).apply {
                    layoutParams = LinearLayout.LayoutParams(photoPx, height).apply { topMargin = gap }
                    scaleType = ImageView.ScaleType.CENTER_CROP
                    setBackgroundResource(R.drawable.photo_placeholder)
                    contentDescription = context.getString(R.string.photo_description)
                    // Несколько фото в одном сообщении листаются в просмотре жестом.
                    setOnClickListener { v -> PhotoViewerActivity.open(context, urls, position, source = v) }
                    setOnLongClickListener { longPress { MenuTarget.Photo(it, url) } }
                }
                Views.clipRounded(image, Views.dp(container, PHOTO_RADIUS_DP).toFloat())
                container.addView(image)
                images.load(image, url, maxOf(photoPx, height))
            }
        }
    }

    private companion object {
        const val TYPE_IN = 0
        const val TYPE_OUT = 1
        const val PAYLOAD_GROUP = "group"
        /** Изменилась только строка состояния (отметка прочтения). */
        const val PAYLOAD_STATUS = "status"
        /** Между сообщениями одной «реплики» — 1 + 1 dp; перед новой — больше, чтобы реплики читались отдельно. */
        const val GROUP_GAP_DP = 1
        const val GROUP_START_DP = 6
        const val PHOTO_DP = 240
        /** Аватар автора в беседе (4.53). */
        const val AVATAR_DP = 32
        /** Картинка реакции под сообщением (4.61). */
        const val REACTION_ICON_DP = 16
        /** Наименьшая зона нажатия в Android. */
        const val MIN_TOUCH_DP = 48
        const val PHOTO_GAP_DP = 4
        const val STICKER_DP = 128
        const val PHOTO_RADIUS_DP = 10
        /** Отступ вложенного пересланного (переслано внутри пересланного) на каждый уровень. */
        const val FORWARD_INDENT_DP = 8
        const val COVER_DP = 48
        const val COVER_RADIUS_DP = 8
        const val COVER_NOTE_INSET_DP = 10
        val DIFF = object : DiffUtil.ItemCallback<ChatMessage>() {
            // 5.00.12: своё из очереди и оно же, принятое сервером, - одна строка (обновляется на месте, без
            // удаления и вставки: иначе при отправке строка мигала и список вздрагивал)
            override fun areItemsTheSame(a: ChatMessage, b: ChatMessage) = a.id == b.id || sameSent(a, b)

            private fun sameSent(a: ChatMessage, b: ChatMessage): Boolean =
                a.out && b.out && a.isPending != b.isPending && (if (a.isPending) !a.isConfirmed && b.isConfirmed else a.isConfirmed && !b.isConfirmed) &&
                    com.example.vkweb.outbox.OutboxLogic.normalize(a.text) == com.example.vkweb.outbox.OutboxLogic.normalize(b.text)
            override fun areContentsTheSame(a: ChatMessage, b: ChatMessage) = a == b
        }
    }
}
