package com.example.vkweb.comments

import android.app.Activity
import android.os.Handler
import android.os.Looper
import android.text.format.DateUtils
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.EditText
import android.widget.ImageView
import android.widget.TextView
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.example.vkweb.R
import com.example.vkweb.diag.AppLog
import com.example.vkweb.diag.LogTag
import com.example.vkweb.offline.ImageCache
import com.example.vkweb.owner.OwnerActions
import com.example.vkweb.owner.OwnerHeader
import com.example.vkweb.owner.PostExtra
import com.example.vkweb.owner.PostHolder
import com.example.vkweb.owner.PostPhoto
import com.example.vkweb.owner.WallPost
import com.example.vkweb.text.RuPlural
import com.example.vkweb.ui.CoverImage
import com.example.vkweb.ui.Notice
import com.example.vkweb.ui.Views
import com.google.android.material.bottomsheet.BottomSheetBehavior
import com.google.android.material.bottomsheet.BottomSheetDialog

/**
 * Комментарии к видео или клипу своим окном (4.45): шторка снизу поверх проигрывателя. Список и отправка — через
 * страницу главного окна ([CommentsHub]); её нет (окно закрыто) — открывается страница ролика, как раньше
 * ([onOpenPage]). Кнопка в заголовке открывает страницу в любой момент.
 *
 * 4.46: ответы. Под комментарием — первые ответы (пришли вместе с ним) и «Показать ответы: N» — вся ветка; нажатие на
 * комментарий или ответ — «ответить» (над полем ввода — кому, крестик отменяет). Строки списка собирает [CommentRows].
 */
class CommentsSheet private constructor(
    private val activity: Activity,
    private val videoId: String,
    private val onOpenPage: () -> Unit,
    private val onShownChanged: (Boolean) -> Unit,
    /** Запись стены, на своём экране которой открыты комментарии (4.14, 5.00.19): она показывается над списком. */
    private var post: WallPost? = null,
    private val postActions: OwnerActions? = null,
) : CommentsHub.Listener {

    private val handler = Handler(Looper.getMainLooper())
    private val tree = CommentRows()
    private var rows: List<CommentRows.Row> = emptyList()
    private val adapter = Adapter()
    private val expandedTexts = com.example.vkweb.owner.ExpandedTexts()
    /** Строка записи над комментариями, если открыт экран записи. */
    private val headerCount: Int get() = if (post != null) 1 else 0
    private lateinit var dialog: BottomSheetDialog
    private lateinit var title: TextView
    private lateinit var status: View
    private lateinit var statusState: com.example.vkweb.ui.EmptyState
    private lateinit var input: EditText
    private lateinit var sendButton: View
    private lateinit var replyBar: View
    private lateinit var replyTo: TextView

    private var nextOffset = 0
    private var hasMore = false
    private var loading = false
    private var sending = false

    /** Кому ответ: комментарий, его ветка (верхний комментарий) и имя; null — просто комментарий. */
    private var target: Target? = null
    /** Ответ ушёл в эту ветку: после отправки она перечитывается. */
    private var sentToRoot = 0L

    private data class Target(val commentId: Long, val rootId: Long, val name: String)

    private val loadTimeout = Runnable {
        if (!loading) return@Runnable
        loading = false
        AppLog.w(LogTag.MEDIA, "комментарии: страница не ответила")
        if (tree.isEmpty) showStatus(R.string.comments_failed)
    }

    private val sendTimeout = Runnable {
        if (!sending) return@Runnable
        setSending(false)
        Notice.show(activity, R.string.comments_send_failed)
    }

    private fun show() {
        // Содержимое листа: родителя ещё нет — размеры задаёт сам BottomSheetDialog: предупреждение InflateParams отключено.
        @android.annotation.SuppressLint("InflateParams")
        val view = LayoutInflater.from(activity).inflate(R.layout.sheet_comments, null)
        title = view.findViewById(R.id.comments_title)
        status = view.findViewById(R.id.comments_status)
        statusState = com.example.vkweb.ui.EmptyState(status, status)
        input = view.findViewById(R.id.comments_input)
        sendButton = view.findViewById(R.id.comments_send)
        replyBar = view.findViewById(R.id.comments_reply_bar)
        replyTo = view.findViewById(R.id.comments_reply_to)
        // Высота списка — около двух третей экрана: шторка не скачет, пока приходят комментарии. Экран записи выше.
        view.findViewById<View>(R.id.comments_body).layoutParams.height =
            (activity.resources.displayMetrics.heightPixels * (if (post != null) POST_BODY_SHARE else BODY_SHARE)).toInt()
        // Над списком запись: строка «загрузка, пусто» внизу, чтобы не закрывать её.
        if (post != null) (status.layoutParams as android.widget.FrameLayout.LayoutParams).gravity = android.view.Gravity.BOTTOM
        val list = view.findViewById<RecyclerView>(R.id.comments_list)
        val layout = LinearLayoutManager(activity)
        list.layoutManager = layout
        list.adapter = adapter
        list.addOnScrollListener(object : RecyclerView.OnScrollListener() {
            override fun onScrolled(rv: RecyclerView, dx: Int, dy: Int) {
                if (dy > 0 && layout.findLastVisibleItemPosition() >= rows.size + headerCount - LOAD_AHEAD) loadMore()
            }
        })
        view.findViewById<View>(R.id.comments_open_page).setOnClickListener {
            AppLog.i(LogTag.MEDIA, "комментарии: открыта страница ролика")
            dialog.dismiss()
            onOpenPage()
        }
        view.findViewById<View>(R.id.comments_reply_cancel).setOnClickListener { setTarget(null) }
        sendButton.setOnClickListener { send() }

        dialog = BottomSheetDialog(activity).apply {
            setContentView(view)
            behavior.state = BottomSheetBehavior.STATE_EXPANDED
            behavior.skipCollapsed = true
            // Клавиатура поднимает поле ввода, а не закрывает его. У окна диалога (не во весь экран) adjustResize
            // по-прежнему работает на всех версиях; замена — отступы клавиатуры вручную, без пользы для этого листа.
            @Suppress("DEPRECATION")
            window?.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE)
            setOnDismissListener {
                handler.removeCallbacksAndMessages(null)
                if (CommentsHub.listener === this@CommentsSheet) CommentsHub.listener = null
                onShownChanged(false)
            }
        }
        CommentsHub.listener = this
        onShownChanged(true)
        dialog.show()
        showStatus(R.string.comments_loading)
        request(0)
    }

    private fun request(offset: Int) {
        val ask = CommentsHub.request ?: run { showStatus(R.string.comments_failed); return }
        loading = true
        handler.removeCallbacks(loadTimeout)
        handler.postDelayed(loadTimeout, ANSWER_TIMEOUT_MS)
        ask(videoId, offset, 0L)
    }

    private fun loadMore() {
        if (loading || !hasMore) return
        request(nextOffset)
    }

    /** Вся ветка ответов на [rootId]. */
    private fun requestThread(rootId: Long) {
        val ask = CommentsHub.request ?: return
        if (!tree.startThread(rootId)) return
        render()
        // Нет ответа — строка «Показать ответы» возвращается.
        handler.postDelayed({ if (tree.failThread(rootId, retry = true)) render() }, ANSWER_TIMEOUT_MS)
        ask(videoId, tree.threadNext(rootId), rootId)
    }

    private fun setTarget(value: Target?) {
        target = value
        replyBar.visibility = if (value != null) View.VISIBLE else View.GONE
        input.setHint(if (value != null) R.string.comments_reply_hint else R.string.comments_hint)
        if (value == null) return
        replyTo.text = activity.getString(R.string.comments_reply_to, value.name.ifEmpty { activity.getString(R.string.comments_unknown_author) })
        com.example.vkweb.ui.Views.showKeyboard(input)
    }

    private fun send() {
        val text = input.text.toString().trim()
        if (text.isEmpty() || sending) return
        val post = CommentsHub.send ?: run { Notice.show(activity, R.string.comments_send_failed); return }
        val to = target
        setSending(true)
        handler.postDelayed(sendTimeout, ANSWER_TIMEOUT_MS)
        sentToRoot = to?.rootId ?: 0L
        AppLog.i(LogTag.MEDIA, "комментарий: отправка", "знаков" to text.length, "ответ" to (to != null))
        post(videoId, text, to?.commentId ?: 0L)
    }

    private fun setSending(on: Boolean) {
        sending = on
        sendButton.isEnabled = !on
        sendButton.alpha = if (on) DISABLED_ALPHA else 1f
    }

    override fun onPage(page: CommentsPage) {
        if (page.videoId != videoId) return
        if (page.parent != 0L) {
            onThread(page)
            return
        }
        handler.removeCallbacks(loadTimeout)
        loading = false
        if (!page.ok) {
            AppLog.w(LogTag.MEDIA, "комментарии не получены", "причина" to page.reason)
            if (tree.isEmpty) showStatus(R.string.comments_failed)
            return
        }
        tree.addTop(page.items, replace = page.offset == 0)
        nextOffset = page.next
        hasMore = page.hasMore
        title.text = if (page.total > 0) activity.getString(R.string.comments_title_count, page.total) else activity.getString(R.string.comments_title)
        render()
        if (tree.isEmpty) showStatus(R.string.comments_empty) else status.visibility = View.GONE
        // Порция целиком из удалённых — сразу следующая, иначе прокручивать нечего.
        if (page.items.isEmpty() && hasMore) loadMore()
    }

    private fun onThread(page: CommentsPage) {
        if (!page.ok) {
            AppLog.w(LogTag.MEDIA, "комментарии: ветка ответов не получена", "причина" to page.reason)
            if (tree.failThread(page.parent, retry = false)) Notice.show(activity, R.string.comments_thread_failed)
            render()
            return
        }
        tree.addThread(page.parent, page.items, replace = page.offset == 0, next = page.next, hasMore = page.hasMore)
        render()
    }

    override fun onSent(result: CommentSent) {
        if (result.videoId != videoId) return
        handler.removeCallbacks(sendTimeout)
        setSending(false)
        if (!result.ok) {
            AppLog.w(LogTag.MEDIA, "комментарий не отправлен", "причина" to result.reason)
            Notice.show(activity, R.string.comments_send_failed)
            return
        }
        input.setText("")
        setTarget(null)
        Notice.show(activity, R.string.comments_sent)
        // Свой ответ — в его ветке (перечитывается), свой комментарий — сверху (новые первыми).
        if (result.replyTo != 0L && sentToRoot != 0L) {
            tree.resetThread(sentToRoot)
            requestThread(sentToRoot)
        } else {
            request(0)
        }
    }

    private fun render() {
        rows = tree.rows()
        adapter.notifyDataSetChanged()
    }

    /**
     * Загрузка, пусто, не загрузились (4.54): в общем оформлении пустых экранов. «Не загрузились» — с «Повторить»,
     * «пусто» — со значком комментариев, загрузка — только строка.
     */
    private fun showStatus(text: Int) {
        statusState.show(
            when (text) {
                R.string.comments_failed -> com.example.vkweb.ui.EmptyState.Content(
                    R.string.comments_failed_title, R.drawable.ic_cloud_off, R.string.comments_failed_body,
                    action = com.example.vkweb.ui.EmptyState.Action(R.string.retry) {
                        showStatus(R.string.comments_loading)
                        request(0)
                    },
                )
                R.string.comments_empty -> com.example.vkweb.ui.EmptyState.Content(text, R.drawable.ic_post_comment)
                else -> com.example.vkweb.ui.EmptyState.Content(text)
            },
        )
        status.visibility = View.VISIBLE
    }

    private inner class Adapter : RecyclerView.Adapter<RecyclerView.ViewHolder>() {
        override fun getItemCount() = rows.size + headerCount
        override fun getItemViewType(position: Int) = when {
            position < headerCount -> TYPE_POST
            rows[position - headerCount] is CommentRows.Row.More -> TYPE_MORE
            else -> TYPE_COMMENT
        }
        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
            val inflater = LayoutInflater.from(parent.context)
            return when (viewType) {
                TYPE_POST -> PostHolder(inflater.inflate(R.layout.item_post, parent, false), SheetActions(), expandedTexts, { 0L }, standalone = true)
                TYPE_MORE -> MoreHolder(inflater.inflate(R.layout.item_comment_more, parent, false))
                else -> Holder(inflater.inflate(R.layout.item_comment, parent, false))
            }
        }
        override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
            if (position < headerCount) {
                post?.let { (holder as PostHolder).bind(it) }
                return
            }
            when (val row = rows[position - headerCount]) {
                is CommentRows.Row.Item -> (holder as Holder).bind(row)
                is CommentRows.Row.More -> (holder as MoreHolder).bind(row)
            }
        }
    }

    /**
     * Нажатия на записи этого экрана: переходы закрывают шторку (иначе страница откроется под ней), лайк сразу виден
     * здесь же - сервер подтвердит или отменит его на самом экране страницы.
     */
    private inner class SheetActions : OwnerActions {
        private val base: OwnerActions get() = postActions ?: object : OwnerActions {
            override fun openLink(target: String) = Unit
            override fun openPhoto(photo: PostPhoto, gallery: List<PostPhoto>) = Unit
            override fun share(post: WallPost) = Unit
            override fun like(post: WallPost) = Unit
            override fun openAvatar(url: String) = Unit
            override fun write(header: OwnerHeader) = Unit
        }

        override fun openLink(target: String) { dialog.dismiss(); base.openLink(target) }
        override fun openPhoto(photo: PostPhoto, gallery: List<PostPhoto>) { dialog.dismiss(); base.openPhoto(photo, gallery) }
        override fun share(post: WallPost) = base.share(post)
        override fun playAudio(post: WallPost, track: PostExtra) = base.playAudio(post, track)
        override fun openAvatar(url: String) = base.openAvatar(url)
        override fun write(header: OwnerHeader) { dialog.dismiss(); base.write(header) }
        override fun like(post: WallPost) {
            val shown = this@CommentsSheet.post ?: return
            base.like(shown)
            this@CommentsSheet.post = shown.copy(liked = !shown.liked, likes = (shown.likes + if (shown.liked) -1 else 1).coerceAtLeast(0))
            adapter.notifyItemChanged(0)
        }
    }

    /** Отступ ответов: под фото верхнего комментария. */
    private fun indent(view: View, depth: Int) {
        val start = Views.dp(view, if (depth > 0) REPLY_INDENT_DP else ROW_PADDING_DP)
        view.setPaddingRelative(start, view.paddingTop, view.paddingEnd, view.paddingBottom)
    }

    private inner class MoreHolder(view: View) : RecyclerView.ViewHolder(view) {
        private val text: TextView = view as TextView
        fun bind(row: CommentRows.Row.More) {
            indent(itemView, 1)
            text.text = if (row.loading) activity.getString(R.string.comments_thread_loading)
            else activity.getString(R.string.comments_thread_more, row.remaining)
            itemView.setOnClickListener(if (row.loading) null else View.OnClickListener { requestThread(row.rootId) })
        }
    }

    private inner class Holder(view: View) : RecyclerView.ViewHolder(view) {
        private val avatar = CoverImage(view.findViewById(R.id.comment_avatar), AVATAR_DP, AVATAR_DP / 2)
        private val author: TextView = view.findViewById(R.id.comment_author)
        private val text: TextView = view.findViewById(R.id.comment_text)
        private val sticker: ImageView = view.findViewById(R.id.comment_sticker)
        private val meta: TextView = view.findViewById(R.id.comment_meta)
        private val images = ImageCache.get(view.context)
        private val stickerPx = Views.dp(view, STICKER_DP)

        fun bind(row: CommentRows.Row.Item) {
            val c = row.comment
            indent(itemView, row.depth)
            avatar.load(c.photoUrl)
            author.text = c.author.ifEmpty { itemView.context.getString(R.string.comments_unknown_author) }
            val label = if (c.text.isEmpty() && c.stickerUrl.isEmpty()) attachmentLabel(c.attachment) else ""
            text.text = c.text.ifEmpty { label }
            text.visibility = if (text.text.isEmpty()) View.GONE else View.VISIBLE
            sticker.visibility = if (c.stickerUrl.isEmpty()) View.GONE else View.VISIBLE
            if (c.stickerUrl.isNotEmpty()) images.load(sticker, c.stickerUrl, stickerPx) { null }
            val parts = ArrayList<String>()
            if (c.date > 0) {
                parts += DateUtils.getRelativeTimeSpanString(
                    c.date * 1000, System.currentTimeMillis(), DateUtils.MINUTE_IN_MILLIS, DateUtils.FORMAT_ABBREV_RELATIVE,
                ).toString()
            }
            if (c.likes > 0) parts += itemView.context.getString(R.string.comments_likes, c.likes)
            // Число ответов — у верхнего, пока ветка не раскрыта (иначе ответы видны ниже).
            if (row.depth == 0 && c.replies > 0 && !row.expanded) parts += RuPlural.count(c.replies.toLong(), "ответ", "ответа", "ответов")
            meta.text = parts.joinToString(" · ")
            itemView.setOnClickListener { setTarget(Target(c.id, row.rootId, c.author)) }
        }

        private fun attachmentLabel(kind: String): String = itemView.context.getString(
            when (kind) {
                "photo" -> R.string.comments_attach_photo
                "video" -> R.string.comments_attach_video
                "audio" -> R.string.comments_attach_audio
                "sticker", "graffiti" -> R.string.comments_attach_sticker
                else -> R.string.comments_attach_other
            },
        )
    }

    companion object {
        private const val BODY_SHARE = 0.6f
        private const val POST_BODY_SHARE = 0.85f
        private const val LOAD_AHEAD = 5
        private const val ANSWER_TIMEOUT_MS = 12_000L
        private const val AVATAR_DP = 40
        private const val STICKER_DP = 96
        private const val DISABLED_ALPHA = 0.4f
        private const val ROW_PADDING_DP = 16
        /** Ответ начинается под текстом верхнего комментария: отступ строки + фото + промежуток. */
        private const val REPLY_INDENT_DP = 16 + AVATAR_DP + 12
        private const val TYPE_COMMENT = 0
        private const val TYPE_MORE = 1
        private const val TYPE_POST = 2

        /**
         * Открыть комментарии к «владелец_номер» [videoId]. Нет страницы главного окна или номера — сразу [onOpenPage].
         * [onShownChanged] — шторка открылась или закрылась (проигрыватель на это время может встать на паузу).
         */
        fun open(activity: Activity, videoId: String?, onOpenPage: () -> Unit, onShownChanged: (Boolean) -> Unit = {}) {
            if (videoId == null || !CommentsHub.isAvailable) {
                AppLog.i(LogTag.MEDIA, "комментарии: своего списка нет — открыта страница", "номер" to (videoId != null))
                onOpenPage()
                return
            }
            AppLog.i(LogTag.MEDIA, "комментарии: открыт свой список")
            CommentsSheet(activity, videoId, onOpenPage, onShownChanged).show()
        }

        /**
         * Экран записи стены (5.00.19): запись и под ней её комментарии. [target] - «w» + номер записи
         * ([CommentTarget.ofPost]); нет страницы главного окна - сразу [onOpenPage], как раньше.
         */
        fun openPost(activity: Activity, target: String?, post: WallPost, actions: OwnerActions, onOpenPage: () -> Unit) {
            if (target == null || !CommentsHub.isAvailable) {
                AppLog.i(LogTag.MEDIA, "запись: своего экрана нет - открыта страница", "номер" to (target != null))
                onOpenPage()
                return
            }
            AppLog.i(LogTag.MEDIA, "запись: открыт свой экран", "комментариев" to post.comments)
            CommentsSheet(activity, target, onOpenPage, {}, post, actions).show()
        }
    }
}
