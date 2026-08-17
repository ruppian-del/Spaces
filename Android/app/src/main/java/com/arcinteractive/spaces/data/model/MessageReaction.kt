package com.arcinteractive.spaces.data.model

data class MessageReaction(
    val emoji: String,
    val count: Int,
    val isSelectedByCurrentUser: Boolean,
    val userNames: List<String> = emptyList()
)
