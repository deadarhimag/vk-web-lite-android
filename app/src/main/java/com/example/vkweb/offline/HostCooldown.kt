package com.example.vkweb.offline

/**
 * Серверы картинок, которые не отвечают. Без зависимостей от Android: проверяется обычными тестами.
 *
 * Бывает, что один сервер VK недоступен из сети пользователя (в отчёте: sun9-62 — 28 раз «failed to connect» по 20 с).
 * Каждая такая попытка занимает поток загрузки, и в очередь за ней встают картинки с рабочих серверов — страница
 * сообщества «не прогружается». Поэтому сервер, к которому не удалось подключиться, на [cooldownMs] пропускается:
 * его картинки сразу получают заглушку, остальные грузятся без очереди.
 */
class HostCooldown(private val cooldownMs: Long = DEFAULT_COOLDOWN_MS) {

    private val until = HashMap<String, Long>()

    /** Сервер сейчас пропускается. */
    @Synchronized
    fun isBlocked(host: String, now: Long): Boolean {
        val end = until[host] ?: return false
        if (now < end) return true
        until.remove(host)
        return false
    }

    /** Не удалось подключиться. @return true — сервер только что начал пропускаться (записать в журнал один раз). */
    @Synchronized
    fun onConnectFailure(host: String, now: Long): Boolean {
        val wasBlocked = (until[host] ?: 0L) > now
        until[host] = now + cooldownMs
        return !wasBlocked
    }

    /** Сервер ответил: пропускать незачем. */
    @Synchronized
    fun onSuccess(host: String) {
        until.remove(host)
    }

    companion object {
        const val DEFAULT_COOLDOWN_MS = 3 * 60_000L
    }
}
