package com.example.vkweb.overlay

import androidx.annotation.StringRes
import com.example.vkweb.offline.OfflineStore
import com.example.vkweb.settings.AppSettings
import com.example.vkweb.web.LinkRouter
import com.example.vkweb.web.PageBridge

/** Что свои экраны поверх страницы берут у главного экрана. */
interface OverlayHost {
    val settings: AppSettings
    val store: OfflineStore
    val bridge: PageBridge
    val links: LinkRouter
    val isOnline: Boolean

    /** Окно открыто из переписки: «Назад» вернёт в неё (поиск музыки тогда «Назад» не закрывает, 4.79). */
    val returnsToChat: Boolean

    /** Показано сохранённое: экран ошибки загрузки страницы не нужен. */
    fun hideLoadError()

    /** Сменилось, какой свой экран показан: обновить кнопку на панели и заголовок. */
    fun onOverlayChanged()

    fun showToast(@StringRes message: Int)

    /** «Поделиться»: [text] и фото [photoUrls] (картинками; пусто — только текст) — в другое приложение. */
    fun share(text: String, photoUrls: List<String>)

    fun postDelayed(task: Runnable, delayMs: Long)

    fun cancel(task: Runnable)

    /** Скачать картинки заранее (аватарки, фото записей), чтобы они были и без сети. */
    fun prefetchImages(urls: List<String>)

    /**
     * Свой экран не дождался свежих данных — вероятно, устарела сессия. Просит фоновое обновление.
     * Возврат true — обновление запущено: стоит подождать и запросить данные снова.
     */
    fun refreshSession(): Boolean
}
