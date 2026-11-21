package com.example.vkweb.web

import android.app.DownloadManager
import com.example.vkweb.ui.Notice
import android.content.Context
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.webkit.CookieManager
import android.webkit.DownloadListener
import android.webkit.URLUtil
import com.example.vkweb.R

/** Скачивание документов из чатов через системный DownloadManager. */
class DownloadStarter(
    private val context: Context,
    private val policy: UrlPolicy,
) : DownloadListener {

    override fun onDownloadStart(
        url: String,
        userAgent: String,
        contentDisposition: String?,
        mimetype: String?,
        contentLength: Long,
    ) {
        val uri = Uri.parse(url)
        // blob: и data: DownloadManager не поддерживает.
        if (uri.scheme != "https") {
            toast(context.getString(R.string.download_unsupported))
            return
        }
        val fileName = URLUtil.guessFileName(url, contentDisposition, mimetype)
        val request = DownloadManager.Request(uri).apply {
            setMimeType(mimetype)
            setTitle(fileName)
            addRequestHeader("User-Agent", userAgent)
            // Cookie сессии отдаём только доменам VK.
            if (policy.isAppHost(uri.host)) {
                CookieManager.getInstance().getCookie(url)?.let { addRequestHeader("Cookie", it) }
            }
            setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                setDestinationInExternalPublicDir(Environment.DIRECTORY_DOWNLOADS, fileName)
            } else {
                // До Android 10 публичная папка требует разрешения на запись.
                setDestinationInExternalFilesDir(context, Environment.DIRECTORY_DOWNLOADS, fileName)
            }
        }
        try {
            context.getSystemService(DownloadManager::class.java).enqueue(request)
            toast(context.getString(R.string.download_started, fileName))
        } catch (e: RuntimeException) {
            toast(context.getString(R.string.download_failed))
        }
    }

    private fun toast(text: String) = Notice.show(context, text)
}
