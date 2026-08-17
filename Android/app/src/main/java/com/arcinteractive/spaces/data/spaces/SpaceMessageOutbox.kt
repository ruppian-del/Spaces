package com.arcinteractive.spaces.data.spaces

import android.content.Context
import android.util.Log
import com.arcinteractive.spaces.data.model.LocalMessageDeliveryState
import com.arcinteractive.spaces.data.model.LinkPreviewData
import com.arcinteractive.spaces.data.model.MediaType
import com.arcinteractive.spaces.data.model.MessageReplyContext
import com.arcinteractive.spaces.data.model.MessageType
import com.arcinteractive.spaces.data.model.Space
import com.arcinteractive.spaces.data.model.SpaceLinkAttachment
import com.arcinteractive.spaces.data.model.SpaceLinkModuleType
import com.arcinteractive.spaces.data.model.SpaceMessage
import com.arcinteractive.spaces.data.model.SpaceMedia
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.Date
import java.util.UUID

data class QueuedMessageAttachment(
    val id: String,
    val dataFileName: String,
    val previewFileName: String,
    val mimeType: String,
    val mediaCategory: String,
    val isVideo: Boolean
)

data class QueuedSpaceMessage(
    val id: String,
    val space: Space,
    val kind: String,
    val createdAt: Long,
    val text: String?,
    val linkPreview: LinkPreviewData?,
    val spaceLinks: List<SpaceLinkAttachment>,
    val caption: String?,
    val replyContext: MessageReplyContext?,
    val attachments: List<QueuedMessageAttachment>,
    val state: LocalMessageDeliveryState,
    val failureMessage: String?
)

class SpaceMessageOutbox(
    private val spaceService: SpaceService = SpaceService(),
    private val connectivityMonitor: ConnectivityMonitor = ConnectivityMonitor()
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val _items = MutableStateFlow<List<QueuedSpaceMessage>>(emptyList())
    val items: StateFlow<List<QueuedSpaceMessage>> = _items.asStateFlow()

    var onSendSucceeded: ((QueuedSpaceMessage, SpaceMessage) -> Unit)? = null

    private var processingJob: Job? = null
    private var started = false

    fun start(context: Context) {
        if (started) return
        started = true
        connectivityMonitor.start(context.applicationContext)
        load(context.applicationContext)
        scope.launch {
            connectivityMonitor.isConnected.collectLatest { isConnected ->
                if (isConnected) {
                    processQueueIfNeeded(context.applicationContext)
                } else {
                    markActiveItemsWaitingForConnection()
                }
            }
        }
    }

    fun enqueueText(
        context: Context,
        space: Space,
        text: String,
        linkPreview: LinkPreviewData?,
        spaceLinks: List<SpaceLinkAttachment>,
        replyContext: MessageReplyContext?
    ): String {
        start(context)
        val messageId = UUID.randomUUID().toString()
        val queued = QueuedSpaceMessage(
            id = messageId,
            space = space,
            kind = "text",
            createdAt = System.currentTimeMillis(),
            text = text,
            linkPreview = linkPreview,
            spaceLinks = spaceLinks,
            caption = null,
            replyContext = replyContext,
            attachments = emptyList(),
            state = if (connectivityMonitor.isConnected.value) LocalMessageDeliveryState.Sending else LocalMessageDeliveryState.WaitingForConnection,
            failureMessage = null
        )
        updateItems(context) { it + queued }
        processQueueIfNeeded(context)
        return messageId
    }

    fun enqueueMedia(
        context: Context,
        space: Space,
        attachments: List<QueuedMediaSelection>,
        caption: String?,
        replyContext: MessageReplyContext?
    ): String {
        start(context)
        val messageId = UUID.randomUUID().toString()
        val queuedAttachments = attachments.map { selection ->
            persistAttachment(
                context = context.applicationContext,
                messageId = messageId,
                mediaBytes = selection.mediaBytes,
                previewBytes = selection.previewBytes,
                mimeType = selection.mimeType,
                mediaCategory = selection.mediaCategory,
                isVideo = selection.isVideo
            )
        }
        val queued = QueuedSpaceMessage(
            id = messageId,
            space = space,
            kind = "media",
            createdAt = System.currentTimeMillis(),
            text = null,
            linkPreview = null,
            spaceLinks = emptyList(),
            caption = caption,
            replyContext = replyContext,
            attachments = queuedAttachments,
            state = if (connectivityMonitor.isConnected.value) LocalMessageDeliveryState.Uploading else LocalMessageDeliveryState.WaitingForConnection,
            failureMessage = null
        )
        updateItems(context) { it + queued }
        processQueueIfNeeded(context)
        return messageId
    }

    fun retry(context: Context, messageId: String) {
        updateItems(context) { current ->
            current.map { item ->
                if (item.id == messageId) {
                    item.copy(
                        state = if (connectivityMonitor.isConnected.value) {
                            if (item.kind == "media") LocalMessageDeliveryState.Uploading else LocalMessageDeliveryState.Sending
                        } else {
                            LocalMessageDeliveryState.WaitingForConnection
                        },
                        failureMessage = null
                    )
                } else {
                    item
                }
            }
        }
        processQueueIfNeeded(context)
    }

    fun delete(context: Context, messageId: String) {
        val deletedItem = _items.value.firstOrNull { it.id == messageId }
        updateItems(context) { current -> current.filterNot { it.id == messageId } }
        deletedItem?.let { cleanupAttachments(context.applicationContext, it) }
    }

    fun itemsForSpace(spaceId: String): List<QueuedSpaceMessage> {
        return _items.value.filter { it.space.id == spaceId }.sortedBy { it.createdAt }
    }

    private fun processQueueIfNeeded(context: Context) {
        if (processingJob?.isActive == true) return
        processingJob = scope.launch {
            while (true) {
                if (!connectivityMonitor.isConnected.value) {
                    markActiveItemsWaitingForConnection()
                    return@launch
                }
                val nextItem = _items.value
                    .sortedBy { it.createdAt }
                    .firstOrNull {
                        it.state == LocalMessageDeliveryState.Sending ||
                            it.state == LocalMessageDeliveryState.Uploading ||
                            it.state == LocalMessageDeliveryState.WaitingForConnection
                    } ?: return@launch

                try {
                    val sentMessage = send(context.applicationContext, nextItem)
                    updateItems(context) { current -> current.filterNot { it.id == nextItem.id } }
                    cleanupAttachments(context.applicationContext, nextItem)
                    onSendSucceeded?.invoke(nextItem, sentMessage)
                } catch (error: Throwable) {
                    Log.e("SpaceMessageOutbox", "Failed sending queued message ${nextItem.id}", error)
                    if (isConnectivityError(error)) {
                        updateState(context, nextItem.id, LocalMessageDeliveryState.WaitingForConnection, null)
                        return@launch
                    } else {
                        updateState(
                            context,
                            nextItem.id,
                            LocalMessageDeliveryState.Failed,
                            if (nextItem.kind == "media") "Upload failed" else "Failed to send"
                        )
                    }
                }
            }
        }
    }

    private suspend fun send(context: Context, item: QueuedSpaceMessage): SpaceMessage {
        return when (item.kind) {
            "text" -> {
                updateState(context, item.id, LocalMessageDeliveryState.Sending, null)
                spaceService.sendTextMessage(
                    context = context,
                    space = item.space,
                    text = item.text.orEmpty(),
                    linkPreview = item.linkPreview,
                    spaceLinks = item.spaceLinks,
                    replyContext = item.replyContext,
                    messageId = item.id
                )
            }
            else -> {
                updateState(context, item.id, LocalMessageDeliveryState.Uploading, null)
                val attachment = item.attachments.first()
                val mediaBytes = File(attachmentsDirectory(context), attachment.dataFileName).readBytes()
                if (attachment.isVideo) {
                    spaceService.sendVideoMessage(
                        context = context,
                        space = item.space,
                        videoBytes = mediaBytes,
                        caption = item.caption,
                        mimeType = attachment.mimeType,
                        replyContext = item.replyContext,
                        messageId = item.id
                    )
                } else {
                    val imageAttachments = item.attachments.map { queuedAttachment ->
                        val attachmentBytes = File(
                            attachmentsDirectory(context),
                            queuedAttachment.dataFileName
                        ).readBytes()
                        SpaceService.ImageAttachmentUpload(
                            data = attachmentBytes,
                            previewBytes = File(
                                attachmentsDirectory(context),
                                queuedAttachment.previewFileName
                            ).readBytes(),
                            mediaCategory = queuedAttachment.mediaCategory,
                            mimeType = queuedAttachment.mimeType
                        )
                    }
                    spaceService.sendImageMessage(
                        context = context,
                        space = item.space,
                        imageAttachments = imageAttachments,
                        caption = item.caption,
                        replyContext = item.replyContext,
                        messageId = item.id
                    )
                }
            }
        }
    }

    private fun persistAttachment(
        context: Context,
        messageId: String,
        mediaBytes: ByteArray,
        previewBytes: ByteArray,
        mimeType: String,
        mediaCategory: String,
        isVideo: Boolean
    ): QueuedMessageAttachment {
        val attachmentId = UUID.randomUUID().toString()
        val dataFileName = "$messageId-$attachmentId-data"
        val previewFileName = "$messageId-$attachmentId-preview"
        File(attachmentsDirectory(context), dataFileName).writeBytes(mediaBytes)
        File(attachmentsDirectory(context), previewFileName).writeBytes(previewBytes)
        return QueuedMessageAttachment(
            id = attachmentId,
            dataFileName = dataFileName,
            previewFileName = previewFileName,
            mimeType = mimeType,
            mediaCategory = mediaCategory,
            isVideo = isVideo
        )
    }

    private fun cleanupAttachments(context: Context, item: QueuedSpaceMessage) {
        item.attachments.forEach { attachment ->
            File(attachmentsDirectory(context), attachment.dataFileName).delete()
            File(attachmentsDirectory(context), attachment.previewFileName).delete()
        }
    }

    private fun updateState(context: Context, messageId: String, state: LocalMessageDeliveryState, failure: String?) {
        updateItems(context) { current ->
            current.map { item ->
                if (item.id == messageId) item.copy(state = state, failureMessage = failure) else item
            }
        }
    }

    private fun markActiveItemsWaitingForConnection() {
        _items.value = _items.value.map { item ->
            if (item.state == LocalMessageDeliveryState.Sending || item.state == LocalMessageDeliveryState.Uploading) {
                item.copy(state = LocalMessageDeliveryState.WaitingForConnection)
            } else {
                item
            }
        }
    }

    private fun load(context: Context) {
        val file = persistenceFile(context)
        if (!file.exists()) {
            _items.value = emptyList()
            return
        }
        val array = JSONArray(file.readText())
        val loaded = mutableListOf<QueuedSpaceMessage>()
        for (index in 0 until array.length()) {
            val json = array.getJSONObject(index)
            loaded += json.toQueuedMessage()
        }
        _items.value = loaded.map { item ->
            if (item.state == LocalMessageDeliveryState.Sending || item.state == LocalMessageDeliveryState.Uploading) {
                item.copy(state = LocalMessageDeliveryState.WaitingForConnection)
            } else {
                item
            }
        }
    }

    private fun updateItems(context: Context, transform: (List<QueuedSpaceMessage>) -> List<QueuedSpaceMessage>) {
        _items.value = transform(_items.value)
        persist(context)
    }

    private fun persist(context: Context) {
        val array = JSONArray()
        _items.value.forEach { item -> array.put(item.toJson()) }
        persistenceFile(context).parentFile?.mkdirs()
        persistenceFile(context).writeText(array.toString())
    }

    private fun persistenceFile(context: Context): File =
        File(context.filesDir, "space_outbox/outbox.json")

    private fun attachmentsDirectory(context: Context): File =
        File(context.filesDir, "space_outbox/attachments").also { it.mkdirs() }

    private fun isConnectivityError(error: Throwable): Boolean {
        val message = error.localizedMessage.orEmpty().lowercase()
        return message.contains("network") || message.contains("offline") || message.contains("connection")
    }

    private fun QueuedSpaceMessage.toJson(): JSONObject {
        return JSONObject().apply {
            put("id", id)
            put("kind", kind)
            put("createdAt", createdAt)
            put("text", text)
            put("linkPreview", linkPreview?.let {
                JSONObject().apply {
                    put("originalUrl", it.originalUrl)
                    put("canonicalUrl", it.canonicalUrl)
                    put("domain", it.domain)
                    put("title", it.title)
                    put("summary", it.summary)
                    put("siteName", it.siteName)
                    put("imageDataBase64", it.imageDataBase64)
                    put("imageMimeType", it.imageMimeType)
                }
            })
            put("spaceLinks", JSONArray().apply {
                spaceLinks.forEach { link ->
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
            put("caption", caption)
            put("state", state.name)
            put("failureMessage", failureMessage)
            put("space", JSONObject().apply {
                put("id", space.id)
                put("name", space.name)
                put("emoji", space.emoji)
                put("colorHex", space.colorHex)
                put("description", space.description)
                put("template", space.template.name)
                put("ownerId", space.ownerId)
                put("memberIds", JSONArray(space.memberIds))
                put("enabledModules", JSONArray(space.enabledModules.map { it.id }))
                put("moduleOrder", JSONArray(space.moduleOrder.map { it.id }))
            })
            put("replyContext", replyContext?.let {
                JSONObject().apply {
                    put("messageId", it.messageId)
                    put("senderName", it.senderName)
                    put("type", it.type)
                    put("preview", it.preview)
                }
            })
            put("attachments", JSONArray().apply {
                attachments.forEach { attachment ->
                    put(JSONObject().apply {
                        put("id", attachment.id)
                        put("dataFileName", attachment.dataFileName)
                        put("previewFileName", attachment.previewFileName)
                        put("mimeType", attachment.mimeType)
                        put("mediaCategory", attachment.mediaCategory)
                        put("isVideo", attachment.isVideo)
                    })
                }
            })
        }
    }

    private fun JSONObject.toQueuedMessage(): QueuedSpaceMessage {
        val spaceJson = getJSONObject("space")
        val enabledModules = mutableListOf<com.arcinteractive.spaces.data.model.SpaceModule>()
        val enabledArray = spaceJson.optJSONArray("enabledModules") ?: JSONArray()
        for (index in 0 until enabledArray.length()) {
            val moduleId = enabledArray.getString(index)
            com.arcinteractive.spaces.data.model.SpaceModules.configurable.firstOrNull { it.id == moduleId }?.let(enabledModules::add)
        }
        val moduleOrder = mutableListOf<com.arcinteractive.spaces.data.model.SpaceModule>()
        val orderArray = spaceJson.optJSONArray("moduleOrder") ?: JSONArray()
        for (index in 0 until orderArray.length()) {
            val moduleId = orderArray.getString(index)
            com.arcinteractive.spaces.data.model.SpaceModules.all.firstOrNull { it.id == moduleId }?.let(moduleOrder::add)
        }
        val members = mutableListOf<String>()
        val memberArray = spaceJson.optJSONArray("memberIds") ?: JSONArray()
        for (index in 0 until memberArray.length()) {
            members += memberArray.getString(index)
        }
        val attachmentsArray = optJSONArray("attachments") ?: JSONArray()
        val attachments = mutableListOf<QueuedMessageAttachment>()
        for (index in 0 until attachmentsArray.length()) {
            val json = attachmentsArray.getJSONObject(index)
            attachments += QueuedMessageAttachment(
                id = json.getString("id"),
                dataFileName = json.getString("dataFileName"),
                previewFileName = json.getString("previewFileName"),
                mimeType = json.getString("mimeType"),
                mediaCategory = json.getString("mediaCategory"),
                isVideo = json.optBoolean("isVideo", false)
            )
        }
        val replyJson = optJSONObject("replyContext")
        val linkPreviewJson = optJSONObject("linkPreview")
        val spaceLinksJson = optJSONArray("spaceLinks")
        return QueuedSpaceMessage(
            id = getString("id"),
            space = Space(
                id = spaceJson.getString("id"),
                name = spaceJson.getString("name"),
                emoji = spaceJson.getString("emoji"),
                colorHex = spaceJson.getString("colorHex"),
                description = spaceJson.getString("description"),
                template = com.arcinteractive.spaces.data.model.SpaceTemplate.valueOf(spaceJson.getString("template")),
                ownerId = spaceJson.getString("ownerId"),
                memberIds = members,
                unreadCount = null,
                enabledModules = enabledModules,
                moduleOrder = moduleOrder
            ),
            kind = getString("kind"),
            createdAt = getLong("createdAt"),
            text = optString("text").ifBlank { null },
            linkPreview = linkPreviewJson?.let {
                val originalUrl = it.optString("originalUrl").ifBlank { return@let null }
                val domain = it.optString("domain").ifBlank { return@let null }
                val title = it.optString("title").ifBlank { return@let null }
                LinkPreviewData(
                    originalUrl = originalUrl,
                    canonicalUrl = it.optString("canonicalUrl").ifBlank { null },
                    domain = domain,
                    title = title,
                    summary = it.optString("summary").ifBlank { null },
                    siteName = it.optString("siteName").ifBlank { null },
                    imageDataBase64 = it.optString("imageDataBase64").ifBlank { null },
                    imageMimeType = it.optString("imageMimeType").ifBlank { null }
                )
            },
            spaceLinks = spaceLinksJson?.toSpaceLinks().orEmpty(),
            caption = optString("caption").ifBlank { null },
            replyContext = replyJson?.let {
                MessageReplyContext(
                    messageId = it.getString("messageId"),
                    senderName = it.getString("senderName"),
                    type = it.getString("type"),
                    preview = it.getString("preview")
                )
            },
            attachments = attachments,
            state = LocalMessageDeliveryState.valueOf(getString("state")),
            failureMessage = optString("failureMessage").ifBlank { null }
        )
    }

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

data class QueuedMediaSelection(
    val mediaBytes: ByteArray,
    val previewBytes: ByteArray,
    val mimeType: String,
    val mediaCategory: String,
    val isVideo: Boolean
)
