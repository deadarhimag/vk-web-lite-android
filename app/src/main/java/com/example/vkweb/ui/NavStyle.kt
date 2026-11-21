package com.example.vkweb.ui

/**
 * Как показывать разделы (4.50). Без зависимостей от Android: проверяется обычными тестами.
 *
 *  - [PINNED] — широкий экран и закрепление включено: меню всегда слева (как раньше);
 *  - [BOTTOM] — у Android есть жест «Назад» от края экрана: свайп от левого края забирает система, и боковое меню
 *    открывалось через раз. Разделы — нижней панелью, остальное — пунктом «Ещё» (то же боковое меню, кнопкой);
 *  - [DRAWER] — жеста от края нет (три кнопки или жест выключен): боковое меню свайпом, как раньше, и всегда кнопка ☰;
 *  - [RAIL] (4.58) — средняя ширина ([RAIL_MIN_WIDTH_DP] и шире, но меню не закреплено): узкая полоса значков слева —
 *    те же разделы и «Ещё», что у нижней панели (так советует Material для планшетов и развёрнутых складных).
 *
 * Вид можно выбрать в настройках ([Choice], 4.64): «Автоматически» — как выше; «Нижняя панель» или «Боковое меню» —
 * всегда этот вид, при любой навигации Android и ширине. Закрепление на широком экране главнее выбора.
 */
enum class NavStyle {
    PINNED, BOTTOM, DRAWER, RAIL;

    /** Выбор в настройках (4.64): Настройки → Навигация → «Вид меню». */
    enum class Choice {
        AUTO, BOTTOM, DRAWER;

        companion object {
            val DEFAULT = AUTO

            fun fromName(name: String?): Choice = entries.firstOrNull { it.name == name } ?: DEFAULT
        }
    }

    companion object {
        /** Сколько разделов на нижней панели: пятый пункт — «Ещё». */
        const val BOTTOM_SECTIONS = 4

        /** С этой ширины (dp) — полоса значков слева вместо нижней панели или бокового меню. */
        const val RAIL_MIN_WIDTH_DP = 600

        /** Значение Settings.Secure «navigation_mode» у жестовой навигации Android 10+. */
        const val NAVIGATION_MODE_GESTURAL = 2

        fun pick(
            permanent: Boolean,
            edgeBackGesture: Boolean,
            widthDp: Int = 0,
            choice: Choice = Choice.AUTO,
        ): NavStyle = when {
            permanent -> PINNED
            choice == Choice.BOTTOM -> BOTTOM
            choice == Choice.DRAWER -> DRAWER
            widthDp >= RAIL_MIN_WIDTH_DP -> RAIL
            edgeBackGesture -> BOTTOM
            else -> DRAWER
        }

        /**
         * Есть ли жест «Назад» от края. Главное — зоны системных жестов окна: у жеста «Назад» они есть и слева, и справа
         * (у навигации кнопками — нет; боковая панель кнопок в альбомной ориентации даёт зону только с одной стороны).
         * Пока окна нет (зоны неизвестны, null) — по настройке Android «navigation_mode». До Android 10 жеста нет.
         */
        fun edgeBackGesture(sdk: Int, gestureLeft: Int?, gestureRight: Int?, navigationMode: Int): Boolean = when {
            sdk < 29 -> false
            gestureLeft != null && gestureRight != null -> gestureLeft > 0 && gestureRight > 0
            else -> navigationMode == NAVIGATION_MODE_GESTURAL
        }

        /** Разделы нижней панели: первые [slots] из [preferred], которые не скрыты в настройках. */
        fun <T> bottomSections(preferred: List<T>, hidden: Set<T>, slots: Int = BOTTOM_SECTIONS): List<T> =
            preferred.filter { it !in hidden }.take(slots)

        /**
         * Какой пункт нижней панели отметить: раздел, если он на панели; иначе «Ещё» ([more]) — раздел из бокового меню;
         * null — страница вне разделов (человек, запись): отметка не меняется.
         */
        fun <T> bottomSelection(current: T?, inOtherSection: Boolean, onBar: List<T>, more: T): T? = when {
            current != null && current in onBar -> current
            current != null || inOtherSection -> more
            else -> null
        }
    }
}
