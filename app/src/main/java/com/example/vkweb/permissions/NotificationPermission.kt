package com.example.vkweb.permissions

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts
import androidx.annotation.StringRes
import androidx.core.content.ContextCompat
import com.example.vkweb.R
import com.google.android.material.dialog.MaterialAlertDialogBuilder

/**
 * Разрешение на уведомления (Android 13+). Спрашиваем один раз за жизнь экрана и только когда оно нужно.
 *
 * 4.88: если человек уже отказал однажды, Android покажет вопрос ещё только раз — перед ним объясняем, зачем
 * разрешение ([requestOnce] с `rationale`). Что делать после разрешения (например, спросить о батарее — без
 * уведомлений это бессмысленно) — `onGranted`: вызывается сразу, если разрешение уже есть.
 */
class NotificationPermission(private val activity: ComponentActivity) {

    private var asked = false
    private var pendingGranted: (() -> Unit)? = null

    private val launcher = activity.registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        // Без разрешения сервис работает, просто уведомления не видно.
        val next = pendingGranted
        pendingGranted = null
        if (granted) next?.invoke()
    }

    fun isGranted(): Boolean =
        Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
            ContextCompat.checkSelfPermission(activity, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED

    fun requestOnce(@StringRes rationale: Int? = null, onGranted: () -> Unit = {}) {
        if (asked) return
        asked = true
        if (isGranted()) {
            onGranted()
            return
        }
        pendingGranted = onGranted
        if (rationale == null || !activity.shouldShowRequestPermissionRationale(Manifest.permission.POST_NOTIFICATIONS)) {
            launcher.launch(Manifest.permission.POST_NOTIFICATIONS)
            return
        }
        MaterialAlertDialogBuilder(activity)
            .setTitle(R.string.notif_permission_title)
            .setMessage(rationale)
            .setPositiveButton(R.string.notif_permission_allow) { _, _ -> launcher.launch(Manifest.permission.POST_NOTIFICATIONS) }
            .setNegativeButton(R.string.battery_ask_later) { _, _ -> pendingGranted = null }
            .show()
    }
}
