package com.arcinteractive.spaces.data.calendar

import android.content.Intent
import android.provider.CalendarContract
import com.arcinteractive.spaces.data.model.SpaceEvent
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

data class DeviceCalendarEvent(
    val title: String,
    val startDateMillis: Long,
    val endDateMillis: Long,
    val allDay: Boolean,
    val location: String,
    val notes: String
)

class DeviceCalendarService {
    fun buildInsertIntent(event: SpaceEvent): Intent {
        val resolvedEvent = resolveEvent(event)
        return Intent(Intent.ACTION_INSERT).apply {
            data = CalendarContract.Events.CONTENT_URI
            putExtra(CalendarContract.Events.TITLE, resolvedEvent.title)
            putExtra(CalendarContract.EXTRA_EVENT_BEGIN_TIME, resolvedEvent.startDateMillis)
            putExtra(CalendarContract.EXTRA_EVENT_END_TIME, resolvedEvent.endDateMillis)
            putExtra(CalendarContract.Events.ALL_DAY, resolvedEvent.allDay)
            putExtra(CalendarContract.Events.EVENT_LOCATION, resolvedEvent.location)
            putExtra(CalendarContract.Events.DESCRIPTION, resolvedEvent.notes)
            putExtra(CalendarContract.Reminders.MINUTES, 15)
        }
    }

    private fun resolveEvent(event: SpaceEvent): DeviceCalendarEvent {
        return DeviceCalendarEvent(
            title = event.title,
            startDateMillis = event.startDate.time,
            endDateMillis = event.endDate.time,
            allDay = event.allDay,
            location = event.location,
            notes = "${event.description}\n\nCreated by ${event.createdByName}"
        )
    }
}
