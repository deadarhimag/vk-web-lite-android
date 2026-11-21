package com.example.vkweb.media

import androidx.annotation.MainThread
import java.lang.ref.WeakReference

/**
 * Связь «уведомление / гарнитура → страница». Сервис не знает об Activity,
 * Activity регистрируется сама. Слабая ссылка исключает утечку Activity.
 */
@MainThread
object PlaybackHub {

    interface Target {
        fun onPlayerCommand(command: PlayerCommand)
        /** Перемотка из уведомления или с экрана блокировки, секунды. */
        fun onSeekRequested(seconds: Double) = Unit
    }

    private var target: WeakReference<Target>? = null

    /**
     * Временный получатель команд — музыка из переписки ([ChatMusic]), которую играет само приложение: пока она звучит,
     * кнопки уведомления, гарнитуры и экрана блокировки управляют ею, а не страницей.
     */
    private var override: Target? = null

    /** Забрать управление: звучащее на странице ставится на паузу (два звука сразу не нужны). */
    fun takeOver(owner: Target) {
        if (override == null) target?.get()?.onPlayerCommand(PlayerCommand.PAUSE)
        override = owner
    }

    fun release(owner: Target) {
        if (override === owner) override = null
    }

    fun attach(target: Target) {
        this.target = WeakReference(target)
    }

    fun detach(target: Target) {
        if (this.target?.get() === target) this.target = null
    }

    /**
     * Своё видео или клип начинает звучать (4.0): на паузу и музыку из переписки, и музыку страницы — иначе при
     * «Фоновом воспроизведении» страница играла поверх клипа.
     */
    fun pauseAll() {
        override?.onPlayerCommand(PlayerCommand.PAUSE)
        target?.get()?.onPlayerCommand(PlayerCommand.PAUSE)
    }

    fun send(command: PlayerCommand) {
        (override ?: target?.get())?.onPlayerCommand(command)
    }

    fun seek(seconds: Double) {
        (override ?: target?.get())?.onSeekRequested(seconds)
    }
}
