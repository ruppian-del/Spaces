package com.arcinteractive.spaces.data.model

import java.text.DateFormat
import java.util.Calendar
import java.util.Date
import java.util.TimeZone

data class SpaceEvent(
    val id: String,
    val spaceId: String,
    val title: String,
    val description: String,
    val location: String,
    val startDate: Date,
    val endDate: Date,
    val allDay: Boolean,
    val timezone: String,
    val createdBy: String,
    val createdByName: String,
    val createdAt: Date?,
    val updatedAt: Date?,
    val deleted: Boolean
) {
    val dateText: String
        get() = if (allDay) allDayDateFormatter.format(startDate) else dateFormatter.format(startDate)

    val timeText: String
        get() = if (allDay) {
            "All Day"
        } else {
            "${timeFormatter.format(startDate)} – ${timeFormatter.format(endDate)}"
        }
}

data class EditableSpaceEvent(
    val title: String = "",
    val description: String = "",
    val location: String = "",
    val startDate: Date = Date(),
    val endDate: Date = Calendar.getInstance().apply { time = Date(); add(Calendar.HOUR_OF_DAY, 1) }.time,
    val allDay: Boolean = false
) {
    val trimmedTitle: String
        get() = title.trim()

    val canSave: Boolean
        get() = trimmedTitle.isNotEmpty() && !endDate.before(startDate)

    companion object {
        fun fromEvent(event: SpaceEvent): EditableSpaceEvent {
            return EditableSpaceEvent(
                title = event.title,
                description = event.description,
                location = event.location,
                startDate = event.startDate,
                endDate = event.endDate,
                allDay = event.allDay
            )
        }
    }
}

sealed interface EventEditorMode {
    data object Create : EventEditorMode
    data class Edit(val event: SpaceEvent) : EventEditorMode

    val title: String
        get() = when (this) {
            Create -> "Add Event"
            is Edit -> "Edit Event"
        }
}

private val dateFormatter: DateFormat = DateFormat.getDateInstance(DateFormat.MEDIUM)
private val allDayDateFormatter: DateFormat = DateFormat.getDateInstance(DateFormat.FULL)
private val timeFormatter: DateFormat = DateFormat.getTimeInstance(DateFormat.SHORT)

private fun configureFormatters() {
    val zone = TimeZone.getDefault()
    dateFormatter.timeZone = zone
    allDayDateFormatter.timeZone = zone
    timeFormatter.timeZone = zone
}

@Suppress("unused")
private val eventFormatterConfig = run { configureFormatters() }
