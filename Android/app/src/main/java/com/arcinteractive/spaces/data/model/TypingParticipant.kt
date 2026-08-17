package com.arcinteractive.spaces.data.model

import java.util.Date

data class TypingParticipant(
    val id: String,
    val displayName: String,
    val isTyping: Boolean,
    val lastUpdated: Date
)
