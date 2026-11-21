package com.example.vkweb.config

/**
 * Адреса из данных страницы, которым приложение доверяет (код ревью 4.44: «только https, не длиннее N, на серверах
 * VK» было переписано в восьми местах). Чужой адрес не показывается и не проигрывается — вместо него пусто.
 * Без зависимостей от Android: проверяется тестами.
 */
object SafeUrls {

    /** Длиннее — не адрес картинки или ролика, а мусор или попытка подсунуть что-то странное. */
    const val MAX_LENGTH = 4000

    fun hostOf(url: String?): String? = try {
        url?.let { java.net.URI(it).host }
    } catch (e: Exception) {
        null
    }

    /** https-адрес (без пробелов по краям), не длиннее [maxLength]; иначе пусто. */
    fun https(value: String?, maxLength: Int = MAX_LENGTH): String =
        value?.trim()?.takeIf { it.startsWith("https://") && it.length <= maxLength }.orEmpty()

    /** Файл видео — на серверах видео VK ([AppConfig.isVideoHost]). */
    fun video(value: String?, maxLength: Int = MAX_LENGTH): String = onHost(value, maxLength, AppConfig::isVideoHost)

    /** Картинка — на серверах картинок VK ([AppConfig.isImageHost]). */
    fun image(value: String?, maxLength: Int = MAX_LENGTH): String = onHost(value, maxLength, AppConfig::isImageHost)

    /** Звук — на серверах звука VK ([AppConfig.isAudioHost]). */
    fun audio(value: String?, maxLength: Int = MAX_LENGTH): String = onHost(value, maxLength, AppConfig::isAudioHost)

    private fun onHost(value: String?, maxLength: Int, allowed: (String?) -> Boolean): String =
        https(value, maxLength).takeIf { it.isNotEmpty() && allowed(hostOf(it)) }.orEmpty()
}
