package com.example.vkweb.ui

import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import androidx.core.content.pm.ShortcutInfoCompat
import androidx.core.content.pm.ShortcutManagerCompat
import androidx.core.graphics.drawable.IconCompat
import com.example.vkweb.Background
import com.example.vkweb.R
import com.example.vkweb.config.Tab
import com.example.vkweb.diag.AppLog
import com.example.vkweb.diag.LogTag
import com.example.vkweb.messages.AvatarBadge
import com.example.vkweb.messages.MessagesActivity
import com.example.vkweb.messages.RecentChats
import com.example.vkweb.offline.ImageCache
import com.example.vkweb.settings.AppSettings
import com.example.vkweb.web.ShortcutActivity

/**
 * Ярлыки на значке приложения (4.57; долгое нажатие на значок). Сначала недавние переписки ([RecentChats], с аватаром),
 * потом разделы — всего столько, сколько показывает лаунчер (около четырёх). До 4.57 ярлыки разделов были постоянными
 * (res/xml/shortcuts.xml) и занимали все места — теперь все ярлыки публикуются из кода.
 *
 * Имена собеседников на значке — только при включённой настройке «Недавние чаты на значке»; при выходе из аккаунта
 * список стирается.
 */
object AppShortcuts {

    private const val PREFS = "app_shortcuts"
    private const val KEY_RECENT = "recent"
    private const val ICON_DP = 48
    /** Больше лаунчеры всё равно не показывают. */
    private const val SLOTS = 4

    /** Разделы по важности: лаунчер покажет первые, сколько поместится после переписок. */
    private val SECTIONS = listOf(
        Tab.MESSAGES to R.drawable.ic_shortcut_messages,
        Tab.MUSIC to R.drawable.ic_shortcut_music,
        Tab.FRIENDS to R.drawable.ic_shortcut_friends,
        Tab.VIDEO to R.drawable.ic_shortcut_video,
    )

    private val io = Background.serial("shortcuts")

    private var published = false

    /** Раз за запуск процесса: ярлыки — по нынешней версии и настройкам (до 4.57 были постоянные из XML). */
    fun refreshOnce(context: Context) {
        if (published) return
        published = true
        refresh(context)
    }

    /** Опубликовать заново (запуск приложения, смена настройки). */
    fun refresh(context: Context) {
        val app = context.applicationContext
        io.execute { publish(app) }
    }

    /** Открыли переписку: она — первой среди недавних на значке. */
    fun chatOpened(context: Context, peerId: String, title: String, avatar: String) {
        val app = context.applicationContext
        if (!AppSettings(app).recentChatShortcuts || title.isBlank()) return
        io.execute {
            val prefs = app.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            val before = RecentChats.fromJson(prefs.getString(KEY_RECENT, null))
            val after = RecentChats.opened(before, RecentChats.Chat(peerId, title, avatar))
            if (after == before) return@execute
            prefs.edit().putString(KEY_RECENT, RecentChats.toJson(after)).apply()
            publish(app)
        }
    }

    /** Выход из аккаунта или выключили настройку: переписки с значка уходят, разделы остаются. */
    fun clearRecent(context: Context) {
        val app = context.applicationContext
        io.execute {
            app.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().remove(KEY_RECENT).apply()
            publish(app)
        }
    }

    private fun publish(context: Context) {
        try {
            val recent = if (AppSettings(context).recentChatShortcuts) {
                RecentChats.fromJson(context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KEY_RECENT, null))
            } else {
                emptyList()
            }
            val slots = minOf(SLOTS, ShortcutManagerCompat.getMaxShortcutCountPerActivity(context).coerceAtLeast(1))
            val (chats, sections) = RecentChats.plan(recent, SECTIONS, slots)
            var rank = 0
            val shortcuts = chats.map { chat(context, it, rank++) } + sections.map { (tab, icon) -> section(context, tab, icon, rank++) }
            ShortcutManagerCompat.setDynamicShortcuts(context, shortcuts)
            AppLog.d(LogTag.APP, "ярлыки на значке", "переписок" to chats.size, "разделов" to sections.size)
        } catch (e: Exception) {
            // Лаунчер без ярлыков или ограничение частоты: без ярлыков приложение работает как обычно.
            AppLog.w(LogTag.APP, "ярлыки на значке не обновлены", "ошибка" to e.javaClass.simpleName)
        }
    }

    private fun chat(context: Context, chat: RecentChats.Chat, rank: Int): ShortcutInfoCompat {
        val intent = Intent(context, MessagesActivity::class.java)
            .setAction(Intent.ACTION_VIEW)
            .putExtra(MessagesActivity.EXTRA_PEER_ID, chat.peerId)
            .putExtra(MessagesActivity.EXTRA_PEER_TITLE, chat.title)
            .putExtra(MessagesActivity.EXTRA_PEER_AVATAR, chat.avatar)
        return ShortcutInfoCompat.Builder(context, "chat:${chat.peerId}")
            .setShortLabel(chat.title.take(25))
            .setLongLabel(chat.title.take(40))
            .setIcon(IconCompat.createWithBitmap(avatar(context, chat)))
            .setIntent(intent)
            .setRank(rank)
            .build()
    }

    private fun section(context: Context, tab: Tab, icon: Int, rank: Int): ShortcutInfoCompat =
        ShortcutInfoCompat.Builder(context, "tab_${tab.name.lowercase()}")
            .setShortLabel(context.getString(tab.titleRes))
            .setIcon(IconCompat.createWithResource(context, icon))
            .setIntent(Intent(context, ShortcutActivity::class.java).setAction(Intent.ACTION_VIEW).putExtra(ShortcutActivity.EXTRA_TAB, tab.name))
            .setRank(rank)
            .build()

    /** Аватар (только уже скачанный — без сети) или кружок с инициалами, круглый. */
    private fun avatar(context: Context, chat: RecentChats.Chat): Bitmap {
        val px = (ICON_DP * context.resources.displayMetrics.density).toInt()
        val photo = chat.avatar.takeIf { it.isNotEmpty() }?.let { ImageCache.get(context).cachedOnly(it, px) }
        val out = Bitmap.createBitmap(px, px, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(out)
        if (photo != null) {
            val shader = android.graphics.BitmapShader(
                Bitmap.createScaledBitmap(photo, px, px, true), android.graphics.Shader.TileMode.CLAMP, android.graphics.Shader.TileMode.CLAMP,
            )
            canvas.drawCircle(px / 2f, px / 2f, px / 2f, android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply { this.shader = shader })
        } else {
            AvatarBadge(chat.title).apply { setBounds(0, 0, px, px) }.draw(canvas)
        }
        return out
    }
}
