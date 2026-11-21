package com.example.vkweb.offline

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SameKeysTest {
    @Test fun `одинаковые наборы в любом порядке — те же`() {
        assertTrue(ListMerge.sameKeys(listOf("b", "a", "c"), listOf("c", "b", "a")) { it })
    }

    @Test fun `другое число или другой ключ — не те же`() {
        assertFalse(ListMerge.sameKeys(listOf("a"), listOf("a", "a")) { it })
        assertFalse(ListMerge.sameKeys(listOf("a", "b"), listOf("a", "c")) { it })
    }

    @Test fun `пустые списки совпадают`() {
        assertTrue(ListMerge.sameKeys(emptyList<String>(), emptyList()) { it })
    }

    @Test fun `ключ берётся из функции`() {
        assertTrue(ListMerge.sameKeys(listOf(1 to "x", 2 to "y"), listOf(2 to "other", 1 to "other")) { it.first.toString() })
    }
}
