package com.example.vkweb.messages

/** Команды и освобождение идут в одной очереди. Закрытая сессия больше не обновляет интерфейс. */
internal class VoicePlaybackTasks(
    private val worker: (Runnable) -> Unit,
    private val main: (Runnable) -> Unit,
) {
    @Volatile private var closed = false

    fun command(action: () -> Unit) {
        worker(Runnable { if (!closed) action() })
    }

    fun result(action: () -> Unit) {
        main(Runnable { if (!closed) action() })
    }

    /** Вызывается главным потоком после снятия ссылки на текущую сессию. */
    fun close(release: () -> Unit) {
        if (closed) return
        closed = true
        worker(Runnable { release() })
    }
}
