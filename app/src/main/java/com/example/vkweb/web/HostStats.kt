package com.example.vkweb.web

/**
 * С какими серверами работает страница и как они отвечают — с начала жизни процесса. Считают оба клиента WebView
 * (главное окно и окна чтения сообщений): запросы, заблокированные (реклама, счётчики) и не загрузившиеся.
 * Попадает в отчёт: по нему видно, какие серверы вообще есть и какие из них не отвечают. Только имена серверов.
 */
object HostStats {
    val requests = HostCounter()
    val blocked = HostCounter()
    val errors = HostCounter()

    /** Строки отчёта: сервер, запросов, заблокировано, ошибок — самые частые и все с ошибками. */
    fun lines(limit: Int = 14): List<String> {
        val hosts = LinkedHashSet<String>()
        requests.top(limit).forEach { hosts += it.first }
        errors.top(limit).forEach { hosts += it.first }
        blocked.top(limit).forEach { hosts += it.first }
        return hosts.sortedByDescending { requests[it] + errors[it] + blocked[it] }.map { host ->
            buildString {
                append(host).append(": запросов ").append(requests[host])
                if (blocked[host] > 0) append(", заблокировано ").append(blocked[host])
                if (errors[host] > 0) append(", ошибок ").append(errors[host])
            }
        }
    }
}
