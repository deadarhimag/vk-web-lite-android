package com.example.vkweb.ui

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.view.View
import android.widget.Toast
import androidx.annotation.StringRes
import com.google.android.material.snackbar.Snackbar

/**
 * Короткие сообщения о действиях (3.94): полоса Snackbar внизу экрана в цветах темы, а не системный Toast — тот на
 * Android 12+ обрезается до двух строк, показывается со значком приложения и выглядит чужим. Если показать негде
 * (экран закрывается, вызов из службы) — обычный Toast, как раньше.
 */
object Notice {

    /** Экран, у которого внизу своя панель (поле ввода): полоса встаёт над ней, а не поверх. */
    interface Anchor {
        fun noticeAnchor(): View?
    }

    fun show(context: Context, @StringRes text: Int, long: Boolean = false) = show(context, context.getString(text), long)

    /**
     * С действием на полосе («Вернуть»): полоса держится дольше. Без окна (Toast) действия нет — тогда показывается
     * только текст.
     */
    fun withAction(context: Context, @StringRes text: Int, @StringRes action: Int, onAction: () -> Unit) =
        show(context, context.getString(text), long = true, action = context.getString(action), onAction = onAction)

    fun show(context: Context, text: CharSequence, long: Boolean = false, action: CharSequence? = null, onAction: (() -> Unit)? = null) {
        val activity = context.activity()
        val root = activity?.takeIf { !it.isFinishing && !it.isDestroyed }?.findViewById<View>(android.R.id.content)
        if (root == null || !root.isAttachedToWindow) {
            Toast.makeText(context.applicationContext, text, if (long) Toast.LENGTH_LONG else Toast.LENGTH_SHORT).show()
            return
        }
        val bar = Snackbar.make(root, text, if (long) Snackbar.LENGTH_LONG else Snackbar.LENGTH_SHORT)
        (activity as? Anchor)?.noticeAnchor()?.takeIf { it.isShown }?.let { bar.setAnchorView(it) }
        if (action != null && onAction != null) bar.setAction(action) { onAction() }
        bar.show()
    }

    private tailrec fun Context.activity(): Activity? = when (this) {
        is Activity -> this
        is ContextWrapper -> baseContext.activity()
        else -> null
    }
}
