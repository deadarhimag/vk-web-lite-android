package com.example.vkweb.messages

import com.example.vkweb.messages.ChatScrollPolicy.Action
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Прокрутка переписки при обновлении (4.67): правило, которое раньше было условиями внутри экрана сообщений. */
class ChatScrollPolicyTest {

    private fun decide(
        previousEmpty: Boolean = false, newAtEnd: Boolean = false, wasAtBottom: Boolean = false,
        idle: Boolean = true, anchorFound: Boolean = true, prepended: Boolean = false,
    ) = ChatScrollPolicy.decide(previousEmpty, newAtEnd, wasAtBottom, idle, anchorFound, prepended)

    @Test fun `первая порция — к последнему, что бы ни было`() {
        assertEquals(Action.FIRST_PAGE, decide(previousEmpty = true, idle = false, anchorFound = false))
    }

    @Test fun `новые снизу у читающего внизу — к последнему, даже пока листает`() {
        assertEquals(Action.NEW_AT_BOTTOM, decide(newAtEnd = true, wasAtBottom = true, idle = false))
    }

    @Test fun `внизу и выше что-то изменилось — остаёмся внизу (прыжки 4_0)`() {
        assertEquals(Action.STAY_AT_BOTTOM, decide(wasAtBottom = true, prepended = true))
    }

    @Test fun `читает выше — строка на месте, отдельно после догрузки старых`() {
        assertEquals(Action.KEEP_ROW_AFTER_OLDER, decide(prepended = true))
        assertEquals(Action.KEEP_ROW, decide())
    }

    @Test fun `листают — не мешаем (отчёт 3_59)`() {
        assertEquals(Action.NONE, decide(idle = false))
        assertEquals(Action.NONE, decide(prepended = true, idle = false))
        // Строки, которую читали, больше нет — держаться не за что.
        assertEquals(Action.NONE, decide(anchorFound = false))
        // Новые снизу, а человек читает выше — к последнему не тянем.
        assertEquals(Action.KEEP_ROW, decide(newAtEnd = true))
    }

    @Test fun `плавно — от одного до пяти новых`() {
        assertFalse(ChatScrollPolicy.smooth(0))
        assertTrue(ChatScrollPolicy.smooth(1))
        assertTrue(ChatScrollPolicy.smooth(5))
        assertFalse(ChatScrollPolicy.smooth(6))
        assertFalse(ChatScrollPolicy.smooth(-2))
    }

    @Test fun `после отправки - к последнему, даже если список не внизу, а листают - не мешать`() {
        val follow = ChatScrollPolicy.decide(
            previousEmpty = false, newAtEnd = true, wasAtBottom = false, idle = true, anchorFound = true, prepended = false, followSent = true,
        )
        assertEquals(ChatScrollPolicy.Action.FOLLOW_SENT, follow)
        val dragging = ChatScrollPolicy.decide(
            previousEmpty = false, newAtEnd = true, wasAtBottom = false, idle = false, anchorFound = true, prepended = false, followSent = true,
        )
        assertEquals(ChatScrollPolicy.Action.NONE, dragging)
        val noFollow = ChatScrollPolicy.decide(
            previousEmpty = false, newAtEnd = false, wasAtBottom = false, idle = true, anchorFound = true, prepended = false,
        )
        assertEquals(ChatScrollPolicy.Action.KEEP_ROW, noFollow)
    }
}
