package com.example.vkweb.messages

import androidx.appcompat.app.AppCompatActivity
import com.example.vkweb.R
import com.example.vkweb.outbox.ScheduleOptions
import com.example.vkweb.ui.Notice
import com.google.android.material.datepicker.CalendarConstraints
import com.google.android.material.datepicker.DateValidatorPointForward
import com.google.android.material.datepicker.MaterialDatePicker
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.timepicker.MaterialTimePicker
import com.google.android.material.timepicker.TimeFormat
import java.time.Instant
import java.time.LocalTime
import java.time.ZoneOffset

/**
 * Когда отправить отложенное сообщение (4.17): готовые варианты ([ScheduleOptions.quick]) и «Выбрать дату и время…»
 * (календарь и часы Material). Итог — момент в мс ([onPicked]).
 */
object ScheduleSendDialog {

    fun show(activity: AppCompatActivity, onPicked: (Long) -> Unit) {
        val options = ScheduleOptions.quick(System.currentTimeMillis())
        val labels = options.map { it.label } + activity.getString(R.string.schedule_custom)
        MaterialAlertDialogBuilder(activity)
            .setTitle(R.string.schedule_title)
            .setItems(labels.toTypedArray()) { _, which ->
                if (which < options.size) onPicked(options[which].at) else pickDate(activity, onPicked)
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    private fun pickDate(activity: AppCompatActivity, onPicked: (Long) -> Unit) {
        val today = MaterialDatePicker.todayInUtcMilliseconds()
        val picker = MaterialDatePicker.Builder.datePicker()
            .setTitleText(R.string.schedule_pick_date)
            .setSelection(today)
            .setCalendarConstraints(CalendarConstraints.Builder().setValidator(DateValidatorPointForward.from(today)).build())
            .build()
        picker.addOnPositiveButtonClickListener { utcMidnight ->
            // Выбор календаря — полночь UTC выбранного дня: берём саму дату, время — следующим окном.
            val date = Instant.ofEpochMilli(utcMidnight).atZone(ZoneOffset.UTC).toLocalDate()
            pickTime(activity) { time ->
                val at = ScheduleOptions.custom(date, time, System.currentTimeMillis())
                if (at == null) Notice.show(activity, R.string.schedule_in_past, long = true) else onPicked(at)
            }
        }
        picker.show(activity.supportFragmentManager, "schedule_date")
    }

    private fun pickTime(activity: AppCompatActivity, onTime: (LocalTime) -> Unit) {
        val soon = LocalTime.now().plusHours(1).withMinute(0)
        val picker = MaterialTimePicker.Builder()
            .setTimeFormat(TimeFormat.CLOCK_24H)
            .setHour(soon.hour)
            .setMinute(soon.minute)
            .setTitleText(R.string.schedule_pick_time)
            .build()
        picker.addOnPositiveButtonClickListener { onTime(LocalTime.of(picker.hour, picker.minute)) }
        picker.show(activity.supportFragmentManager, "schedule_time")
    }
}
