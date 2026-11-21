package com.example.vkweb.sync

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.BitmapShader
import android.graphics.Canvas
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Shader
import android.os.Build
import com.example.vkweb.R
import com.example.vkweb.diag.AppLog
import com.example.vkweb.diag.LogTag
import com.example.vkweb.messages.AvatarBadge
import com.example.vkweb.messages.DialogEntry
import com.example.vkweb.messages.MessagesActivity
import com.example.vkweb.offline.ImageCache

/**
 * Уведомления о новых сообщениях. С 4.48 — своё у каждого диалога (с «Ответить» и «Прочитано»), а когда их два и больше,
 * Android собирает их в группу под сводкой «Новые сообщения»; что показать и кто звучит — [NotifyGroup]. До 4.48 было
 * одно уведомление на всё приложение. На заблокированном экране вместо имён и текста — общая надпись
 * (в уведомлении нет ничего, чего не видно в самом приложении, но чужие глаза на экране блокировки ни к чему).
 *
 * 4.47: свои настройки диалогов ([DialogNotify]). Звучит уведомление, только если новое пришло в диалог, который
 * «звучит»; иначе оно обновляется молча — в тихом канале. Текст сообщения не показывается, если так решено для всех
 * (общая настройка) или для этого диалога: остаётся имя и «Новое сообщение».
 */
class MessageNotifier(private val context: Context) {

    private val manager = context.getSystemService(NotificationManager::class.java)

    /**
     * Привести шторку к списку непрочитанных (4.48: по уведомлению на диалог, группой со сводкой — [NotifyGroup]).
     * Вызывается после каждой проверки, а не только когда есть новое: прочитанные в другом месте диалоги уходят из шторки.
     *
     * @param unread непрочитанные, о которых можно сообщать (уже без выключенных и отмеченных «Прочитано»);
     * @param fresh те из них, где новое: их уведомления ставятся заново, по ним решается, звучать ли;
     * @param rules свои настройки диалогов;
     * @param preview общая настройка «текст в уведомлениях».
     * @return false — показать не удалось (нет разрешения или уведомления выключены).
     */
    fun show(
        unread: List<DialogEntry>,
        fresh: List<DialogEntry> = unread,
        rules: Map<String, DialogNotify> = emptyMap(),
        preview: Boolean = true,
    ): Boolean {
        val shown = shownDialogs()
        if (unread.isEmpty()) {
            cancel()
            return true
        }
        if (!canNotify()) {
            AppLog.i(LogTag.SYNC, "уведомление не показано: нет разрешения или уведомления выключены")
            return false
        }
        ensureChannels()
        val plan = NotifyGroup.plan(unread, fresh, shown, rules)
        plan.cancel.forEach { manager.cancel(tagOf(it), DIALOG_ID) }
        plan.post.forEach { post ->
            manager.notify(tagOf(post.dialog.peerId), DIALOG_ID, dialogNotification(post, plan.childrenSilent, rules, preview, emptyList(), quiet = false))
        }
        val summary = plan.summary
        if (summary == null) {
            // Сводки нет — убрать и одиночное уведомление прежних версий: у него тот же номер.
            manager.cancel(SUMMARY_ID)
        } else {
            val lines = summary.dialogs.map { line(it, DialogNotifications.showsText(it.peerId, rules, preview)) }
            manager.notify(SUMMARY_ID, summaryNotification(lines, unreadMessages(summary.dialogs), summary.loudChannel, summary.alerts))
        }
        AppLog.i(
            LogTag.SYNC, "уведомления обновлены",
            "новых" to plan.post.size, "убрано" to plan.cancel.size, "группой" to (summary != null),
            "со звуком" to (if (summary != null) summary.alerts else plan.post.any { it.loud }),
        )
        return true
    }

    /**
     * Дополнить показанные уведомления цепочкой последних сообщений (5.00.12): история диалогов пришла после показа.
     * Ставятся заново только уже показанные и только молча - звук уже прозвучал при первом показе.
     *
     * @param unread непрочитанные, как в [show]; [chains] - строки цепочки по номеру диалога ([NotifyChain]).
     */
    fun updateChains(
        unread: List<DialogEntry>,
        chains: Map<String, List<NotifyChain.Line>>,
        rules: Map<String, DialogNotify> = emptyMap(),
        preview: Boolean = true,
    ) {
        if (chains.isEmpty() || !canNotify()) return
        val shown = shownDialogs()
        var updated = 0
        for (dialog in unread) {
            val chain = chains[dialog.peerId]?.takeIf { it.size > 1 } ?: continue
            val loud = shown[dialog.peerId] ?: continue
            // Скрытый текст - цепочка не показывается вовсе
            if (!DialogNotifications.showsText(dialog.peerId, rules, preview)) continue
            val post = NotifyGroup.Post(dialog, loud)
            manager.notify(tagOf(dialog.peerId), DIALOG_ID, dialogNotification(post, shown.size > 1, rules, preview, chain, quiet = true))
            updated++
        }
        if (updated > 0) AppLog.i(LogTag.SYNC, "уведомления дополнены цепочкой сообщений", "диалогов" to updated)
    }

    /** Убрать все уведомления о сообщениях (открыт экран сообщений, выключены, выход из аккаунта). */
    fun cancel() {
        shownDialogs().keys.forEach { manager.cancel(tagOf(it), DIALOG_ID) }
        manager.cancel(SUMMARY_ID)
    }

    /**
     * Убрать уведомление одного диалога («Прочитано», «Ответить» в шторке). Остальные остаются; сводка пересобирается
     * из оставшихся (их заголовков и текста — уже с учётом «скрывать текст») или уходит, если остался один.
     */
    fun cancelDialog(peer: String) {
        manager.cancel(tagOf(peer), DIALOG_ID)
        val rest = try {
            manager.activeNotifications.filter { it.id == DIALOG_ID && peerOf(it.tag) != null && peerOf(it.tag) != peer }
        } catch (e: Exception) {
            emptyList()
        }
        if (!NotifyGroup.keepsSummary(rest.size)) {
            manager.cancel(SUMMARY_ID)
            return
        }
        val lines = rest.map { sbn ->
            val title = sbn.notification.extras.getCharSequence(Notification.EXTRA_TITLE)?.toString().orEmpty()
            val text = sbn.notification.extras.getCharSequence(Notification.EXTRA_TEXT)?.toString().orEmpty()
            if (text.isBlank()) title else "$title: $text"
        }
        val loud = rest.any { it.notification.channelId == CHANNEL_ID }
        manager.notify(SUMMARY_ID, summaryNotification(lines, rest.sumOf { it.notification.number.coerceAtLeast(1) }, loud, alerts = false))
    }

    /**
     * Уведомление одного диалога: имя, текст (или «Новое сообщение»), аватар, «Ответить» и «Прочитано».
     * [chain] - последние сообщения подряд (5.00.12): больше одного - показываются все; [quiet] - обновление уже
     * показанного без звука. В диалог, куда писать нельзя ([DialogEntry.canWrite]), «Ответить» не предлагается.
     */
    private fun dialogNotification(
        post: NotifyGroup.Post,
        silentInGroup: Boolean,
        rules: Map<String, DialogNotify>,
        preview: Boolean,
        chain: List<NotifyChain.Line>,
        quiet: Boolean,
    ): Notification {
        val dialog = post.dialog
        val showsText = DialogNotifications.showsText(dialog.peerId, rules, preview)
        val lines = if (showsText) chain else emptyList()
        val text = when {
            !showsText -> context.getString(R.string.notif_text_hidden)
            lines.isNotEmpty() -> lines.last().text
            else -> dialog.snippet
        }
        val builder = base(if (post.loud) CHANNEL_ID else CHANNEL_SILENT)
            .setContentTitle(dialog.title)
            .setContentText(text.ifBlank { null })
            .setContentIntent(openIntent(dialog))
            .setGroup(GROUP)
            // В группе звучит сводка (один звук на проверку), одиночное - само.
            .setGroupAlertBehavior(
                if (silentInGroup) Notification.GROUP_ALERT_SUMMARY else Notification.GROUP_ALERT_ALL,
            )
            .setPublicVersion(publicVersion())
        if (quiet) builder.setOnlyAlertOnce(true)

        // Несколько сообщений подряд - все, по строке; одно длинное - разворачивается целиком
        val photo = avatar(dialog)
        when {
            lines.size > 1 -> builder.setStyle(chainStyle(dialog, lines, photo))
            text.length > LONG_TEXT -> builder.setStyle(Notification.BigTextStyle().bigText(text))
        }

        // Сколько непрочитанных (3.94): число на значке приложения и «3 новых» под именем.
        builder.setNumber(dialog.unreadCount.coerceAtLeast(1))
        if (dialog.unreadCount > 1) {
            builder.setSubText(context.resources.getQuantityString(R.plurals.notif_new_messages, dialog.unreadCount, dialog.unreadCount))
        }
        // 4.29: аватар (только уже скачанный, без сети) или кружок с инициалами, как в списке.
        photo?.let(builder::setLargeIcon)

        // «Ответить» - только где писать можно; «Прочитано» - всегда
        if (dialog.canWrite) builder.addAction(replyAction(dialog))
        builder.addAction(readAction(dialog))
        return builder.build()
    }

    /**
     * Цепочка сообщений (5.00.12): в беседе - с именами авторов. Android 9+ - переписка (MessagingStyle, с аватаром
     * собеседника), раньше - строки (InboxStyle).
     */
    private fun chainStyle(dialog: DialogEntry, lines: List<NotifyChain.Line>, photo: Bitmap?): Notification.Style {
        val group = lines.any { it.sender.isNotEmpty() }
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.P) {
            val inbox = Notification.InboxStyle()
            lines.forEach { inbox.addLine(if (group) "${it.sender}: ${it.text}" else it.text) }
            return inbox
        }
        val me = android.app.Person.Builder().setName(context.getString(R.string.notif_me)).build()
        val peer = android.app.Person.Builder().setName(dialog.title).setKey(dialog.peerId)
            .apply { photo?.let { setIcon(android.graphics.drawable.Icon.createWithBitmap(it)) } }.build()
        val style = Notification.MessagingStyle(me)
        if (group) {
            style.setConversationTitle(dialog.title)
            style.setGroupConversation(true)
        }
        val now = System.currentTimeMillis()
        lines.forEachIndexed { i, line ->
            val author = if (line.sender.isNotEmpty()) android.app.Person.Builder().setName(line.sender).build() else peer
            // Время неизвестно - по порядку, чтобы строки не перемешались
            val at = if (line.dateSec > 0) line.dateSec * 1000 else now - (lines.size - i) * 1000L
            style.addMessage(Notification.MessagingStyle.Message(line.text, at, author))
        }
        return style
    }

    /**
     * Сводка группы: «Новые сообщения», строки диалогов (до [MAX_LINES]), число непрочитанных. Нажатие — список диалогов.
     * Звучит, только если [alerts]; иначе молчит и при первом показе (GROUP_ALERT_CHILDREN глушит сводку).
     */
    private fun summaryNotification(lines: List<String>, messages: Int, loudChannel: Boolean, alerts: Boolean): Notification {
        val style = Notification.InboxStyle()
        lines.take(MAX_LINES).forEach { style.addLine(it) }
        if (lines.size > MAX_LINES) style.setSummaryText(context.getString(R.string.notif_more, lines.size - MAX_LINES))
        val builder = base(if (loudChannel) CHANNEL_ID else CHANNEL_SILENT)
            .setContentTitle(context.getString(R.string.notif_messages_many))
            .setContentText(context.getString(R.string.notif_unread_count, unreadCount(lines.size)))
            .setContentIntent(openIntent(null))
            .setStyle(style)
            .setGroup(GROUP)
            .setGroupSummary(true)
            .setGroupAlertBehavior(if (alerts) Notification.GROUP_ALERT_SUMMARY else Notification.GROUP_ALERT_CHILDREN)
            .setOnlyAlertOnce(!alerts)
            .setPublicVersion(publicVersion())
        if (messages > 0) builder.setNumber(messages)
        return builder.build()
    }

    private fun base(channel: String): Notification.Builder = Notification.Builder(context, channel)
        .setSmallIcon(R.drawable.ic_notification_message)
        // 4.29: значок и название приложения в шторке — синим приложения, а не серым системным.
        .setColor(context.getColor(R.color.signal_blue_light))
        .setAutoCancel(true)
        .setCategory(Notification.CATEGORY_MESSAGE)
        .setVisibility(Notification.VISIBILITY_PRIVATE)

    /** Показанные сейчас уведомления диалогов: номер диалога → в обычном ли канале. */
    private fun shownDialogs(): Map<String, Boolean> = try {
        manager.activeNotifications
            .filter { it.id == DIALOG_ID }
            .mapNotNull { sbn -> peerOf(sbn.tag)?.let { it to (sbn.notification.channelId == CHANNEL_ID) } }
            .toMap()
    } catch (e: Exception) {
        // Список системы недоступен — считаем, что показано ничего: хуже всего, что новое не уберёт старое.
        AppLog.w(LogTag.SYNC, "список показанных уведомлений недоступен", "ошибка" to e.javaClass.simpleName)
        emptyMap()
    }

    private fun tagOf(peer: String) = TAG_PREFIX + peer

    private fun peerOf(tag: String?): String? = tag?.takeIf { it.startsWith(TAG_PREFIX) }?.removePrefix(TAG_PREFIX)?.ifEmpty { null }

    /** Всего непрочитанных сообщений; у диалога без числа (отмечен вручную) — одно. */
    private fun unreadMessages(unread: List<DialogEntry>): Int = unread.sumOf { it.unreadCount.coerceAtLeast(1) }

    private fun line(dialog: DialogEntry, withText: Boolean): String =
        if (!withText || dialog.snippet.isBlank()) dialog.title else "${dialog.title}: ${dialog.snippet}"

    private fun unreadCount(n: Int): String = context.resources.getQuantityString(R.plurals.notif_dialogs_count, n, n)

    /**
     * Круглый аватар собеседника для уведомления. Картинка — только с диска ([ImageCache.cachedOnly]): фоновая
     * проверка не ждёт загрузки, а аватары диалогов уже скачаны списком. Нет картинки — инициалы ([AvatarBadge]).
     * На экране блокировки не виден: там общая надпись ([publicVersion]).
     */
    private fun avatar(dialog: DialogEntry): Bitmap? = try {
        val px = context.resources.getDimensionPixelSize(android.R.dimen.notification_large_icon_width).coerceAtLeast(1)
        val photo = ImageCache.get(context).cachedOnly(dialog.avatar, px)
        if (photo != null) circle(photo, px) else initials(dialog.title, px)
    } catch (e: Exception) {
        // Аватар — украшение: не вышло — уведомление без него, но показывается.
        AppLog.w(LogTag.SYNC, "аватар для уведомления не получился", "ошибка" to e.javaClass.simpleName)
        null
    }

    private fun circle(source: Bitmap, px: Int): Bitmap {
        val out = Bitmap.createBitmap(px, px, Bitmap.Config.ARGB_8888)
        val scale = px.toFloat() / minOf(source.width, source.height).coerceAtLeast(1)
        val shader = BitmapShader(source, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP).apply {
            setLocalMatrix(Matrix().apply {
                setScale(scale, scale)
                postTranslate((px - source.width * scale) / 2f, (px - source.height * scale) / 2f)
            })
        }
        val paint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG).apply { this.shader = shader }
        Canvas(out).drawCircle(px / 2f, px / 2f, px / 2f, paint)
        return out
    }

    private fun initials(title: String, px: Int): Bitmap {
        val out = Bitmap.createBitmap(px, px, Bitmap.Config.ARGB_8888)
        AvatarBadge(title).apply { setBounds(0, 0, px, px) }.draw(Canvas(out))
        return out
    }

    private fun replyAction(dialog: DialogEntry): Notification.Action {
        val intent = Intent(context, ReplyReceiver::class.java)
            .setAction(ReplyReceiver.ACTION)
            .putExtra(ReplyReceiver.EXTRA_PEER, dialog.peerId)
            .putExtra(ReplyReceiver.EXTRA_TITLE, dialog.title)
        // Поле ответа дописывает текст в намерение, поэтому оно изменяемое (Android 12+ требует указать явно);
        // адресат — свой неэкспортируемый приёмник, подменить его нельзя.
        val flags = PendingIntent.FLAG_UPDATE_CURRENT or
            (if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) PendingIntent.FLAG_MUTABLE else 0)
        val pending = PendingIntent.getBroadcast(context, REPLY_REQUEST_BASE + dialog.peerId.hashCode(), intent, flags)
        val input = android.app.RemoteInput.Builder(ReplyReceiver.KEY_TEXT)
            .setLabel(context.getString(R.string.notif_reply_hint))
            .build()
        return Notification.Action.Builder(
            android.graphics.drawable.Icon.createWithResource(context, R.drawable.ic_notification_reply),
            context.getString(R.string.notif_action_reply),
            pending,
        ).addRemoteInput(input).setAllowGeneratedReplies(false).build()
    }

    private fun readAction(dialog: DialogEntry): Notification.Action {
        val intent = Intent(context, MarkReadReceiver::class.java)
            .setAction(MarkReadReceiver.ACTION)
            .putExtra(MarkReadReceiver.EXTRA_PEER, dialog.peerId)
        val pending = PendingIntent.getBroadcast(
            context, dialog.peerId.hashCode(), intent, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        return Notification.Action.Builder(
            android.graphics.drawable.Icon.createWithResource(context, R.drawable.ic_notification_read),
            context.getString(R.string.notif_action_read),
            pending,
        ).build()
    }

    private fun publicVersion(): Notification = Notification.Builder(context, CHANNEL_ID)
        .setSmallIcon(R.drawable.ic_notification_message)
        .setContentTitle(context.getString(R.string.notif_messages_many))
        .build()

    /** Нажатие: уведомление диалога — сразу в него, сводка — в список. */
    private fun openIntent(single: DialogEntry?): PendingIntent {
        val intent = Intent(context, MessagesActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
        if (single != null) {
            intent.putExtra(MessagesActivity.EXTRA_PEER_ID, single.peerId)
                .putExtra(MessagesActivity.EXTRA_PEER_TITLE, single.title)
                .putExtra(MessagesActivity.EXTRA_PEER_AVATAR, single.avatar)
        }
        // Разные запросы для «один диалог» и «список»: иначе система подменила бы одно другим.
        val requestCode = single?.peerId?.hashCode() ?: 0
        return PendingIntent.getActivity(
            context, requestCode, intent, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
    }

    /**
     * Каналы сообщений: обычный и тихий (4.47). Создаются заранее и для экрана настроек — системный экран канала
     * («Звук и вибрация») открывается только у существующего канала. Важность канала после создания меняет только
     * человек в настройках Android, поэтому тихий — отдельный канал, а не «приглушить» обычный.
     */
    fun ensureChannels() {
        val loud = NotificationChannel(
            CHANNEL_ID, context.getString(R.string.notif_channel_messages), NotificationManager.IMPORTANCE_DEFAULT,
        )
        loud.description = context.getString(R.string.notif_channel_messages_desc)
        loud.lockscreenVisibility = Notification.VISIBILITY_PRIVATE
        val silent = NotificationChannel(
            CHANNEL_SILENT, context.getString(R.string.notif_channel_messages_silent), NotificationManager.IMPORTANCE_LOW,
        )
        silent.description = context.getString(R.string.notif_channel_messages_silent_desc)
        silent.lockscreenVisibility = Notification.VISIBILITY_PRIVATE
        silent.setSound(null, null)
        silent.enableVibration(false)
        manager.createNotificationChannels(listOf(loud, silent))
    }

    /** Уведомления приложения разрешены в Android (и разрешение Android 13+ дано). */
    fun systemAllows(): Boolean = canNotify()

    /** Канал обычных уведомлений о сообщениях не выключен в настройках Android. */
    fun channelEnabled(): Boolean =
        manager.getNotificationChannel(CHANNEL_ID)?.importance != NotificationManager.IMPORTANCE_NONE

    private fun canNotify(): Boolean {
        val granted = Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
            context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED
        return granted && manager.areNotificationsEnabled()
    }

    companion object {
        const val CHANNEL_ID = "messages"
        /** Тихий канал (4.47): новое в диалогах «без звука». */
        const val CHANNEL_SILENT = "messages_silent"
        /** Сводка группы; тот же номер, что у одиночного уведомления до 4.48, — оно заменяется или убирается. */
        private const val SUMMARY_ID = 2001
        /** Уведомления диалогов: один номер, диалог — в метке ([TAG_PREFIX] + номер диалога). */
        private const val DIALOG_ID = 2002
        private const val TAG_PREFIX = "peer:"
        private const val GROUP = "com.example.vkweb.MESSAGES"
        /** Длиннее — разворачивается целиком (BigTextStyle). */
        private const val LONG_TEXT = 40
        private const val MAX_LINES = 5
        /** Код запроса «Ответить» — отдельно от «Прочитано» той же переписки (иначе система подменила бы одно другим). */
        private const val REPLY_REQUEST_BASE = 0x5250
    }
}
