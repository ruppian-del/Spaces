package com.arcinteractive.spaces.data.spaces

import android.net.Uri

object InviteLink {
    private const val Scheme = "spaces"
    private const val Host = "join"
    private const val CodeParameter = "code"

    fun build(code: String): Uri {
        return Uri.Builder()
            .scheme(Scheme)
            .authority(Host)
            .appendQueryParameter(CodeParameter, code.trim().uppercase())
            .build()
    }

    fun parse(uri: Uri?): String? {
        if (uri?.scheme?.lowercase() != Scheme || uri.host?.lowercase() != Host) {
            return null
        }

        return uri.getQueryParameter(CodeParameter)
            ?.trim()
            ?.uppercase()
            ?.takeIf { it.isNotEmpty() }
    }

    fun buildOrganization(code: String): Uri = Uri.Builder().scheme(Scheme).authority("organization-invite").appendQueryParameter(CodeParameter, code.trim().uppercase()).build()

    fun parseOrganization(uri: Uri?): String? = if (uri?.scheme?.lowercase() == Scheme && uri.host?.lowercase() == "organization-invite") uri.getQueryParameter(CodeParameter)?.trim()?.uppercase()?.takeIf { it.isNotEmpty() } else null
}
