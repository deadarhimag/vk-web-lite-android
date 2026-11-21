package com.example.vkweb.messages

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.Base64
import android.webkit.WebView
import com.example.vkweb.Background
import java.io.File
import java.io.IOException
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/** Bounded staging tied to both an operation and its original conversation. */
class AttachmentSender(
    private val context: Context,
    private val channel: ReaderChannel,
    private val reader: WebView,
    private val currentPeer: () -> String?,
) {
    private val worker = Background.serial("attachments")
    private val main = Handler(Looper.getMainLooper())
    @Volatile private var generation = 0L
    private var operation = ""

    fun send(files: List<PickedFile>, text: String, onProgress: (String, Int, Int) -> Unit = { _, _, _ -> }, onFailed: (String) -> Unit) {
        val peer = currentPeer() ?: return
        cancel()
        val mine = generation
        val token = UUID.randomUUID().toString().also { operation = it }
        channel.beginTransfer(reader, token, peer)
        worker.execute {
            val copies = ArrayList<File>()
            try {
                if (files.size !in 1..AttachmentPolicy.MAX_FILES) throw IOException("слишком много файлов")
                var total = 0L
                // Validate provider streams before transferring anything to the page.
                files.forEachIndexed { index, picked ->
                    checkActive(mine)
                    main.post { if (mine == generation && currentPeer() == peer) onProgress("preparing", index + 1, files.size) }
                    val copy = File.createTempFile("vk-attachment-", ".part", context.cacheDir).also { copies += it }
                    val input = context.contentResolver.openInputStream(picked.uri) ?: throw IOException("файл недоступен")
                    input.use { source -> copy.outputStream().use { target ->
                        val buffer = ByteArray(64 * 1024)
                        var size = 0L
                        while (true) {
                            checkActive(mine)
                            val count = source.read(buffer)
                            if (count < 0) break
                            size += count
                            total += count
                            if (size > AttachmentPolicy.MAX_FILE_BYTES || total > AttachmentPolicy.MAX_TOTAL_BYTES) {
                                throw IOException("превышен размер вложений")
                            }
                            target.write(buffer, 0, count)
                        }
                        if (size == 0L) throw IOException("пустой файл")
                    } }
                }
                files.forEachIndexed { index, picked ->
                    main.post { if (mine == generation && currentPeer() == peer) onProgress("transferring", index + 1, files.size) }
                    push(mine, token, index, picked.name, picked.mime, copies[index])
                }
                main.post { if (mine == generation && currentPeer() == peer) channel.attachAndSend(reader, text, token) }
            } catch (error: Exception) {
                fail(mine, token, error, onFailed)
            } finally { copies.forEach { it.delete() } }
        }
    }

    fun sendVoice(file: File, peer: String, randomId: Long, onFailed: (String) -> Unit): String {
        cancel()
        val mine = generation
        val token = UUID.randomUUID().toString().also { operation = it }
        channel.beginTransfer(reader, token, peer)
        worker.execute {
            try {
                if (file.length() !in 1..AttachmentPolicy.MAX_FILE_BYTES) throw IOException("запись пустая или слишком большая")
                push(mine, token, 0, file.name, "audio/ogg", file)
                main.post { if (mine == generation && currentPeer() == peer) channel.sendVoice(reader, peer, randomId, token) }
            } catch (error: Exception) {
                fail(mine, token, error, onFailed)
            } finally { file.delete() }
        }
        return token
    }

    fun cancel() {
        generation++
        if (operation.isNotEmpty()) channel.cancelTransfer(reader, operation)
        operation = ""
    }

    private fun checkActive(mine: Long) {
        if (mine != generation) throw IOException("отменено")
    }

    private fun fail(mine: Long, token: String, error: Exception, onFailed: (String) -> Unit) {
        main.post {
            if (mine == generation) {
                channel.cancelTransfer(reader, token)
                onFailed(error.message ?: error.javaClass.simpleName)
            }
        }
    }

    private fun push(mine: Long, token: String, index: Int, name: String, mime: String, file: File) {
        val total = AttachmentPolicy.chunkCount(file.length())
        file.inputStream().buffered().use { source ->
            val chunk = ByteArray(AttachmentPolicy.CHUNK_BYTES)
            for (part in 0 until total) {
                checkActive(mine)
                val expected = minOf(chunk.size.toLong(), file.length() - part.toLong() * chunk.size).toInt()
                var count = 0
                while (count < expected) {
                    val n = source.read(chunk, count, expected - count)
                    if (n < 0) throw IOException("файл изменился")
                    count += n
                }
                val data = Base64.encodeToString(chunk, 0, count, Base64.NO_WRAP)
                val accepted = CountDownLatch(1)
                var ok = false
                main.post {
                    if (mine != generation) { accepted.countDown(); return@post }
                    channel.stageFile(reader, index, name, mime, total, part, data, token) {
                        ok = it
                        accepted.countDown()
                    }
                }
                if (!accepted.await(20, TimeUnit.SECONDS) || !ok) throw IOException("страница не приняла файл")
            }
        }
    }
}
