package com.example.vkweb.web

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import com.example.vkweb.MainActivity
import com.example.vkweb.config.Tab
import com.example.vkweb.diag.AppLog
import com.example.vkweb.diag.LogTag

/**
 * Ярлык раздела на значке приложения (3.94; с 4.57 публикуется из кода — ui/AppShortcuts.kt): без окна передаёт раздел главному экрану — с секретом
 * процесса, как свои экраны ([MainActivity.EXTRA_OPEN_TAB]), — и закрывается. Не экспортирована: чужие приложения
 * её не запустят. Неизвестный раздел — просто открыть приложение.
 */
class ShortcutActivity : Activity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val tab = intent?.getStringExtra(EXTRA_TAB)?.let { name -> Tab.entries.firstOrNull { it.name == name } }
        AppLog.i(LogTag.APP, "ярлык на значке", "раздел" to tab?.name)
        // «Сообщения» своим экраном — сразу он, как при запуске приложения с этим разделом (страница VK под ним не нужна).
        if (tab == Tab.MESSAGES && com.example.vkweb.settings.AppSettings(this).nativeMessages) {
            startActivity(
                Intent(this, com.example.vkweb.messages.MessagesActivity::class.java)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP),
            )
            finish()
            return
        }
        val open = Intent(this, MainActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
        if (tab != null) {
            open.putExtra(MainActivity.EXTRA_OPEN_TAB, tab.name)
                .putExtra(MainActivity.EXTRA_INTERNAL_TOKEN, MainActivity.INTERNAL_TOKEN)
        }
        startActivity(open)
        finish()
    }

    companion object {
        /** Раздел (имя [Tab]); ярлыки строит ui/AppShortcuts.kt (4.57). */
        const val EXTRA_TAB = "tab"
    }
}
