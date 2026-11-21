package com.example.vkweb.messages

import android.os.Handler
import java.io.File

/**
 * Запись голосового — то, что нужно экрану ([VoiceComposer]) (4.69). Сам записывающий класс ([VoiceRecorder]) — только с
 * Android 10 (формат голосовых VK, ogg/opus), а экран есть на всех версиях: через этот интерфейс экран не обращается к
 * классу Android 10+ напрямую (Lint NewApi не видел, что он создаётся только там).
 */
interface VoiceRecording {

    class Result(val file: File, val durationMs: Long)

    val isRecording: Boolean

    /** Сколько уже записано. */
    val elapsedMs: Long

    /** Начать в своём потоке; [done] — в главном: false — запись не началась (микрофон занят, нет разрешения). */
    fun startAsync(main: Handler, done: (Boolean) -> Unit)

    /** Закончить; [done] — в главном: null — слишком коротко или не записалось. */
    fun stopAsync(main: Handler, done: (Result?) -> Unit)

    /** Отменить: запись не сохраняется. */
    fun cancelAsync()

    /** Экран закрыт: запись отменяется, поток записи завершается. */
    fun shutdown()

    companion object {
        const val MIN_MS = 700L

        /** Дольше — запись заканчивается сама (голосовые VK — до нескольких минут, файл — в пределах вложений). */
        const val MAX_MS = 5 * 60 * 1000L
    }
}
