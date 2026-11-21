package com.example.vkweb.video

import com.example.vkweb.config.SafeUrls
import com.example.vkweb.config.VkIds
import com.example.vkweb.config.AppConfig
import org.json.JSONObject

/** Ролик каталога VK Видео (3.78). Без зависимостей от Android: проверяется обычными тестами. */
data class VideoItem(
    /** «владелец_номер». */
    val id: String,
    val title: String,
    val cover: String = "",
    val durationSec: Int = 0,
    val views: Long = 0,
    val date: Long = 0,
    val live: Boolean = false,
    val author: String = "",
    val avatar: String = "",
    val mp4: String = "",
    val hls: String = "",
    val player: String = "",
) {
    /** Страница ролика на сайте (запасной путь проигрывателя). */
    val pagePath: String get() = "/video$id"
}

/**
 * Раздел каталога VK Видео («Для вас», «Тренды», «Фильмы»…; 3.85) — фильтр своего экрана. [url] — страница раздела на
 * том же сайте; [subs] — подразделы («Политика»: «Все», «СМИ»…).
 */
data class VideoSection(val title: String, val url: String, val selected: Boolean, val subs: List<VideoSection> = emptyList())

/** Все качества файла ролика каталога (3.86): страница присылает их по запросу при открытии ролика. */
data class VideoFiles(val id: String, val files: Map<Int, String>, val hls: String) {
    companion object {
        const val TYPE = "videofiles"

        fun fromJson(o: JSONObject): VideoFiles? {
            val id = o.optString("id")
            if (!VkIds.isMediaId(id)) return null
            return VideoFiles(id, VideoQuality.parse(o.optJSONObject("files")) { VideoCatalog.videoUrl(it) }, VideoCatalog.videoUrl(o.optString("hls")))
        }
    }
}

/** Каталог открытой страницы VK Видео ([key] — путь: «/», «/trends»…). */
data class VideoCatalog(
    val key: String,
    val items: List<VideoItem>,
    val hasMore: Boolean,
    val sections: List<VideoSection> = emptyList(),
) {
    companion object {
        const val TYPE = "vkvideo"
        private const val MAX_ITEMS = 300

        fun fromJson(o: JSONObject): VideoCatalog? {
            val key = o.optString("key").take(100)
            if (!key.startsWith("/")) return null
            val array = o.optJSONArray("items") ?: return null
            val items = ArrayList<VideoItem>()
            for (i in 0 until minOf(array.length(), MAX_ITEMS)) {
                val v = array.optJSONObject(i) ?: continue
                val id = v.optString("id")
                if (!VkIds.isMediaId(id)) continue
                items += VideoItem(
                    id = id,
                    title = v.optString("title").trim().take(300),
                    cover = image(v.optString("cover")),
                    durationSec = v.optInt("duration").coerceIn(0, 7 * 24 * 3600),
                    views = v.optLong("views").coerceAtLeast(0),
                    date = v.optLong("date").coerceAtLeast(0),
                    live = v.optBoolean("live"),
                    author = v.optString("author").trim().take(200),
                    avatar = image(v.optString("avatar")),
                    mp4 = video(v.optString("mp4")),
                    hls = video(v.optString("hls")),
                    player = video(v.optString("player")),
                )
            }
            return VideoCatalog(key, items, o.optBoolean("hasMore"), sections(o.optJSONArray("sections"), depth = 0))
        }

        private const val MAX_SECTIONS = 20

        private fun sections(array: org.json.JSONArray?, depth: Int): List<VideoSection> {
            if (array == null) return emptyList()
            val result = ArrayList<VideoSection>()
            for (i in 0 until minOf(array.length(), MAX_SECTIONS)) {
                val s = array.optJSONObject(i) ?: continue
                val title = s.optString("title").trim().take(40)
                val url = s.optString("url")
                if (title.isEmpty() || !isSectionUrl(url)) continue
                result += VideoSection(
                    title, url, s.optBoolean("selected"),
                    if (depth == 0) sections(s.optJSONArray("subs"), depth + 1) else emptyList(),
                )
            }
            return result
        }

        /** Только страницы самого VK Видео: раздел открывается переходом внутри страницы. */
        private fun isSectionUrl(url: String): Boolean =
            com.example.vkweb.web.SiteDomains.isVideoSite(SafeUrls.hostOf(SafeUrls.https(url, maxLength = 300)))

        /** https на серверах видео VK — иначе пусто. */
        fun videoUrl(url: String): String = video(url)

        private fun video(url: String) = SafeUrls.video(url)

        private fun image(url: String) = SafeUrls.image(url)
    }
}

/** Подписи карточки ролика. */
object VideoFormat {
    /** «1:05», «1:02:30». */
    fun duration(sec: Int): String = if (sec <= 0) "" else com.example.vkweb.ui.PlayerFormat.timeSec(sec.toLong())

    /** «56 тыс. просмотров», «1,2 млн просмотров», «12 просмотров». */
    fun views(n: Long): String {
        val word = com.example.vkweb.text.RuPlural.pick(n, "просмотр", "просмотра", "просмотров")
        return when {
            n >= 1_000_000 -> String.format(java.util.Locale("ru"), "%.1f млн просмотров", n / 1_000_000.0).replace(",0 ", " ")
            n >= 1_000 -> "${n / 1000} тыс. просмотров"
            else -> "$n $word"
        }
    }
}
