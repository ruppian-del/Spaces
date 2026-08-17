package com.arcinteractive.spaces.data.spaces

import android.content.Context
import com.arcinteractive.spaces.data.model.SpaceLinkAttachment
import com.arcinteractive.spaces.data.model.SpaceLinkModuleType
import org.json.JSONArray
import org.json.JSONObject

data class SpaceDraftRecord(
    val spaceId: String,
    val text: String,
    val updatedAt: Long,
    val spaceLinks: List<SpaceLinkAttachment> = emptyList(),
    val replyToMessageId: String? = null,
    val replyToSenderName: String? = null,
    val replyToType: String? = null,
    val replyToPreview: String? = null,
    val submittedQueuedMessageId: String? = null
) {
    val previewText: String?
        get() = text.trim().ifEmpty { spaceLinks.firstOrNull()?.title }
}

class SpaceDraftStore {
    fun loadDraft(context: Context, userId: String, spaceId: String): SpaceDraftRecord? {
        val prefs = prefs(context, userId)
        val raw = prefs.getString(spaceId, null) ?: return null
        val json = JSONObject(raw)
        return SpaceDraftRecord(
            spaceId = json.getString("spaceId"),
            text = json.getString("text"),
            updatedAt = json.getLong("updatedAt"),
            spaceLinks = json.optJSONArray("spaceLinks")?.toSpaceLinks().orEmpty(),
            replyToMessageId = json.optString("replyToMessageId").ifBlank { null },
            replyToSenderName = json.optString("replyToSenderName").ifBlank { null },
            replyToType = json.optString("replyToType").ifBlank { null },
            replyToPreview = json.optString("replyToPreview").ifBlank { null },
            submittedQueuedMessageId = json.optString("submittedQueuedMessageId").ifBlank { null }
        )
    }

    fun saveDraft(context: Context, userId: String, draft: SpaceDraftRecord) {
        prefs(context, userId).edit().putString(
            draft.spaceId,
            JSONObject().apply {
                put("spaceId", draft.spaceId)
                put("text", draft.text)
                put("updatedAt", draft.updatedAt)
                put("spaceLinks", JSONArray().apply {
                    draft.spaceLinks.forEach { link ->
                        put(JSONObject().apply {
                            put("id", link.id)
                            put("moduleType", link.moduleType.name.lowercase())
                            put("targetId", link.targetId)
                            put("title", link.title)
                            put("subtitle", link.subtitle)
                            put("icon", link.icon)
                            put("version", link.version)
                        })
                    }
                })
                put("replyToMessageId", draft.replyToMessageId)
                put("replyToSenderName", draft.replyToSenderName)
                put("replyToType", draft.replyToType)
                put("replyToPreview", draft.replyToPreview)
                put("submittedQueuedMessageId", draft.submittedQueuedMessageId)
            }.toString()
        ).apply()
    }

    fun clearDraft(context: Context, userId: String, spaceId: String) {
        prefs(context, userId).edit().remove(spaceId).apply()
    }

    fun draftPreviews(context: Context, userId: String): Map<String, String> {
        return prefs(context, userId).all.mapNotNull { (spaceId, raw) ->
            val json = (raw as? String)?.let(::JSONObject) ?: return@mapNotNull null
            val text = json.optString("text").trim()
            if (text.isEmpty()) null else spaceId to text
        }.toMap()
    }

    private fun prefs(context: Context, userId: String) =
        context.applicationContext.getSharedPreferences("space_drafts_$userId", Context.MODE_PRIVATE)

    private fun JSONArray.toSpaceLinks(): List<SpaceLinkAttachment> {
        return buildList {
            for (index in 0 until length()) {
                val json = optJSONObject(index) ?: continue
                val moduleType = json.optString("moduleType")
                    .ifBlank { null }
                    ?.let { raw -> SpaceLinkModuleType.entries.firstOrNull { it.name.equals(raw, ignoreCase = true) } }
                    ?: continue
                val targetId = json.optString("targetId").ifBlank { continue }
                val title = json.optString("title").ifBlank { continue }
                add(
                    SpaceLinkAttachment(
                        id = json.optString("id").ifBlank { targetId },
                        moduleType = moduleType,
                        targetId = targetId,
                        title = title,
                        subtitle = json.optString("subtitle").ifBlank { null },
                        icon = json.optString("icon").ifBlank { moduleType.icon },
                        version = json.optInt("version", 1)
                    )
                )
            }
        }
    }
}
