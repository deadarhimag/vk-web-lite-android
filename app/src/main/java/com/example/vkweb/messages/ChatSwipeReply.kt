package com.example.vkweb.messages

import android.graphics.Canvas
import android.view.HapticFeedbackConstants
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.ItemTouchHelper
import androidx.recyclerview.widget.RecyclerView
import com.example.vkweb.R
import com.example.vkweb.ui.Views
import com.google.android.material.color.MaterialColors

/**
 * «Ответить» жестом (3.94): сообщение тянут вправо — слева проступает стрелка; дотянули до порога (лёгкий отклик) и
 * отпустили — ответ на это сообщение, как из меню. Сообщение не уезжает с экрана: ход ограничен, после отпускания
 * пузырь возвращается на место. Отвечать можно не на всё — решает [canReply] (как пункт меню «Ответить»).
 */
class ChatSwipeReply(
    list: RecyclerView,
    private val adapter: ChatAdapter,
    private val canReply: (ChatMessage) -> Boolean,
    private val onReply: (ChatMessage) -> Unit,
) : ItemTouchHelper.Callback() {

    private val triggerPx = Views.dp(list, TRIGGER_DP).toFloat()
    private val maxPx = Views.dp(list, MAX_DP).toFloat()
    private val iconPx = Views.dp(list, ICON_DP)
    private val icon = ContextCompat.getDrawable(list.context, R.drawable.ic_reply)?.mutate()?.apply {
        setTint(MaterialColors.getColor(list, com.google.android.material.R.attr.colorOnSurfaceVariant))
    }

    /** Дотянули до порога и ещё держат: отпустят — будет ответ. */
    private var armed = false

    init {
        ItemTouchHelper(this).attachToRecyclerView(list)
    }

    private fun messageAt(holder: RecyclerView.ViewHolder): ChatMessage? =
        adapter.currentList.getOrNull(holder.bindingAdapterPosition)

    override fun getMovementFlags(recyclerView: RecyclerView, viewHolder: RecyclerView.ViewHolder): Int {
        val message = messageAt(viewHolder) ?: return 0
        return if (canReply(message)) makeMovementFlags(0, ItemTouchHelper.RIGHT) else 0
    }

    override fun onMove(recyclerView: RecyclerView, viewHolder: RecyclerView.ViewHolder, target: RecyclerView.ViewHolder) = false

    // Сообщение никогда не «смахивается» целиком: порог и скорость недостижимы, ответ — по отпусканию за порогом.
    override fun onSwiped(viewHolder: RecyclerView.ViewHolder, direction: Int) = Unit
    override fun getSwipeThreshold(viewHolder: RecyclerView.ViewHolder): Float = Float.MAX_VALUE
    override fun getSwipeEscapeVelocity(defaultValue: Float): Float = Float.MAX_VALUE
    override fun isLongPressDragEnabled(): Boolean = false

    override fun onChildDraw(
        c: Canvas, recyclerView: RecyclerView, viewHolder: RecyclerView.ViewHolder,
        dX: Float, dY: Float, actionState: Int, isCurrentlyActive: Boolean,
    ) {
        // До порога — за пальцем, дальше — туго, и не больше [MAX_DP].
        val x = if (dX <= triggerPx) dX.coerceAtLeast(0f) else (triggerPx + (dX - triggerPx) * RESISTANCE).coerceAtMost(maxPx)
        if (isCurrentlyActive) {
            val nowArmed = x >= triggerPx
            if (nowArmed && !armed) viewHolder.itemView.performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY)
            armed = nowArmed
        }
        icon?.let { d ->
            val item = viewHolder.itemView
            val progress = (x / triggerPx).coerceIn(0f, 1f)
            val left = item.left + ((x - iconPx) / 2f).toInt().coerceAtLeast(0)
            val top = item.top + (item.height - iconPx) / 2
            d.setBounds(left, top, left + iconPx, top + iconPx)
            d.alpha = (progress * 255).toInt()
            if (x > 0f) d.draw(c)
        }
        super.onChildDraw(c, recyclerView, viewHolder, x, dY, actionState, isCurrentlyActive)
    }

    override fun clearView(recyclerView: RecyclerView, viewHolder: RecyclerView.ViewHolder) {
        super.clearView(recyclerView, viewHolder)
        if (!armed) return
        armed = false
        messageAt(viewHolder)?.takeIf(canReply)?.let(onReply)
    }

    private companion object {
        const val TRIGGER_DP = 56
        const val MAX_DP = 80
        const val ICON_DP = 24
        const val RESISTANCE = 0.3f
    }
}
