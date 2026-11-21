package com.example.vkweb.ui

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Matrix
import android.graphics.RectF
import android.util.AttributeSet
import android.view.GestureDetector
import android.view.MotionEvent
import android.view.ScaleGestureDetector
import androidx.appcompat.widget.AppCompatImageView

/**
 * Картинка с масштабированием: щипок — приблизить/отдалить, перетаскивание — сдвиг,
 * двойное касание — приблизить в 2.5 раза или вернуть исходный вид, одиночное нажатие — клик.
 * Если задан [onPage], горизонтальный жест листает фото ([PageGesture]): у неувеличенного — сразу,
 * у увеличенного — только когда картинка уже упёрлась в край.
 *
 * Внутри ленты (просмотр фото): у увеличенной картинки жест забирает она сама (сдвиг, щипок), а лента получает его,
 * только когда картинка упёрлась в край в сторону жеста — тогда листается к соседнему фото.
 */
class ZoomImageView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
) : AppCompatImageView(context, attrs) {

    private val drawMatrix = Matrix()
    /** Один прямоугольник на все расчёты: жест — это десятки кадров в секунду, без мусора для сборщика. */
    private val bounds = RectF()
    private val baseMatrix = Matrix()
    private var scale = 1f

    /** Попросили листать: [PageGesture.NEXT] или [PageGesture.PREVIOUS]. null — листать нечего (одно фото). */
    var onPage: ((Int) -> Unit)? = null

    private var downX = 0f
    private var downY = 0f
    private var lastX = 0f
    /** В этом жесте были два пальца (масштабирование) — листанием он не считается. */
    private var pinched = false
    /** Жест уже перелистнул фото: второй раз за один жест не листаем. */
    private var paged = false
    private val density = resources.displayMetrics.density

    /**
     * Закрытие смахиванием вверх/вниз (3.90, [SwipeDismiss]): фото едет за пальцем ([onDismissDrag] — сдвиг), отпустили
     * — [onDismissRelease] (сдвиг, скорость). Только у неувеличенного фото и одним пальцем; null — выключено.
     */
    var onDismissDrag: ((Float) -> Unit)? = null
    var onDismissRelease: ((Float, Float) -> Unit)? = null
    private var dismissing = false
    /** Начало жеста на экране: само фото во время смахивания сдвигается, его координаты для этого не годятся. */
    private var downRawX = 0f
    private var downRawY = 0f
    private var velocity: android.view.VelocityTracker? = null
    private val touchSlop = android.view.ViewConfiguration.get(context).scaledTouchSlop.toFloat()

    private val scaleDetector = ScaleGestureDetector(context, object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
        override fun onScale(detector: ScaleGestureDetector): Boolean {
            val target = (scale * detector.scaleFactor).coerceIn(MIN_SCALE, MAX_SCALE)
            val factor = target / scale
            scale = target
            drawMatrix.postScale(factor, factor, detector.focusX, detector.focusY)
            applyMatrix()
            return true
        }
    })

    private val gestureDetector = GestureDetector(context, object : GestureDetector.SimpleOnGestureListener() {
        override fun onSingleTapConfirmed(e: MotionEvent): Boolean {
            performClick()
            return true
        }

        override fun onFling(e1: MotionEvent?, e2: MotionEvent, velocityX: Float, velocityY: Float): Boolean {
            if (e1 == null || pinched) return false
            return tryPage(PageGesture.direction(e2.x - e1.x, e2.y - e1.y, velocityX, width.toFloat(), isZoomed(), density))
        }

        override fun onScroll(e1: MotionEvent?, e2: MotionEvent, dx: Float, dy: Float): Boolean {
            if (scale <= MIN_SCALE) return false
            drawMatrix.postTranslate(-dx, -dy)
            applyMatrix()
            return true
        }

        override fun onDoubleTap(e: MotionEvent): Boolean {
            if (scale > MIN_SCALE) {
                reset()
            } else {
                scale = DOUBLE_TAP_SCALE
                drawMatrix.postScale(DOUBLE_TAP_SCALE, DOUBLE_TAP_SCALE, e.x, e.y)
                applyMatrix()
            }
            return true
        }
    })

    init {
        scaleType = ScaleType.MATRIX
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        reset()
    }

    override fun setImageBitmap(bm: android.graphics.Bitmap?) {
        super.setImageBitmap(bm)
        reset()
    }

    @SuppressLint("ClickableViewAccessibility")
    /** Можно ли ещё сдвинуть увеличенную картинку на [dx] (палец вправо — показать левую часть). */
    private fun canPan(dx: Float): Boolean {
        val d = drawable ?: return false
        val rect = bounds.apply { set(0f, 0f, d.intrinsicWidth.toFloat(), d.intrinsicHeight.toFloat()) }
        drawMatrix.mapRect(rect)
        return when {
            dx > 0 -> rect.left < -EDGE_SLOP
            dx < 0 -> rect.right > width + EDGE_SLOP
            else -> true
        }
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (handleDismiss(event)) return true
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                downX = event.x
                downY = event.y
                lastX = event.x
                pinched = false
                paged = false
                parent?.requestDisallowInterceptTouchEvent(isZoomed())
            }
            MotionEvent.ACTION_POINTER_DOWN -> {
                pinched = true
                // Щипок — масштаб, а не листание ленты.
                parent?.requestDisallowInterceptTouchEvent(true)
            }
            MotionEvent.ACTION_MOVE -> {
                if (isZoomed() && event.pointerCount == 1) {
                    // Увеличенная картинка двигается сама, пока есть куда; упёрлась в край — жест забирает лента.
                    parent?.requestDisallowInterceptTouchEvent(canPan(event.x - lastX))
                }
                lastX = event.x
            }
        }
        scaleDetector.onTouchEvent(event)
        if (scaleDetector.isInProgress) pinched = true
        gestureDetector.onTouchEvent(event)
        // Палец отпущен без броска, но прошёл достаточно далеко — тоже листаем (бросок обработан в onFling).
        if (event.actionMasked == MotionEvent.ACTION_UP && !pinched && !paged) {
            tryPage(PageGesture.direction(event.x - downX, event.y - downY, null, width.toFloat(), isZoomed(), density))
        }
        return true
    }

    /** Скорость — по экрану: само фото едет за пальцем, и его собственные координаты её занижали бы. */
    private fun track(tracker: android.view.VelocityTracker, event: MotionEvent) {
        val screen = MotionEvent.obtain(event)
        screen.setLocation(event.rawX, event.rawY)
        tracker.addMovement(screen)
        screen.recycle()
    }

    /** Смахивание для закрытия; true — жест его (масштаб, листание и касание его не видят). */
    private fun handleDismiss(event: MotionEvent): Boolean {
        val drag = onDismissDrag ?: return false
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                dismissing = false
                downRawX = event.rawX
                downRawY = event.rawY
                velocity?.recycle()
                velocity = android.view.VelocityTracker.obtain().also { track(it, event) }
                return false
            }
            MotionEvent.ACTION_MOVE -> {
                velocity?.let { track(it, event) }
                if (!dismissing && event.pointerCount == 1 && !pinched && !isZoomed() &&
                    SwipeDismiss.starts(event.rawX - downRawX, event.rawY - downRawY, touchSlop)
                ) {
                    dismissing = true
                    parent?.requestDisallowInterceptTouchEvent(true)
                    // Касание и долгое нажатие этого жеста отменяются.
                    val cancel = MotionEvent.obtain(event).apply { action = MotionEvent.ACTION_CANCEL }
                    gestureDetector.onTouchEvent(cancel)
                    cancel.recycle()
                }
                if (dismissing) drag(event.rawY - downRawY)
                return dismissing
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                val tracker = velocity
                velocity = null
                if (!dismissing) { tracker?.recycle(); return false }
                dismissing = false
                tracker?.let { track(it, event) }
                tracker?.computeCurrentVelocity(1000)
                val vy = tracker?.yVelocity ?: 0f
                tracker?.recycle()
                onDismissRelease?.invoke(if (event.actionMasked == MotionEvent.ACTION_CANCEL) 0f else event.rawY - downRawY, vy)
                return true
            }
            else -> return dismissing
        }
    }

    override fun performClick(): Boolean = super.performClick()

    private fun isZoomed() = scale > MIN_SCALE + SCALE_EPSILON

    private fun tryPage(direction: Int): Boolean {
        val callback = onPage
        if (direction == PageGesture.NONE || callback == null || !canPage(direction)) return false
        paged = true
        callback(direction)
        return true
    }

    /** Увеличенную картинку листаем только с края: иначе палец двигает её, а не перелистывает. */
    private fun canPage(direction: Int): Boolean {
        if (!isZoomed()) return true
        val d = drawable ?: return true
        val rect = bounds.apply { set(0f, 0f, d.intrinsicWidth.toFloat(), d.intrinsicHeight.toFloat()) }
        drawMatrix.mapRect(rect)
        return if (direction == PageGesture.NEXT) rect.right <= width + EDGE_PX else rect.left >= -EDGE_PX
    }

    /** Картинка целиком по центру. */
    private fun reset() {
        val d = drawable ?: return
        if (width == 0 || height == 0 || d.intrinsicWidth <= 0 || d.intrinsicHeight <= 0) return
        val fit = minOf(width.toFloat() / d.intrinsicWidth, height.toFloat() / d.intrinsicHeight)
        baseMatrix.reset()
        baseMatrix.postScale(fit, fit)
        baseMatrix.postTranslate((width - d.intrinsicWidth * fit) / 2f, (height - d.intrinsicHeight * fit) / 2f)
        drawMatrix.set(baseMatrix)
        scale = MIN_SCALE
        imageMatrix = drawMatrix
    }

    /** Не даём увести картинку за край экрана. */
    private fun applyMatrix() {
        val d = drawable ?: return
        val rect = bounds.apply { set(0f, 0f, d.intrinsicWidth.toFloat(), d.intrinsicHeight.toFloat()) }
        drawMatrix.mapRect(rect)
        val dx = when {
            rect.width() <= width -> (width - rect.width()) / 2f - rect.left
            rect.left > 0 -> -rect.left
            rect.right < width -> width - rect.right
            else -> 0f
        }
        val dy = when {
            rect.height() <= height -> (height - rect.height()) / 2f - rect.top
            rect.top > 0 -> -rect.top
            rect.bottom < height -> height - rect.bottom
            else -> 0f
        }
        drawMatrix.postTranslate(dx, dy)
        imageMatrix = drawMatrix
    }

    private companion object {
        const val MIN_SCALE = 1f
        const val MAX_SCALE = 8f
        const val DOUBLE_TAP_SCALE = 2.5f
        const val SCALE_EPSILON = 0.01f
        const val EDGE_PX = 2f
        /** Упёрлась в край: меньше этого сдвигать уже некуда (погрешность вычислений). */
        const val EDGE_SLOP = 1f
    }
}
