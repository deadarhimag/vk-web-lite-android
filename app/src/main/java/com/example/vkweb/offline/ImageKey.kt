package com.example.vkweb.offline

/**
 * Ключ картинки в кэше. Без зависимостей от Android: проверяется обычными тестами.
 *
 * Адрес картинки VK меняется, а сама картинка — нет: в нём есть подпись (`sign`) и метка (`c_uniq_tag`),
 * которые бывают разными при каждой выдаче, а сервер (`sun1-18`, `sun9-46`) выбирается по загрузке.
 * Если ключом брать адрес целиком, одна и та же аватарка или фото записи скачивалась бы заново при каждом
 * открытии. Поэтому ключ — путь картинки и параметры, которые определяют её вид (`size`, `crop`, `quality`…),
 * без хоста и без подписи.
 */
object ImageKey {

    /** Параметры, которые меняются сами и на содержимое не влияют. */
    private val VOLATILE = setOf("sign", "c_uniq_tag", "extra", "expires", "ts", "hash", "access_token", "token", "u")

    /**
     * Какая это картинка — только путь, без сервера и параметров. Для вопроса «то же ли это фото»: один снимок приходит с
     * разными подписями, размерами и списками размеров (`as`, `cs`, `u`…), а путь у него один. Раньше такое фото в сообщении
     * при каждом обновлении считалось другим, пузырь пересоздавал картинки, и контент дёргался.
     */
    fun identity(url: String): String = "/" + url.substringAfter("://", url).substringBefore('#').substringBefore('?').substringAfter('/', "")

    /** Ключ для адреса [url]: путь и вид картинки без подписи и сервера. */
    fun of(url: String): String {
        val rest = url.substringAfter("://", url).substringBefore('#')
        val path = rest.substringBefore('?').substringAfter('/', "")
        val kept = rest.substringAfter('?', "")
            .split('&')
            .filter { it.isNotEmpty() && it.substringBefore('=') !in VOLATILE }
            .sorted()
        return "/" + path + if (kept.isEmpty()) "" else "?" + kept.joinToString("&")
    }
}
