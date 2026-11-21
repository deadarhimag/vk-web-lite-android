package com.example.vkweb.messages

import com.example.vkweb.ui.Notice
import android.net.Uri
import android.os.Handler
import android.text.TextUtils
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.TextView
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts
import com.example.vkweb.Background
import com.example.vkweb.R
import com.example.vkweb.diag.AppLog
import com.example.vkweb.diag.LogTag
import com.example.vkweb.ui.Views

/**
 * Вложения в поле ввода: выбор файлов, полоса выбранных над полем и отправка вместе с текстом. Вынесено из экрана
 * переписки, который перерос тысячу строк; всё про вложения — здесь, экрану остаются четыре вызова ([pick], [send],
 * [onPageStage], [onSent]) и признак [isBusy].
 *
 * Идёт отправка — занято ([isBusy]): кнопки отключены, очередь неотправленных не отправляет текст (иначе он ушёл бы вместе с
 * файлами), автообновление страницы не перезагружает её. Занятость обязательно заканчивается: ответом страницы, ошибкой или
 * сторожевым таймером [TIMEOUT_MS] — раньше при потерянном ответе страницы кнопка «Отправить» оставалась отключённой навсегда.
 */
class AttachmentComposer(
    private val activity: ComponentActivity,
    private val scroll: View,
    private val bar: LinearLayout,
    private val sendButton: View,
    private val attachButton: View,
    private val handler: Handler,
    private val statusRow: View,
    private val statusText: TextView,
    private val cancelButton: TextView,
    private val sender: () -> AttachmentSender,
    private val isOnline: () -> Boolean,
    /** Экран показывает саму страницу VK: там файлы прикрепляют кнопкой сайта. */
    private val isSiteMode: () -> Boolean,
    /** Занятость изменилась: экран сообщает очереди и автообновлению. */
    private val onBusyChanged: (Boolean) -> Unit,
    /** Сообщение с вложениями ушло: экран очищает поле ввода и перечитывает историю. */
    private val onDelivered: () -> Unit,
    /** Отправка началась: текст и названия файлов — экран сразу показывает пузырь «отправляется». */
    private val onStarted: (text: String, fileNames: List<String>) -> Unit = { _, _ -> },
    /** Не отправилось: пузырь «отправляется» убирается (файлы остаются в полосе — можно повторить). */
    private val onFailed: () -> Unit = {},
    /** Файлы добавлены или убраны (4.59: микрофон или «Отправить»). */
    private val onFilesChanged: () -> Unit = {},
) {
    private val pickFiles = activity.registerForActivityResult(ActivityResultContracts.GetMultipleContents()) {
        if (pickerGeneration == selectionGeneration) add(it)
    }
    private val files = ArrayList<PickedFile>()
    private val io = Background.serial("attachment-pick")
    private var statusMessage: CharSequence? = null
    private var sendStarted = false
    private var awaitingDelivery = false
    private var resettingComposer = false
    private var selectionGeneration = 0L
    private var pickerGeneration = 0L

    var isBusy = false
        private set

    val hasFiles: Boolean get() = files.isNotEmpty()

    private val timeout = Runnable { if (isBusy) fail(activity.getString(R.string.attach_timeout)) }

    init {
        attachButton.setOnClickListener { pick() }
        cancelButton.setOnClickListener {
            if (isBusy && !sendStarted) {
                sender().cancel()
                statusMessage = activity.getString(R.string.attach_cancelled_kept)
                setBusy(false)
                onFailed()
                renderStatus()
            } else if (!isBusy && !awaitingDelivery) {
                statusMessage = null
                renderStatus()
            }
        }
    }

    fun pick() {
        if (isBusy) return
        if (isSiteMode()) {
            toast(R.string.attach_site_mode)
            return
        }
        pickerGeneration = selectionGeneration
        Notice.show(activity, R.string.attach_limits, long = true)
        pickFiles.launch("*/*")
    }

    /** Файлы из «Поделиться» (4.57): как выбранные кнопкой — с теми же ограничениями. */
    fun addShared(uris: List<Uri>) {
        if (isBusy || isSiteMode()) return
        add(uris)
    }

    /** Сведения о выбранных файлах читает провайдер файлов — не в главном потоке. */
    private fun add(uris: List<Uri>) {
        if (uris.isEmpty()) return
        val mine = selectionGeneration
        val context = activity.applicationContext
        io.execute {
            val picked = uris.map { PickedFile.describe(context, it) }
            handler.post { if (mine == selectionGeneration && !isBusy) accept(picked) }
        }
    }

    private fun accept(picked: List<PickedFile>) {
        val added = picked.filter { file -> files.none { it.uri == file.uri } }
        val problem = AttachmentPolicy.check((files + added).map { it.size })
        if (problem != null) {
            val message = when (problem) {
                AttachmentPolicy.Problem.TOO_MANY -> activity.getString(R.string.attach_too_many, AttachmentPolicy.MAX_FILES)
                AttachmentPolicy.Problem.FILE_TOO_BIG -> activity.getString(R.string.attach_too_big)
                AttachmentPolicy.Problem.TOTAL_TOO_BIG -> activity.getString(R.string.attach_total_too_big)
                AttachmentPolicy.Problem.EMPTY_FILE -> activity.getString(R.string.attach_empty)
            }
            AppLog.i(LogTag.MESSAGES, "вложения не приняты", "причина" to problem.name, "выбрано" to picked.size)
            Notice.show(activity, message, long = true)
            return
        }
        files += added
        statusMessage = null
        AppLog.d(LogTag.MESSAGES, "вложения выбраны", "всего" to files.size)
        render()
    }

    /** Полоса выбранных файлов над полем ввода: нажатие на файл убирает его. */
    private fun render() {
        onFilesChanged()
        bar.removeAllViews()
        scroll.visibility = if (files.isEmpty()) View.GONE else View.VISIBLE
        val gap = Views.dp(bar, 8)
        files.toList().forEach { file ->
            val chip = TextView(activity).apply {
                text = listOf(file.name, AttachmentPolicy.size(file.size)).filter { it.isNotEmpty() }.joinToString(" · ")
                setTextColor(activity.getColor(R.color.media_on))
                maxLines = 1
                ellipsize = TextUtils.TruncateAt.END
                maxWidth = Views.dp(bar, 240)
                minHeight = Views.dp(bar, 48)
                minWidth = Views.dp(bar, 48)
                gravity = android.view.Gravity.CENTER_VERTICAL
                setBackgroundResource(R.drawable.photo_counter_bg)
                setPadding(Views.dp(bar, 12), Views.dp(bar, 6), Views.dp(bar, 8), Views.dp(bar, 6))
                compoundDrawablePadding = Views.dp(bar, 6)
                setCompoundDrawablesRelativeWithIntrinsicBounds(0, 0, R.drawable.ic_close, 0)
                contentDescription = activity.getString(R.string.attach_remove, file.name)
                layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT)
                    .apply { marginEnd = gap }
                setOnClickListener {
                    if (!isBusy) {
                        files.remove(file)
                        statusMessage = null
                        render()
                    }
                }
            }
            bar.addView(chip)
        }
        renderStatus()
    }

    private fun renderStatus() {
        val message = statusMessage ?: if (files.isNotEmpty()) activity.getString(R.string.attach_selected, files.size) else null
        statusRow.visibility = if (message == null) View.GONE else View.VISIBLE
        statusText.text = message
        cancelButton.visibility = when {
            resettingComposer || awaitingDelivery || (isBusy && sendStarted) -> View.GONE
            isBusy || (files.isEmpty() && message != null) -> View.VISIBLE
            else -> View.GONE
        }
        cancelButton.setText(if (isBusy) R.string.cancel else R.string.close)
    }

    fun onComposerResetting() {
        resettingComposer = true
        statusMessage = activity.getString(R.string.attach_resetting)
        renderStatus()
    }

    fun onComposerReady() {
        if (!resettingComposer) return
        resettingComposer = false
        statusMessage = null
        renderStatus()
    }

    fun onHistoryArrived() {
        awaitingDelivery = false
        statusMessage = null
        renderStatus()
    }

    fun onHistoryTimeout() {
        if (!awaitingDelivery) return
        awaitingDelivery = false
        statusMessage = activity.getString(R.string.attach_delivery_review)
        renderStatus()
    }

    private fun setBusy(busy: Boolean) {
        if (isBusy == busy) return
        isBusy = busy
        sendButton.isEnabled = !busy
        attachButton.isEnabled = !busy
        handler.removeCallbacks(timeout)
        if (busy) handler.postDelayed(timeout, TIMEOUT_MS)
        onBusyChanged(busy)
        renderStatus()
    }

    /** Отправить сообщение с выбранными файлами и текстом [text] (он может быть пустым). */
    fun send(text: String) {
        if (isBusy || files.isEmpty()) return
        if (isSiteMode()) {
            toast(R.string.attach_site_mode)
            return
        }
        if (!isOnline()) {
            toast(R.string.attach_offline)
            return
        }
        setBusy(true)
        sendStarted = false
        awaitingDelivery = false
        statusMessage = activity.getString(R.string.attach_preparing, 1, files.size)
        renderStatus()
        toast(R.string.attach_sending)
        AppLog.i(LogTag.MESSAGES, "отправка с вложениями", "файлов" to files.size, "есть текст" to text.isNotEmpty())
        onStarted(text, files.map { it.name.ifEmpty { "файл" } })
        sender().send(files.toList(), text, onProgress = { stage, index, count ->
            if (isBusy) {
                statusMessage = activity.getString(if (stage == "preparing") R.string.attach_preparing else R.string.attach_transferring, index, count)
                renderStatus()
            }
        }) { reason -> fail(reason) }
    }

    /** Ответ страницы о вложениях: загрузка идёт («uploading») или не получилось («error»). */
    fun onPageStage(stage: String, detail: String) {
        AppLog.i(LogTag.MESSAGES, "вложения: ответ страницы", "этап" to stage, "подробности" to detail.ifEmpty { null })
        if (stage == "error" && isBusy) fail(detail)
        else if (isBusy) {
            if (stage == "sending") sendStarted = true
            statusMessage = activity.getString(if (sendStarted) R.string.attach_checking else R.string.attach_uploading)
            renderStatus()
        }
    }

    /** Страница нажала «Отправить» после загрузки вложений. */
    fun onSent(ok: Boolean) {
        if (!isBusy) return
        if (!ok) {
            fail(activity.getString(R.string.attach_not_confirmed))
            return
        }
        awaitingDelivery = true
        statusMessage = activity.getString(R.string.attach_checking)
        setBusy(false)
        files.clear()
        render()
        onDelivered()
    }

    private fun fail(reason: String) {
        sender().cancel()
        statusMessage = activity.getString(R.string.attach_failed, reason)
        setBusy(false)
        onFailed()
        Notice.show(activity, activity.getString(R.string.attach_failed, reason), long = true)
    }

    /**
     * Переписка сменилась (или показана страница VK): выбранные файлы и незаконченная отправка относятся к прежней —
     * отменяем, иначе файлы ушли бы не тому, а кнопки оставались бы отключены до сторожевого таймера (код ревью 3.59).
     */
    fun reset() {
        selectionGeneration++
        statusMessage = null
        awaitingDelivery = false
        resettingComposer = false
        sendStarted = false
        sender().cancel()
        handler.removeCallbacks(timeout)
        files.clear()
        render()
        setBusy(false)
    }

    /** Экран закрывается: незаконченная передача отменяется. */
    fun cancel() {
        handler.removeCallbacks(timeout)
        sender().cancel()
    }

    private fun toast(res: Int) = Notice.show(activity, res)

    private companion object {
        /** Передача файлов, загрузка страницей (до 90 с) и отправка: дольше трёх минут ждать незачем. */
        const val TIMEOUT_MS = 180_000L
    }
}
