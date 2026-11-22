package com.example.vkweb.config

/**
 * Номера VK в адресах и данных страницы — в одном месте (код ревью 4.43: одно и то же правило было переписано в
 * восьми местах). Без зависимостей от Android: проверяется тестами.
 */
object VkIds {

    /** Номера бесед начинаются отсюда (peer_id = 2e9 + номер беседы). Так же — CHAT_PEER_MIN в vk_reader.js. */
    const val CHAT_PEER_MIN = 2_000_000_000L

    /** Номер переписки: человек, сообщество (отрицательный) или беседа. */
    fun isPeer(value: String?): Boolean = value != null && PEER.matches(value)

    /** Номер ролика, клипа, трека, фото: «владелец_номер» («-1_2»). */
    fun isMediaId(value: String?): Boolean = value != null && MEDIA.matches(value)

    /** Номер, к которому пишут комментарии: ролик или клип («-1_2»), запись стены — с буквой «w» («w-1_2»). */
    fun isCommentTarget(value: String?): Boolean = value != null && COMMENT_TARGET.matches(value)

    fun isChat(peer: Long): Boolean = peer >= CHAT_PEER_MIN

    fun isChat(peer: String?): Boolean = peer?.toLongOrNull()?.let(::isChat) == true

    /** Человек (не сообщество и не беседа). */
    fun isUser(peer: Long): Boolean = peer in 1 until CHAT_PEER_MIN

    private val PEER = Regex("^-?\\d{1,12}$")
    private val MEDIA = Regex("^-?\\d{1,12}_\\d{1,12}$")
    private val COMMENT_TARGET = Regex("^w?-?\\d{1,12}_\\d{1,12}$")
}
