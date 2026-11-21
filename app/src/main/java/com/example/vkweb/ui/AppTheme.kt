package com.example.vkweb.ui

import android.app.Activity
import android.content.res.Configuration
import com.example.vkweb.R
import com.example.vkweb.settings.AppSettings

/** Выбор темы экрана. Вызывать до super.onCreate. */
object AppTheme {

    /** @return true, если применена тёмная тема. */
    fun apply(activity: Activity, settings: AppSettings): Boolean {
        val dark = isDark(activity.resources.configuration, settings)
        activity.setTheme(if (dark) R.style.Theme_VkWeb_Dark else R.style.Theme_VkWeb_Light)
        // «Цвета обоев» (Material You, Android 12+): палитра темы строится из обоев, как у системных приложений.
        if (settings.dynamicColors) com.google.android.material.color.DynamicColors.applyToActivityIfAvailable(activity)
        // Чёрная (4.0) — после цветов обоев: фон и поверхности остаются чёрными и с ними.
        if (dark && isBlack(settings)) activity.theme.applyStyle(R.style.ThemeOverlay_VkWeb_Black, true)
        // 4.54: заставка следующего запуска — в теме из настроек (Android 13+ её запоминает), без вспышки тёмного.
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.TIRAMISU) {
            try {
                activity.splashScreen.setSplashScreenTheme(if (dark) R.style.Theme_VkWeb_Dark else R.style.Theme_VkWeb_Light)
            } catch (e: Exception) {
                // Не у всех оболочек Android есть заставка: тогда просто без неё.
            }
        }
        return dark
    }

    fun isBlack(settings: AppSettings): Boolean = settings.themeMode.isBlack

    /**
     * Тема, с которой создан экран (4.66, код ревью 4.64): тёмная ли, чёрная ли, цвета обоев. Раньше главное окно и
     * экран сообщений держали по три своих поля и одинаково их сверяли.
     */
    data class Applied(val dark: Boolean = true, val black: Boolean = false, val dynamic: Boolean = false) {
        /** Выбранная сейчас тема ([settings], тема системы из [configuration]) — уже не та, что у экрана. */
        fun changed(configuration: Configuration, settings: AppSettings): Boolean =
            isDark(configuration, settings) != dark || isBlack(settings) != black || settings.dynamicColors != dynamic
    }

    /** [apply] и запомнить, какая тема применена. */
    fun applyAndRemember(activity: Activity, settings: AppSettings): Applied =
        Applied(dark = apply(activity, settings), black = isBlack(settings), dynamic = settings.dynamicColors)

    fun isDark(configuration: Configuration, settings: AppSettings): Boolean =
        settings.themeMode.isDark(
            (configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES
        )
}
