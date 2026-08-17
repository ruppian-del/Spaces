package com.arcinteractive.spaces.data.auth

import com.arcinteractive.spaces.data.model.LinkedProvider

data class AuthSession(
    val uid: String,
    val displayName: String,
    val email: String?,
    val phoneNumber: String?,
    val providers: List<LinkedProvider>
)
