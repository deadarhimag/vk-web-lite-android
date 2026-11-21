package com.example.vkweb.ui

import androidx.annotation.DrawableRes
import androidx.annotation.StringRes
import com.example.vkweb.R
import com.example.vkweb.config.HiddenSection

/** Названия и значки разделов VK для настроек и бокового меню. */
object SectionLabels {
    @StringRes
    fun of(section: HiddenSection): Int = when (section) {
        HiddenSection.FEED -> R.string.section_feed
        HiddenSection.STORIES -> R.string.section_stories
        HiddenSection.DISCOVER -> R.string.section_discover
        HiddenSection.FAVE -> R.string.section_fave
        HiddenSection.MARKET -> R.string.section_market
        HiddenSection.APPS -> R.string.section_apps
        HiddenSection.GAMES -> R.string.section_games
        HiddenSection.PAYMENTS -> R.string.section_payments
        HiddenSection.VK_MENU -> R.string.section_vk_menu
        HiddenSection.COMMUNITIES_CATALOG -> R.string.section_communities_catalog
        HiddenSection.AD_SETTINGS -> R.string.section_ad_settings
    }

    /** Значок пункта меню; у разделов без своего значка — общая сетка плиток. */
    @DrawableRes
    fun iconOf(section: HiddenSection): Int = when (section) {
        HiddenSection.FEED -> R.drawable.ic_section_feed
        HiddenSection.DISCOVER -> R.drawable.ic_section_discover
        HiddenSection.FAVE -> R.drawable.ic_section_fave
        HiddenSection.MARKET -> R.drawable.ic_section_market
        HiddenSection.APPS -> R.drawable.ic_section_apps
        HiddenSection.GAMES -> R.drawable.ic_section_games
        HiddenSection.VK_MENU -> R.drawable.ic_section_vk_menu
        else -> R.drawable.ic_section_extra
    }
}
