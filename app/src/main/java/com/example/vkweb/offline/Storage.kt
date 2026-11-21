package com.example.vkweb.offline

import com.example.vkweb.Background
import android.content.Context
import android.os.Handler
import android.os.Looper
import com.example.vkweb.dev.DumpRecorder
import com.example.vkweb.diag.AppLog
import com.example.vkweb.outbox.Outbox
import com.example.vkweb.media.MusicCache

/**
 * Всё, что приложение хранит на устройстве, по категориям ([StorageCategory]): сколько занято и удаление.
 * Сохранённое для работы без сети — [OfflineStore], картинки — [ImageCache], кэш страниц — папка кэша
 * приложения, где его держит WebView (удаляет его сам WebView главного экрана: см. [StorageCategory.needsWebView]),
 * собранное для разработки — [DumpRecorder], журнал событий — [AppLog].
 */
class Storage(context: Context) {

    private val appContext = context.applicationContext
    private val store = OfflineStore.get(appContext)
    private val images = ImageCache.get(appContext)
    private val dump = DumpRecorder.get(appContext)
    private val worker = Background.serial("storage")
    private val main = Handler(Looper.getMainLooper())

    /** Размер каждой категории, байт; [result] — в главном потоке. */
    fun measure(result: (Map<StorageCategory, Long>) -> Unit) {
        store.measure { saved ->
            worker.execute {
                val sizes = saved + mapOf(
                    StorageCategory.IMAGES to images.sizeBytes(),
                    StorageCategory.MUSIC_FILES to runCatching { MusicCache.warmUp(appContext); MusicCache.sizeBytes() }.getOrDefault(0L),
                    StorageCategory.WEB_CACHE to OfflineStore.sizeOf(appContext.cacheDir),
                    StorageCategory.DEV_DUMP to dump.sizeBytes(),
                    StorageCategory.EVENT_LOG to AppLog.sizeBytes(),
                )
                main.post { result(sizes) }
            }
        }
    }

    /** Удаляет категорию, кроме [StorageCategory.WEB_CACHE] (её удаляет WebView); [done] — в главном потоке. */
    fun clear(category: StorageCategory, done: () -> Unit = {}) = when (category) {
        StorageCategory.IMAGES -> images.clear(done)
        StorageCategory.MUSIC_FILES -> worker.execute {
            MusicCache.clear(appContext)
            main.post(done)
        }
        StorageCategory.DEV_DUMP -> dump.clear(done)
        StorageCategory.EVENT_LOG -> AppLog.clear { main.post(done) }
        StorageCategory.WEB_CACHE -> done()
        // Память очереди тоже сбрасывается: иначе следующее изменение записало бы удалённое обратно.
        // Память — до удаления файла (код ревью 3.59): изменение очереди между удалением и сбросом записывало бы
        // старое обратно на диск, и «удалённые» сообщения после перезапуска отправлялись бы сами.
        StorageCategory.OUTBOX -> {
            Outbox.get(appContext).reset()
            store.clear(category, done)
        }
        StorageCategory.MESSAGES -> {
            com.example.vkweb.messages.VoiceSendStore.clear(appContext)
            store.clear(category, done)
        }
        else -> store.clear(category, done)
    }

    /** Удаляет всё, что можно удалить без WebView; [done] — когда закончено всё. */
    fun clearAll(done: () -> Unit = {}) {
        val categories = StorageCategory.entries.filterNot { it.needsWebView }
        var left = categories.size
        categories.forEach { clear(it) { if (--left == 0) done() } }
    }
}
