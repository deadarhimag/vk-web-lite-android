package com.example.vkweb

import java.util.concurrent.ExecutorService
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit

/**
 * Фоновые потоки, которые сами завершаются после простоя.
 *
 * `Executors.newSingleThreadExecutor()` держит свой поток вечно, пока его не закроют. Многие объекты приложения живут
 * недолго (канал экрана сообщений, фоновая проверка раз в 15–60 минут, экран настроек), и каждый оставлял после себя
 * спящий поток до конца жизни процесса. Закрывать пул явно опасно: сообщение страницы, пришедшее после закрытия, уронило
 * бы приложение (RejectedExecutionException). Здесь поток просто завершается после [KEEP_ALIVE_SEC] без работы и
 * создаётся заново при следующей задаче; порядок задач у последовательного пула сохраняется (поток всегда один).
 */
object Background {

    const val KEEP_ALIVE_SEC = 30L

    /** Последовательный пул: задачи выполняются по одной, в порядке постановки. */
    fun serial(name: String, priority: Int = Thread.NORM_PRIORITY, keepAliveSec: Long = KEEP_ALIVE_SEC): ExecutorService =
        pool(name, 1, priority, keepAliveSec)

    /** Пул из [threads] потоков. */
    fun pool(
        name: String,
        threads: Int,
        priority: Int = Thread.NORM_PRIORITY,
        keepAliveSec: Long = KEEP_ALIVE_SEC,
    ): ExecutorService = ThreadPoolExecutor(
        threads, threads, keepAliveSec, TimeUnit.SECONDS, LinkedBlockingQueue(),
    ) { runnable ->
        Thread(runnable, name).apply {
            isDaemon = true
            this.priority = priority
        }
    }.apply { allowCoreThreadTimeOut(true) }
}
