package com.example.vkweb.owner

import com.example.vkweb.offline.ImageKey
import com.example.vkweb.offline.ListMerge

/**
 * Слияние записей стены. Одна и та же запись приходит с картинками (аватар автора, фото) под разными адресами:
 * подпись, размер и сервер в них меняются от ответа к ответу. Если заменять запись целиком, она «менялась» при каждом
 * обновлении страницы: пузырь пересоздавал картинки, а файл сохранённой страницы перезаписывался (в отчёте он прыгал между двумя
 * размерами каждые несколько секунд). Здесь известные адреса остаются, пока картинка та же. Без зависимостей от Android:
 * проверяется обычными тестами.
 */
object PostMerge {

    /** Свежая запись [fresh] с известными адресами тех картинок, которые не изменились. */
    fun stabilize(known: WallPost?, fresh: WallPost): WallPost {
        if (known == null) return fresh
        return fresh.copy(
            fromPhoto = if (sameImage(known.fromPhoto, fresh.fromPhoto)) known.fromPhoto else fresh.fromPhoto,
            photos = if (samePhotos(known.photos, fresh.photos)) keepUrls(known.photos, fresh.photos) else fresh.photos,
            repost = fresh.repost?.let { stabilize(known.repost, it) },
        )
    }

    /**
     * Те же фото: адреса картинок и их размеры — прежние (не мигают), остальное (страница фото) — свежее. Раньше бралась вся прежняя запись фото, и
     * новые поля (страница фото, размеры) у сохранённых записей не появлялись никогда (код ревью 3.59).
     */
    private fun keepUrls(known: List<PostPhoto>, fresh: List<PostPhoto>): List<PostPhoto> {
        val byKey = known.associateBy(::photoKey)
        // Размеры — вместе с адресом (по ним резервируется место под картинку именно этого адреса).
        return fresh.map { f -> byKey[photoKey(f)]?.let { k -> f.copy(url = k.url, full = k.full, width = k.width, height = k.height) } ?: f }
    }

    private fun photoKey(p: PostPhoto): String = p.open.ifEmpty { ImageKey.identity(p.full.ifEmpty { p.url }) }

    fun sameImage(a: String, b: String): Boolean = a.isNotEmpty() && b.isNotEmpty() && ImageKey.identity(a) == ImageKey.identity(b)

    /** Те же фото: по странице фото (`open`), а если её нет — по пути картинки; порядок не важен. */
    fun samePhotos(a: List<PostPhoto>, b: List<PostPhoto>): Boolean {
        return ListMerge.sameKeys(a, b) { p -> p.open.ifEmpty { ImageKey.identity(p.full.ifEmpty { p.url }) } }
    }

    /** Какие поля у тех же записей изменились — для журнала (не больше трёх записей): «ключ: фото+лайки». */
    fun describeChanges(before: List<WallPost>, after: List<WallPost>): String {
        val old = before.associateBy { it.key }
        val parts = ArrayList<String>()
        for (post in after) {
            val was = old[post.key] ?: continue
            val fields = buildList {
                if (was.text != post.text) add("текст")
                if (was.photos != post.photos) add("фото ${was.photos.size}→${post.photos.size}")
                if (was.fromPhoto != post.fromPhoto) add("аватар")
                if (was.fromName != post.fromName) add("автор")
                if (was.extras != post.extras) add("вложения")
                if (was.repost != post.repost) add("репост")
                if (was.pinned != post.pinned) add("закреплено")
                if (was.likes != post.likes || was.comments != post.comments || was.reposts != post.reposts || was.views != post.views) add("счётчики")
            }
            if (fields.isNotEmpty()) parts += "${post.key.take(24)}: ${fields.joinToString("+")}"
            if (parts.size >= 3) break
        }
        return parts.joinToString("; ")
    }
}
