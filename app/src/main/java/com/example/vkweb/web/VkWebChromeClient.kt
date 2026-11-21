package com.example.vkweb.web

import android.net.Uri
import android.view.View
import android.webkit.GeolocationPermissions
import android.webkit.PermissionRequest
import android.webkit.ValueCallback
import android.webkit.WebChromeClient
import android.webkit.WebView
import com.example.vkweb.permissions.WebPermissions
import com.example.vkweb.ui.FullscreenController

/** Тонкий адаптер: передаёт события WebView профильным классам. */
class VkWebChromeClient(
    private val fullscreen: FullscreenController,
    private val fileChooser: FileChooser,
    private val permissions: WebPermissions,
    private val listener: Listener,
) : WebChromeClient() {

    interface Listener {
        fun onPageProgress(progress: Int)
        fun onPageTitle(title: String?)
    }

    override fun onProgressChanged(view: WebView, newProgress: Int) = listener.onPageProgress(newProgress)

    override fun onReceivedTitle(view: WebView, title: String?) = listener.onPageTitle(title)

    override fun onShowCustomView(view: View, callback: CustomViewCallback) =
        fullscreen.enter(view, callback)

    override fun onHideCustomView() = fullscreen.dismiss()

    override fun onShowFileChooser(
        webView: WebView,
        filePathCallback: ValueCallback<Array<Uri>>,
        fileChooserParams: FileChooserParams,
    ): Boolean = fileChooser.open(filePathCallback, fileChooserParams)

    /** Местоположение сайту не отдаём никогда. */
    override fun onGeolocationPermissionsShowPrompt(origin: String, callback: GeolocationPermissions.Callback) {
        callback.invoke(origin, false, false)
    }

    override fun onPermissionRequest(request: PermissionRequest) = permissions.onRequest(request)

    override fun onPermissionRequestCanceled(request: PermissionRequest) = permissions.onCanceled(request)
}
