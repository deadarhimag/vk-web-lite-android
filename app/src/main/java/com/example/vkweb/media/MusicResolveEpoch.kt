package com.example.vkweb.media

/** Main-thread identity of one hidden-page lookup. A result is consumed once, only by its own operation. */
class MusicResolveEpoch(private val prefix: String = java.lang.Long.toString(System.nanoTime(), 36)) {
    private var sequence = 0L
    var token: String = ""
        private set
    private var pending = HashSet<String>()
    fun start(ids: Collection<String>): String {
        sequence++; token = "$prefix-$sequence"; pending = HashSet(ids); return token
    }
    fun take(operation: String, id: String): Boolean = operation.isNotEmpty() && operation == token && pending.remove(id)
    fun invalidate() { sequence++; token = ""; pending.clear() }
}
