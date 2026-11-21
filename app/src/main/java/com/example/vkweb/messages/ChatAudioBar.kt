package com.example.vkweb.messages

import android.view.View
import android.widget.ImageView
import android.widget.SeekBar
import android.widget.TextView
import com.example.vkweb.R
import com.example.vkweb.media.ChatMusic
import com.example.vkweb.offline.ImageCache
import com.example.vkweb.ui.Views

/**
 * Полоса музыки из переписки над полем ввода — как плеер раздела «Музыка» (3.60): обложка, название и исполнитель,
 * время, перемотка, «назад», пауза, «вперёд», закрыть. Играет приложение ([ChatMusic], очередь — треки переписки);
 * полоса показывает его состояние. Запасной путь — трек нажимает сама страница ([update]): тогда только пауза и время.
 */
class ChatAudioBar(root: View, private val onPageToggle: () -> Unit, private val onPageStop: () -> Unit) {
    private val bar: View = root.findViewById(R.id.chat_audio_bar)
    private val toggle: ImageView = root.findViewById(R.id.chat_audio_toggle)
    private val prev: View = root.findViewById(R.id.chat_audio_prev)
    private val next: View = root.findViewById(R.id.chat_audio_next)
    private val seek: SeekBar = root.findViewById(R.id.chat_audio_seek)
    private val cover: ImageView = root.findViewById(R.id.chat_audio_cover)
    private val title: TextView = root.findViewById(R.id.chat_audio_title)
    private val artist: TextView = root.findViewById(R.id.chat_audio_artist)
    private val time: TextView = root.findViewById(R.id.chat_audio_time)
    private val speed: TextView = root.findViewById(R.id.chat_audio_speed)
    private val images = ImageCache.get(root.context)

    /** Трек играет страница (запасной путь), а не приложение. */
    private var pageMode = false
    private var dragging = false
    private var durationMs = 0
    private var shownCover: String? = null

    val isShown: Boolean get() = bar.visibility == View.VISIBLE

    /** Играет приложение (полоса следит за [ChatMusic]). */
    val isNative: Boolean get() = isShown && !pageMode

    private val listener: (ChatMusic.Snapshot) -> Unit = ::render

    init {
        Views.clipRounded(cover, Views.dp(cover, COVER_RADIUS_DP).toFloat())
        toggle.setOnClickListener { if (pageMode) onPageToggle() else ChatMusic.toggle() }
        prev.setOnClickListener { ChatMusic.previous() }
        speed.setOnClickListener { ChatMusic.cycleVoiceSpeed() }
        next.setOnClickListener { ChatMusic.next() }
        root.findViewById<View>(R.id.chat_audio_stop).setOnClickListener {
            if (pageMode) onPageStop() else ChatMusic.stop()
            hide()
        }
        seek.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(bar: SeekBar, progress: Int, fromUser: Boolean) {
                if (fromUser && durationMs > 0) time.text = clock(progress.toLong() * durationMs / SEEK_MAX, durationMs)
            }

            override fun onStartTrackingTouch(bar: SeekBar) {
                dragging = true
            }

            override fun onStopTrackingTouch(bar: SeekBar) {
                dragging = false
                if (durationMs > 0) ChatMusic.seekTo((bar.progress.toLong() * durationMs / SEEK_MAX).toInt())
            }
        })
    }

    /** Экран виден — полоса следит за музыкой из переписки (и сразу показывает то, что уже играет). */
    fun attach() = ChatMusic.addListener(listener)

    fun detach() = ChatMusic.removeListener(listener)

    private fun render(s: ChatMusic.Snapshot) {
        val track = s.track
        if (track == null) {
            if (!pageMode) bar.visibility = View.GONE
            return
        }
        pageMode = false
        bar.visibility = View.VISIBLE
        setControls(native = true)
        title.text = track.title
        artist.text = track.artist
        artist.visibility = if (track.artist.isEmpty()) View.GONE else View.VISIBLE
        prev.isEnabled = true
        next.isEnabled = s.index + 1 < s.size
        next.alpha = if (next.isEnabled) 1f else DISABLED_ALPHA
        if (shownCover != track.cover) {
            shownCover = track.cover
            images.load(cover, track.cover.ifEmpty { null }, Views.dp(cover, COVER_DP)) {
                androidx.core.content.ContextCompat.getDrawable(cover.context, R.drawable.ic_card_music)
            }
        }
        speed.visibility = if (track.voice) View.VISIBLE else View.GONE
        if (track.voice) {
            val label = com.example.vkweb.media.PlaybackSpeed.label(ChatMusic.voiceSpeed)
            speed.text = label
            speed.contentDescription = speed.context.getString(R.string.voice_speed_description, label)
        }
        val state = s.state
        val playing = state.playing || state.loading
        toggle.setImageResource(if (playing) R.drawable.ic_player_pause else R.drawable.ic_player_play)
        toggle.contentDescription = toggle.context.getString(if (playing) R.string.player_pause else R.string.player_play)
        durationMs = if (state.durationMs > 0) state.durationMs else track.durationSec * 1000
        seek.isEnabled = durationMs > 0 && !state.loading
        if (!dragging) {
            seek.progress = if (durationMs > 0) (state.positionMs.toLong() * SEEK_MAX / durationMs).toInt().coerceIn(0, SEEK_MAX) else 0
            time.text = when {
                state.loading -> time.context.getString(R.string.chat_audio_loading)
                durationMs > 0 -> clock(state.positionMs.toLong(), durationMs)
                else -> VoiceFormat.time(state.positionMs)
            }
        }
    }

    /** Запасной путь: трек играет страница мессенджера — её состояние (пауза и время, без перемотки и очереди). */
    fun update(audio: ReaderPayload.Audio) {
        when (audio.state) {
            "playing", "paused" -> {
                pageMode = true
                bar.visibility = View.VISIBLE
                setControls(native = false)
                val playing = audio.state == "playing"
                toggle.setImageResource(if (playing) R.drawable.ic_player_pause else R.drawable.ic_player_play)
                toggle.contentDescription = toggle.context.getString(if (playing) R.string.voice_pause else R.string.voice_play)
                if (audio.title.isNotEmpty()) title.text = audio.title
                artist.visibility = View.GONE
                time.text = when {
                    audio.durationSec > 0 -> com.example.vkweb.ui.PlayerFormat.clockMs(audio.positionSec * 1000L, audio.durationSec * 1000L)
                    else -> VoiceFormat.time(audio.positionSec * 1000)
                }
            }
            else -> if (pageMode) hide()
        }
    }

    private fun setControls(native: Boolean) {
        val v = if (native) View.VISIBLE else View.GONE
        if (!native) speed.visibility = View.GONE
        prev.visibility = v
        next.visibility = v
        seek.visibility = if (native) View.VISIBLE else View.INVISIBLE
        cover.visibility = v
    }

    fun hide() {
        pageMode = false
        bar.visibility = View.GONE
    }

    private fun clock(positionMs: Long, totalMs: Int) = com.example.vkweb.ui.PlayerFormat.clockMs(positionMs, totalMs.toLong())

    private companion object {
        const val SEEK_MAX = 1000
        const val COVER_DP = 40
        const val COVER_RADIUS_DP = 6
        const val DISABLED_ALPHA = 0.4f
    }
}
