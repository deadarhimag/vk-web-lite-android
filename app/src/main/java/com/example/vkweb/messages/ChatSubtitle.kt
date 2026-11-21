package com.example.vkweb.messages

import android.content.Context
import android.os.Handler
import com.example.vkweb.R

/**
 * Подпись под именем в открытой переписке (4.67; до этого — внутри MessagesActivity): в сети ли собеседник и когда был,
 * а пока кто-то печатает (4.60) — «печатает…» на [TypingText.SHOW_MS]; потом возвращается присутствие.
 */
class ChatSubtitle(
    private val context: Context,
    private val handler: Handler,
    /** Показать подпись (null — убрать). */
    private val show: (CharSequence?) -> Unit,
    /** Переписка ещё на экране: когда «печатает» истекло, подпись меняется только тогда. */
    private val chatShown: () -> Boolean,
) {
    /** В сети, когда был; null — не показывать. */
    private var presence: CharSequence? = null

    /** Сейчас показано «печатает…»; null — нет. */
    private var typing: String? = null

    private val typingTimeout = Runnable {
        typing = null
        if (chatShown()) show(presence)
    }

    /** Присутствие собеседника; пока печатают — видно «печатает…», присутствие вернётся после. */
    fun setPresence(text: CharSequence?) {
        presence = text
        show(typing ?: presence)
    }

    /** Кто-то печатает в открытой переписке ([isChat] — беседа, [names] — кто, [voice] — записывает голосовое). */
    fun onTyping(isChat: Boolean, names: List<String>, voice: Boolean) {
        val label = TypingText.of(isChat, names, voice)
        val text = when (label.kind) {
            TypingText.Kind.PRIVATE -> context.getString(if (label.voice) R.string.typing_voice else R.string.typing)
            TypingText.Kind.ONE -> context.getString(if (label.voice) R.string.typing_voice_one else R.string.typing_one, label.names[0])
            TypingText.Kind.TWO -> context.getString(R.string.typing_two, label.names[0], label.names[1])
            TypingText.Kind.SOMEONE -> context.getString(if (label.voice) R.string.typing_voice_someone else R.string.typing_someone)
        }
        typing = text
        show(text)
        handler.removeCallbacks(typingTimeout)
        handler.postDelayed(typingTimeout, TypingText.SHOW_MS)
    }

    /** Переписка сменилась или закрыта: подпись убрана, «печатает» прежней не показываем. */
    fun clear() {
        handler.removeCallbacks(typingTimeout)
        typing = null
        presence = null
        show(null)
    }
}
