package com.example.vkweb.video

/**
 * Поворот экрана под видео (3.88): горизонтальный ролик — в альбомную ориентацию сам, даже при выключенном
 * автоповороте (как в приложении VK); вертикальный и квадратный — как у системы. Ручной выбор кнопкой главнее.
 * Без зависимостей от Android: проверяется обычными тестами.
 */
object VideoOrientation {
    enum class Mode { SYSTEM, LANDSCAPE, PORTRAIT }

    /** Запас, чтобы почти квадратное (4:3 повёрнутое, 1:1 с полосами) не крутило экран. */
    private const val WIDE_RATIO = 1.1f

    fun isWide(width: Int, height: Int): Boolean = width > 0 && height > 0 && width > height * WIDE_RATIO

    /** Что ставить: ручной выбор, иначе по кадру (если включено в настройках), иначе как у системы. */
    fun choose(width: Int, height: Int, autoRotate: Boolean, manual: Mode?): Mode = when {
        manual != null -> manual
        autoRotate && isWide(width, height) -> Mode.LANDSCAPE
        else -> Mode.SYSTEM
    }

    /** Кнопка: альбомная ↔ портретная (от того, что на экране сейчас). */
    fun toggled(isLandscapeNow: Boolean): Mode = if (isLandscapeNow) Mode.PORTRAIT else Mode.LANDSCAPE
}
