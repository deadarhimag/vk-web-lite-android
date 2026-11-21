package com.example.vkweb.messages

import android.content.Context
import android.media.MediaRecorder
import android.os.Build
import android.os.SystemClock
import androidx.annotation.RequiresApi
import com.example.vkweb.diag.AppLog
import com.example.vkweb.diag.LogTag
import java.io.File

/**
 * Запись голосового (4.59): ogg/opus, моно — формат голосовых VK (docs.getMessagesUploadServer type=audio_message).
 * Такой формат MediaRecorder пишет с Android 10, поэтому и кнопка микрофона — с Android 10.
 */
@RequiresApi(Build.VERSION_CODES.Q)
class VoiceRecorder(private val context: Context) : VoiceRecording {

    @Volatile private var recorder: MediaRecorder? = null
    @Volatile private var file: File? = null
    @Volatile private var startedAt = 0L

    /**
     * MediaRecorder сам остановился на пределе длины (4.65). После этого его stop() бросает исключение — раньше запись
     * в пять минут считалась неудачной и удалялась. Теперь такой файл готов и отправляется.
     */
    @Volatile private var reachedMax = false

    /**
     * Запуск и остановка MediaRecorder — сотни миллисекунд (отчёт 4.62: start 258 мс, stop 109 мс в главном потоке),
     * поэтому — в своём потоке, по очереди: «отпустили» сразу после «нажали» дождётся начала записи (4.63).
     */
    private val io = java.util.concurrent.Executors.newSingleThreadExecutor { r -> Thread(r, "voice-recorder") }

    override fun startAsync(main: android.os.Handler, done: (Boolean) -> Unit) {
        if (!submit { val ok = start(); main.post { done(ok) } }) main.post { done(false) }
    }

    override fun stopAsync(main: android.os.Handler, done: (VoiceRecording.Result?) -> Unit) {
        if (!submit { val r = stop(); main.post { done(r) } }) main.post { done(null) }
    }

    override fun cancelAsync() {
        submit { cancel() }
    }

    /** Экран закрыт (4.65): идущая запись отменяется, поток записи завершается после неё. Повторный вызов безвреден. */
    override fun shutdown() {
        cancelAsync()
        io.shutdown()
    }

    /** В поток записи; после [shutdown] — ничего (false), а не RejectedExecutionException. */
    private fun submit(task: () -> Unit): Boolean = try {
        io.execute(task)
        true
    } catch (e: java.util.concurrent.RejectedExecutionException) {
        false
    }

    override val isRecording: Boolean get() = recorder != null

    /** Сколько уже записано. */
    override val elapsedMs: Long get() = if (recorder == null || startedAt == 0L) 0 else SystemClock.elapsedRealtime() - startedAt

    /** @return false — запись не началась (микрофон занят, нет разрешения). */
    fun start(): Boolean {
        if (recorder != null) return true
        val out = File(context.cacheDir, "voice").apply { mkdirs() }.let { File(it, "voice-${System.currentTimeMillis()}.ogg") }
        @Suppress("DEPRECATION")
        val r = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) MediaRecorder(context) else MediaRecorder()
        return try {
            r.setAudioSource(MediaRecorder.AudioSource.MIC)
            r.setOutputFormat(MediaRecorder.OutputFormat.OGG)
            r.setAudioEncoder(MediaRecorder.AudioEncoder.OPUS)
            r.setAudioChannels(1)
            r.setAudioSamplingRate(SAMPLE_RATE)
            r.setAudioEncodingBitRate(BIT_RATE)
            // Предел — с запасом: экран заканчивает запись сам на MAX_MS (VoiceComposer), а если MediaRecorder всё же
            // успел первым — запись не теряется ([reachedMax]).
            r.setMaxDuration((MAX_MS + RECORDER_LIMIT_MARGIN_MS).toInt())
            reachedMax = false
            r.setOnInfoListener { _, what, _ ->
                if (what == MediaRecorder.MEDIA_RECORDER_INFO_MAX_DURATION_REACHED) {
                    reachedMax = true
                    AppLog.i(LogTag.MESSAGES, "голосовое: достигнут предел длины")
                }
            }
            r.setOutputFile(out.absolutePath)
            r.prepare()
            r.start()
            recorder = r
            file = out
            startedAt = SystemClock.elapsedRealtime()
            AppLog.i(LogTag.MESSAGES, "голосовое: запись началась")
            true
        } catch (e: Exception) {
            AppLog.w(LogTag.MESSAGES, "голосовое: запись не началась", "ошибка" to e.javaClass.simpleName)
            r.release()
            out.delete()
            false
        }
    }

    /** Закончить: null — слишком коротко или не записалось (файл удалён). */
    fun stop(): VoiceRecording.Result? {
        val r = recorder ?: return null
        val out = file
        val duration = elapsedMs
        recorder = null
        startedAt = 0L
        file = null
        val ok = try {
            r.stop()
            true
        } catch (e: RuntimeException) {
            // Остановили сразу после начала — данных нет; или MediaRecorder уже остановился сам на пределе — файл готов.
            reachedMax
        } finally {
            r.release()
        }
        if (!ok || out == null || duration < MIN_MS || !out.isFile || out.length() == 0L) {
            out?.delete()
            return null
        }
        AppLog.i(LogTag.MESSAGES, "голосовое: записано", "мс" to duration, "байт" to out.length())
        return VoiceRecording.Result(out, duration)
    }

    /** Отменить: запись не сохраняется. */
    fun cancel() {
        stop()?.file?.delete()
    }

    companion object {
        /** Пределы — общие с экраном (4.69). */
        const val MIN_MS = VoiceRecording.MIN_MS
        const val MAX_MS = VoiceRecording.MAX_MS
        /** Запас предела MediaRecorder над [MAX_MS]: его отсчёт начинается раньше, чем [elapsedMs] (4.65). */
        private const val RECORDER_LIMIT_MARGIN_MS = 3_000L
        private const val SAMPLE_RATE = 48_000
        private const val BIT_RATE = 32_000
    }
}
