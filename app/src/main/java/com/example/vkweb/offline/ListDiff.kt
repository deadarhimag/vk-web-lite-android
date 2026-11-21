package com.example.vkweb.offline

/**
 * Что изменилось между показанным списком и новым: добавлено, убрано, изменено, переставлено. Для журнала: когда
 * список «дышит» или «сжимается», по записи видно, что именно произошло. Без зависимостей от Android: проверяется
 * обычными тестами.
 */
object ListDiff {

    class Summary(
        val before: Int,
        val after: Int,
        val added: Int,
        val removed: Int,
        val changed: Int,
        val reordered: Boolean,
    ) {
        val isSame: Boolean get() = added == 0 && removed == 0 && changed == 0 && !reordered

        /** «было 35, стало 36: +1 −0 ~2, порядок изменился». */
        fun describe(): String = buildString {
            append("было ").append(before).append(", стало ").append(after)
            if (isSame) { append(": без изменений"); return@buildString }
            append(": +").append(added).append(" −").append(removed).append(" ~").append(changed)
            if (reordered) append(", порядок изменился")
        }
    }

    fun <T, K> summarize(before: List<T>, after: List<T>, key: (T) -> K): Summary {
        val old = LinkedHashMap<K, T>()
        before.forEach { old[key(it)] = it }
        val now = LinkedHashMap<K, T>()
        after.forEach { now[key(it)] = it }
        var added = 0
        var changed = 0
        for ((k, item) in now) {
            val was = old[k]
            if (was == null && !old.containsKey(k)) added++ else if (was != item) changed++
        }
        val removed = old.keys.count { it !in now }
        // Порядок общих элементов: совпадает ли последовательность ключей у обоих списков.
        val common = old.keys.filter { it in now }
        val reordered = common != now.keys.filter { it in old }
        return Summary(before.size, after.size, added, removed, changed, reordered)
    }
}
