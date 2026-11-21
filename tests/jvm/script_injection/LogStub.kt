package com.example.vkweb.diag

enum class LogTag { PAGE }
object AppLog {
    val messages = ArrayList<String>()
    fun w(tag: LogTag, message: String, vararg fields: Pair<String, Any?>) { messages += message }
}
