package com.example.vkweb.messages

import com.example.vkweb.media.ChatQueue

/**
 * Очередь голосовых переписки (3.71; с 4.4 вынесено из MessagesActivity): все голосовые по порядку — и в сообщениях,
 * и в пересланных, — чтобы после нажатого сами играли следующие. Без Android: проверяется [ChatVoiceQueueTest].
 */
object ChatVoiceQueue {

    /** Начало номера трека у голосовых: у них нет свежего адреса по номеру, как у музыки. */
    const val ID_PREFIX = "voice:"

    /** Подписи (из ресурсов): «Вы», «Голосовое сообщение», название переписки — автор личных сообщений. */
    data class Labels(val fromMe: String, val trackTitle: String, val chatTitle: String)

    data class Queue(val tracks: List<ChatQueue.Track>, val start: Int)

    fun isVoice(trackId: String): Boolean = trackId.startsWith(ID_PREFIX)

    /** Очередь с нажатым [voice]; его нет в [history] (только что пришло и не слито) — одно голосовое. */
    fun build(history: List<ChatMessage>, voice: ChatVoice, labels: Labels): Queue {
        val tracks = ArrayList<ChatQueue.Track>()
        var start = -1
        history.forEach { m ->
            val author = when {
                m.out -> labels.fromMe
                m.sender.isNotEmpty() -> m.sender
                else -> labels.chatTitle
            }
            val entries = m.voices.map { it to author } + m.forwards.flatMap { f -> f.voices.map { it to f.sender.ifEmpty { author } } }
            entries.forEach { (v, who) ->
                if (tracks.any { it.url == v.url }) return@forEach
                if (v.url == voice.url) start = tracks.size
                tracks += ChatQueue.Track(
                    id = ID_PREFIX + v.id.ifEmpty { v.url.hashCode().toString() },
                    title = labels.trackTitle,
                    artist = listOf(who, m.time).filter { it.isNotBlank() }.joinToString(" · "),
                    url = v.url,
                    durationSec = v.durationSec,
                    voice = true,
                )
            }
        }
        if (start < 0) {
            return Queue(
                listOf(ChatQueue.Track(ID_PREFIX + voice.id, labels.trackTitle, labels.chatTitle, voice.url, durationSec = voice.durationSec, voice = true)),
                0,
            )
        }
        return Queue(tracks, start)
    }
}
