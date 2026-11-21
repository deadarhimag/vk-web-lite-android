package com.example.vkweb.ui

/** Заголовок страницы для верхней панели: без хвоста «| ВКонтакте». Чистая функция — есть тест. */
object PageTitle {

    private val SITE_SUFFIX = Regex("""\s*[|—–-]\s*(ВКонтакте|VK|ВК)\s*$""", RegexOption.IGNORE_CASE)

    /** Адрес без схемы («m.vk.ru/club123», «vk.com/id1») и «слизь» страницы: club123, id1, public5, event7, wall-1_2. */
    private val ADDRESS_LIKE = Regex(
        """^(?:(?:[\w-]+\.)+[a-z]{2,}(?:/\S*)?|/?(?:club|public|event|id|wall|video|clip)-?\d+(?:_\d+)?)$""",
        RegexOption.IGNORE_CASE,
    )

    fun clean(raw: String?): String? {
        val title = raw?.replace(SITE_SUFFIX, "")?.trim().orEmpty()
        // Пока страница грузится, WebView отдаёт адрес вместо заголовка — его не показываем.
        if (title.isEmpty() || title.startsWith("http://") || title.startsWith("https://")) return null
        if (title.equals("ВКонтакте", ignoreCase = true) || title.equals("VK", ignoreCase = true)) return null
        // Пока у страницы нет своего <title>, WebView отдаёт адрес без схемы — раньше он попадал в шапку как «club14595052».
        if (ADDRESS_LIKE.matches(title)) return null
        return title
    }
}
