package com.example.vkweb.web

import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.Intent
import android.net.Uri
import android.webkit.ValueCallback
import android.webkit.WebChromeClient.FileChooserParams
import androidx.activity.ComponentActivity
import androidx.activity.result.ActivityResult
import androidx.activity.result.contract.ActivityResultContracts

/** Выбор файлов для отправки в чат. Создавайте как поле Activity (до onStart). */
class FileChooser(activity: ComponentActivity) {

    private var pending: ValueCallback<Array<Uri>>? = null

    private val launcher = activity.registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result -> deliver(parse(result)) }

    fun open(callback: ValueCallback<Array<Uri>>, params: FileChooserParams): Boolean {
        cancel()
        val intent = params.createIntent().apply {
            if (params.mode == FileChooserParams.MODE_OPEN_MULTIPLE) {
                putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true)
            }
        }
        return try {
            pending = callback
            launcher.launch(intent)
            true
        } catch (e: ActivityNotFoundException) {
            // Возвращаем false — WebView сам завершит запрос, вызывать callback нельзя.
            pending = null
            false
        }
    }

    /** WebView обязательно должен получить ответ, иначе кнопка выбора файла перестанет работать. */
    fun cancel() = deliver(null)

    private fun deliver(uris: Array<Uri>?) {
        pending?.onReceiveValue(uris)
        pending = null
    }

    /** FileChooserParams.parseResult не умеет несколько файлов (clipData), поэтому разбираем сами. */
    private fun parse(result: ActivityResult): Array<Uri>? {
        if (result.resultCode != Activity.RESULT_OK) return null
        val data = result.data ?: return null
        val clip = data.clipData
        val uris = if (clip != null) {
            List(clip.itemCount) { clip.getItemAt(it).uri }.filterNotNull()
        } else {
            listOfNotNull(data.data)
        }
        return uris.takeIf { it.isNotEmpty() }?.toTypedArray()
    }
}
