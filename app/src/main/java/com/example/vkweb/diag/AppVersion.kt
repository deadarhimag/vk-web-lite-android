package com.example.vkweb.diag

import android.content.Context
import android.os.Build

/** Версия приложения для журнала и собранных данных (4.11): «4.11», номер сборки. */
object AppVersion {

    data class Info(val name: String, val build: Long)

    fun of(context: Context): Info = try {
        val info = context.packageManager.getPackageInfo(context.packageName, 0)
        val build = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) info.longVersionCode
        else @Suppress("DEPRECATION") info.versionCode.toLong()
        Info(info.versionName ?: "?", build)
    } catch (e: Exception) {
        Info("?", 0)
    }
}
