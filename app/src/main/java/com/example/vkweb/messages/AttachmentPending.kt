package com.example.vkweb.messages

/**
 * Сообщение с файлами, пока оно уходит: пузырь сразу в переписке («отправляется», текст и названия файлов), а не тишина
 * до перезахода. Настоящее сообщение пришло — новое своё подтверждённое, которого не было при отправке, — пузырь
 * убирается, и сообщение показывается как обычно. Без зависимостей от Android: проверяется обычными тестами.
 */
object AttachmentPending {

    const val ID_PREFIX = "a:"

    fun message(text: String, fileNames: List<String>, now: Long): ChatMessage = ChatMessage(
        id = ID_PREFIX + now,
        out = true,
        text = (listOf(text.trim()) + fileNames.map { "📎 $it" }).filter { it.isNotBlank() }.joinToString("\n"),
        time = "",
        dateSec = now / 1000,
        status = MessageStatus.SENDING,
    )

    /**
     * Наибольший номер сообщения в беседе на момент отправки. Новое — только своё подтверждённое с номером больше: по
     * одному «не было в списке» пузырь пропадал от догрузки старых сообщений во время отправки (код ревью 3.59).
     */
    fun maxCmid(server: List<ChatMessage>): Long = server.maxOfOrNull { cmidOf(it) } ?: 0L

    fun arrived(server: List<ChatMessage>, maxBefore: Long): Boolean = server.any { it.out && cmidOf(it) > maxBefore }

    private fun cmidOf(m: ChatMessage): Long = if (m.isConfirmed) m.id.drop(1).toLongOrNull() ?: 0L else 0L

    fun isPendingId(id: String) = id.startsWith(ID_PREFIX)
}
