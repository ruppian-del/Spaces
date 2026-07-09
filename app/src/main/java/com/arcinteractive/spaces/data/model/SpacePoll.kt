package com.arcinteractive.spaces.data.model

import java.util.Date

data class SpacePollOption(
    val id: String,
    val text: String
)

data class SpacePollVote(
    val id: String,
    val userId: String,
    val optionIds: List<String>,
    val createdAt: Date?,
    val updatedAt: Date?
)

data class SpacePoll(
    val id: String,
    val spaceId: String,
    val question: String,
    val options: List<SpacePollOption>,
    val createdBy: String,
    val createdByName: String,
    val createdAt: Date?,
    val updatedAt: Date?,
    val closesAt: Date?,
    val allowMultipleVotes: Boolean,
    val anonymous: Boolean,
    val deleted: Boolean,
    val votes: List<SpacePollVote> = emptyList()
) {
    val isClosed: Boolean
        get() = closesAt?.let { !it.after(Date()) } ?: false

    val totalVotes: Int
        get() = votes.size

    fun votesCount(optionId: String): Int = votes.count { it.optionIds.contains(optionId) }

    fun percentage(optionId: String): Float {
        if (totalVotes == 0) return 0f
        return votesCount(optionId).toFloat() / totalVotes.toFloat()
    }

    fun selectedOptionIds(userId: String?): Set<String> {
        if (userId.isNullOrBlank()) return emptySet()
        return votes.firstOrNull { it.userId == userId }?.optionIds?.toSet() ?: emptySet()
    }
}
