package com.example.vkweb.offline

import androidx.annotation.StringRes
import com.example.vkweb.R

/**
 * Что приложение хранит на устройстве и что можно удалить из настроек по отдельности.
 * Порядок здесь — порядок в настройках. Новая категория: строка здесь, файлы — в [OfflineStore.filesOf]
 * (или свой способ в [Storage]), подписи — в strings.xml.
 */
enum class StorageCategory(@StringRes val titleRes: Int, @StringRes val summaryRes: Int) {
    MESSAGES(R.string.storage_messages, R.string.storage_messages_summary),

    /** Сообщения, которые ещё не отправлены ([com.example.vkweb.outbox.Outbox]): удаляются только осознанно. */
    OUTBOX(R.string.storage_outbox, R.string.storage_outbox_summary),
    MUSIC(R.string.storage_music, R.string.storage_music_summary),

    /** Кэш музыки в mp3 ([com.example.vkweb.media.MusicCache], 4.95): отдельно от списков — их удаление не трогает файлы. */
    MUSIC_FILES(R.string.storage_music_files, R.string.storage_music_files_summary),
    PEOPLE(R.string.storage_people, R.string.storage_people_summary),
    PAGES(R.string.storage_pages, R.string.storage_pages_summary),
    IMAGES(R.string.storage_images, R.string.storage_images_summary),

    /** Кэш страниц VK у WebView: очищает сам WebView главного экрана ([needsWebView]). */
    WEB_CACHE(R.string.storage_web, R.string.storage_web_summary),

    /** Собранное для разработки ([com.example.vkweb.dev.DumpRecorder]). */
    DEV_DUMP(R.string.storage_dump, R.string.storage_dump_summary),

    /** Журнал событий приложения ([com.example.vkweb.diag.AppLog]). */
    EVENT_LOG(R.string.storage_log, R.string.storage_log_summary);

    val needsWebView: Boolean get() = this == WEB_CACHE
}
