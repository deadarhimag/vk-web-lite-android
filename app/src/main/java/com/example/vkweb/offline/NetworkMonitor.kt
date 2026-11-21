package com.example.vkweb.offline

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import com.example.vkweb.diag.AppLog
import com.example.vkweb.diag.LogTag

/**
 * Есть ли сеть, и уведомление, когда она появилась. Колбэк вызывается в главном потоке.
 * Регистрировать на время жизни экрана: [start] в onStart, [stop] в onStop.
 */
class NetworkMonitor(
    context: Context,
    private val onOnline: () -> Unit,
    private val onOffline: () -> Unit = {},
) {

    private val connectivity = context.getSystemService(ConnectivityManager::class.java)
    private val main = Handler(Looper.getMainLooper())
    private var registered = false
    private var lastAvailable = ""
    private var lastAvailableAt = 0L

    /**
     * Последнее, о чём сообщили экрану: «есть сеть» или «нет». Только главный поток.
     * Экран получает [onOnline]/[onOffline] только при смене, а не на каждое событие системы.
     */
    private var reportedOnline: Boolean? = null

    private val callback = object : ConnectivityManager.NetworkCallback() {
        override fun onAvailable(network: Network) {
            logAvailable(kindOf(network))
            // Сеть часто появляется ещё не проверенной системой: тогда «есть сеть» придёт из onCapabilitiesChanged.
            main.post { reportIfChanged(isOnline) }
        }

        /**
         * Система проверила сеть (VALIDATED) — обычно через секунду-другую после onAvailable. Раньше этот момент
         * не отслеживался: onAvailable приходил с непроверенной сетью, экран решал «сети нет», и после включения
         * Wi-Fi страница и свои экраны так и не обновлялись.
         */
        override fun onCapabilitiesChanged(network: Network, caps: NetworkCapabilities) {
            // Колбэк сети по умолчанию: caps — её возможности, без лишних обращений к системе.
            // Приходит часто (меняется скорость связи) — экран узнаёт только о смене «есть / нет».
            val online = caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) &&
                caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
            main.post { reportIfChanged(online) }
        }

        override fun onLost(network: Network) {
            // Пропала одна сеть — другая может остаться (Wi-Fi → мобильная), поэтому проверяем.
            main.post {
                val online = isOnline
                AppLog.i(LogTag.NET, "сеть пропала", "осталась другая" to online)
                reportIfChanged(online)
            }
        }
    }

    private fun reportIfChanged(online: Boolean) {
        if (!registered) return
        if (online == reportedOnline) return
        val first = reportedOnline == null
        reportedOnline = online
        // Первое событие после start() — текущее состояние. «Есть сеть» передаётся: пока экран был в фоне, сеть могла
        // появиться, и ему нужно снять пометку «нет сети». «Нет сети» экран узнаёт сам при открытии.
        when {
            online -> onOnline()
            !first -> onOffline()
        }
    }

    /** Система сообщает о каждой сети отдельно (Wi-Fi и проверенная Wi-Fi): одинаковую подряд записываем один раз. */
    private fun logAvailable(kind: String) {
        val now = SystemClock.elapsedRealtime()
        if (kind == lastAvailable && now - lastAvailableAt < DUPLICATE_WINDOW_MS) return
        lastAvailable = kind
        lastAvailableAt = now
        AppLog.i(LogTag.NET, "сеть появилась", "тип" to kind)
    }

    private fun kindOf(network: Network): String {
        val caps = connectivity?.getNetworkCapabilities(network) ?: return "?"
        return when {
            caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) -> "wifi"
            caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) -> "мобильная"
            else -> "другая"
        }
    }

    val isOnline: Boolean
        get() {
            val network = connectivity?.activeNetwork ?: return false
            val caps = connectivity.getNetworkCapabilities(network) ?: return false
            return caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) &&
                caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
        }

    fun start() {
        if (registered || connectivity == null) return
        try {
            reportedOnline = null
            connectivity.registerDefaultNetworkCallback(callback)
            registered = true
        } catch (e: RuntimeException) {
            // Слишком много колбэков у процесса — просто живём без уведомлений.
        }
    }

    fun stop() {
        if (!registered) return
        try { connectivity?.unregisterNetworkCallback(callback) } catch (e: IllegalArgumentException) { /* уже снят */ }
        registered = false
    }

    private companion object {
        const val DUPLICATE_WINDOW_MS = 2_000L
    }
}
