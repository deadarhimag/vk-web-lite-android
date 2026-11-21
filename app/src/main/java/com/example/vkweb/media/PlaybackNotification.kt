package com.example.vkweb.media

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.graphics.drawable.Icon
import android.media.session.MediaSession
import androidx.annotation.DrawableRes
import androidx.annotation.StringRes
import com.example.vkweb.MainActivity
import com.example.vkweb.R

/** Сборка медиа-уведомления. Не хранит состояние: всё берётся из [MediaState]. */
class PlaybackNotification(
    private val context: Context,
    private val sessionToken: MediaSession.Token,
) {
    init {
        val channel = NotificationChannel(
            CHANNEL_ID, context.getString(R.string.playback_channel), NotificationManager.IMPORTANCE_LOW
        )
        context.getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
    }

    fun build(state: MediaState, cover: android.graphics.Bitmap? = null): Notification {
        val playPause = if (state.playing) {
            commandAction(android.R.drawable.ic_media_pause, R.string.action_pause, PlayerCommand.PAUSE)
        } else {
            commandAction(android.R.drawable.ic_media_play, R.string.action_play, PlayerCommand.PLAY)
        }
        val builder = Notification.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notification_music)
            .setContentTitle(state.title.ifBlank { context.getString(R.string.app_name) })
            .setContentText(state.artist.ifBlank { statusText(state) })
            .setContentIntent(openIntent(state.fromChat))
            .setDeleteIntent(MusicService.actionIntent(context, MusicService.ACTION_DISMISS))
            .setOngoing(state.playing)
            .setVisibility(Notification.VISIBILITY_PUBLIC)
            .setCategory(Notification.CATEGORY_TRANSPORT)
            .addAction(commandAction(android.R.drawable.ic_media_previous, R.string.action_previous, PlayerCommand.PREVIOUS))
            .addAction(playPause)
            .addAction(commandAction(android.R.drawable.ic_media_next, R.string.action_next, PlayerCommand.NEXT))
            .addAction(
                action(
                    android.R.drawable.ic_menu_close_clear_cancel, R.string.action_close,
                    MusicService.actionIntent(context, MusicService.ACTION_CLOSE_PLAYER)
                )
            )
            .setStyle(
                Notification.MediaStyle()
                    .setMediaSession(sessionToken)
                    .setShowActionsInCompactView(0, 1, 2)
            )
        // Обложка (4.0): крупная картинка в шторке; на экран блокировки и в медиаплеер Android она идёт из сессии.
        if (cover != null) builder.setLargeIcon(cover)
        return builder.build()
    }

    private fun statusText(state: MediaState) =
        context.getString(if (state.playing) R.string.status_playing else R.string.status_paused)

    private fun commandAction(@DrawableRes icon: Int, @StringRes label: Int, command: PlayerCommand) =
        action(icon, label, MusicService.commandIntent(context, command))

    private fun action(@DrawableRes icon: Int, @StringRes label: Int, intent: PendingIntent) =
        Notification.Action.Builder(Icon.createWithResource(context, icon), context.getString(label), intent).build()

    /**
     * Нажатие на уведомление (и на медиаплеер в шторке Android 13+): музыка или голосовое из переписки — экран
     * сообщений (4.0; раньше всегда главное окно), музыка страницы — главное окно.
     */
    fun openIntent(fromChat: Boolean): PendingIntent = PendingIntent.getActivity(
        context,
        if (fromChat) 1 else 0,
        Intent(context, if (fromChat) com.example.vkweb.messages.MessagesActivity::class.java else MainActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_REORDER_TO_FRONT),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
    )

    companion object {
        const val NOTIFICATION_ID = 1
        private const val CHANNEL_ID = "playback"
    }
}
