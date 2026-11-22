package com.example.vkweb.clips

import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.GestureDetector
import android.view.MotionEvent
import android.view.View
import android.widget.ImageView
import android.widget.SeekBar
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.HttpDataSource
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.PlayerView
import com.example.vkweb.video.VkExoPlayer
import androidx.core.content.ContextCompat
import androidx.core.view.isVisible
import com.example.vkweb.MainActivity
import com.example.vkweb.R
import com.example.vkweb.config.SafeUrls
import com.example.vkweb.diag.AppLog
import com.example.vkweb.diag.LogTag
import com.example.vkweb.messages.VoicePlayer
import com.example.vkweb.offline.ImageCache
import com.example.vkweb.ui.SystemGestures
import com.example.vkweb.ui.Views
import kotlin.math.abs

/**
 * Свой проигрыватель ленты клипов (3.76). Отчёт 3.74: в ленте клипов страницы звук был, а картинки — нет, и ролики не
 * листались. Лента ([ClipsHub]) приходит от страницы; здесь ролик играет система ([VideoView]) во весь экран с
 * сохранением пропорций (4.0: Media3 ExoPlayer, один на всю ленту — перелистывание больше не подвешивает экран).
 * Смахивание вверх — следующий, вниз — прежний, нажатие — пауза, полоса — перемотка. Ролик
 * идёт по кругу, как в VK. Под конец ленты просим у страницы продолжение. «Назад» закрывает проигрыватель.
 */
@androidx.annotation.OptIn(markerClass = [UnstableApi::class])
class ClipsActivity : AppCompatActivity() {

    private lateinit var video: PlayerView
    private lateinit var exo: ExoPlayer
    private lateinit var cover: ImageView
    private lateinit var progress: View
    private lateinit var paused: View
    private lateinit var author: TextView
    private lateinit var avatar: ImageView
    private lateinit var text: TextView
    private lateinit var likes: TextView
    private lateinit var comments: TextView
    private lateinit var seek: SeekBar
    private lateinit var time: TextView
    private val handler = Handler(Looper.getMainLooper())
    private lateinit var chrome: ClipsChrome
    private val images by lazy { ImageCache.get(this) }

    private var index = 0
    private var currentId: String? = null
    private var dragging = false
    private var userPaused = false
    private var resumeAt = 0L
    private var sourceHost = ""
    private var startedAt = 0L
    private var firstFramePending = false

    private fun durationMs(): Long = VkExoPlayer.durationMs(exo.duration)

    private val listener = object : Player.Listener {
        override fun onPlaybackStateChanged(playbackState: Int) {
            progress.isVisible = playbackState == Player.STATE_BUFFERING
            if (playbackState == Player.STATE_READY) updateTime()
        }

        // Первый кадр на экране — обложка больше не нужна.
        override fun onRenderedFirstFrame() {
            cover.isVisible = false
            if (firstFramePending) {
                firstFramePending = false
                AppLog.d(LogTag.MEDIA, "клип: первый кадр в своём проигрывателе",
                    "номер в ленте" to index + 1, "сервер" to sourceHost, "мс" to SystemClock.elapsedRealtime() - startedAt)
            }
        }

        override fun onPlayerError(error: PlaybackException) {
            val http = generateSequence(error.cause) { it.cause }
                .filterIsInstance<HttpDataSource.InvalidResponseCodeException>().firstOrNull()
            AppLog.w(LogTag.MEDIA, "клип не воспроизводится в своём проигрывателе",
                "номер в ленте" to index + 1, "сервер" to sourceHost, "код" to error.errorCodeName,
                "HTTP" to http?.responseCode, "первый кадр ожидался" to firstFramePending)
            Toast.makeText(this@ClipsActivity, R.string.clips_failed, Toast.LENGTH_SHORT).show()
            handler.post { show(index + 1) }
        }
    }

    private val feedListener: () -> Unit = { onFeedChanged() }

    /** Экран не гаснет, пока клип играет (4.88). */
    private val screenAwake by lazy { com.example.vkweb.ui.ScreenAwake.Follower(this) }

    private val tick = object : Runnable {
        override fun run() {
            updateTime()
            handler.postDelayed(this, TICK_MS)
        }
    }

    @SuppressLint("ClickableViewAccessibility")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_clips)
        video = findViewById(R.id.clips_video)
        cover = findViewById(R.id.clips_cover)
        progress = findViewById(R.id.clips_progress)
        paused = findViewById(R.id.clips_paused)
        author = findViewById(R.id.clips_author)
        avatar = findViewById(R.id.clips_avatar)
        text = findViewById(R.id.clips_text)
        likes = findViewById(R.id.clips_likes)
        comments = findViewById(R.id.clips_comments)
        seek = findViewById(R.id.clips_seek)
        SystemGestures.exclude(seek)
        // 4.41: как у видео — перемотка выше зоны жеста снизу (иначе вызов кнопок Android перематывал ролик).
        SystemGestures.keepAboveBottomGestures(findViewById(R.id.clips_bottom))
        time = findViewById(R.id.clips_time)
        exo = VkExoPlayer.create(this).also {
            it.repeatMode = Player.REPEAT_MODE_ONE
            it.addListener(listener)
            // 4.88: экран не гаснет, пока клип играет.
            it.addListener(screenAwake)
        }
        video.player = exo
        Views.clipRounded(avatar, Views.dp(avatar, AVATAR_DP / 2).toFloat())
        com.example.vkweb.ui.SystemBars.immersive(this)
        // 4.31: интерфейс поверх ролика по умолчанию скрыт, нажатие показывает (ClipsChrome).
        chrome = ClipsChrome(
            listOf(findViewById(R.id.clips_top), findViewById(R.id.clips_side), findViewById(R.id.clips_bottom)),
        ) { getSystemService(android.view.accessibility.AccessibilityManager::class.java)?.isTouchExplorationEnabled == true }
        chrome.init()

        findViewById<View>(R.id.clips_close).setOnClickListener { finish() }
        findViewById<View>(R.id.clips_page).setOnClickListener { openPage() }
        // 4.45: комментарии — своим списком поверх ролика (раньше — страница клипа в главном окне).
        comments.setOnClickListener { openComments() }
        // «Нравится» прямо здесь (4.0): от имени страницы в главном окне; его нет — на страницу клипа.
        likes.setOnClickListener { chrome.touched(); toggleLike() }
        val heart = ContextCompat.getDrawable(this, R.drawable.ic_post_like)?.mutate()?.apply { setTint(getColor(R.color.media_on)) }
        likes.setCompoundDrawablesRelativeWithIntrinsicBounds(null, heart, null, null)
        val bubble = ContextCompat.getDrawable(this, R.drawable.ic_post_comment)?.mutate()?.apply { setTint(getColor(R.color.media_on)) }
        comments.setCompoundDrawablesRelativeWithIntrinsicBounds(null, bubble, null, null)

        val gestures = GestureDetector(this, object : GestureDetector.SimpleOnGestureListener() {
            override fun onDown(e: MotionEvent) = true

            // Интерфейс скрыт — нажатие показывает его; виден — ставит на паузу и снимает с неё (4.31).
            override fun onSingleTapUp(e: MotionEvent): Boolean {
                if (chrome.isShown) togglePause() else chrome.show()
                return true
            }

            override fun onFling(e1: MotionEvent?, e2: MotionEvent, vx: Float, vy: Float): Boolean {
                val start = e1 ?: return false
                val dy = e2.y - start.y
                val dx = e2.x - start.x
                if (abs(dy) < Views.dp(video, SWIPE_MIN_DP) || abs(dy) < abs(dx) * 1.5f) return false
                if (dy < 0) show(index + 1) else show(index - 1)
                return true
            }
        })
        findViewById<View>(R.id.clips_touch).setOnTouchListener { _, e -> gestures.onTouchEvent(e) }

        seek.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(bar: SeekBar, value: Int, fromUser: Boolean) {
                val d = durationMs()
                if (fromUser && d > 0) time.text = clock(value.toLong() * d / SEEK_MAX, d)
            }

            override fun onStartTrackingTouch(bar: SeekBar) {
                dragging = true
                chrome.hold(true)
            }

            override fun onStopTrackingTouch(bar: SeekBar) {
                dragging = false
                chrome.hold(userPaused)
                val d = durationMs()
                if (d > 0) exo.seekTo(bar.progress.toLong() * d / SEEK_MAX)
            }
        })

        // Звук не накладывается: голосовое или музыка из переписки замолкают, музыка страницы — на паузу (4.0).
        VoicePlayer.stop()
        com.example.vkweb.media.PlaybackHub.pauseAll()
        ClipsHub.addListener(feedListener)
        val start = savedInstanceState?.getString(STATE_ID) ?: ClipsHub.startId
        index = ClipsHub.items.indexOfFirst { it.id == start }.coerceAtLeast(0)
        resumeAt = savedInstanceState?.getLong(STATE_POSITION) ?: 0L
        AppLog.i(LogTag.PAGE, "клипы: свой проигрыватель открыт", "в ленте" to ClipsHub.items.size, "с" to index + 1)
        show(index)
    }

    private fun onFeedChanged() {
        // Лента пополнилась (или пришла заново): текущий ролик ищем по номеру, его место могло сдвинуться.
        val id = currentId
        val at = ClipsHub.items.indexOfFirst { it.id == id }
        if (at >= 0) {
            index = at
            // Ответ на «нравится» (или его отказ) — отметка и число по ленте.
            likePending = false
            showLikes(ClipsHub.items[at])
        } else if (id == null && ClipsHub.items.isNotEmpty()) {
            show(0)
        }
    }

    /** Ждём ответа страницы на «нравится»: второе нажатие до него не уходит. */
    private var likePending = false

    private fun toggleLike() {
        val clip = ClipsHub.items.getOrNull(index) ?: return
        val request = ClipsHub.requestLike
        if (request == null) { openPage(); return }
        if (likePending) return
        likePending = true
        val on = !clip.liked
        AppLog.i(LogTag.PAGE, "клипы: «нравится»", "поставить" to on)
        // Сразу на экране (как в VK); если страница откажет, лента вернёт прежнее.
        showLikes(clip.copy(liked = on, likes = ClipLikes.count(clip, on)))
        request(clip.id, on)
    }

    private fun showLikes(clip: ClipItem) {
        likes.text = count(clip.likes)
        likes.contentDescription = getString(if (clip.liked) R.string.clips_liked_description else R.string.clips_likes_description, count(clip.likes))
        val heart = ContextCompat.getDrawable(this, if (clip.liked) R.drawable.ic_post_liked else R.drawable.ic_post_like)?.mutate()
        heart?.setTint(getColor(if (clip.liked) R.color.signal_red else R.color.media_on))
        likes.setCompoundDrawablesRelativeWithIntrinsicBounds(null, heart, null, null)
    }

    private fun show(target: Int) {
        val list = ClipsHub.items
        if (list.isEmpty()) return
        if (target >= list.size) {
            if (ClipsHub.hasMore) ClipsHub.requestMore?.invoke()
            Toast.makeText(this, R.string.clips_end, Toast.LENGTH_SHORT).show()
            return
        }
        if (target < 0) return
        index = target
        val clip = list[target]
        if (clip.id == currentId && exo.isPlaying) return
        currentId = clip.id
        userPaused = false
        paused.isVisible = false
        chrome.reset()
        progress.isVisible = true
        cover.isVisible = clip.cover.isNotEmpty()
        if (clip.cover.isNotEmpty()) images.load(cover, clip.cover, resources.displayMetrics.widthPixels)
        author.text = clip.author
        text.text = clip.text
        text.isVisible = clip.text.isNotEmpty()
        avatar.isVisible = clip.avatar.isNotEmpty()
        if (clip.avatar.isNotEmpty()) images.load(avatar, clip.avatar, Views.dp(avatar, AVATAR_DP))
        showLikes(clip)
        comments.text = count(clip.comments)
        comments.contentDescription = getString(R.string.clips_comments_description, count(clip.comments))
        seek.progress = 0
        time.text = ""
        // Тот же проигрыватель — новая запись: без освобождения и пересоздания (раньше — подвисание на каждом ролике).
        sourceHost = SafeUrls.hostOf(clip.source).orEmpty()
        startedAt = SystemClock.elapsedRealtime()
        firstFramePending = true
        AppLog.d(LogTag.MEDIA, "клип: источник передан своему проигрывателю",
            "номер в ленте" to index + 1, "сервер" to sourceHost, "HLS" to VkExoPlayer.isHls(clip.source))
        exo.setMediaItem(VkExoPlayer.item(clip.source), resumeAt)
        resumeAt = 0L
        exo.prepare()
        exo.playWhenReady = true
        // Обложка следующего — заранее (4.0): при перелистывании сразу картинка, а не чёрный экран.
        list.getOrNull(target + 1)?.cover?.takeIf { it.isNotEmpty() }?.let { images.loadFull(it, NEXT_COVER_PX) { } }
        // Под конец ленты — продолжение заранее, чтобы листать без остановки.
        if (list.size - target <= PREFETCH_LEFT && ClipsHub.hasMore) ClipsHub.requestMore?.invoke()
    }

    private fun togglePause() {
        if (exo.playWhenReady) {
            exo.pause()
            userPaused = true
        } else {
            exo.play()
            userPaused = false
        }
        paused.isVisible = userPaused
        chrome.hold(userPaused)
    }

    private fun updateTime() {
        val d = durationMs()
        if (d <= 0 || dragging) return
        val p = exo.currentPosition.coerceIn(0, d)
        seek.progress = (p * SEEK_MAX / d).toInt().coerceIn(0, SEEK_MAX)
        seek.secondaryProgress = (exo.bufferedPosition.coerceIn(0, d) * SEEK_MAX / d).toInt()
        time.text = clock(p, d)
    }

    private fun clock(positionMs: Long, totalMs: Long) = com.example.vkweb.ui.PlayerFormat.clockMs(positionMs, totalMs)

    private fun count(n: Int): String = when {
        n >= 1_000_000 -> String.format(java.util.Locale.ROOT, "%.1fM", n / 1_000_000.0)
        n >= 10_000 -> "${n / 1000}K"
        else -> n.toString()
    }

    /** Комментарии к клипу своим списком; пока он открыт, ролик на паузе. Списка нет — страница клипа. */
    private fun openComments() {
        val clip = ClipsHub.items.getOrNull(index) ?: return
        chrome.touched()
        com.example.vkweb.comments.CommentsSheet.open(this, clip.id, onOpenPage = ::openPage) { shown ->
            if (shown) exo.pause() else if (!userPaused && currentId != null && !isFinishing) exo.play()
        }
    }

    /** Страница клипа на сайте (комментарии, поделиться) — в главном окне. */
    private fun openPage() {
        val clip = ClipsHub.items.getOrNull(index) ?: return
        // Сначала — «закрыт ради страницы»: главное окно выходит из списка раздела до того, как начнёт её загружать.
        openedPage = true
        notifyClosed()
        startActivity(
            Intent(this, MainActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_REORDER_TO_FRONT or Intent.FLAG_ACTIVITY_SINGLE_TOP)
                .putExtra(MainActivity.EXTRA_OPEN_URL, clip.pagePath)
                .putExtra(MainActivity.EXTRA_INTERNAL_TOKEN, MainActivity.INTERNAL_TOKEN),
        )
        openedPage = true
        finish()
    }

    private var openedPage = false


    override fun onResume() {
        super.onResume()
        handler.post(tick)
        if (!userPaused && currentId != null) exo.play()
    }

    override fun onPause() {
        super.onPause()
        handler.removeCallbacks(tick)
        exo.pause()
        // Закрытие — сразу, а не в onDestroy (отчёт 3.80): главное окно успевало показаться со страницей клипа под
        // проигрывателем (пустой чёрный экран) и лишь через полсекунды уходило назад.
        if (isFinishing) notifyClosed()
    }

    private var closedNotified = false

    private fun notifyClosed() {
        if (closedNotified) return
        closedNotified = true
        ClipsHub.onClosed?.invoke(openedPage)
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putString(STATE_ID, currentId)
        outState.putLong(STATE_POSITION, exo.currentPosition)
    }

    override fun onDestroy() {
        ClipsHub.removeListener(feedListener)
        handler.removeCallbacksAndMessages(null)
        if (::chrome.isInitialized) chrome.release()
        video.player = null
        exo.removeListener(listener)
        screenAwake.release(exo)
        exo.release()
        if (isFinishing) notifyClosed()
        super.onDestroy()
    }

    companion object {
        /** Обложку следующего достаточно положить в кэш — разбирать её в полный размер незачем. */
        private const val NEXT_COVER_PX = 64
        /** Отмеченное «нравится» — красным, как в VK (поверх видео цвет темы не подходит). */
        private const val STATE_ID = "clip"
        private const val STATE_POSITION = "position"
        private const val SEEK_MAX = 1000
        private const val TICK_MS = 500L
        private const val SWIPE_MIN_DP = 60
        private const val AVATAR_DP = 32
        private const val PREFETCH_LEFT = 3

        fun open(context: Context, startId: String?) {
            ClipsHub.startId = startId
            context.startActivity(Intent(context, ClipsActivity::class.java))
        }
    }
}
