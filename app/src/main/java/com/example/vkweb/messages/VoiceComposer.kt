package com.example.vkweb.messages

import android.Manifest
import android.annotation.SuppressLint
import android.content.pm.PackageManager
import android.os.Build
import android.os.Handler
import android.view.MotionEvent
import android.view.View
import android.view.accessibility.AccessibilityManager
import android.widget.TextView
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import com.example.vkweb.R
import com.example.vkweb.diag.AppLog
import com.example.vkweb.diag.LogTag
import com.example.vkweb.ui.Notice
import java.io.File

/**
 * Кнопка микрофона в поле ввода (4.59, Android 10+): удерживать — запись, отпустить — отправить, смахнуть влево —
 * отмена. С TalkBack (жесты удержания там неудобны) — нажатие начинает запись, второе нажатие отправляет.
 * Пока идёт запись, вместо поля ввода — точка, время и подсказка ([recordingPanel]).
 */
class VoiceComposer(
    private val activity: ComponentActivity,
    private val micButton: View,
    /** Что прятать на время записи (поле, скрепка, стикеры). */
    private val composeViews: List<View>,
    private val recordingPanel: View,
    private val timeLabel: TextView,
    private val handler: Handler,
    /** Записано: отправить [file]. */
    private val onRecorded: (file: File, durationMs: Long) -> Unit,
) {
    /** Только с Android 10 (VoiceRecorder); экран работает с ним через интерфейс (4.69, Lint NewApi). */
    private val recorder: VoiceRecording? =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) VoiceRecorder(activity) else null

    private val askPermission = activity.registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        AppLog.i(LogTag.MESSAGES, "голосовое: разрешение на микрофон", "дано" to granted)
        if (!granted) Notice.show(activity, R.string.voice_record_no_permission, long = true)
    }

    private var downX = 0f
    private val cancelDistancePx = 120 * activity.resources.displayMetrics.density

    private val tick = object : Runnable {
        override fun run() {
            val r = recorder ?: return
            if (!isRecording) return
            val sec = r.elapsedMs / 1000
            timeLabel.text = activity.getString(R.string.voice_record_time, sec / 60, sec % 60)
            if (r.elapsedMs >= VoiceRecording.MAX_MS) finish(send = true) else handler.postDelayed(this, 250)
        }
    }

    /** Запись возможна на этом телефоне (формат голосовых VK — с Android 10). */
    val isAvailable: Boolean get() = recorder != null

    /** Экран уничтожен (4.65): ответы потока записи, пришедшие позже, ничего не делают. */
    private var destroyed = false
    private var generation = 0L

    /** Идёт запись (с точки зрения экрана: панель записи показана). */
    var isRecording: Boolean = false
        private set

    init {
        setup()
    }

    @SuppressLint("ClickableViewAccessibility")
    private fun setup() {
        if (recorder == null) return
        micButton.setOnTouchListener { v, event ->
            if (touchExploration()) return@setOnTouchListener false
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    downX = event.rawX
                    v.isPressed = true
                    begin()
                }
                MotionEvent.ACTION_MOVE -> if (isRecording && downX - event.rawX > cancelDistancePx) finish(send = false)
                MotionEvent.ACTION_UP -> {
                    v.isPressed = false
                    if (isRecording) finish(send = true) else if (event.eventTime - event.downTime < VoiceRecording.MIN_MS) {
                        Notice.show(activity, R.string.voice_record_tap_hint)
                    }
                }
                MotionEvent.ACTION_CANCEL -> {
                    v.isPressed = false
                    if (isRecording) finish(send = false)
                }
            }
            true
        }
        // TalkBack: двойное касание — начать, ещё раз — отправить.
        micButton.setOnClickListener {
            if (!touchExploration()) return@setOnClickListener
            if (isRecording) finish(send = true) else if (begin()) Notice.show(activity, R.string.voice_record_toggle_hint)
        }
    }

    private fun touchExploration(): Boolean =
        (activity.getSystemService(AccessibilityManager::class.java))?.isTouchExplorationEnabled == true

    private fun begin(): Boolean {
        val r = recorder ?: return false
        if (ContextCompat.checkSelfPermission(activity, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            askPermission.launch(Manifest.permission.RECORD_AUDIO)
            return false
        }
        // Панель — сразу, запись — в фоне (4.63): нажатие не подвисает.
        val mine = ++generation
        isRecording = true
        composeViews.forEach { it.visibility = View.GONE }
        recordingPanel.visibility = View.VISIBLE
        timeLabel.text = activity.getString(R.string.voice_record_time, 0, 0)
        r.startAsync(handler) { ok ->
            if (destroyed || mine != generation) return@startAsync
            if (!ok) {
                if (isRecording) {
                    isRecording = false
                    restoreCompose()
                }
                Notice.show(activity, R.string.voice_record_failed)
            } else if (isRecording) {
                handler.post(tick)
            }
        }
        return true
    }

    private fun restoreCompose() {
        handler.removeCallbacks(tick)
        recordingPanel.visibility = View.GONE
        composeViews.forEach { it.visibility = View.VISIBLE }
    }

    /** Закончить запись: отправить или отменить. */
    fun finish(send: Boolean) {
        val r = recorder ?: return
        if (!isRecording) return
        isRecording = false
        restoreCompose()
        if (!send) {
            r.cancelAsync()
            AppLog.i(LogTag.MESSAGES, "голосовое: отменено")
            Notice.show(activity, R.string.voice_record_cancelled)
            return
        }
        val mine = generation
        r.stopAsync(handler) { result ->
            if (destroyed || mine != generation) {
                result?.file?.delete()
                return@stopAsync
            }
            if (result == null) Notice.show(activity, R.string.voice_record_tap_hint)
            else onRecorded(result.file, result.durationMs)
        }
    }

    /** Экран уходит: запись не продолжается в фоне. */
    fun release() {
        generation++
        if (isRecording) finish(send = false)
    }

    /** Экран уничтожен (4.65): запись отменена, поток записи завершается (раньше оставался жить после каждого экрана). */
    fun destroy() {
        release()
        destroyed = true
        recorder?.shutdown()
    }
}
