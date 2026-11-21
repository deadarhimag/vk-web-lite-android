package com.example.vkweb.clips

import com.example.vkweb.config.SafeUrls
import com.example.vkweb.config.VkIds
import com.example.vkweb.config.AppConfig
import org.json.JSONObject

/**
 * Лента клипов для своего проигрывателя (3.76): ролики из ответа страницы (shortVideo.getRecom). Без зависимостей от
 * Android: проверяется обычными тестами.
 */
data class ClipItem(
    /** «владелец_номер». */
    val id: String,
    /** Файл mp4 (до 720p) или пусто. */
    val mp4: String,
    /** Поток HLS или пусто. */
    val hls: String,
    val cover: String = "",
    val author: String = "",
    val avatar: String = "",
    val text: String = "",
    val likes: Int = 0,
    val liked: Boolean = false,
    val comments: Int = 0,
    val durationSec: Int = 0,
) {
    /** Что играть: файл надёжнее потока (быстрее начинает, перематывается). */
    val source: String get() = mp4.ifEmpty { hls }

    /** Страница клипа на сайте (комментарии, «поделиться»). */
    val pagePath: String get() = "/clip$id"
}

/** Вкладка раздела клипов на странице («Для вас», «Подписки»…), 3.84: выбирается нажатием самой вкладки страницы. */
data class ClipTab(val label: String, val selected: Boolean)

data class ClipsPayload(
    val items: List<ClipItem>,
    val hasMore: Boolean,
    val reset: Boolean,
    val tabs: List<ClipTab> = emptyList(),
) {
    companion object {
        const val TYPE = "clips"
        private const val MAX_ITEMS = 300
        private const val MAX_TABS = 8

        fun fromJson(o: JSONObject): ClipsPayload? {
            val array = o.optJSONArray("items") ?: return null
            val items = ArrayList<ClipItem>()
            for (i in 0 until minOf(array.length(), MAX_ITEMS)) {
                val c = array.optJSONObject(i) ?: continue
                val id = c.optString("id")
                if (!VkIds.isMediaId(id)) continue
                val mp4 = videoUrl(c.optString("mp4"))
                val hls = videoUrl(c.optString("hls"))
                if (mp4.isEmpty() && hls.isEmpty()) continue
                items += ClipItem(
                    id = id, mp4 = mp4, hls = hls,
                    cover = imageUrl(c.optString("cover")),
                    author = c.optString("author").trim().take(200),
                    avatar = imageUrl(c.optString("avatar")),
                    text = c.optString("text").trim().take(2000),
                    likes = c.optInt("likes").coerceAtLeast(0),
                    liked = c.optBoolean("liked"),
                    comments = c.optInt("comments").coerceAtLeast(0),
                    durationSec = c.optInt("duration").coerceIn(0, 24 * 3600),
                )
            }
            val tabs = ArrayList<ClipTab>()
            val tabArray = o.optJSONArray("tabs")
            if (tabArray != null) {
                for (i in 0 until minOf(tabArray.length(), MAX_TABS)) {
                    val t = tabArray.optJSONObject(i) ?: continue
                    val label = t.optString("label").trim().take(40)
                    if (label.isNotEmpty()) tabs += ClipTab(label, t.optBoolean("selected"))
                }
            }
            return ClipsPayload(items, o.optBoolean("hasMore"), o.optBoolean("reset"), tabs)
        }

        private fun videoUrl(url: String): String = SafeUrls.video(url)

        private fun imageUrl(url: String): String = SafeUrls.image(url)
    }
}

/**
 * Общая лента между главным окном (получает её от страницы) и проигрывателем ([ClipsActivity]). Только главный поток.
 */
object ClipsHub {
    var items: List<ClipItem> = emptyList()
        private set
    var hasMore: Boolean = false
        private set

    /** Вкладки раздела клипов со страницы (пусто — не нашлись). */
    var tabs: List<ClipTab> = emptyList()
        private set

    /** Лента пришла заново (вкладка, новая страница) — свой экран прокручивается к началу. */
    var generation: Int = 0
        private set

    /** С какого ролика начать (клип, открытый на странице). */
    var startId: String? = null

    /** Попросить у страницы продолжение ленты. Ставит главное окно. */
    var requestMore: (() -> Unit)? = null

    /** «Нравится» клипу [id]: поставить (true) или снять — от имени страницы (4.0). Ставит главное окно. */
    var requestLike: ((id: String, on: Boolean) -> Unit)? = null

    /** Ответ страницы на «нравится»: отметка и число — в ленте; ошибка — отметка остаётся прежней. */
    fun onLike(result: ClipLike) {
        if (!result.ok) {
            listeners.toList().forEach { it() }
            return
        }
        items = items.map { c ->
            if (c.id != result.id) c else c.copy(liked = result.liked, likes = if (result.likes >= 0) result.likes else ClipLikes.count(c, result.liked))
        }
        listeners.toList().forEach { it() }
    }

    /**
     * Проигрыватель закрыт; true — ради страницы клипа (комментарии): её открывает сам проигрыватель, главному окну
     * остаётся вернуть странице звук. Ставит главное окно.
     */
    var onClosed: ((toPage: Boolean) -> Unit)? = null

    private val listeners = LinkedHashSet<() -> Unit>()

    fun update(payload: ClipsPayload) {
        items = if (payload.reset) payload.items else merge(items, payload.items)
        if (payload.reset) generation++
        hasMore = payload.hasMore
        tabs = payload.tabs
        listeners.toList().forEach { it() }
    }

    /** Прежние ролики остаются на своих местах (их уже смотрят), новые — в конец. */
    fun merge(old: List<ClipItem>, fresh: List<ClipItem>): List<ClipItem> {
        val known = old.map { it.id }.toHashSet()
        return old + fresh.filter { it.id !in known }
    }

    fun addListener(l: () -> Unit) { listeners += l }

    fun removeListener(l: () -> Unit) { listeners -= l }
}

/** Ответ страницы на «нравится» клипу (4.0). */
data class ClipLike(val id: String, val ok: Boolean, val liked: Boolean, val likes: Int, val reason: String) {
    companion object {
        const val TYPE = "clipLike"

        fun fromJson(o: JSONObject): ClipLike? {
            val id = o.optString("id")
            if (!VkIds.isMediaId(id)) return null
            return ClipLike(id, o.optBoolean("ok"), o.optBoolean("liked"), o.optInt("likes", -1), o.optString("reason").take(200))
        }
    }
}

/** Число лайков, пока страница не прислала точное: ±1 от прежнего. */
object ClipLikes {
    fun count(clip: ClipItem, liked: Boolean): Int = when {
        liked == clip.liked -> clip.likes
        liked -> clip.likes + 1
        else -> (clip.likes - 1).coerceAtLeast(0)
    }
}
