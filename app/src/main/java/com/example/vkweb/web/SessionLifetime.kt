package com.example.vkweb.web

/**
 * Насколько «свежей» считается веб-сессия VK — по наблюдениям, а не по зашитому числу. Без зависимостей от Android:
 * проверяется обычными тестами, хранится между запусками ([toState]/[fromState]).
 *
 * Ответы API VK подписаны токенами и ключами, которые скрипты VK держат в своих куках; у приложения прямого доступа
 * к ним нет (это секрет аккаунта), но видно, когда они протухли: ответ приходит с 401/403, либо свой экран не
 * дожидается данных. Между успешным обновлением и первым таким признаком сессия точно была жива — это и есть
 * измеренный срок её жизни. Приложение запоминает самый короткий такой срок за несколько последних наблюдений
 * и обновляет сессию заранее, за [SAFETY_FRACTION] от него, а не раз в фиксированные 20 минут.
 *
 * Пока наблюдений нет — [defaultLifetimeMs] (осторожное начальное значение из настройки/конфига). Срок держится
 * в коридоре [[MIN_LIFETIME_MS]; [MAX_LIFETIME_MS]]: короче незачем (лишние загрузки), дольше рискованно.
 */
class SessionLifetime(
    private val defaultLifetimeMs: Long,
    /** Последние измеренные сроки жизни, мс (новые в конце). */
    observations: List<Long> = emptyList(),
) {
    private val samples = ArrayDeque(observations.takeLast(MAX_SAMPLES))

    /**
     * Сессия только что обновлена в [atMs]: вызвать, чтобы отсчитывать от этого момента срок жизни.
     * Отдельного состояния не требует — срок считается от переданного времени в [refreshDueAt].
     */

    /**
     * Сессия жила с [refreshedAtMs] и сейчас ([nowMs]) обнаружен признак, что она протухла (401/403 или свой экран
     * не дождался данных). Прожитое время — измерение; слишком короткое (меньше [MIN_LIFETIME_MS]) не в счёт:
     * это, скорее, случайная ошибка сети, а не истёкший токен.
     */
    fun observeExpiry(refreshedAtMs: Long, nowMs: Long) {
        val lived = nowMs - refreshedAtMs
        if (lived < MIN_LIFETIME_MS) return
        samples.addLast(lived.coerceAtMost(MAX_LIFETIME_MS))
        while (samples.size > MAX_SAMPLES) samples.removeFirst()
    }

    /** Насколько сессия считается живой, мс: самое короткое из наблюдений (с запасом), иначе — значение по умолчанию. */
    fun lifetimeMs(): Long {
        val base = samples.minOrNull() ?: return defaultLifetimeMs.coerceIn(MIN_LIFETIME_MS, MAX_LIFETIME_MS)
        return base.coerceIn(MIN_LIFETIME_MS, MAX_LIFETIME_MS)
    }

    /** Когда пора обновить сессию, обновлённую в [refreshedAtMs]: заранее, за [SAFETY_FRACTION] от срока жизни. */
    fun refreshDueAt(refreshedAtMs: Long): Long =
        refreshedAtMs + (lifetimeMs() * SAFETY_FRACTION / 100).coerceAtLeast(MIN_LIFETIME_MS)

    /** Через сколько от [nowMs] пора обновлять (0 — уже пора). */
    fun delayUntilDueMs(refreshedAtMs: Long, nowMs: Long): Long =
        (refreshDueAt(refreshedAtMs) - nowMs).coerceAtLeast(0)

    /** Сроки для сохранения между запусками. */
    fun toState(): List<Long> = samples.toList()

    companion object {
        /** Обновлять по достижении этой доли измеренного срока (80 %): запас на разброс и задержку загрузки. */
        const val SAFETY_FRACTION = 80

        /** Короче смысла нет: частые перезагрузки сами жгут батарею. */
        const val MIN_LIFETIME_MS = 5 * 60_000L

        /** Дольше держать свежей не рискуем, даже если наблюдения показали больше. */
        const val MAX_LIFETIME_MS = 6 * 60 * 60_000L

        private const val MAX_SAMPLES = 5

        fun fromState(values: List<Long>, defaultLifetimeMs: Long) = SessionLifetime(defaultLifetimeMs, values)
    }
}
