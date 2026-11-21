package com.example.vkweb.settings

import android.content.Context
import com.example.vkweb.ui.Notice
import android.text.format.Formatter
import androidx.preference.Preference
import androidx.preference.PreferenceCategory
import com.example.vkweb.R
import com.example.vkweb.offline.Storage
import com.example.vkweb.offline.StorageCategory
import com.google.android.material.dialog.MaterialAlertDialogBuilder

/**
 * Раздел настроек «Сохранённые данные»: по пункту на каждую [StorageCategory] с занятым местом
 * и «Удалить всё». Каждое удаление — с подтверждением.
 */
class StorageSection(
    private val context: Context,
    private val section: PreferenceCategory,
    private val listener: Listener,
) {
    interface Listener {
        /** Кэш страниц удаляет WebView главного экрана — при возврате к нему. */
        fun onWebCacheClearRequested()

        /** Удалено сохранённое: открытые экраны должны забыть показанное. */
        fun onStorageCleared()
    }

    private val storage = Storage(context)
    private val items = LinkedHashMap<StorageCategory, Preference>()
    private val clearAll = Preference(context)
    /** Кэш страниц уже попросили удалить: его размер до возврата к страницам не важен. */
    private var webCacheRequested = false
    private var active = true

    init {
        StorageCategory.entries.forEach { category ->
            val item = Preference(context).apply {
                key = KEY_PREFIX + category.name.lowercase()
                title = context.getString(category.titleRes)
                summary = context.getString(R.string.pref_storage_measuring, context.getString(category.summaryRes))
                isPersistent = false
                isIconSpaceReserved = false
                setOnPreferenceClickListener { confirm(category); true }
            }
            items[category] = item
            section.addPreference(item)
        }
        clearAll.apply {
            key = KEY_PREFIX + "all"
            title = context.getString(R.string.pref_storage_clear_all)
            isPersistent = false
            isIconSpaceReserved = false
            setOnPreferenceClickListener { confirmAll(); true }
        }
        section.addPreference(clearAll)
    }

    /** Пересчитать занятое место (при открытии настроек и после удаления). */
    fun refresh() {
        storage.measure { sizes ->
            if (!active) return@measure
            sizes.forEach { (category, bytes) -> items[category]?.summary = summaryOf(category, bytes) }
            clearAll.summary = context.getString(R.string.pref_storage_clear_all_summary, size(sizes.values.sum()))
        }
    }

    /** Экран настроек закрыт: результаты подсчёта больше некуда показывать. */
    fun detach() {
        active = false
    }

    private fun summaryOf(category: StorageCategory, bytes: Long): String {
        if (category.needsWebView && webCacheRequested) return context.getString(R.string.pref_clear_cache_done)
        return context.getString(R.string.pref_storage_size, context.getString(category.summaryRes), size(bytes))
    }

    private fun size(bytes: Long): String = Formatter.formatShortFileSize(context, bytes)

    private fun confirm(category: StorageCategory) {
        val title = context.getString(category.titleRes)
        MaterialAlertDialogBuilder(context)
            .setTitle(context.getString(R.string.pref_storage_confirm_title, title))
            .setMessage(R.string.pref_storage_confirm_body)
            .setNegativeButton(R.string.cancel, null)
            .setPositiveButton(R.string.pref_storage_confirm) { _, _ -> clear(category) }
            .show()
    }

    private fun confirmAll() {
        MaterialAlertDialogBuilder(context)
            .setTitle(R.string.pref_storage_confirm_all_title)
            .setMessage(R.string.pref_storage_confirm_body)
            .setNegativeButton(R.string.cancel, null)
            .setPositiveButton(R.string.pref_storage_confirm) { _, _ -> clearEverything() }
            .show()
    }

    private fun clear(category: StorageCategory) {
        if (category.needsWebView) {
            requestWebCacheClear()
            refresh()
            return
        }
        storage.clear(category) { onCleared() }
    }

    private fun clearEverything() {
        requestWebCacheClear()
        storage.clearAll { onCleared() }
    }

    private fun requestWebCacheClear() {
        webCacheRequested = true
        listener.onWebCacheClearRequested()
    }

    private fun onCleared() {
        listener.onStorageCleared()
        if (!active) return
        Notice.show(context, R.string.pref_storage_cleared)
        refresh()
    }

    private companion object {
        const val KEY_PREFIX = "storage_"
    }
}
