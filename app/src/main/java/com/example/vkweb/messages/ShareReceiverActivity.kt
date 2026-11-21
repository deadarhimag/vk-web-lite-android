package com.example.vkweb.messages

import android.app.Activity
import android.content.ClipData
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import com.example.vkweb.R
import com.example.vkweb.diag.AppLog
import com.example.vkweb.diag.LogTag
import com.example.vkweb.settings.AppSettings
import com.example.vkweb.ui.Notice

/**
 * «Поделиться» из других приложений (4.57): текст, ссылка, фото и файлы — в переписку VK. Прозрачная прослойка без окна:
 * проверяет присланное ([ShareContent]) и передаёт его экрану сообщений (он не экспортирован — снаружи его не открыть),
 * а там человек выбирает, кому. Доступ к файлам передаётся дальше вместе с намерением.
 */
class ShareReceiverActivity : Activity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val share = read(intent)
        if (share == null) {
            AppLog.i(LogTag.MESSAGES, "поделиться: нечего отправлять")
            finish()
            return
        }
        if (!AppSettings(this).nativeMessages) {
            Notice.show(this, R.string.share_needs_messages, long = true)
            finish()
            return
        }
        AppLog.i(LogTag.MESSAGES, "поделиться: получено", "текст" to share.text.isNotEmpty(), "файлов" to share.uris.size)
        val open = Intent(this, MessagesActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
            .putExtra(MessagesActivity.EXTRA_SHARE_TEXT, share.text)
            .putStringArrayListExtra(MessagesActivity.EXTRA_SHARE_URIS, ArrayList(share.uris))
        if (share.uris.isNotEmpty()) {
            // Право читать файлы — дальше, экрану сообщений (иначе у него его нет).
            val clip = ClipData.newRawUri("", Uri.parse(share.uris.first()))
            share.uris.drop(1).forEach { clip.addItem(ClipData.Item(Uri.parse(it))) }
            open.clipData = clip
            open.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        startActivity(open)
        finish()
    }

    private fun read(intent: Intent?): ShareContent? {
        intent ?: return null
        val uris: List<String?> = when (intent.action) {
            Intent.ACTION_SEND -> listOfNotNull(stream(intent)).map { it.toString() }
            Intent.ACTION_SEND_MULTIPLE -> streams(intent).map { it?.toString() }
            else -> return null
        }
        return ShareContent.of(
            intent.getCharSequenceExtra(Intent.EXTRA_TEXT)?.toString(),
            intent.getStringExtra(Intent.EXTRA_SUBJECT),
            uris,
            ownPackage = packageName,
        )
    }

    @Suppress("DEPRECATION")
    private fun stream(intent: Intent): Uri? =
        if (Build.VERSION.SDK_INT >= 33) intent.getParcelableExtra(Intent.EXTRA_STREAM, Uri::class.java)
        else intent.getParcelableExtra(Intent.EXTRA_STREAM)

    @Suppress("DEPRECATION")
    private fun streams(intent: Intent): List<Uri?> =
        (if (Build.VERSION.SDK_INT >= 33) intent.getParcelableArrayListExtra(Intent.EXTRA_STREAM, Uri::class.java)
        else intent.getParcelableArrayListExtra(Intent.EXTRA_STREAM)).orEmpty()
}
