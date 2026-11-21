package com.example.vkweb.owner

/** Строка своего экрана страницы: шапка, пост или «догружается» внизу. */
sealed class OwnerRow {
    /** Постоянный ключ строки для DiffUtil. */
    abstract val key: String

    /** [notice] — пояснение под шапкой (закрытый профиль, пустая стена); [wallTitle] — показать «Записи». */
    data class Header(val header: OwnerHeader, val notice: String, val wallTitle: Boolean) : OwnerRow() {
        override val key: String get() = "header"
    }

    data class Post(val post: WallPost) : OwnerRow() {
        override val key: String get() = "post:" + post.key
    }

    object Footer : OwnerRow() {
        override val key: String get() = "footer"
    }
}
