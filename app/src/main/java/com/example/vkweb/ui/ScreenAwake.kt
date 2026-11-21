package com.example.vkweb.ui

import android.app.Activity
import android.view.WindowManager
import androidx.media3.common.Player

/**
 * Экран не гаснет, пока видео или клип играет (4.88). Раньше ни один проигрыватель не просил об этом систему, и через
 * время из настроек Android («Время до отключения экрана») экран гас посреди ролика. На паузе, после конца ролика и при
 * ошибке экран снова гаснет как обычно — флаг окна снимается.
 */
object ScreenAwake {

    /**
     * Держать экран: ролик должен играть ([playWhenReady]) и он готов или подгружается. Подгрузка входит: на плохой сети
     * она длится десятки секунд, и экран не должен погаснуть, пока человек ждёт. Без зависимостей от Android: проверяется
     * обычными тестами.
     */
    fun shouldKeep(playWhenReady: Boolean, playbackState: Int): Boolean =
        playWhenReady && (playbackState == Player.STATE_READY || playbackState == Player.STATE_BUFFERING)

    fun set(activity: Activity, on: Boolean) {
        if (on) activity.window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        else activity.window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
    }

    /** Слушатель проигрывателя: флаг окна следует за состоянием. Снимать — вместе с проигрывателем ([release]). */
    class Follower(private val activity: Activity) : Player.Listener {
        override fun onEvents(player: Player, events: Player.Events) {
            if (events.containsAny(Player.EVENT_PLAY_WHEN_READY_CHANGED, Player.EVENT_PLAYBACK_STATE_CHANGED, Player.EVENT_PLAYER_ERROR)) {
                set(activity, player.playerError == null && shouldKeep(player.playWhenReady, player.playbackState))
            }
        }

        /** Проигрыватель отпущен: экран больше не держим. */
        fun release(player: Player) {
            player.removeListener(this)
            set(activity, false)
        }
    }
}
