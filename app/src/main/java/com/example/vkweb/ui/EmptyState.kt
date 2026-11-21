package com.example.vkweb.ui

import android.view.View
import android.widget.Button
import android.widget.ImageView
import android.widget.TextView
import androidx.annotation.DrawableRes
import androidx.annotation.StringRes
import com.example.vkweb.R

/**
 * Пустой экран, ошибка, «нечего показать» (4.51; разметка layout/view_empty_state.xml): значок, заголовок, пояснение
 * и до двух кнопок. Одно оформление для своих экранов ([PanelFrame]), списка диалогов и ошибки главного окна.
 *
 * @param root корень view_empty_state; [container] — что показывать и прятать (рамка вокруг, если она есть).
 */
class EmptyState(private val root: View, private val container: View = root) {

    /** Кнопка: подпись и действие. */
    class Action(@StringRes val label: Int, val run: () -> Unit)

    /** Что показать. Без кнопок — просто пояснение («ищем…»). */
    class Content(
        @StringRes val title: Int,
        @DrawableRes val icon: Int? = null,
        @StringRes val body: Int? = null,
        val action: Action? = null,
        val secondary: Action? = null,
    )

    private val icon: ImageView = root.findViewById(R.id.empty_icon)
    private val title: TextView = root.findViewById(R.id.empty_title)
    private val body: TextView = root.findViewById(R.id.empty_body)
    private val actions: View = root.findViewById(R.id.empty_actions)
    private val primary: Button = root.findViewById(R.id.empty_action)
    private val secondary: Button = root.findViewById(R.id.empty_action_secondary)

    val isShown: Boolean get() = container.visibility == View.VISIBLE

    fun show(content: Content) {
        if (content.icon != null) icon.setImageResource(content.icon)
        icon.visibility = if (content.icon != null) View.VISIBLE else View.GONE
        title.setText(content.title)
        if (content.body != null) body.setText(content.body)
        body.visibility = if (content.body != null) View.VISIBLE else View.GONE
        bind(primary, content.action)
        bind(secondary, content.secondary)
        actions.visibility = if (content.action != null || content.secondary != null) View.VISIBLE else View.GONE
        container.visibility = View.VISIBLE
    }

    fun hide() {
        container.visibility = View.GONE
    }

    private fun bind(button: Button, action: Action?) {
        button.visibility = if (action != null) View.VISIBLE else View.GONE
        if (action == null) {
            button.setOnClickListener(null)
            return
        }
        button.setText(action.label)
        button.setOnClickListener { action.run() }
    }
}
