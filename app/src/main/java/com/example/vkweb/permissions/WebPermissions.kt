package com.example.vkweb.permissions

import android.Manifest
import android.content.pm.PackageManager
import android.net.Uri
import android.webkit.PermissionRequest
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat

/**
 * Микрофон и камера для страниц (голосовые сообщения, звонки).
 * Выдаются только доверенным origin и только после системного разрешения Android.
 */
class WebPermissions(
    private val activity: ComponentActivity,
    private val isTrustedOrigin: (Uri) -> Boolean,
) {
    private var pending: PermissionRequest? = null

    private val launcher = activity.registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) {
        pending?.let(::grantAvailable)
        pending = null
    }

    fun onRequest(request: PermissionRequest) {
        if (!isTrustedOrigin(request.origin)) {
            request.deny()
            return
        }
        val required = request.resources.mapNotNull(::androidPermissionFor).distinct()
        val missing = required.filterNot(::isGranted)
        when {
            required.isEmpty() -> request.deny()
            missing.isEmpty() -> grantAvailable(request)
            else -> {
                pending?.deny()
                pending = request
                launcher.launch(missing.toTypedArray())
            }
        }
    }

    fun onCanceled(request: PermissionRequest) {
        if (pending === request) pending = null
    }

    private fun grantAvailable(request: PermissionRequest) {
        val granted = request.resources
            .filter { resource -> androidPermissionFor(resource)?.let(::isGranted) == true }
            .toTypedArray()
        if (granted.isEmpty()) request.deny() else request.grant(granted)
    }

    private fun isGranted(permission: String) =
        ContextCompat.checkSelfPermission(activity, permission) == PackageManager.PERMISSION_GRANTED

    private fun androidPermissionFor(resource: String): String? = when (resource) {
        PermissionRequest.RESOURCE_AUDIO_CAPTURE -> Manifest.permission.RECORD_AUDIO
        PermissionRequest.RESOURCE_VIDEO_CAPTURE -> Manifest.permission.CAMERA
        else -> null
    }
}
