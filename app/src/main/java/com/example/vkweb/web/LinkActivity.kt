package com.example.vkweb.web

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import com.example.vkweb.MainActivity
import com.example.vkweb.diag.AppLog
import com.example.vkweb.diag.LogFormat
import com.example.vkweb.diag.LogTag

/**
 * Ссылка VK из другого приложения (3.94): без окна передаёт путь главному экрану в задаче приложения и закрывается.
 * Главное окно открывает его обычным переходом ([MainActivity.EXTRA_OPEN_PAGE], с секретом процесса — как свои
 * экраны). Ссылка, которую [VkLinks] не принял, только пишется в журнал.
 */
class LinkActivity : Activity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val data = intent?.data
        val path = data?.let { VkLinks.sitePath(it.scheme, it.host, it.encodedPath, it.encodedQuery) }
        if (path == null) {
            // Не передаём дальше: ссылка снова могла бы прийти сюда же.
            AppLog.w(LogTag.APP, "ссылка из другого приложения отклонена: не страница сайта VK")
        } else {
            AppLog.i(LogTag.PAGE, "ссылка VK из другого приложения", "адрес" to LogFormat.url(path))
            startActivity(
                Intent(this, MainActivity::class.java)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
                    .putExtra(MainActivity.EXTRA_OPEN_PAGE, path)
                    .putExtra(MainActivity.EXTRA_INTERNAL_TOKEN, MainActivity.INTERNAL_TOKEN),
            )
        }
        finish()
    }
}
