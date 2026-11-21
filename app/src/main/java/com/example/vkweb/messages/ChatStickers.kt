package com.example.vkweb.messages

import android.app.Activity
import android.os.Handler
import android.os.Looper
import androidx.core.widget.doAfterTextChanged
import android.view.ViewGroup
import android.widget.EditText
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.view.isVisible
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.example.vkweb.R
import com.example.vkweb.diag.AppLog
import com.example.vkweb.diag.LogTag
import com.example.vkweb.offline.ImageCache
import com.google.android.material.bottomsheet.BottomSheetDialog

/**
 * Стикеры в переписке (3.94: вынесено из MessagesActivity без изменения поведения).
 *  - Недавние (3.73): кнопка у поля ввода → страница присылает список → нижняя панель с сеткой, нажатие — отправить.
 *  - Подсказки (3.92): набрали слово («привет», «спасибо») — над полем стикеры из словаря страницы; нажатие —
 *    отправить, поле очищается.
 */
class ChatStickers(
    private val activity: Activity,
    private val input: EditText,
    private val suggestList: RecyclerView,
    private val host: Host,
) {
    interface Host {
        /** Открыта своя переписка (не запасной показ страницы). */
        val canSend: Boolean
        /** Выбраны файлы: подсказки стикеров мешали бы. */
        val hasFiles: Boolean
        fun requestRecent()
        fun requestSuggest(text: String)
        fun send(stickerId: Long)
    }

    private val handler = Handler(Looper.getMainLooper())
    private val images = ImageCache.get(activity)
    private val density = activity.resources.displayMetrics.density
    private var recentRequested = false
    private var suggestItems: List<ReaderPayload.Stickers.Sticker> = emptyList()
    private val suggestRequest = Runnable { requestSuggest() }

    init {
        setupSuggest()
    }

    // ------------------------------------------------------------ недавние

    /** Недавние стикеры у страницы; панель откроется, когда они придут. */
    fun requestRecent() {
        if (!host.canSend) return
        recentRequested = true
        host.requestRecent()
    }

    /** Панель недавних стикеров: сетка картинок, нажатие — отправить в открытую переписку. */
    fun showRecent(payload: ReaderPayload.Stickers) {
        if (!recentRequested || activity.isFinishing || activity.isDestroyed) return
        recentRequested = false
        if (payload.reason.isNotEmpty()) AppLog.w(LogTag.MESSAGES, "недавние стикеры не получены", "причина" to payload.reason)
        val dialog = BottomSheetDialog(activity)
        val pad = (density * 12).toInt()
        val root = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(pad, pad, pad, pad)
        }
        root.addView(TextView(activity).apply {
            setText(R.string.stickers_title)
            setTextAppearance(com.google.android.material.R.style.TextAppearance_Material3_TitleMedium)
            setPadding(pad / 2, 0, 0, pad)
        })
        if (payload.items.isEmpty()) {
            root.addView(TextView(activity).apply { setText(R.string.stickers_empty); setPadding(pad / 2, 0, pad / 2, pad) })
        } else {
            val size = (density * CELL_DP).toInt()
            val grid = RecyclerView(activity).apply {
                layoutManager = GridLayoutManager(activity, COLUMNS)
                adapter = object : RecyclerView.Adapter<RecyclerView.ViewHolder>() {
                    override fun getItemCount() = payload.items.size
                    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int) =
                        object : RecyclerView.ViewHolder(
                            ImageView(parent.context).apply {
                                layoutParams = ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, size)
                                scaleType = ImageView.ScaleType.FIT_CENTER
                                setPadding(pad / 2, pad / 2, pad / 2, pad / 2)
                                contentDescription = activity.getString(R.string.sticker_description)
                                isClickable = true
                                isFocusable = true
                                setBackgroundResource(android.R.drawable.list_selector_background)
                            },
                        ) {}

                    override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
                        val sticker = payload.items[position]
                        val view = holder.itemView as ImageView
                        images.load(view, sticker.url, size) { null }
                        view.setOnClickListener {
                            dialog.dismiss()
                            host.send(sticker.id)
                        }
                    }
                }
            }
            root.addView(grid, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        }
        dialog.setContentView(root)
        dialog.show()
    }

    // ------------------------------------------------------------ подсказки

    private fun setupSuggest() {
        suggestList.layoutManager = LinearLayoutManager(activity, LinearLayoutManager.HORIZONTAL, false)
        val size = (density * SUGGEST_DP).toInt()
        suggestList.adapter = object : RecyclerView.Adapter<RecyclerView.ViewHolder>() {
            override fun getItemCount() = suggestItems.size
            override fun onCreateViewHolder(parent: ViewGroup, viewType: Int) =
                object : RecyclerView.ViewHolder(
                    ImageView(parent.context).apply {
                        layoutParams = ViewGroup.LayoutParams(size, size)
                        val pad = size / 10
                        setPadding(pad, pad, pad, pad)
                        scaleType = ImageView.ScaleType.FIT_CENTER
                        contentDescription = activity.getString(R.string.sticker_description)
                        setBackgroundResource(android.R.drawable.list_selector_background)
                    },
                ) {}

            override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
                val sticker = suggestItems[position]
                val view = holder.itemView as ImageView
                images.load(view, sticker.url, size) { null }
                view.setOnClickListener {
                    if (!host.canSend || !input.isEnabled) return@setOnClickListener
                    AppLog.i(LogTag.MESSAGES, "стикер из подсказки", "номер в подсказке" to position + 1)
                    host.send(sticker.id)
                    // Как в VK: слово, к которому подсказан стикер, заменяется им.
                    input.setText("")
                    hideSuggest()
                }
            }
        }
        input.doAfterTextChanged { s ->
            handler.removeCallbacks(suggestRequest)
            val text = s?.toString()?.trim().orEmpty()
            if (text.isEmpty() || text.length > SUGGEST_MAX_CHARS) hideSuggest()
            else handler.postDelayed(suggestRequest, SUGGEST_DELAY_MS)
        }
    }

    private fun requestSuggest() {
        if (!host.canSend || !input.isEnabled || host.hasFiles) return
        val text = input.text?.toString()?.trim().orEmpty()
        if (text.isEmpty() || text.length > SUGGEST_MAX_CHARS) return
        host.requestSuggest(text)
    }

    fun showSuggest(payload: ReaderPayload.StickerSuggest) {
        // Ответ на уже изменённый текст — не к месту.
        val text = input.text?.toString()?.trim().orEmpty()
        if (payload.query.trim() != text || payload.items.isEmpty() || !host.canSend) {
            hideSuggest()
            return
        }
        suggestItems = payload.items
        @Suppress("NotifyDataSetChanged")
        suggestList.adapter?.notifyDataSetChanged()
        suggestList.scrollToPosition(0)
        suggestList.isVisible = true
    }

    /** Экран закрывается: отложенный запрос подсказки не должен уйти в уничтоженную страницу. */
    fun release() = handler.removeCallbacksAndMessages(null)

    fun hideSuggest() {
        if (!suggestList.isVisible) return
        suggestList.isVisible = false
        suggestItems = emptyList()
        @Suppress("NotifyDataSetChanged")
        suggestList.adapter?.notifyDataSetChanged()
    }

    private companion object {
        const val COLUMNS = 4
        const val CELL_DP = 80
        const val SUGGEST_DP = 72
        const val SUGGEST_MAX_CHARS = 40
        const val SUGGEST_DELAY_MS = 250L
    }
}
