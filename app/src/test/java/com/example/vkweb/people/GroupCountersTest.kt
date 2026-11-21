package com.example.vkweb.people

import org.junit.Assert.assertEquals
import org.junit.Test

class GroupCountersTest {

    @Test fun `открыли — счётчик пропал, новые записи — снова видны`() {
        val seen = GroupCounters.remember(emptyMap(), 5, 13)
        assertEquals(0, GroupCounters.shown(13, seen[5] ?: 0))
        assertEquals(2, GroupCounters.shown(15, seen[5] ?: 0))
    }

    @Test fun `VK сам сбросил — показываем как есть`() {
        assertEquals(1, GroupCounters.shown(1, 13))
        assertEquals(0, GroupCounters.shown(0, 13))
    }

    @Test fun `сохранение`() {
        val m = GroupCounters.remember(GroupCounters.remember(emptyMap(), 1, 3), 2, 7)
        assertEquals(m, GroupCounters.decode(GroupCounters.encode(m)))
        assertEquals(emptyMap<Long, Int>(), GroupCounters.decode("мусор"))
        assertEquals(mapOf(2L to 7), GroupCounters.remember(m, 1, 0))
    }
}
