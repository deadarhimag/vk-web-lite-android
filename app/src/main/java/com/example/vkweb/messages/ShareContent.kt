package com.example.vkweb.messages

/**
 * Что прислали через «Поделиться» из другого приложения (4.57): текст или ссылка и файлы. Без зависимостей от Android:
 * проверяется обычными тестами. Принимает messages/ShareReceiverActivity.kt, отправку готовит [ShareIntoChat].
 */
data class ShareContent(val text: String, val uris: List<String>) {

    val isEmpty: Boolean get() = text.isBlank() && uris.isEmpty()

    companion object {
        private const val MAX_TEXT = 4000

        /**
         * Тема ([subject], её присылают браузеры: заголовок страницы) — перед текстом, если её там ещё нет. Файлы — только
         * content:// (свои файлы чужому приложению так и передают; file:// на Android 7+ не открыть) и не больше
         * [AttachmentPolicy.MAX_FILES]. null — нечего отправлять.
         *
         * 4.65: адреса собственных провайдеров приложения ([ownPackage] и его «.files» — журнал, отчёты, собранные данные
         * в cache/shared) не принимаются: иначе любое приложение могло прислать такой адрес, и VK Web Lite сам прочитал бы
         * свой файл и предложил отправить его в переписку. Номер пользователя в адресе («content://0@…») не помогает.
         */
        fun of(text: String?, subject: String?, uris: List<String?>, ownPackage: String = ""): ShareContent? {
            val body = text?.trim().orEmpty()
            val title = subject?.trim().orEmpty()
            val combined = when {
                title.isEmpty() || body.contains(title) -> body
                body.isEmpty() -> title
                else -> "$title\n$body"
            }.take(MAX_TEXT)
            val files = uris.filterNotNull()
                .filter { it.startsWith("content://") && !isOwnProvider(it, ownPackage) }
                .distinct().take(AttachmentPolicy.MAX_FILES)
            return ShareContent(combined, files).takeUnless { it.isEmpty }
        }

        /** Провайдер самого приложения: authority — имя пакета или начинается с «пакет.» (без номера пользователя «N@»). */
        fun isOwnProvider(uri: String, ownPackage: String): Boolean {
            if (ownPackage.isEmpty()) return false
            val authority = uri.removePrefix("content://").substringBefore('/').substringBefore('?').substringBefore('#')
                .substringAfterLast('@').substringBefore(':').lowercase()
            val own = ownPackage.lowercase()
            return authority == own || authority.startsWith("$own.")
        }
    }
}
