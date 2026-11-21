package com.example.vkweb.ui

import android.view.View
import android.widget.TextView
import androidx.annotation.StringRes
import com.example.vkweb.R
import com.google.android.material.progressindicator.CircularProgressIndicator

/**
 * Общее для своих экранов поверх страницы (разметка layout/panel_chrome.xml внутри панели):
 * видимость, индикатор загрузки, пустой экран по центру ([EmptyState], 4.51) и плашка внизу.
 * Панель (музыка, друзья, страница человека) показывает данные, рамка — всё вокруг них.
 */
class PanelFrame(private val root: View) {
    private val progress: CircularProgressIndicator = root.findViewById(R.id.panel_progress)
    private val skeleton: SkeletonView? = root.findViewById(R.id.panel_skeleton)

    private fun stopLoading() {
        progress.hide()
        skeleton?.hide()
    }
    private val statusBox: View = root.findViewById(R.id.panel_status)
    private val empty = EmptyState(statusBox, statusBox)

    /**
     * «Обновить» / «Повторить» на пустом экране (4.51): попросить у страницы свежее. Ставит
     * [com.example.vkweb.overlay.PageOverlay]; нет — кнопки нет.
     */
    var onRetry: (() -> Boolean)? = null

    /**
     * Обновление потягиванием (4.52), если в разметке панели есть рамка panel_refresh. Что делать — [onRetry]
     * (тот же запрос свежего, что «Обновить» на пустом экране); индикатор убирает [pullDone].
     */
    private val pull: PullRefresh? = root.findViewById<androidx.swiperefreshlayout.widget.SwipeRefreshLayout>(R.id.panel_refresh)
        ?.let { layout -> PullRefresh(layout) { onRetry?.invoke() ?: false } }

    /** Свежее пришло или ждать перестали: индикатор потягивания убирается. */
    fun pullDone() {
        pull?.done()
    }

    /** Сейчас крутится индикатор потягивания (ждём свежее, которое попросил человек). */
    val isPulling: Boolean get() = pull?.isRefreshing == true

    /** Последнее показанное «пусто»: если запрос не начался (нет сети), оно возвращается. */
    private var lastEmpty: EmptyState.Content? = null
    private val banner: TextView = root.findViewById(R.id.panel_banner)

    val isShown: Boolean get() = root.visibility == View.VISIBLE

    /** 4.55: свой экран раздела появляется мягко (Motion.fadeIn), а не вспышкой поверх страницы. */
    fun show() {
        Motion.fadeIn(root)
    }

    fun hide() {
        root.visibility = View.GONE
    }

    /**
     * Данных ещё нет: индикатор (с задержкой, см. panel_chrome.xml) вместо строки состояния. У панелей со списком строк
     * (друзья, сообщества, музыка) — силуэты строк panel_skeleton (4.56), у остальных — крутилка.
     */
    fun showLoading() {
        empty.hide()
        if (skeleton != null) skeleton.show() else progress.show()
    }

    /** Данные есть (или ждать больше нечего): без индикатора и строки состояния. */
    fun showContent() {
        stopLoading()
        empty.hide()
    }

    /** Пусто: только пояснение по центру (без значка и кнопок — например, «ищем…»). */
    fun showStatus(@StringRes text: Int) = showEmpty(EmptyState.Content(text))

    /** Пусто, нет сети, данные не пришли (4.51): значок, заголовок, пояснение и кнопки. */
    fun showEmpty(content: EmptyState.Content) {
        stopLoading()
        lastEmpty = content
        empty.show(content)
    }

    /** Список пуст: значок раздела, пояснение и «Обновить» (если экран умеет попросить свежее). */
    fun showEmptyList(@StringRes text: Int, @androidx.annotation.DrawableRes icon: Int, @StringRes body: Int? = null) =
        showEmpty(EmptyState.Content(text, icon, body, action = retryAction(R.string.empty_refresh)))

    /** Без сети и без сохранённого: «Нет сети — нечего показать» и «Повторить». */
    fun showNothingSaved() = showEmpty(
        EmptyState.Content(
            R.string.empty_offline_title, R.drawable.ic_cloud_off, R.string.empty_offline_body,
            action = retryAction(R.string.retry),
        ),
    )

    /** Кнопка, которая просит свежее: на время ожидания — индикатор; запрос не начался — пустой экран остаётся. */
    fun retryAction(@StringRes label: Int): EmptyState.Action? = onRetry?.let { retry ->
        EmptyState.Action(label) {
            val before = lastEmpty
            showLoading()
            if (!retry()) before?.let(::showEmpty)
        }
    }

    /** Приподнять плашку над тем, что занимает низ панели (мини-плеер), чтобы она его не закрывала. */
    fun liftBanner(px: Int) {
        banner.translationY = -px.toFloat()
    }

    /** Плашка внизу (null — убрать). Пока она есть, ждать загрузки нечего: индикатор скрывается. */
    fun setBanner(@StringRes text: Int?) {
        if (text == null) {
            banner.visibility = View.GONE
            return
        }
        banner.setText(text)
        banner.visibility = View.VISIBLE
        stopLoading()
    }
}
