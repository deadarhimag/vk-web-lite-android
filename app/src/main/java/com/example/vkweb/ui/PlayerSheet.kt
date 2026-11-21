package com.example.vkweb.ui

import android.content.Context
import android.content.ContextWrapper
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.LayoutInflater
import android.view.View
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.TextView
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import com.example.vkweb.R
import com.example.vkweb.media.MediaState
import com.example.vkweb.media.PlayerCommand
import com.example.vkweb.offline.ImageCache
import com.google.android.material.bottomsheet.BottomSheetDialog
import com.google.android.material.slider.Slider

/**
 * Плеер снизу экрана: обложка, полоса проигрывания с перемоткой, кнопки. Звук по-прежнему играет страница VK:
 * кнопки и перемотка — команды ей ([onCommand], [onSeek]), а сюда приходит её состояние ([update]).
 *
 * Полоса движется раз в [TICK_MS], только пока трек играет, плеер открыт и экран виден: на паузе позиция не
 * меняется, а при выключенном экране перерисовывать нечего — раньше отсчёт шёл и тогда, будя процессор дважды в секунду.
 */
class PlayerSheet(
    private val context: Context,
    private val onCommand: (PlayerCommand) -> Unit,
    private val onSeek: (Double) -> Unit,
    /** «Добавить к себе» играющий трек «владелец_номер» (4.46); null — кнопки нет. */
    private val onAdd: ((String) -> Unit)? = null,
    /** Нажатие на исполнителя (4.79): его треки в поиске музыки; null — исполнитель просто подпись. */
    private val onArtist: ((String) -> Unit)? = null,
) {
    /** Треки, которые в этот раз уже попросили добавить: кнопка прячется сразу, не дожидаясь страницы. */
    private val addedNow = HashSet<String>()

    private val images = ImageCache.get(context)
    private val handler = Handler(Looper.getMainLooper())
    private val coverPx = context.resources.displayMetrics.widthPixels

    private var dialog: BottomSheetDialog? = null
    private var holder: Holder? = null
    private var state = MediaState.IDLE
    private var receivedAt = 0L
    private var cover: String? = null

    /** Палец на полосе: позиция не обновляется, пока человек выбирает место. */
    private var dragging = false

    /** Экран, на котором открыт плеер, виден (между onStart и onStop). */
    private var hostVisible = true

    private val tick = object : Runnable {
        override fun run() {
            renderPosition()
            if (shouldTick) handler.postDelayed(this, TICK_MS)
        }
    }

    private val shouldTick: Boolean get() = isShowing && hostVisible && state.playing && !dragging

    init {
        lifecycleOwner(context)?.lifecycle?.addObserver(object : DefaultLifecycleObserver {
            override fun onStart(owner: LifecycleOwner) {
                hostVisible = true
                syncTick()
            }

            override fun onStop(owner: LifecycleOwner) {
                hostVisible = false
                handler.removeCallbacks(tick)
            }

            override fun onDestroy(owner: LifecycleOwner) {
                handler.removeCallbacks(tick)
                dialog?.dismiss()
            }
        })
    }

    /** Запустить отсчёт, если он нужен, или остановить. Позиция при этом сразу перерисовывается. */
    private fun syncTick() {
        handler.removeCallbacks(tick)
        if (isShowing) handler.post(tick)
    }

    private class Holder(view: View) {
        val cover: ImageView = view.findViewById(R.id.player_cover)
        val title: TextView = view.findViewById(R.id.player_title)
        val artist: TextView = view.findViewById(R.id.player_artist)
        val seek: Slider = view.findViewById(R.id.player_seek)
        val elapsed: TextView = view.findViewById(R.id.player_elapsed)
        val total: TextView = view.findViewById(R.id.player_total)
        val toggle: ImageButton = view.findViewById(R.id.player_toggle)
        val prev: ImageButton = view.findViewById(R.id.player_prev)
        val next: ImageButton = view.findViewById(R.id.player_next)
        val add: ImageButton = view.findViewById(R.id.player_add)
    }

    private val isShowing: Boolean get() = dialog?.isShowing == true

    fun show() {
        if (isShowing) return
        // Содержимое листа: родителя ещё нет — размеры задаёт сам BottomSheetDialog: предупреждение InflateParams отключено.
        @android.annotation.SuppressLint("InflateParams")
        val view = LayoutInflater.from(context).inflate(R.layout.sheet_player, null)
        val views = Holder(view)
        views.toggle.setOnClickListener { onCommand(if (state.playing) PlayerCommand.PAUSE else PlayerCommand.PLAY) }
        views.prev.setOnClickListener { onCommand(PlayerCommand.PREVIOUS) }
        views.next.setOnClickListener { onCommand(PlayerCommand.NEXT) }
        onArtist?.let { open ->
            views.artist.setOnClickListener {
                val artist = state.artist.takeIf { it.isNotBlank() } ?: return@setOnClickListener
                dismiss()
                open(artist)
            }
        }
        views.add.setOnClickListener {
            val id = state.trackId.takeIf { state.canAddTrack && it !in addedNow } ?: return@setOnClickListener
            addedNow += id
            render()
            onAdd?.invoke(id)
        }
        views.seek.addOnChangeListener { _, value, fromUser -> if (fromUser) views.elapsed.text = PlayerFormat.time(value.toDouble()) }
        views.seek.addOnSliderTouchListener(object : Slider.OnSliderTouchListener {
            override fun onStartTrackingTouch(slider: Slider) {
                dragging = true
                handler.removeCallbacks(tick)
            }

            override fun onStopTrackingTouch(slider: Slider) {
                dragging = false
                val target = slider.value.toDouble()
                // Сразу показываем новое место: подтверждение страницы придёт чуть позже.
                state = state.copy(position = target)
                receivedAt = SystemClock.elapsedRealtime()
                onSeek(target)
                syncTick()
            }
        })
        holder = views
        dialog = BottomSheetDialog(context).apply {
            setContentView(view)
            setOnDismissListener {
                handler.removeCallbacks(tick)
                dialog = null
                holder = null
            }
            show()
        }
        render()
        syncTick()
    }

    /** Новое состояние плеера страницы; [receivedAtMs] — когда оно пришло (от него досчитывается позиция). */
    fun update(state: MediaState, receivedAtMs: Long, coverUrl: String?) {
        this.state = state
        receivedAt = receivedAtMs
        cover = coverUrl
        if (isShowing) {
            render()
            syncTick()
        }
    }

    /** Ответ страницы на «добавить к себе»: не принято — кнопка возвращается. */
    fun onAddResult(id: String, ok: Boolean) {
        if (ok || !addedNow.remove(id)) return
        if (isShowing) render()
    }

    fun dismiss() {
        dialog?.dismiss()
    }

    private fun render() {
        val views = holder ?: return
        views.title.text = state.title
        views.artist.text = state.artist
        views.artist.visibility = if (state.artist.isEmpty()) View.GONE else View.VISIBLE
        views.toggle.setImageResource(if (state.playing) R.drawable.ic_player_pause else R.drawable.ic_player_play)
        views.toggle.contentDescription = context.getString(if (state.playing) R.string.player_pause else R.string.player_play)
        val addable = onAdd != null && state.canAddTrack && state.trackId !in addedNow
        // invisible, а не gone: место кнопки остаётся, пуск не сдвигается.
        views.add.visibility = if (addable) View.VISIBLE else View.INVISIBLE
        images.load(views.cover, cover, coverPx) { Views.musicPlaceholder(views.cover) }
        val known = state.duration > 0
        views.seek.isEnabled = known
        // Сначала значение в пределах, потом граница: полоса проверяет, что значение не больше конца.
        views.seek.value = 0f
        views.seek.valueTo = if (known) state.duration.toFloat() else 1f
        views.total.text = if (known) PlayerFormat.time(state.duration) else ""
        renderPosition()
    }

    private fun renderPosition() {
        val views = holder ?: return
        if (dragging) return
        val position = PlayerFormat.position(state.playing, state.position, state.duration, receivedAt, SystemClock.elapsedRealtime())
        if (state.duration > 0) views.seek.value = position.coerceIn(0.0, state.duration).toFloat()
        views.elapsed.text = PlayerFormat.time(position)
    }

    private companion object {
        const val TICK_MS = 500L

        /** Экран, которому принадлежит [context] (он может быть обёрнут темой). */
        fun lifecycleOwner(context: Context): LifecycleOwner? {
            var c: Context? = context
            while (c != null) {
                if (c is LifecycleOwner) return c
                c = (c as? ContextWrapper)?.baseContext
            }
            return null
        }
    }
}
