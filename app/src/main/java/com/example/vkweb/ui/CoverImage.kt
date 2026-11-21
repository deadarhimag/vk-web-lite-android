package com.example.vkweb.ui

import android.graphics.drawable.Drawable
import android.widget.ImageView
import com.example.vkweb.offline.ImageCache

/**
 * Квадратная картинка в строке списка (обложка трека или плейлиста, аватар): размер в dp, скругление и загрузка через
 * [ImageCache]. Общее для адаптеров музыки, плейлистов, друзей и записей — раньше каждый повторял одни и те же три
 * строки (кэш, размер в пикселях, скругление).
 */
class CoverImage(private val view: ImageView, sizeDp: Int, radiusDp: Int = 0) {
    private val images = ImageCache.get(view.context)
    private val px = Views.dp(view, sizeDp)

    init {
        if (radiusDp > 0) Views.clipRounded(view, Views.dp(view, radiusDp).toFloat())
    }

    /** Загрузить [url]; пока нет картинки (или адрес пуст) — [placeholder]. */
    fun load(url: String?, placeholder: () -> Drawable? = { null }) = images.load(view, url, px, placeholder)
}
