package com.example.vkweb.ui

import android.view.View
import androidx.core.view.isVisible
import com.example.vkweb.R
import com.example.vkweb.diag.AppLog
import com.example.vkweb.diag.LogTag

/**
 * Экран «страница не загрузилась» главного окна (3.94; с 4.6 — отдельно от MainActivity). Без сети — так и сказано,
 * страница откроется сама, и можно открыть сохранённую копию из кэша WebView; при сети — «Повторить».
 * С 4.51 — в общем оформлении пустых экранов ([EmptyState]): значок, заголовок, пояснение, кнопки.
 */
class LoadErrorScreen(
    private val root: View,
    /** «Повторить»: перезагрузить страницу. */
    private val onRetry: () -> Unit,
    /** «Сохранённая копия»: перезагрузить только из кэша. */
    private val onCachedCopy: () -> Unit,
) {
    /** Нажали «Сохранённая копия», а копии нет: кнопка больше не предлагается до следующей попытки. */
    private var triedCachedCopy = false

    private val state = EmptyState(root, root)

    private val retry = EmptyState.Action(R.string.retry) {
        reset()
        onRetry()
    }

    private val cachedCopy = EmptyState.Action(R.string.error_show_cached) {
        // Только кэш: без сети WebView не ждёт её, а сразу показывает сохранённое (или ошибку, если его нет).
        AppLog.i(LogTag.PAGE, "без сети: открыта сохранённая копия страницы")
        triedCachedCopy = true
        hide()
        onCachedCopy()
    }

    val isShown: Boolean get() = root.isVisible

    fun show(offline: Boolean) {
        state.show(
            EmptyState.Content(
                title = if (offline) R.string.error_offline_title else R.string.error_title,
                icon = if (offline) R.drawable.ic_cloud_off else R.drawable.ic_status_error,
                body = if (offline) R.string.error_offline_body else R.string.error_body,
                action = retry,
                secondary = if (offline && !triedCachedCopy) cachedCopy else null,
            ),
        )
        if (offline && triedCachedCopy) Notice.show(root.context, R.string.error_no_cached)
    }

    fun hide() {
        root.isVisible = false
    }

    /** Новая попытка (кнопка или вернувшаяся сеть): экран скрыт, сохранённая копия снова предлагается. */
    fun reset() {
        hide()
        triedCachedCopy = false
    }
}
