package com.arcinteractive.spaces.data.model

import java.text.DateFormat
import java.util.Calendar
import java.util.Date

data class ActivityItem(
    val id: String,
    val spaceId: String,
    val spaceName: String,
    val spaceEmoji: String,
    val actorId: String,
    val actorName: String,
    val actorEmoji: String?,
    val type: ActivityType,
    val title: String,
    val subtitle: String?,
    val targetId: String?,
    val targetType: ActivityTargetType?,
    val createdAt: Date?,
    val readBy: List<String>
) {
    fun isUnread(currentUserId: String?): Boolean {
        return currentUserId != null && !readBy.contains(currentUserId)
    }

    val section: ActivitySection
        get() = ActivitySection.resolve(createdAt ?: Date())

    val timestampText: String
        get() = relativeTimeFormatter.format(createdAt ?: Date())

    val primaryText: String
        get() = "$actorName $title"
}

enum class ActivityType {
    SpaceCreated,
    MemberJoined,
    MessageSent,
    PhotoShared,
    VideoShared,
    FileUploaded,
    PollCreated,
    PollVoted,
    EventCreated,
    EventUpdated,
    ReactionAdded,
    ReplyAdded,
    AnnouncementCreated,
    RoomCreated,
    RoomMessageSent,
    ListCreated,
    NoteCreated
}

enum class ActivityTargetType {
    Space,
    General,
    Photos,
    Files,
    Polls,
    Events,
    Members,
    Announcements,
    Rooms,
    Lists,
    Notes
}

enum class ActivitySection(val label: String) {
    Today("Today"),
    Yesterday("Yesterday"),
    ThisWeek("This Week"),
    Older("Older");

    companion object {
        fun resolve(date: Date): ActivitySection {
            val calendar = Calendar.getInstance()
            return when {
                isSameDay(date, Date()) -> Today
                isSameDay(date, Calendar.getInstance().apply { add(Calendar.DAY_OF_YEAR, -1) }.time) -> Yesterday
                date.after(Calendar.getInstance().apply { add(Calendar.DAY_OF_YEAR, -7) }.time) -> ThisWeek
                else -> Older
            }
        }

        private fun isSameDay(lhs: Date, rhs: Date): Boolean {
            val calendar = Calendar.getInstance()
            calendar.time = lhs
            val lhsYear = calendar.get(Calendar.YEAR)
            val lhsDay = calendar.get(Calendar.DAY_OF_YEAR)
            calendar.time = rhs
            return lhsYear == calendar.get(Calendar.YEAR) && lhsDay == calendar.get(Calendar.DAY_OF_YEAR)
        }
    }
}

private val relativeTimeFormatter: DateFormat = DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT)
