package com.example.vkweb.ui

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.isVisible
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.PagerSnapHelper
import androidx.recyclerview.widget.RecyclerView
import com.example.vkweb.R
import com.example.vkweb.offline.ImageCache
import com.google.android.material.progressindicator.CircularProgressIndicator

/**
 * Фото на весь экран — лентой (закрыть можно и смахиванием вверх или вниз, 3.90): следующее фото выезжает вслед за пальцем, а не подменяет текущее после свайпа.
 * Берётся из кэша, поэтому открывается и без сети, если фото уже показывалось.
 *
 * У каждой страницы своя загрузка: не загрузившееся фото не мешает листать дальше, а кнопка «Повторить» пробует
 * снова (и сервер, который недавно не отвечал). Соседние страницы готовятся заранее ([PreloadingLayoutManager]),
 * чтобы выезжающее фото уже было на месте. Закрыть — кнопкой сверху (на телефонах без жестов и кнопки «Назад»)
 * или «Назад»; касание прячет и показывает кнопки.
 */
class PhotoViewerActivity : AppCompatActivity() {

    private lateinit var pager: RecyclerView
    private lateinit var counter: TextView
    private lateinit var controls: View
    private lateinit var pageButton: View

    /** Страницы фото на сайте (с лайками и комментариями), по одной на фото; пусто — у фото её нет. */
    private var pages: List<String> = emptyList()

    private var urls: List<String> = emptyList()
    private var index = 0

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_photo)
        pager = findViewById(R.id.photo_pager)
        counter = findViewById(R.id.photo_counter)
        controls = findViewById(R.id.photo_controls)
        findViewById<View>(R.id.photo_close).setOnClickListener { finish() }
        pageButton = findViewById(R.id.photo_page)
        pageButton.setOnClickListener { openPage() }
        findViewById<View>(R.id.photo_save).apply {
            isVisible = android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.Q
            setOnClickListener { urls.getOrNull(index)?.let { com.example.vkweb.web.ShareActions(this@PhotoViewerActivity).savePhoto(it) } }
        }
        findViewById<View>(R.id.photo_share).setOnClickListener {
            urls.getOrNull(index)?.let { com.example.vkweb.web.ShareActions(this).sharePhotos(listOf(it)) }
        }

        val gallery = PhotoGallery.clean(intent.getStringArrayListExtra(EXTRA_URLS), intent.getIntExtra(EXTRA_INDEX, 0))
        if (gallery.urls.isEmpty()) { finish(); return }
        urls = gallery.urls
        // После пересоздания — то же фото (по адресу): сохранённый номер — в очищенном списке, а не в исходном
        // (код ревью 3.59).
        index = savedInstanceState?.getString(STATE_URL)?.let { urls.indexOf(it) }?.takeIf { it >= 0 } ?: gallery.index
        // Страницы приходят вровень с адресами; если очистка галереи что-то выбросила, сопоставить нельзя — без них.
        pages = intent.getStringArrayListExtra(EXTRA_PAGES)?.takeIf { it.size == urls.size }.orEmpty()

        // Готовятся до трёх страниц сразу (текущая и соседние): каждой — не больше шестнадцатой части памяти.
        val maxSide = PhotoGallery.maxSidePx(Runtime.getRuntime().maxMemory() / 2, MAX_SIDE_PX)
        val layout = PreloadingLayoutManager(this)
        pager.layoutManager = layout
        pager.setHasFixedSize(true)
        pager.adapter = PhotoPagerAdapter(urls, maxSide, onTap = ::toggleControls, onDrag = ::onDismissDrag, onRelease = ::onDismissRelease)
        PagerSnapHelper().attachToRecyclerView(pager)
        pager.scrollToPosition(index)
        pager.addOnScrollListener(object : RecyclerView.OnScrollListener() {
            override fun onScrollStateChanged(recyclerView: RecyclerView, newState: Int) {
                if (newState != RecyclerView.SCROLL_STATE_IDLE) return
                val shown = layout.findFirstCompletelyVisibleItemPosition()
                if (shown != RecyclerView.NO_POSITION && shown != index) {
                    index = shown
                    updateCounter()
                }
            }
        })
        updateCounter()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        urls.getOrNull(index)?.let { outState.putString(STATE_URL, it) }
    }

    override fun onDestroy() {
        // Страницы отвязываются: их загрузки больше ничего не нарисуют и не держат экран.
        if (::pager.isInitialized) pager.adapter = null
        super.onDestroy()
    }

    private fun toggleControls() {
        controls.isVisible = !controls.isVisible
    }

    // ---- закрытие смахиванием (3.90)

    private val root: View by lazy { findViewById(R.id.photo_root) }
    private var controlsBeforeDrag = true
    private var dragStarted = false
    private var closing = false

    private fun onDismissDrag(dy: Float) {
        if (closing) return
        if (!dragStarted) {
            dragStarted = true
            controlsBeforeDrag = controls.isVisible
            controls.isVisible = false
            pager.animate().cancel()
        }
        pager.translationY = dy
        root.background?.mutate()?.alpha = (SwipeDismiss.backgroundAlpha(dy, root.height) * 255).toInt()
    }

    private fun onDismissRelease(dy: Float, velocityY: Float) {
        dragStarted = false
        if (closing) return
        val height = root.height
        if (SwipeDismiss.shouldDismiss(dy, velocityY, height, resources.displayMetrics.density)) {
            closing = true
            val target = if (dy == 0f) height.toFloat() else Math.copySign(height.toFloat(), dy)
            pager.animate().translationY(target).setDuration(DISMISS_MS)
                .setUpdateListener { root.background?.alpha = (SwipeDismiss.backgroundAlpha(pager.translationY, height) * 255).toInt() }
                .withEndAction {
                    finish()
                    @Suppress("DEPRECATION")
                    overridePendingTransition(0, android.R.anim.fade_out)
                }
                .start()
        } else {
            // Не дотянули — фото на место, фон и кнопки как были.
            pager.animate().translationY(0f).setDuration(RETURN_MS)
                .setUpdateListener { root.background?.alpha = (SwipeDismiss.backgroundAlpha(pager.translationY, height) * 255).toInt() }
                .withEndAction { root.background?.alpha = 255 }
                .start()
            controls.isVisible = controlsBeforeDrag
        }
    }

    /** Режим «страницей»: фото открывается на сайте в главном окне — там лайк и комментарии. */
    private fun openPage() {
        val page = pages.getOrNull(index)?.takeIf { it.isNotEmpty() } ?: return
        startActivity(
            Intent(this, com.example.vkweb.MainActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_REORDER_TO_FRONT or Intent.FLAG_ACTIVITY_SINGLE_TOP)
                .putExtra(com.example.vkweb.MainActivity.EXTRA_OPEN_PAGE, page)
                .putExtra(com.example.vkweb.MainActivity.EXTRA_INTERNAL_TOKEN, com.example.vkweb.MainActivity.INTERNAL_TOKEN),
        )
        finish()
    }

    private fun updateCounter() {
        pageButton.isVisible = !pages.getOrNull(index).isNullOrEmpty()
        val many = urls.size > 1
        counter.visibility = if (many) View.VISIBLE else View.GONE
        if (many) {
            counter.text = PhotoGallery.counter(index, urls.size)
            counter.contentDescription = getString(R.string.photo_counter_description, index + 1, urls.size)
        }
    }

    /** Лента, которая раскладывает и соседние страницы: выезжающее фото уже загружено, а не грузится на глазах. */
    private class PreloadingLayoutManager(context: Context) : LinearLayoutManager(context, HORIZONTAL, false) {
        override fun calculateExtraLayoutSpace(state: RecyclerView.State, extraLayoutSpace: IntArray) {
            extraLayoutSpace[0] = width
            extraLayoutSpace[1] = width
        }
    }

    private class PhotoPagerAdapter(
        private val urls: List<String>,
        private val maxSidePx: Int,
        private val onTap: () -> Unit,
        private val onDrag: (Float) -> Unit,
        private val onRelease: (Float, Float) -> Unit,
    ) : RecyclerView.Adapter<PageHolder>() {

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int) = PageHolder(
            LayoutInflater.from(parent.context).inflate(R.layout.item_photo_page, parent, false), maxSidePx, onTap, onDrag, onRelease,
        )

        override fun onBindViewHolder(holder: PageHolder, position: Int) = holder.bind(urls[position])

        override fun getItemCount(): Int = urls.size

        override fun onViewRecycled(holder: PageHolder) = holder.clear()
    }

    private class PageHolder(
        view: View,
        private val maxSidePx: Int,
        onTap: () -> Unit,
        onDrag: (Float) -> Unit,
        onRelease: (Float, Float) -> Unit,
    ) : RecyclerView.ViewHolder(view) {
        private val image: ZoomImageView = view.findViewById(R.id.photo_image)
        private val progress: CircularProgressIndicator = view.findViewById(R.id.photo_progress)
        private val failed: View = view.findViewById(R.id.photo_failed)
        private var url: String? = null
        /** Номер загрузки: ответ на устаревшую (страница уже показывает другое фото) не рисуется. */
        private var token = 0

        init {
            image.setOnClickListener { onTap() }
            image.onDismissDrag = onDrag
            image.onDismissRelease = onRelease
            view.findViewById<View>(R.id.photo_retry).setOnClickListener { url?.let { load(it, retry = true) } }
        }

        fun bind(url: String) {
            this.url = url
            load(url, retry = false)
        }

        private fun load(url: String, retry: Boolean) {
            val current = ++token
            image.setImageDrawable(null)
            failed.isVisible = false
            progress.show()
            ImageCache.get(itemView.context).loadFull(url, maxSidePx, ignoreCooldown = retry) { bitmap ->
                if (current != token) return@loadFull
                progress.hide()
                if (bitmap != null) image.setImageBitmap(bitmap) else failed.isVisible = true
            }
        }

        fun clear() {
            token++
            image.setImageDrawable(null)
        }
    }

    companion object {
        private const val EXTRA_URLS = "photo.urls"
        private const val EXTRA_INDEX = "photo.index"
        private const val EXTRA_PAGES = "photo.pages"
        private const val STATE_URL = "photo.state_url"
        private const val MAX_SIDE_PX = 4096
        private const val DISMISS_MS = 180L
        private const val RETURN_MS = 200L

        /** Одно фото. */
        fun open(context: Context, url: String) = open(context, listOf(url), 0)

        /**
         * Несколько фото (запись, сообщение): откроется [index]-е, остальные можно листать. [pages] — страницы фото на
         * сайте (вровень с [urls]): с ними в просмотре есть кнопка «Страница фото» (лайки, комментарии).
         */
        fun open(context: Context, urls: List<String>, index: Int, pages: List<String> = emptyList(), source: android.view.View? = null) {
            val intent = Intent(context, PhotoViewerActivity::class.java)
                .putStringArrayListExtra(EXTRA_URLS, ArrayList(urls))
                .putExtra(EXTRA_INDEX, index)
            if (pages.size == urls.size && pages.any { it.isNotEmpty() }) intent.putStringArrayListExtra(EXTRA_PAGES, ArrayList(pages))
            // 4.55: просмотр раскрывается из нажатого фото.
            context.startActivity(intent, Motion.fromView(source))
        }
    }
}
