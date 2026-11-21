package com.example.vkweb.config

/**
 * Селекторы для чтения страниц сообщений VK в облегчённом режиме.
 * Подобраны предположительно — реальную вёрстку снимайте через chrome://inspect
 * и правьте здесь; это единственное место, которое нужно трогать при смене вёрстки VK.
 *
 * Каждое поле — CSS-селектор (можно несколько через запятую).
 */
object ReaderConfig {

    /** Есть на странице входа и нет в мессенджере — признак «не авторизован». */
    const val LOGIN_MARKER = "form[action*=\"login\"], input[name=\"pass\"], .vkc__Auth, #login_form, [data-test-id=\"enter-password\"]"

    /** Список диалогов (reforged-мессенджер, m.vk.ru/mail). Диалог — ссылка на /mail/convo/{peer}. */
    object Dialogs {
        const val ROW = "a[href*=\"/mail/convo/\"], .im-page--dialogs-item, .dl_item"
        const val LINK = "a[href*=\"/mail/convo/\"]"
        const val TITLE = ".ConvoTitle__author, .PeerTitle__title, .im_dialog_title, .dl_title"
        const val SNIPPET = ".ConvoLastMessage, .im_dialog_text, .dl_body"
        const val TIME = ".ConvoInfo__date, .im_dialog_date, .dl_date, time"
        const val UNREAD = ".UnreadCounter, .im_dialog_unread, .unread"
        const val ONLINE = ".MEOnlineBadge, .im_online, .online"
    }

    /** Переписка (reforged). Внимание: список виртуализирован — в DOM только видимые сообщения. */
    object History {
        const val ROW = ".ConvoHistory__messageBlock, .im-mess, [data-msgid]"
        const val TEXT = ".MessageText, .ConvoMessageWithoutBubble__text, .im-mess--text"
        const val OUT_MARKER = ".ConvoMessage_out, .ConvoMessageWithBubble_out, .im-mess_out, .out"
        const val TIME = ".ConvoMessageInfoWithoutBubbles__date, .ConvoMessageInfo__date, .im-mess--time, time"
        const val TITLE = ".ConvoTitle__author, .ConvoHeader__title, .im-page--title, title"
    }

    /** Поле ввода — contenteditable, а не textarea; кнопка отправки помечена data-testid. */
    object Compose {
        const val INPUT = "[data-testid=\"vkme_composer_input\"], .ComposerInput__input[contenteditable], .im-chat-input textarea, textarea"
        const val SEND = "[data-testid=\"vkme_composer_send\"], .ConvoComposer__sendButton, button[type=\"submit\"]"
    }
}
