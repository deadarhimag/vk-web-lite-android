package com.example.vkweb.web

import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import java.io.ByteArrayInputStream

/**
 * Ответ на запрос, который не нужно загружать (реклама, счётчики). Общий для главного окна ([VkWebViewClient]) и
 * окон чтения сообщений ([com.example.vkweb.messages.ReaderPageClient]): раньше блокировка была только в главном.
 */
object BlockedResponse {

    private const val API_PATH = "/method/"

    /** Запрос данных (/method/…) получает «успех», а не пустоту: скрипты VK не будут слать его снова. */
    fun of(request: WebResourceRequest): WebResourceResponse =
        if (request.url.path.orEmpty().startsWith(API_PATH)) acknowledged(request) else empty()

    /**
     * Ответ «принято». Запрос идёт на другой домен VK, поэтому в ответе нужны заголовки CORS — иначе браузер всё
     * равно сочтёт его ошибкой.
     */
    private fun acknowledged(request: WebResourceRequest): WebResourceResponse {
        val headers = HashMap<String, String>()
        request.requestHeaders["Origin"]?.let {
            headers["Access-Control-Allow-Origin"] = it
            headers["Access-Control-Allow-Credentials"] = "true"
            headers["Vary"] = "Origin"
        }
        request.requestHeaders["Access-Control-Request-Headers"]?.let { headers["Access-Control-Allow-Headers"] = it }
        headers["Access-Control-Allow-Methods"] = "GET, POST, OPTIONS"
        return WebResourceResponse(
            "application/json", "utf-8", 200, "OK", headers, ByteArrayInputStream("{\"response\":1}".toByteArray()),
        )
    }

    /** Новый объект на каждый запрос: поток ответа одноразовый. */
    private fun empty() = WebResourceResponse(
        "text/plain", "utf-8", 204, "No Content", emptyMap(), ByteArrayInputStream(ByteArray(0)),
    )
}
