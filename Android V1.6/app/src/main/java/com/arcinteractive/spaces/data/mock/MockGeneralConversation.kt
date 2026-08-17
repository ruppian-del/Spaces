package com.arcinteractive.spaces.data.mock

import com.arcinteractive.spaces.data.model.MessageType
import com.arcinteractive.spaces.data.model.Space
import com.arcinteractive.spaces.data.model.SpaceMedia
import com.arcinteractive.spaces.data.model.SpaceMessage

object MockGeneralConversation {
    fun messagesFor(space: Space): List<SpaceMessage> {
        return listOf(
            SpaceMessage(
                id = "${space.id}-mom-1",
                senderName = "Mom",
                text = "Who's bringing dessert?",
                timestamp = "6:42 PM",
                isOutgoing = false
            ),
            SpaceMessage(
                id = "${space.id}-ian-1",
                senderName = "Ian",
                text = "I'll grill.",
                timestamp = "6:44 PM",
                isOutgoing = true,
                deliveryStatus = "Delivered"
            ),
            SpaceMessage(
                id = "${space.id}-dad-1",
                senderName = "Dad",
                text = "I'll bring drinks.",
                timestamp = "6:46 PM",
                isOutgoing = false
            ),
            SpaceMessage(
                id = "${space.id}-sarah-1",
                senderName = "Sarah",
                text = "I can bring chips.",
                timestamp = "6:47 PM",
                isOutgoing = false
            ),
            SpaceMessage(
                id = "${space.id}-mom-photo",
                senderName = "Mom",
                media = SpaceMedia(
                    id = "${space.id}-media-1",
                    type = MessageType.Image,
                    placeholderIconName = MessageType.Image.placeholderIconName,
                    caption = "I found the cake idea.",
                    senderName = "Mom",
                    timestamp = "6:48 PM"
                ),
                timestamp = "6:48 PM",
                isOutgoing = false
            ),
            SpaceMessage(
                id = "${space.id}-sarah-meme",
                senderName = "Sarah",
                media = SpaceMedia(
                    id = "${space.id}-media-2",
                    type = MessageType.Meme,
                    placeholderIconName = MessageType.Meme.placeholderIconName,
                    caption = "Mood if Dad forgets the ice again.",
                    senderName = "Sarah",
                    timestamp = "6:49 PM"
                ),
                timestamp = "6:49 PM",
                isOutgoing = false
            ),
            SpaceMessage(
                id = "${space.id}-ian-2",
                senderName = "Ian",
                text = "Perfect. I'll fire up the grill around 5.",
                timestamp = "6:50 PM",
                isOutgoing = true,
                deliveryStatus = "Seen"
            ),
            SpaceMessage(
                id = "${space.id}-ian-video",
                senderName = "Ian",
                media = SpaceMedia(
                    id = "${space.id}-media-3",
                    type = MessageType.Video,
                    placeholderIconName = MessageType.Video.placeholderIconName,
                    caption = "Quick grill setup preview.",
                    senderName = "Ian",
                    timestamp = "6:51 PM"
                ),
                timestamp = "6:51 PM",
                isOutgoing = true,
                deliveryStatus = "Delivered"
            )
        )
    }

    fun photosMediaFor(space: Space): List<SpaceMedia> {
        return messagesFor(space)
            .mapNotNull { it.media }
            .filter { it.type.isPhotosModuleSupported }
    }
}
