package com.example.vkweb.messages

/**
 * Как выглядит строка диалога в списке (4.49). Без зависимостей от Android: проверяется обычными тестами.
 *
 * @property emphasized непрочитанный: имя — полужирным, последнее сообщение — основным цветом текста;
 * @property accentTime время — цветом акцента: непрочитанный и «зовёт» (заглушённый остаётся серым, как его счётчик); не на
 *   выделенной строке двух колонок — там синий на голубом читается хуже;
 * @property mutedIcon значок «уведомления выключены» — у заглушённого, прочитан он или нет.
 */
data class DialogRowStyle(val emphasized: Boolean, val accentTime: Boolean, val mutedIcon: Boolean) {
    companion object {
        /** [quiet] — диалог «не зовёт» (выключен в VK или своей настройкой, [com.example.vkweb.sync.DialogNotifications.quiet]). */
        fun of(item: DialogEntry, quiet: Boolean, selected: Boolean = false) = DialogRowStyle(
            emphasized = item.unread,
            accentTime = item.unread && !quiet && !selected,
            mutedIcon = quiet,
        )
    }
}
