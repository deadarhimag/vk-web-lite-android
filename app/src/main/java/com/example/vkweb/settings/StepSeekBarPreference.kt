package com.example.vkweb.settings

import android.content.Context
import android.util.AttributeSet
import android.widget.SeekBar
import android.widget.TextView
import androidx.preference.PreferenceViewHolder
import androidx.preference.SeekBarPreference
import com.example.vkweb.ui.SliderSteps

/**
 * Ползунок настроек с шагом [getSeekBarIncrement] (4.24; первый — «Размер текста», шаг 10 %).
 *
 * Обычный [SeekBarPreference] шаг применяет только к клавишам: пальцем ползунок встаёт на любое число, и подпись
 * рядом показывает 113 %. В 4.23 значение подравнивалось уже после того, как ползунок отпустили. Здесь подпись при
 * перетаскивании сразу показывает ровное значение, а при отпускании ползунок встаёт на него. Порядок записи тот же,
 * что у родителя: [callChangeListener], затем [setValue].
 */
class StepSeekBarPreference @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
) : SeekBarPreference(context, attrs) {

    override fun onBindViewHolder(holder: PreferenceViewHolder) {
        super.onBindViewHolder(holder)
        val bar = holder.findViewById(androidx.preference.R.id.seekbar) as? SeekBar ?: return
        val label = holder.findViewById(androidx.preference.R.id.seekbar_value) as? TextView
        // Слушатель родителя заменяется целиком: он записал бы промежуточное значение.
        bar.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            private var tracking = false

            override fun onProgressChanged(seekBar: SeekBar, progress: Int, fromUser: Boolean) {
                if (!fromUser) return
                // Клавиши (стрелки, внешняя клавиатура) меняют значение без касания — записать сразу, как у родителя.
                if (tracking) label?.text = snapped(progress).toString() else commit(seekBar, label)
            }

            override fun onStartTrackingTouch(seekBar: SeekBar) {
                tracking = true
            }

            override fun onStopTrackingTouch(seekBar: SeekBar) {
                tracking = false
                commit(seekBar, label)
            }
        })
    }

    private fun snapped(progress: Int): Int = SliderSteps.snap(min + progress, min, max, seekBarIncrement)

    private fun commit(bar: SeekBar, label: TextView?) {
        val wanted = snapped(bar.progress)
        if (wanted != value && callChangeListener(wanted)) value = wanted
        // Ползунок и подпись — на записанном (или прежнем, если запись отклонена).
        bar.progress = value - min
        label?.text = value.toString()
    }
}
