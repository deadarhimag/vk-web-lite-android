package com.example.vkweb.photos

import com.example.vkweb.config.SafeUrls
import org.json.JSONArray
import org.json.JSONObject

/**
 * Что открыто из фото VK (5.00.12): список альбомов владельца, один альбом или все фото владельца.
 * Без зависимостей от Android: проверяется обычными тестами.
 */
sealed class PhotoPlace {
    abstract val owner: Long

    /** Ключ места: по нему экран понимает, что пришедшая порция относится к открытому. */
    abstract val key: String

    /** /albums<владелец> - альбомы. */
    data class Albums(override val owner: Long) : PhotoPlace() {
        override val key: String get() = "albums$owner"
    }

    /**
     * /album<владелец>_<номер> или /photos<владелец> (тогда [album] пуст - все фото). [album] - номер для API:
     * системные альбомы в адресе VK пишет нулями (_0 - фото профиля, _00 - со стены, _000 - сохранённые).
     */
    data class Album(override val owner: Long, val album: String) : PhotoPlace() {
        override val key: String get() = "album${owner}_$album"
    }

    companion object {
        private val ALBUMS = Regex("^/albums(-?\\d{1,12})/?$")
        private val ALBUM = Regex("^/album(-?\\d{1,12})_(-?\\d{1,12})/?$")
        private val ALL = Regex("^/photos(-?\\d{1,12})/?$")

        /** Адрес системного альбома VK -> номер для API. */
        private val SYSTEM = mapOf("0" to "-6", "00" to "-7", "000" to "-15")

        /** Место по пути страницы VK; null - не фото. Владелец 0 не бывает. */
        fun fromPath(path: String?): PhotoPlace? {
            val p = path ?: return null
            ALBUMS.matchEntire(p)?.let { m -> return m.groupValues[1].toLongOrNull()?.takeIf { it != 0L }?.let(::Albums) }
            ALBUM.matchEntire(p)?.let { m ->
                val owner = m.groupValues[1].toLongOrNull()?.takeIf { it != 0L } ?: return null
                val raw = m.groupValues[2]
                return Album(owner, SYSTEM[raw] ?: raw.toLongOrNull()?.toString() ?: return null)
            }
            ALL.matchEntire(p)?.let { m -> return m.groupValues[1].toLongOrNull()?.takeIf { it != 0L }?.let { Album(it, "") } }
            return null
        }

        /** Адрес страницы VK для альбома: системные - обратно нулями, как их пишет сам VK. */
        fun albumPath(owner: Long, album: Long): String {
            val raw = SYSTEM.entries.firstOrNull { it.value == album.toString() }?.key ?: album.toString()
            return "/album${owner}_$raw"
        }
    }
}

/** Альбом: обложка и число фото. */
data class PhotoAlbum(
    val id: Long,
    val owner: Long,
    val title: String,
    val size: Int,
    val cover: String,
) {
    val pagePath: String get() = PhotoPlace.albumPath(owner, id)

    companion object {
        fun fromJson(o: JSONObject): PhotoAlbum? {
            if (!o.has("id") || !o.has("owner")) return null
            val owner = o.optLong("owner")
            if (owner == 0L) return null
            return PhotoAlbum(
                id = o.optLong("id"), owner = owner, title = o.optString("title").trim().take(MAX_TEXT),
                size = o.optInt("size").coerceAtLeast(0), cover = SafeUrls.https(o.optString("cover")),
            )
        }
    }
}

/** Фото: превью для сетки, крупный адрес для просмотра и сохранения, запасные размеры. */
data class PhotoItem(
    val id: Long,
    val owner: Long,
    val thumb: String,
    val full: String,
    val fallback: List<String>,
    val dateSec: Long,
) {
    /** Ключ строки и имени файла: "владелец_номер". */
    val key: String get() = "${owner}_$id"

    /** Страница фото на VK (для просмотра со ссылкой на страницу). */
    val pagePath: String get() = "/photo${owner}_$id"

    companion object {
        fun fromJson(o: JSONObject): PhotoItem? {
            val id = o.optLong("id")
            val owner = o.optLong("owner")
            if (id <= 0 || owner == 0L) return null
            val full = SafeUrls.https(o.optString("full"))
            val thumb = SafeUrls.https(o.optString("thumb")).ifEmpty { full }
            if (thumb.isEmpty()) return null
            return PhotoItem(
                id = id, owner = owner, thumb = thumb, full = full.ifEmpty { thumb },
                fallback = strings(o.optJSONArray("fallback")).map(SafeUrls::https).filter { it.isNotEmpty() }.take(MAX_FALLBACK),
                dateSec = o.optLong("date").coerceAtLeast(0),
            )
        }
    }
}

/** Порция списка от страницы: альбомы владельца или фото альбома. */
data class PhotosPayload(
    val place: PhotoPlace,
    val ok: Boolean,
    val offset: Int,
    val next: Int,
    val more: Boolean,
    val total: Int,
    val albums: List<PhotoAlbum>,
    val photos: List<PhotoItem>,
    val reason: String,
) {
    companion object {
        /** Тип сообщения страницы (поле type). */
        const val TYPE = "photos"

        /** null - сообщение не о фото или испорчено. */
        fun fromJson(json: JSONObject): PhotosPayload? {
            val owner = json.optLong("owner")
            if (owner == 0L) return null
            val place = when (json.optString("kind")) {
                "albums" -> PhotoPlace.Albums(owner)
                "album" -> PhotoPlace.Album(owner, json.optString("album").take(20))
                else -> return null
            }
            val items = json.optJSONArray("items")
            return PhotosPayload(
                place = place,
                ok = json.optBoolean("ok"),
                offset = json.optInt("offset").coerceAtLeast(0),
                next = json.optInt("next").coerceAtLeast(0),
                more = json.optBoolean("more"),
                total = json.optInt("total").coerceAtLeast(0),
                albums = if (place is PhotoPlace.Albums) objects(items).mapNotNull(PhotoAlbum::fromJson).take(MAX_ITEMS) else emptyList(),
                photos = if (place is PhotoPlace.Album) objects(items).mapNotNull(PhotoItem::fromJson).take(MAX_ITEMS) else emptyList(),
                reason = json.optString("reason").take(200),
            )
        }
    }
}

/** Порция адресов всего альбома для сохранения в папку ([AlbumSaver]). */
data class PhotosAllPayload(
    val job: String,
    val ok: Boolean,
    val done: Boolean,
    val total: Int,
    val photos: List<PhotoItem>,
    val reason: String,
) {
    companion object {
        const val TYPE = "photosAll"

        fun fromJson(json: JSONObject): PhotosAllPayload? {
            val job = json.optString("job").take(40)
            if (job.isEmpty()) return null
            return PhotosAllPayload(
                job = job, ok = json.optBoolean("ok"), done = json.optBoolean("done"),
                total = json.optInt("total").coerceAtLeast(0),
                photos = objects(json.optJSONArray("items")).mapNotNull { o ->
                    // У адресов для сохранения превью нет: крупный адрес и есть картинка
                    if (!o.has("thumb")) o.put("thumb", o.optString("full"))
                    PhotoItem.fromJson(o)
                }.take(MAX_ITEMS),
                reason = json.optString("reason").take(200),
            )
        }
    }
}

private const val MAX_TEXT = 300
private const val MAX_ITEMS = 1000
private const val MAX_FALLBACK = 4

private fun objects(array: JSONArray?): List<JSONObject> {
    if (array == null) return emptyList()
    return (0 until array.length()).mapNotNull { array.optJSONObject(it) }
}

private fun strings(array: JSONArray?): List<String> {
    if (array == null) return emptyList()
    return (0 until array.length()).map { array.optString(it) }
}
