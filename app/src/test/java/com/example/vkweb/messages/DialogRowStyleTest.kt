package com.example.vkweb.messages

import org.junit.Assert.assertEquals
import org.junit.Test

/** Строка диалога в списке (4.49): выделение непрочитанных и значок выключенных уведомлений. */
class DialogRowStyleTest {

    private fun d(unread: Boolean) = DialogEntry(peerId = "1", title = "Аня", snippet = "привет", time = "12:30", unread = unread, online = false)

    @Test fun `непрочитанный и зовёт — выделен, время синим, без значка`() =
        assertEquals(DialogRowStyle(emphasized = true, accentTime = true, mutedIcon = false), DialogRowStyle.of(d(true), quiet = false))

    @Test fun `непрочитанный заглушённый — выделен, время серым, со значком`() =
        assertEquals(DialogRowStyle(emphasized = true, accentTime = false, mutedIcon = true), DialogRowStyle.of(d(true), quiet = true))

    @Test fun `прочитанный заглушённый — значок виден`() =
        assertEquals(DialogRowStyle(emphasized = false, accentTime = false, mutedIcon = true), DialogRowStyle.of(d(false), quiet = true))

    @Test fun `прочитанный — как обычно`() =
        assertEquals(DialogRowStyle(emphasized = false, accentTime = false, mutedIcon = false), DialogRowStyle.of(d(false), quiet = false))

    @Test fun `на выделенной строке время не синее`() =
        assertEquals(false, DialogRowStyle.of(d(true), quiet = false, selected = true).accentTime)
}
