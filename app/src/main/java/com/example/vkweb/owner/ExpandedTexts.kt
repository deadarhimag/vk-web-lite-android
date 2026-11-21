package com.example.vkweb.owner

/**
 * Какие длинные тексты развёрнуты («Показать полностью»). Живёт, пока открыта страница:
 * при прокрутке туда и обратно развёрнутое не сворачивается.
 */
class ExpandedTexts {
    private val keys = HashSet<String>()

    fun isExpanded(key: String): Boolean = key in keys

    fun expand(key: String) {
        keys += key
    }

    fun clear() = keys.clear()

    companion object {
        const val DESCRIPTION = "header#description"

        /** Ключ текста записи, которой поделились, внутри поста [postKey]. */
        fun repostKey(postKey: String) = "$postKey#repost"
    }
}
