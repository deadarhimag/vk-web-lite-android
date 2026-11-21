package com.example.vkweb.diag

import com.example.vkweb.offline.ListDiff

/**
 * Записи о том, как данные попадают на экран: что пришло, что стало после слияния. Общее место для всех экранов
 * (музыка, плейлисты, друзья, переписки): раньше каждый писал по-своему или не писал вовсе.
 */
object FlowLog {

    /** Слияние свежей порции с показанным: сколько было, стало, добавлено, убрано, изменено. */
    fun <T, K> merged(screen: String, what: String, before: List<T>, after: List<T>, key: (T) -> K) {
        if (before.isEmpty() && after.isEmpty()) return
        AppLog.d(LogTag.SCREEN, "$screen: слияние", "список" to what, "итог" to ListDiff.summarize(before, after, key).describe())
    }
}
