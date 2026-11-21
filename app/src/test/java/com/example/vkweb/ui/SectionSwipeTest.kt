package com.example.vkweb.ui

import com.example.vkweb.config.Tab
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class SectionSwipeTest {

    @Test fun `влево — следующий раздел, вправо — предыдущий`() {
        assertEquals(SectionSwipe.Direction.NEXT, SectionSwipe.direction(-300f, 20f, 200, 100f))
        assertEquals(SectionSwipe.Direction.PREVIOUS, SectionSwipe.direction(300f, -20f, 200, 100f))
    }

    @Test fun `не свайп — коротко, наискосок или медленно`() {
        assertNull("короче порога", SectionSwipe.direction(-80f, 0f, 100, 100f))
        assertNull("наискосок — прокрутка списка", SectionSwipe.direction(-300f, 200f, 200, 100f))
        assertNull("медленно — тянут или читают", SectionSwipe.direction(-300f, 0f, 1500, 100f))
    }

    @Test fun `соседи по меню, за краем — ничего`() {
        assertEquals(Tab.GROUPS, SectionSwipe.target(Tab.FRIENDS, SectionSwipe.Direction.NEXT))
        assertEquals(Tab.MESSAGES, SectionSwipe.target(Tab.FRIENDS, SectionSwipe.Direction.PREVIOUS))
        assertNull(SectionSwipe.target(Tab.MESSAGES, SectionSwipe.Direction.PREVIOUS))
        assertNull(SectionSwipe.target(Tab.VIDEO, SectionSwipe.Direction.NEXT))
        assertNull(SectionSwipe.target(null, SectionSwipe.Direction.NEXT))
    }

    @Test fun `соседняя вкладка — без перехода по кругу, без отмеченной — первая (4_19)`() {
        assertEquals(1, SectionSwipe.neighbor(0, 3, SectionSwipe.Direction.NEXT))
        assertEquals(1, SectionSwipe.neighbor(2, 3, SectionSwipe.Direction.PREVIOUS))
        assertNull(SectionSwipe.neighbor(2, 3, SectionSwipe.Direction.NEXT))
        assertNull(SectionSwipe.neighbor(0, 3, SectionSwipe.Direction.PREVIOUS))
        assertEquals(0, SectionSwipe.neighbor(-1, 3, SectionSwipe.Direction.NEXT))
        assertNull("одна вкладка — листать нечего", SectionSwipe.neighbor(0, 1, SectionSwipe.Direction.NEXT))
    }
}
