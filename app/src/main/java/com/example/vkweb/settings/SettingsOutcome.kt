package com.example.vkweb.settings

import android.content.Context
import android.content.Intent
import android.os.Bundle
import androidx.activity.result.contract.ActivityResultContract

/**
 * Действия, которые экран настроек просит выполнить главный экран (у него есть WebView и свои экраны).
 * [clearCache] — очистить кэш страниц WebView; [storageCleared] — сохранённое удалено, свои экраны должны
 * забыть показанное (иначе следующая запись вернула бы удалённое); [signedOut] — вышли из аккаунта.
 */
data class SettingsOutcome(
    val clearCache: Boolean = false,
    val storageCleared: Boolean = false,
    val signedOut: Boolean = false,
) {

    fun toIntent(): Intent = Intent().putExtras(toBundle())

    fun toBundle(): Bundle = Bundle().apply {
        putBoolean(KEY_CLEAR_CACHE, clearCache)
        putBoolean(KEY_STORAGE_CLEARED, storageCleared)
        putBoolean(KEY_SIGNED_OUT, signedOut)
    }

    /** Открыть настройки и получить результат. */
    class Contract : ActivityResultContract<Unit, SettingsOutcome>() {
        override fun createIntent(context: Context, input: Unit): Intent =
            Intent(context, SettingsActivity::class.java)

        override fun parseResult(resultCode: Int, intent: Intent?): SettingsOutcome = fromBundle(intent?.extras)
    }

    companion object {
        private const val KEY_CLEAR_CACHE = "settings.clear_cache"
        private const val KEY_STORAGE_CLEARED = "settings.storage_cleared"
        private const val KEY_SIGNED_OUT = "settings.signed_out"

        fun fromBundle(bundle: Bundle?): SettingsOutcome = if (bundle == null) SettingsOutcome() else SettingsOutcome(
            clearCache = bundle.getBoolean(KEY_CLEAR_CACHE),
            storageCleared = bundle.getBoolean(KEY_STORAGE_CLEARED),
            signedOut = bundle.getBoolean(KEY_SIGNED_OUT),
        )
    }
}
