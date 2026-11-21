package com.example.vkweb.web

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SessionLifetimeTest {

    private val def = 20 * 60_000L

    @Test fun `без наблюдений — срок по умолчанию`() {
        assertEquals(def, SessionLifetime(def).lifetimeMs())
    }

    @Test fun `обновлять заранее, за 80 процентов срока`() {
        val s = SessionLifetime(def)
        // 20 мин × 80 % = 16 мин
        assertEquals(16 * 60_000L, s.refreshDueAt(0L))
    }

    @Test fun `измерение протухания уменьшает срок`() {
        val s = SessionLifetime(def)
        s.observeExpiry(refreshedAtMs = 0L, nowMs = 10 * 60_000L)
        assertEquals(10 * 60_000L, s.lifetimeMs())
        assertEquals(8 * 60_000L, s.refreshDueAt(0L))
    }

    @Test fun `берётся самый короткий из наблюдений`() {
        val s = SessionLifetime(def)
        s.observeExpiry(0L, 30 * 60_000L)
        s.observeExpiry(0L, 12 * 60_000L)
        s.observeExpiry(0L, 40 * 60_000L)
        assertEquals(12 * 60_000L, s.lifetimeMs())
    }

    @Test fun `слишком короткое не в счёт — это сбой сети, а не токен`() {
        val s = SessionLifetime(def)
        s.observeExpiry(0L, 60_000L) // 1 мин < минимума
        assertEquals(def, s.lifetimeMs())
    }

    @Test fun `срок держится в коридоре`() {
        val low = SessionLifetime(60_000L) // меньше минимума
        assertEquals(SessionLifetime.MIN_LIFETIME_MS, low.lifetimeMs())
        val high = SessionLifetime(100 * 60 * 60_000L) // больше максимума
        assertEquals(SessionLifetime.MAX_LIFETIME_MS, high.lifetimeMs())
    }

    @Test fun `наблюдения переживают перезапуск`() {
        val s = SessionLifetime(def)
        s.observeExpiry(0L, 15 * 60_000L)
        val restored = SessionLifetime.fromState(s.toState(), def)
        assertEquals(15 * 60_000L, restored.lifetimeMs())
    }

    @Test fun `хранится не больше пяти наблюдений`() {
        val s = SessionLifetime(def)
        repeat(8) { i -> s.observeExpiry(0L, (10 + i) * 60_000L) }
        assertTrue(s.toState().size <= 5)
    }

    @Test fun `время до обновления не отрицательное`() {
        val s = SessionLifetime(def)
        assertEquals(0L, s.delayUntilDueMs(refreshedAtMs = 0L, nowMs = 100 * 60_000L))
    }
}
