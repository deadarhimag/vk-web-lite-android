package com.example.vkweb.owner

/** Нажатия на своём экране страницы; выполняет их главный экран. */
interface OwnerActions {
    /** Путь на сайте (/wall1_2, /id1) или полный адрес ссылки. */
    fun openLink(target: String)

    /**
      * Фото — в просмотр вместе с остальными фото записи ([gallery]: их можно листать), превью видео — на страницу
      * видео.
     */
    fun openPhoto(photo: PostPhoto, gallery: List<PostPhoto>)

    /** Поделиться записью: ссылка на неё и начало текста — в другое приложение; фото записи — картинками. */
    fun share(post: WallPost)

    /** Лайк записи: поставить, если не стоит, иначе снять. */
    fun like(post: WallPost)

    /** Трек [track] из записи [post]: играть (очередь — треки записи). */
    fun playAudio(post: WallPost, track: PostExtra) = Unit

    /** Есть ли сеть: без неё лайк не ставится (экран записи не должен показывать его поставленным). */
    val isOnline: Boolean get() = true

    /** Запись: свой экран с комментариями; нет его - страница записи на сайте. */
    fun openPost(post: WallPost) = openLink(post.url)

    /** Фото владельца страницы на весь экран. */
    fun openAvatar(url: String)

    /** «Написать» владельцу страницы. */
    fun write(header: OwnerHeader)
}
