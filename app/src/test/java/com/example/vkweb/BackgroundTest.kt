package com.example.vkweb

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Collections
import java.util.concurrent.CountDownLatch
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit

class BackgroundTest {

    @Test fun `последовательный пул сохраняет порядок`() {
        val pool = Background.serial("test-order")
        val seen = Collections.synchronizedList(ArrayList<Int>())
        val done = CountDownLatch(1)
        repeat(500) { i -> pool.execute { seen += i } }
        pool.execute { done.countDown() }
        assertTrue(done.await(5, TimeUnit.SECONDS))
        assertEquals((0 until 500).toList(), seen.toList())
    }

    @Test fun `поток завершается после простоя и создаётся снова`() {
        val pool = Background.serial("test-idle", keepAliveSec = 1) as ThreadPoolExecutor
        val first = CountDownLatch(1)
        pool.execute { first.countDown() }
        assertTrue(first.await(5, TimeUnit.SECONDS))
        val deadline = System.currentTimeMillis() + 5_000
        while (pool.poolSize > 0 && System.currentTimeMillis() < deadline) Thread.sleep(50)
        assertEquals("после простоя потоков не осталось", 0, pool.poolSize)
        val second = CountDownLatch(1)
        pool.execute { second.countDown() }
        assertTrue("новая задача выполнена (без RejectedExecutionException)", second.await(5, TimeUnit.SECONDS))
    }
}
