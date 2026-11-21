package com.example.vkweb.config

import androidx.annotation.DrawableRes
import androidx.annotation.StringRes
import com.example.vkweb.R

/**
 * Разделы бокового меню. Порядок здесь — порядок в меню.
 * Чтобы добавить раздел: строка здесь, название в strings.xml и значок в drawable.
 * [query] добавляется к адресу при открытии из меню, но не участвует в определении раздела.
 */
enum class Tab(@StringRes val titleRes: Int, @DrawableRes val iconRes: Int, val path: String, val query: String = "") {
    MESSAGES(R.string.tab_messages, R.drawable.ic_section_messages, "/mail"),
    FRIENDS(R.string.tab_friends, R.drawable.ic_section_friends, "/friends"),
    // «Мои подписки», а не главная каталога с рекомендациями: только здесь VK присылает весь список.
    GROUPS(R.string.tab_groups, R.drawable.ic_section_groups, "/groups", "?tab=my"),
    CLIPS(R.string.tab_clips, R.drawable.ic_section_clips, "/clips"),
    MUSIC(R.string.tab_music, R.drawable.ic_section_music, "/audio"),
    VIDEO(R.string.tab_video, R.drawable.ic_section_video, "/video");

    val url: String get() = AppConfig.SITE_ORIGIN + path + query

    fun matches(urlPath: String): Boolean = urlPath == path || urlPath.startsWith("$path/")

    companion object {
        val DEFAULT = MESSAGES

        fun fromPath(urlPath: String?): Tab? = urlPath?.let { p -> entries.firstOrNull { it.matches(p) } }

        fun fromName(name: String?): Tab = entries.firstOrNull { it.name == name } ?: DEFAULT
    }
}
