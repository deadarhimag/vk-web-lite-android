package com.example.vkweb.messages

import android.app.Activity
import android.widget.ScrollView
import android.widget.TextView
import androidx.core.widget.TextViewCompat
import com.example.vkweb.R
import com.google.android.material.color.MaterialColors
import com.google.android.material.dialog.MaterialAlertDialogBuilder

/**
 * «Выделить текст» в меню сообщения (4.2): текст сообщения в окне, где его можно выделить частично и скопировать
 * кусок. В самом пузыре выделение выключено: нажатие открывает меню, а движение вправо — ответ жестом.
 */
object SelectableTextDialog {

    fun show(activity: Activity, text: String) {
        val density = activity.resources.displayMetrics.density
        val side = (PADDING_DP * density).toInt()
        val view = TextView(activity).apply {
            TextViewCompat.setTextAppearance(this, com.google.android.material.R.style.TextAppearance_Material3_BodyLarge)
            setTextColor(MaterialColors.getColor(this, com.google.android.material.R.attr.colorOnSurface))
            this.text = text
            setTextIsSelectable(true)
            setPadding(side, side / 2, side, 0)
        }
        MaterialAlertDialogBuilder(activity)
            .setTitle(R.string.select_text)
            .setView(ScrollView(activity).apply { addView(view) })
            .setPositiveButton(R.string.action_close, null)
            .show()
    }

    private const val PADDING_DP = 24
}
