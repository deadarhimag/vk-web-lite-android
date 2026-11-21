package com.example.vkweb.offline

/**
 * Правила обновления списков, когда на экране уже что-то показано (обычно — сохранённое без сети).
 * Главное — не дёргать интерфейс: то, на что человек смотрит, остаётся на месте.
 * Без зависимостей от Android: проверяется обычными тестами.
 */
object ListMerge {

    /**
     * Для показа: элементы остаются на своих местах и обновляются (новая версия заменяет старую),
     * новые добавляются снизу в порядке новой порции. Ничего не исчезает и не переставляется.
     */
    fun <T, K> stable(shown: List<T>, incoming: List<T>, key: (T) -> K): List<T> {
        if (shown.isEmpty()) return incoming
        val fresh = LinkedHashMap<K, T>()
        incoming.forEach { fresh[key(it)] = it }
        val result = ArrayList<T>(shown.size + incoming.size)
        val placed = HashSet<K>()
        for (item in shown) {
            val k = key(item)
            if (placed.add(k)) result += fresh[k] ?: item
        }
        for ((k, item) in fresh) if (placed.add(k)) result += item
        return result
    }

    /**
     * Свежие данные поверх показанного (обычно — сохранённого) списка с настоящим порядком:
     * сначала свежая порция в порядке страницы, за ней то, до чего страница ещё не дошла.
     * Список не сжимается, пока страница догружает своё. Когда [complete] (страница прислала весь список),
     * лишнее (удалённое за это время) убирается. Пустая порция ничего не убирает: это ещё не ответ.
     * Положение прокрутки сохраняет [com.example.vkweb.ui.ListUpdates] — порядок можно менять.
     */
    fun <T, K> refresh(shown: List<T>, incoming: List<T>, complete: Boolean, key: (T) -> K): List<T> = when {
        incoming.isEmpty() -> shown
        complete -> incoming
        else -> freshFirst(shown, incoming, key)
    }

    /** Те же элементы: одинаковое число и одинаковые наборы ключей (порядок не важен). Общее для сравнения вложений и фото. */
    fun <T> sameKeys(a: List<T>, b: List<T>, key: (T) -> String): Boolean =
        a.size == b.size && a.map(key).sorted() == b.map(key).sorted()

    /**
     * Свежая порция: того же списка ([sameList]) — сливается с показанным ([refresh]), другого (человек перешёл на
     * другую страницу) — заменяет его. Общее правило для музыки, плейлистов и друзей: раньше его писали заново.
     */
    fun <T, K> accept(shown: List<T>, incoming: List<T>, sameList: Boolean, complete: Boolean, key: (T) -> K): List<T> =
        if (sameList) refresh(shown, incoming, complete, key) else incoming

    /** Добавить снизу только недостающее (например, сохранённое пришло позже свежего — оно не главнее). */
    fun <T, K> appendMissing(shown: List<T>, extra: List<T>, key: (T) -> K): List<T> {
        val have = shown.mapTo(HashSet()) { key(it) }
        return shown + extra.filter { have.add(key(it)) }
    }

    /**
     * Для сохранения: настоящий порядок из свежей порции, затем то, чего в ней нет
     * (дальше по списку, чем страница успела загрузить). При следующем открытии покажется он.
     */
    fun <T, K> freshFirst(known: List<T>, incoming: List<T>, key: (T) -> K): List<T> {
        if (incoming.isEmpty()) return known
        val fresh = incoming.mapTo(HashSet()) { key(it) }
        return incoming + known.filter { key(it) !in fresh }
    }
}
