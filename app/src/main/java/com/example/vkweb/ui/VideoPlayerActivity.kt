package com.example.vkweb.ui

import com.example.vkweb.config.SafeUrls
import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.content.pm.ActivityInfo
import android.media.AudioManager
import android.os.Bundle
import android.view.View
import android.view.ViewGroup
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.FrameLayout
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.VideoSize
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.PlayerView
import androidx.core.view.isVisible
import com.example.vkweb.MainActivity
import com.example.vkweb.R
import com.example.vkweb.config.AppConfig
import com.example.vkweb.diag.AppLog
import com.example.vkweb.diag.LogTag
import com.example.vkweb.messages.VoicePlayer
import com.example.vkweb.settings.AppSettings
import com.example.vkweb.video.VideoOrientation
import com.example.vkweb.video.VideoQuality
import com.example.vkweb.video.VideoVolumeGesture
import com.example.vkweb.video.VkExoPlayer
import com.example.vkweb.web.ExternalLinks
import com.example.vkweb.web.WebViewSetup

/**
 * Видео и клипы из переписки — в самой переписке, без перехода в главное окно (как в обычном VK: нажал — играет).
 *
 * Порядок: файл mp4 или поток HLS играет Media3 ExoPlayer (4.0; до того — системный VideoView, подвешивал экран); нет
 * файла или он не играет —
 * встраиваемый проигрыватель VK ([EXTRA_PLAYER], video_ext.php) в WebView с теми же cookie, что у приложения; не вышло и
 * так — страница видео в главном окне ([EXTRA_PAGE]), как раньше. Кнопка сверху открывает страницу видео в любой момент.
 * Все адреса — только https на серверах VK (проверены при разборе и ещё раз здесь).
 */
@androidx.annotation.OptIn(markerClass = [UnstableApi::class])
class VideoPlayerActivity : AppCompatActivity() {

    private lateinit var video: PlayerView
    /** Проигрыватель файла; null — играет встроенный проигрыватель VK или ещё ничего. */
    private var exo: ExoPlayer? = null
    private lateinit var progress: View
    private lateinit var controls: View
    /** Нижняя часть: кнопки над полосой и полоса перемотки от края до края (4.0). */
    private lateinit var bottom: View
    private lateinit var playButton: android.widget.ImageButton
    private lateinit var seek: android.widget.SeekBar
    private lateinit var time: TextView
    private var dragging = false
    private val handler = android.os.Handler(android.os.Looper.getMainLooper())
    private val tick = object : Runnable {
        override fun run() {
            updateTime()
            handler.postDelayed(this, TICK_MS)
        }
    }
    private val autoHide = Runnable { if (isPlaying() && !dragging) setControlsShown(false) }
    private lateinit var webHolder: FrameLayout
    private var web: WebView? = null
    private var customView: View? = null
    private var customCallback: WebChromeClient.CustomViewCallback? = null

    private var page = ""
    private var player = ""
    /** Файлы по высоте кадра и поток (3.86): выбор качества. */
    private var files: Map<Int, String> = emptyMap()
    private var hls = ""
    /** Играющее качество: высота или [VideoQuality.AUTO] (поток); -1 — один адрес без выбора. */
    private var quality = -1
    private lateinit var qualityButton: TextView
    /** Поворот (3.88): выбранный кнопкой (главнее автоматического) и размер кадра для автоматического. */
    private var manualOrientation: VideoOrientation.Mode? = null
    private var frameWidth = 0
    private var frameHeight = 0
    private val autoRotate by lazy { AppSettings(this).videoAutoRotate }
    private var position = 0L
    /** Играло ли видео, когда экран ушёл в фон (на паузе — после возврата остаётся на паузе). */
    private var resumePlaying = true
    private var finishedWithFallback = false

    private var speed = 1f
    private lateinit var speedButton: TextView
    private lateinit var skipHint: TextView
    private val hideSkipHint = Runnable { skipHint.isVisible = false }

    // Громкость жестом по видео (4.9): вверх — громче, вниз — тише; подсказка — там же, где «+10 с».
    private val audio by lazy { getSystemService(Context.AUDIO_SERVICE) as AudioManager }
    private var volumeGestureEnabled = true
    private var volumeDragging = false
    private var volumeStart = 0
    private var volumeAnchorY = 0f

    /** Место в ролике между открытиями (4.0). Пусто — не запоминаем (клип по кругу). */
    private val resume by lazy { com.example.vkweb.video.VideoResume(this) }
    private var resumeKey = ""

    private fun isPlaying(): Boolean = exo?.isPlaying == true
    private fun durationMs(): Long = exo?.let { VkExoPlayer.durationMs(it.duration) } ?: 0L

    private val listener = object : Player.Listener {
        override fun onPlaybackStateChanged(playbackState: Int) {
            // Подгрузка посреди ролика (плохая сеть) — крутилка, пока не продолжит.
            progress.isVisible = playbackState == Player.STATE_BUFFERING
            when (playbackState) {
                Player.STATE_READY -> {
                    updateSpeedButton()
                    updateTime()
                }
                Player.STATE_ENDED -> {
                    updatePlayButton()
                    setControlsShown(true)
                }
                // Подгрузка — крутилка выше; простой — ничего (Lint SwitchIntDef: все состояния названы явно).
                Player.STATE_BUFFERING, Player.STATE_IDLE -> Unit
            }
        }

        override fun onIsPlayingChanged(isPlaying: Boolean) {
            updatePlayButton()
            if (isPlaying) scheduleAutoHide()
        }

        override fun onVideoSizeChanged(videoSize: VideoSize) {
            if (videoSize.width <= 0 || videoSize.height <= 0) return
            if (videoSize.width == frameWidth && videoSize.height == frameHeight) return
            frameWidth = videoSize.width
            frameHeight = videoSize.height
            applyOrientation(VideoOrientation.choose(frameWidth, frameHeight, autoRotate, manualOrientation))
        }

        override fun onPlayerError(error: PlaybackException) {
            AppLog.w(LogTag.MEDIA, "файл видео не играет", "код" to error.errorCodeName)
            if (player.isNotEmpty()) playEmbedded() else fallback("ошибка проигрывателя ${error.errorCodeName}")
        }
    }

    private fun updateSpeedButton() {
        speedButton.isVisible = video.isVisible && exo != null
        val label = com.example.vkweb.media.PlaybackSpeed.label(speed)
        speedButton.text = label
        speedButton.contentDescription = getString(R.string.video_speed_button, label)
    }

    private fun chooseSpeed() {
        val steps = com.example.vkweb.media.PlaybackSpeed.VIDEO
        val labels = steps.map { com.example.vkweb.media.PlaybackSpeed.label(it) }.toTypedArray()
        com.google.android.material.dialog.MaterialAlertDialogBuilder(this)
            .setTitle(R.string.video_speed_title)
            .setSingleChoiceItems(labels, steps.indexOfFirst { it == speed }) { dialog, which ->
                dialog.dismiss()
                speed = steps[which]
                AppLog.i(LogTag.MEDIA, "скорость видео", "скорость" to speed)
                exo?.setPlaybackSpeed(speed)
                updateSpeedButton()
                scheduleAutoHide()
            }
            .show()
    }

    /** Двойное касание: левая треть — назад на 10 с, правая — вперёд, середина — пауза/игра. */
    private fun onDoubleTap(x: Float) {
        val p = exo ?: return
        val d = durationMs()
        if (!video.isVisible || d <= 0) return
        val delta = when (com.example.vkweb.video.VideoTap.zone(x, video.width)) {
            com.example.vkweb.video.VideoTap.Zone.BACK -> -SKIP_MS
            com.example.vkweb.video.VideoTap.Zone.FORWARD -> SKIP_MS
            com.example.vkweb.video.VideoTap.Zone.PLAY_PAUSE -> { togglePlay(); return }
        }
        val target = com.example.vkweb.video.VideoTap.target(p.currentPosition, delta, d)
        p.seekTo(target)
        position = target
        skipHint.text = getString(if (delta < 0) R.string.video_skip_back else R.string.video_skip_forward, SKIP_MS / 1000)
        skipHint.isVisible = true
        handler.removeCallbacks(hideSkipHint)
        handler.postDelayed(hideSkipHint, SKIP_HINT_MS)
        updateTime()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        onBackPressedDispatcher.addCallback(this, exitFullscreen)
        setContentView(R.layout.activity_video)
        video = findViewById(R.id.video_view)
        progress = findViewById(R.id.video_progress)
        controls = findViewById(R.id.video_controls)
        bottom = findViewById(R.id.video_bottom)
        SystemGestures.keepAboveBottomGestures(bottom)
        playButton = findViewById(R.id.video_play)
        seek = findViewById(R.id.video_seek)
        SystemGestures.exclude(seek)
        time = findViewById(R.id.video_time)
        playButton.setOnClickListener { togglePlay() }
        seek.setOnSeekBarChangeListener(object : android.widget.SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(bar: android.widget.SeekBar, value: Int, fromUser: Boolean) {
                val d = durationMs()
                if (fromUser && d > 0) time.text = clock(value.toLong() * d / SEEK_MAX, d)
            }

            override fun onStartTrackingTouch(bar: android.widget.SeekBar) {
                dragging = true
                handler.removeCallbacks(autoHide)
            }

            override fun onStopTrackingTouch(bar: android.widget.SeekBar) {
                dragging = false
                val d = durationMs()
                if (d > 0) exo?.seekTo(bar.progress.toLong() * d / SEEK_MAX)
                scheduleAutoHide()
            }
        })
        webHolder = findViewById(R.id.video_web_holder)
        findViewById<View>(R.id.video_close).setOnClickListener { finish() }
        findViewById<TextView>(R.id.video_title_bar).text = intent.getStringExtra(EXTRA_TITLE).orEmpty()

        page = intent.getStringExtra(EXTRA_PAGE)?.takeIf { it.startsWith("/") && !it.startsWith("//") }.orEmpty()
        player = vkUrl(intent.getStringExtra(EXTRA_PLAYER))
        val file = vkUrl(intent.getStringExtra(EXTRA_FILE))
        findViewById<View>(R.id.video_page).apply {
            isVisible = page.isNotEmpty()
            setOnClickListener { openPage() }
        }
        // 4.45: комментарии — своим списком поверх ролика, ролик на это время на паузе.
        val commentsId = com.example.vkweb.comments.CommentTarget.fromPagePath(page)
        findViewById<View>(R.id.video_comments).apply {
            isVisible = commentsId != null
            setOnClickListener {
                var wasPlaying = false
                com.example.vkweb.comments.CommentsSheet.open(this@VideoPlayerActivity, commentsId, onOpenPage = this@VideoPlayerActivity::openPage) { shown ->
                    val p = exo ?: return@open
                    if (shown) { wasPlaying = p.playWhenReady; p.pause() } else if (wasPlaying && !isFinishing) p.play()
                }
            }
        }
        files = VideoQuality.decode(intent.getStringArrayListExtra(EXTRA_FILES), ::vkUrl)
        hls = vkUrl(intent.getStringExtra(EXTRA_HLS))
        speedButton = findViewById(R.id.video_speed)
        speedButton.setOnClickListener { chooseSpeed() }
        skipHint = findViewById(R.id.video_skip_hint)
        speed = savedInstanceState?.getFloat(STATE_SPEED, 1f)?.let { com.example.vkweb.media.PlaybackSpeed.valid(it, com.example.vkweb.media.PlaybackSpeed.VIDEO) } ?: 1f
        qualityButton = findViewById(R.id.video_quality)
        qualityButton.setOnClickListener { chooseQuality() }
        findViewById<View>(R.id.video_rotate).setOnClickListener { rotateByHand() }
        manualOrientation = savedInstanceState?.getString(STATE_ORIENTATION)?.let { name ->
            VideoOrientation.Mode.entries.firstOrNull { it.name == name }
        }
        manualOrientation?.let { applyOrientation(it) }
        position = savedInstanceState?.getLong(STATE_POSITION) ?: 0L
        // Длинный ролик, закрытый посередине, — продолжаем оттуда же (4.0); поворот экрана — своё место выше.
        if (!intent.getBooleanExtra(EXTRA_LOOP, false)) {
            resumeKey = com.example.vkweb.video.VideoResumeRules.keyOf(page, file.ifEmpty { hls })
            if (savedInstanceState == null) {
                val saved = resume.get(resumeKey)
                if (saved > 0) {
                    position = saved
                    AppLog.i(LogTag.MEDIA, "видео продолжено с прошлого места", "с, сек" to saved / 1000)
                }
            }
        }
        resumePlaying = savedInstanceState?.getBoolean(STATE_PLAYING, true) ?: true
        // Звук не должен накладываться: голосовое из переписки останавливается, музыка — на паузу (4.0).
        VoicePlayer.stop()
        com.example.vkweb.media.PlaybackHub.pauseAll()
        com.example.vkweb.ui.SystemBars.immersive(this)

        // Качество по настройке: самое высокое не выше выбранного; «Авто» — поток. Повёрнутый экран — то, что смотрели.
        val start = savedInstanceState?.getInt(STATE_QUALITY, -1)?.takeIf { it >= 0 } ?: initialQuality(AppSettings(this).videoQuality)
        val chosen = urlFor(start)
        when {
            chosen != null -> { quality = start; playFile(chosen) }
            file.isNotEmpty() -> playFile(file)
            player.isNotEmpty() -> playEmbedded()
            else -> fallback("нет адреса")
        }
        // Касание видео прячет и показывает обе полосы кнопок (3.90: свои вместо системных); двойное у края —
        // перемотка на 10 с (4.0).
        val root = findViewById<View>(R.id.video_root)
        val gestures = android.view.GestureDetector(this, object : android.view.GestureDetector.SimpleOnGestureListener() {
            override fun onDown(e: android.view.MotionEvent) = true
            override fun onSingleTapConfirmed(e: android.view.MotionEvent): Boolean {
                root.performClick()
                return true
            }
            override fun onDoubleTap(e: android.view.MotionEvent): Boolean {
                onDoubleTap(e.x)
                return true
            }
            override fun onScroll(e1: android.view.MotionEvent?, e2: android.view.MotionEvent, distanceX: Float, distanceY: Float): Boolean {
                val down = e1 ?: return false
                return onVolumeDrag(down, e2)
            }
        })
        root.setOnClickListener { toggleControls() }
        // Палец подняли — жест громкости закончен (GestureDetector об этом не сообщает).
        val touch = View.OnTouchListener { _, e ->
            val handled = gestures.onTouchEvent(e)
            if (e.actionMasked == android.view.MotionEvent.ACTION_UP || e.actionMasked == android.view.MotionEvent.ACTION_CANCEL) endVolumeDrag()
            handled
        }
        @SuppressLint("ClickableViewAccessibility")
        root.setOnTouchListener(touch)
        // PlayerView сам ловит касания (у него свои кнопки, здесь выключенные) — отдаём их общему обработчику.
        @SuppressLint("ClickableViewAccessibility")
        video.setOnTouchListener(touch)
        // Кнопки громкости телефона — тоже громкость видео, а не звонка.
        volumeControlStream = AudioManager.STREAM_MUSIC
        volumeGestureEnabled = AppSettings(this).videoVolumeGesture
    }

    /** Громкость меняется жестом: включено в настройках, играет своё видео (не страница VK), громкость не фиксирована. */
    private fun canChangeVolume(): Boolean = volumeGestureEnabled && exo != null && video.isVisible && !audio.isVolumeFixed

    /** Палец ведут от [down] к [now]: начать жест громкости (если движение вертикальное) или продолжить его. */
    private fun onVolumeDrag(down: android.view.MotionEvent, now: android.view.MotionEvent): Boolean {
        val height = video.height
        if (!volumeDragging) {
            if (!canChangeVolume() || height <= 0) return false
            val slop = android.view.ViewConfiguration.get(this).scaledTouchSlop.toFloat()
            val edge = resources.displayMetrics.density * SYSTEM_EDGE_DP
            if (VideoVolumeGesture.inSystemEdge(down.y, height, edge)) return false
            if (!VideoVolumeGesture.inVolumeZone(down.x, video.width)) return false
            if (!VideoVolumeGesture.starts(now.x - down.x, now.y - down.y, slop)) return false
            volumeDragging = true
            volumeStart = audio.getStreamVolume(AudioManager.STREAM_MUSIC)
            // Отсчёт — от точки, где жест узнан: иначе громкость прыгнула бы на длину порога.
            volumeAnchorY = now.y
            handler.removeCallbacks(hideSkipHint)
        }
        val max = audio.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
        val level = VideoVolumeGesture.level(volumeStart, max, now.y - volumeAnchorY, height)
        if (level != audio.getStreamVolume(AudioManager.STREAM_MUSIC)) {
            try {
                // Без флагов: системная полоса громкости не выезжает поверх видео — своя подсказка.
                audio.setStreamVolume(AudioManager.STREAM_MUSIC, level, 0)
            } catch (e: SecurityException) {
                // «Не беспокоить» на некоторых прошивках: громкость менять нельзя — жест ничего не делает.
                AppLog.w(LogTag.MEDIA, "громкость видео не изменена", "причина" to e.javaClass.simpleName)
            }
        }
        skipHint.text = getString(R.string.video_volume, VideoVolumeGesture.percent(level, max))
        skipHint.isVisible = true
        return true
    }

    private fun endVolumeDrag() {
        if (!volumeDragging) return
        volumeDragging = false
        AppLog.d(LogTag.MEDIA, "громкость видео жестом", "уровень" to audio.getStreamVolume(AudioManager.STREAM_MUSIC))
        handler.removeCallbacks(hideSkipHint)
        handler.postDelayed(hideSkipHint, VOLUME_HINT_MS)
    }

    private fun applyOrientation(mode: VideoOrientation.Mode) {
        val wanted = when (mode) {
            VideoOrientation.Mode.LANDSCAPE -> ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
            VideoOrientation.Mode.PORTRAIT -> ActivityInfo.SCREEN_ORIENTATION_SENSOR_PORTRAIT
            VideoOrientation.Mode.SYSTEM -> ActivityInfo.SCREEN_ORIENTATION_FULL_USER
        }
        if (requestedOrientation != wanted) requestedOrientation = wanted
    }

    /** Кнопка поворота: альбомная ↔ портретная; выбранное держится до закрытия (в том числе при смене качества). */
    private fun rotateByHand() {
        val landscape = resources.configuration.orientation == android.content.res.Configuration.ORIENTATION_LANDSCAPE
        val mode = VideoOrientation.toggled(landscape)
        manualOrientation = mode
        AppLog.i(LogTag.MEDIA, "видео: поворот вручную", "на" to if (mode == VideoOrientation.Mode.LANDSCAPE) "альбомную" else "портретную")
        applyOrientation(mode)
    }

    /** Качества, из которых можно выбрать: поток («Авто») и файлы — от высокого к низкому. */
    private fun choices(): List<Int> = VideoQuality.choices(files, hls)

    private fun initialQuality(preferred: Int): Int = VideoQuality.initial(files, hls, preferred)

    private fun urlFor(q: Int): String? = VideoQuality.urlFor(q, files, hls)

    private fun updateQualityButton() {
        val many = choices().size >= 2
        qualityButton.isVisible = many && quality >= 0 && video.isVisible
        val label = VideoQuality.label(quality)
        qualityButton.text = label
        qualityButton.contentDescription = getString(R.string.video_quality_button, label)
    }

    /** Список качеств; выбранное играет с того же места (и на паузе остаётся на паузе). */
    private fun chooseQuality() {
        val list = choices()
        if (list.size < 2) return
        val labels = list.map { VideoQuality.label(it) }.toTypedArray()
        com.google.android.material.dialog.MaterialAlertDialogBuilder(this)
            .setTitle(R.string.video_quality_title)
            .setSingleChoiceItems(labels, list.indexOf(quality)) { dialog, which ->
                dialog.dismiss()
                switchQuality(list[which])
            }
            .show()
    }

    /** Другое качество — тот же проигрыватель, новая запись с того же места (кадр не гаснет: keep_content). */
    private fun switchQuality(q: Int) {
        if (q == quality) return
        val url = urlFor(q) ?: return
        val p = exo ?: return
        position = p.currentPosition
        resumePlaying = p.isPlaying || p.playWhenReady
        AppLog.i(LogTag.MEDIA, "качество видео переключено", "было" to VideoQuality.label(quality), "стало" to VideoQuality.label(q))
        quality = q
        updateQualityButton()
        p.setMediaItem(VkExoPlayer.item(url), position)
        p.prepare()
        p.playWhenReady = resumePlaying
    }

    private fun playFile(url: String) {
        AppLog.i(
            LogTag.MEDIA, "видео играет своим проигрывателем", "откуда" to if (intent.getBooleanExtra(EXTRA_FROM_CHAT, false)) "переписка" else "раздел", "вид" to if (VkExoPlayer.isHls(url)) "поток" else "файл",
            "качество" to quality.takeIf { it >= 0 }?.let { VideoQuality.label(it) }, "есть" to choices().joinToString(",") { VideoQuality.label(it) },
        )
        updateQualityButton()
        val p = exo ?: VkExoPlayer.create(this).also {
            it.addListener(listener)
            // 4.88: экран не гаснет, пока ролик играет.
            it.addListener(screenAwake)
            exo = it
            video.player = it
        }
        p.repeatMode = if (intent.getBooleanExtra(EXTRA_LOOP, false)) Player.REPEAT_MODE_ONE else Player.REPEAT_MODE_OFF
        p.setPlaybackSpeed(speed)
        p.setMediaItem(VkExoPlayer.item(url), position)
        p.prepare()
        p.playWhenReady = resumePlaying
        progress.isVisible = true
        updatePlayButton()
        setControlsShown(true)
    }

    /** Проигрыватель файла больше не нужен (встроенный VK, закрытие). */
    private fun releasePlayer() {
        val p = exo ?: return
        video.player = null
        p.removeListener(listener)
        screenAwake.release(p)
        p.release()
        exo = null
    }

    /** Экран не гаснет, пока играет свой проигрыватель (4.88). */
    private val screenAwake by lazy { ScreenAwake.Follower(this) }

    /** Встраиваемый проигрыватель VK: сам выбирает качество, играет и то, у чего нет файла (трансляции, закрытые). */
    @SuppressLint("SetJavaScriptEnabled")
    private fun playEmbedded() {
        AppLog.i(LogTag.MEDIA, "видео играет встроенным проигрывателем VK")
        releasePlayer()
        video.isVisible = false
        // У встроенного проигрывателя VK свои кнопки и качество: внизу остаётся только поворот.
        qualityButton.isVisible = false
        speedButton.isVisible = false
        playButton.isVisible = false
        seek.isVisible = false
        time.isVisible = false
        progress.isVisible = false
        val view = WebView(this)
        WebViewSetup.configure(view)
        view.setBackgroundColor(getColor(R.color.media_background))
        view.webViewClient = object : WebViewClient() {
            override fun shouldOverrideUrlLoading(v: WebView, request: WebResourceRequest): Boolean {
                val uri = request.url
                if (uri.scheme == "https" && AppConfig.isImageHost(uri.host)) return false
                // Ссылки из проигрывателя наружу (автор, «смотреть на сайте» чужого домена) — в браузер, не сюда.
                if (request.isForMainFrame) ExternalLinks(this@VideoPlayerActivity).open(uri)
                return true
            }
        }
        view.webChromeClient = object : WebChromeClient() {
            override fun onShowCustomView(v: View, callback: CustomViewCallback) {
                customView?.let { (it.parent as? ViewGroup)?.removeView(it) }
                customView = v
                customCallback = callback
                exitFullscreen.isEnabled = true
                // Полный экран встроенного проигрывателя VK — альбомно (размер кадра здесь не известен).
                if (manualOrientation == null && autoRotate) applyOrientation(VideoOrientation.Mode.LANDSCAPE)
                (findViewById<ViewGroup>(R.id.video_root)).addView(v, ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
                controls.bringToFront()
                bottom.bringToFront()
            }

            override fun onHideCustomView() = hideCustomView()
        }
        webHolder.removeAllViews()
        webHolder.addView(view, FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT))
        webHolder.isVisible = true
        controls.bringToFront()
        bottom.bringToFront()
        web = view
        view.loadUrl(withAutoplay(player))
        handler.removeCallbacks(embeddedAwake)
        handler.post(embeddedAwake)
    }

    /**
     * Встроенный проигрыватель VK (4.88): играет ли ролик, приложение не знает — раз в несколько секунд спрашивает
     * страницу. Ролик на паузе или кончился — экран гаснет как обычно. Ролика на странице не видно (ещё грузится или
     * он в чужом фрейме) — экран держится, пока окно открыто: погасший посреди ролика экран хуже.
     */
    private val embeddedAwake = object : Runnable {
        override fun run() {
            val view = web ?: return
            view.evaluateJavascript(EMBEDDED_PLAYING_JS) { result ->
                if (web === view) ScreenAwake.set(this@VideoPlayerActivity, result != "false")
            }
            handler.postDelayed(this, EMBEDDED_CHECK_MS)
        }
    }

    private fun hideCustomView() {
        if (customView != null && manualOrientation == null) applyOrientation(VideoOrientation.Mode.SYSTEM)
        customView?.let { (it.parent as? ViewGroup)?.removeView(it) }
        customView = null
        exitFullscreen.isEnabled = false
        customCallback?.onCustomViewHidden()
        customCallback = null
    }

    /** Не играет ничего — страница видео в главном окне, как было до 3.54. */
    private fun fallback(reason: String) {
        AppLog.w(LogTag.MEDIA, "видео не играет своим проигрывателем, открывается страница", "причина" to reason)
        if (finishedWithFallback) return
        finishedWithFallback = true
        Toast.makeText(this, R.string.video_failed, Toast.LENGTH_SHORT).show()
        if (page.isEmpty()) {
            finish()
            return
        }
        openPage()
    }

    private fun openPage() {
        if (page.isEmpty()) return
        startActivity(
            Intent(this, MainActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_REORDER_TO_FRONT or Intent.FLAG_ACTIVITY_SINGLE_TOP)
                .putExtra(MainActivity.EXTRA_OPEN_URL, page)
                // 4.45: «Назад» со страницы ведёт в переписку, только если ролик открыли из неё.
                .putExtra(MainActivity.EXTRA_FROM_CHAT, intent.getBooleanExtra(EXTRA_FROM_CHAT, false))
                .putExtra(MainActivity.EXTRA_INTERNAL_TOKEN, MainActivity.INTERNAL_TOKEN),
        )
        finish()
    }

    private fun toggleControls() = setControlsShown(!controls.isVisible)

    /** Верхняя и нижняя полосы вместе; показанные во время игры сами прячутся. */
    private fun setControlsShown(shown: Boolean) {
        controls.isVisible = shown
        bottom.isVisible = shown
        handler.removeCallbacks(autoHide)
        if (shown) scheduleAutoHide()
    }

    private fun scheduleAutoHide() {
        handler.removeCallbacks(autoHide)
        handler.postDelayed(autoHide, CONTROLS_SHOW_MS.toLong())
    }

    private fun togglePlay() {
        val p = exo ?: return
        if (p.playbackState == Player.STATE_ENDED) p.seekTo(0)
        if (p.isPlaying) p.pause() else p.play()
        resumePlaying = p.playWhenReady
        updatePlayButton()
        scheduleAutoHide()
    }

    private fun updatePlayButton() {
        val playing = exo?.playWhenReady == true && exo?.playbackState != Player.STATE_ENDED
        playButton.setImageResource(if (playing) R.drawable.ic_player_pause else R.drawable.ic_player_play)
        playButton.contentDescription = getString(if (playing) R.string.video_pause else R.string.video_play)
    }

    private fun updateTime() {
        val p = exo ?: return
        if (!video.isVisible || dragging) return
        val d = durationMs()
        if (d <= 0) return
        val pos = p.currentPosition.coerceIn(0, d)
        seek.progress = (pos * SEEK_MAX / d).toInt().coerceIn(0, SEEK_MAX)
        // Сколько уже загружено — светлой частью полосы (4.0).
        seek.secondaryProgress = (p.bufferedPosition.coerceIn(0, d) * SEEK_MAX / d).toInt()
        time.text = clock(pos, d)
    }

    private fun clock(positionMs: Long, totalMs: Long) = PlayerFormat.clockMs(positionMs, totalMs)


    override fun onPause() {
        super.onPause()
        // Место запоминается и на паузе: поставленное на паузу видео после возврата не начинается с нуля.
        exo?.let { p ->
            if (p.currentPosition > 0) position = p.currentPosition
            resumePlaying = p.playWhenReady
            p.pause()
            val d = durationMs()
            if (d > 0) resume.put(resumeKey, if (p.playbackState == Player.STATE_ENDED) d else p.currentPosition, d)
        }
        handler.removeCallbacks(tick)
        handler.removeCallbacks(autoHide)
        handler.removeCallbacks(embeddedAwake)
        web?.onPause()
    }

    override fun onResume() {
        super.onResume()
        handler.post(tick)
        web?.onResume()
        if (web != null) {
            handler.removeCallbacks(embeddedAwake)
            handler.post(embeddedAwake)
        }
        exo?.let { if (resumePlaying) it.play() }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putLong(STATE_POSITION, exo?.currentPosition ?: position)
        outState.putBoolean(STATE_PLAYING, resumePlaying)
        outState.putInt(STATE_QUALITY, quality)
        outState.putFloat(STATE_SPEED, speed)
        manualOrientation?.let { outState.putString(STATE_ORIENTATION, it.name) }
    }

    /**
     * «Назад» из полноэкранного видео страницы — сначала выход из полного экрана (через диспетчер: с жестом «назад»
     * Android 14+ onBackPressed не вызывается). Колбэк включён, только пока видео во весь экран.
     */
    private val exitFullscreen = object : androidx.activity.OnBackPressedCallback(false) {
        override fun handleOnBackPressed() = hideCustomView()
    }

    override fun onDestroy() {
        handler.removeCallbacksAndMessages(null)
        releasePlayer()
        hideCustomView()
        web?.let {
            webHolder.removeView(it)
            it.destroy()
        }
        web = null
        super.onDestroy()
    }

    companion object {
        /** Проверка встроенного проигрывателя VK: true — ролик играет, false — нет, null — ролика на странице не видно. */
        private const val EMBEDDED_PLAYING_JS = "(function(){var v=document.getElementsByTagName('video');" +
            "if(!v.length)return null;for(var i=0;i<v.length;i++){if(!v[i].paused&&!v[i].ended)return true;}return false;})()"
        private const val EMBEDDED_CHECK_MS = 5_000L
        const val EXTRA_FILE = "file"
        const val EXTRA_PLAYER = "player"
        const val EXTRA_PAGE = "page"
        const val EXTRA_TITLE = "title"
        const val EXTRA_LOOP = "loop"
        /** Ролик открыт из переписки (4.45): страница видео из проигрывателя возвращает «Назад» туда. */
        const val EXTRA_FROM_CHAT = "from_chat"
        const val EXTRA_FILES = "files"
        const val EXTRA_HLS = "hls"
        private const val STATE_POSITION = "position"
        private const val STATE_QUALITY = "quality"
        private const val STATE_PLAYING = "playing"
        private const val STATE_SPEED = "speed"
        private const val SKIP_MS = 10_000L
        private const val SKIP_HINT_MS = 600L
        /** Подсказка громкости держится после того, как палец подняли. */
        private const val VOLUME_HINT_MS = 800L
        /** У верхнего и нижнего края касание — системный жест (шторка, домой), не громкость. */
        private const val SYSTEM_EDGE_DP = 48
        private const val STATE_ORIENTATION = "orientation"
        private const val CONTROLS_SHOW_MS = 3500
        private const val SEEK_MAX = 1000
        private const val TICK_MS = 500L

        /** Только https на серверах VK и их сетях доставки — иначе пусто. */
        fun vkUrl(value: String?): String = SafeUrls.video(value)

        /** Встраиваемому проигрывателю — сразу играть (autoplay=1), если в адресе этого ещё нет. */
        fun withAutoplay(url: String): String =
            if (url.contains("autoplay=")) url else url + (if (url.contains('?')) "&" else "?") + "autoplay=1"

        /**
         * Открыть видео: [file] — mp4 или поток HLS, [player] — встраиваемый проигрыватель, [page] — путь страницы видео
         * на сайте (запасной путь), [loop] — клип играет по кругу, как в VK. [files] — файлы по высоте кадра и [hls] —
         * поток: из них проигрыватель выбирает качество по настройке и даёт переключать (3.86); без них — [file].
         */
        fun open(
            context: Context, file: String, player: String, page: String, title: String, loop: Boolean,
            files: Map<Int, String> = emptyMap(), hls: String = "", fromChat: Boolean = false,
        ) {
            context.startActivity(
                Intent(context, VideoPlayerActivity::class.java)
                    .putExtra(EXTRA_FILE, file)
                    .putStringArrayListExtra(EXTRA_FILES, VideoQuality.encode(files))
                    .putExtra(EXTRA_HLS, hls)
                    .putExtra(EXTRA_PLAYER, player)
                    .putExtra(EXTRA_PAGE, page)
                    .putExtra(EXTRA_TITLE, title)
                    .putExtra(EXTRA_LOOP, loop)
                    .putExtra(EXTRA_FROM_CHAT, fromChat),
            )
        }
    }
}
