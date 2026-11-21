package com.example.vkweb.web

/** Only the two origins used by lifecycle tests. Production URL policy has its own tests. */
object UrlPolicy {
    fun isTrustedUrl(url: String?): Boolean = url?.startsWith("https://m.vk.ru/") == true
    fun hostOf(url: String?): String? = url?.let { java.net.URI(it).host }
}
