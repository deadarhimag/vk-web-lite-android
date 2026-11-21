package com.example.vkweb.web

import android.content.ActivityNotFoundException
import com.example.vkweb.ui.Notice
import android.content.Context
import android.content.Intent
import android.net.Uri
import com.example.vkweb.R
import java.net.URISyntaxException

/** Открытие ссылок в других приложениях, включая intent://-ссылки. */
class ExternalLinks(private val context: Context) {

    fun open(uri: Uri) {
        // Сюда приходят и адреса из данных страницы (свои экраны): только разрешённые схемы — file:// и прочее
        // уронило бы приложение или открыло бы не то (код ревью 3.59).
        if (uri.scheme?.lowercase() !in com.example.vkweb.config.AppConfig.EXTERNAL_SCHEMES) {
            Notice.show(context, R.string.no_app_for_link)
            return
        }
        val intent = if (uri.scheme == "intent") parseIntentUri(uri) else viewIntent(uri)
        if (intent == null || !tryStart(intent)) {
            val fallback = intent?.getStringExtra(EXTRA_FALLBACK_URL)?.let(Uri::parse)
            if (fallback == null || fallback.scheme != "https" || !tryStart(viewIntent(fallback))) {
                Notice.show(context, R.string.no_app_for_link)
            }
        }
    }

    private fun viewIntent(uri: Uri) =
        Intent(Intent.ACTION_VIEW, uri).addCategory(Intent.CATEGORY_BROWSABLE)

    /**
     * Сайт может передать в intent:// что угодно, поэтому обнуляем явный компонент,
     * селектор и флаги: так ссылка не откроет внутренний экран чужого или нашего приложения.
     */
    private fun parseIntentUri(uri: Uri): Intent? = try {
        Intent.parseUri(uri.toString(), Intent.URI_INTENT_SCHEME).apply {
            component = null
            selector = null
            flags = 0
            addCategory(Intent.CATEGORY_BROWSABLE)
        }
    } catch (e: URISyntaxException) {
        null
    }

    private fun tryStart(intent: Intent): Boolean = try {
        context.startActivity(intent)
        true
    } catch (e: ActivityNotFoundException) {
        false
    } catch (e: SecurityException) {
        false
    } catch (e: RuntimeException) {
        // FileUriExposedException и подобное от чужих адресов.
        false
    }

    private companion object {
        const val EXTRA_FALLBACK_URL = "browser_fallback_url"
    }
}
