package com.example.vkweb.web

import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger

/**
 * Потокобезопасный счётчик по ключам (серверам). Запросы WebView приходят из многих потоков, поэтому обычная карта
 * не годится. Без зависимостей от Android: проверяется обычными тестами.
 */
class HostCounter {
    private val counts = ConcurrentHashMap<String, AtomicInteger>()

    fun add(key: String?) {
        if (key.isNullOrEmpty()) return
        counts.getOrPut(key) { AtomicInteger() }.incrementAndGet()
    }

    operator fun get(key: String): Int = counts[key]?.get() ?: 0

    val total: Int get() = counts.values.sumOf { it.get() }

    val keys: Set<String> get() = counts.keys.toSet()

    /** Самые частые ключи: пары «ключ — сколько раз». */
    fun top(limit: Int): List<Pair<String, Int>> =
        counts.entries.map { it.key to it.value.get() }.sortedByDescending { it.second }.take(limit)

    fun clear() = counts.clear()
}
