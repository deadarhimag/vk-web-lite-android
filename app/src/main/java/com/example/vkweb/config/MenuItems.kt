package com.example.vkweb.config

/**
 * Пункты бокового меню, которые можно включать и выключать: разделы [Tab] и разделы VK с адресом в меню
 * ([HiddenSection.menuPath]). Хранятся по имени. По умолчанию включены все; всегда остаётся хотя бы один.
 * В настройках записаны именно выключенные пункты: всё, чего там нет, включено, поэтому новый пункт меню
 * появляется у всех сам.
 * Без зависимостей от Android: проверяется обычными тестами.
 */
object MenuItems {

    /** Все пункты в порядке меню: сначала разделы, затем открытые разделы VK. */
    val ALL: List<String> =
        Tab.entries.map { it.name } + HiddenSection.entries.filter { it.menuPath != null }.map { it.name }

    /** Включённые пункты по записанным выключенным: всё остальное включено. Выключить все нельзя — тогда включены все. */
    fun fromDisabled(disabled: Collection<String>?): Set<String> = enabled(ALL - disabled.orEmpty().toSet())

    /** Выключенные пункты — то, что записывается в настройки. */
    fun disabledOf(enabled: Collection<String>): Set<String> = (ALL - enabled.toSet()).toSet()

    /** Включённые пункты из набора включённых. Пустой или негодный набор — включены все (меню не бывает пустым). */
    fun enabled(stored: Collection<String>?): Set<String> {
        val valid = stored.orEmpty().filter { it in ALL }.toSet()
        return if (valid.isEmpty()) ALL.toSet() else valid
    }

    /** Экран при запуске: выбранный, если он включён, иначе первый включённый пункт меню. */
    fun start(stored: String?, enabled: Set<String>): String =
        stored?.takeIf { it in enabled } ?: ALL.first { it in enabled }

    fun tab(name: String): Tab? = Tab.entries.firstOrNull { it.name == name }

    fun section(name: String): HiddenSection? =
        HiddenSection.entries.firstOrNull { it.name == name && it.menuPath != null }
}
