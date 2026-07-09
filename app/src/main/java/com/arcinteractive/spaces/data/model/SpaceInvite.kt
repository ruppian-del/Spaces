package com.arcinteractive.spaces.data.model

import java.util.Date

data class SpaceInvite(
    val id: String,
    val code: String,
    val spaceId: String,
    val spaceName: String,
    val spaceEmoji: String,
    val createdBy: String,
    val createdAt: Date,
    val expiresAt: Date,
    val maxUses: Int,
    val usedCount: Int,
    val active: Boolean
) {
    val remainingUses: Int
        get() = (maxUses - usedCount).coerceAtLeast(0)
}
