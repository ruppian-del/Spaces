package com.arcinteractive.spaces.data.mock

import com.arcinteractive.spaces.data.model.Ping
import com.arcinteractive.spaces.data.model.Space
import com.arcinteractive.spaces.data.model.SpaceModule
import com.arcinteractive.spaces.data.model.SpaceModules
import com.arcinteractive.spaces.data.model.SpaceTemplate
import java.util.Date

object MockSpacesRepository {
    val sharedModules = listOf(
        SpaceModules.General,
        SpaceModules.Photos,
        SpaceModules.Events,
        SpaceModules.Members
    )

    val spaces = listOf(
        Space("rupp-family", "Rupp Family", "🏡", "#3A6EA5", "3 new messages", SpaceTemplate.Family, "mock-ian", listOf("mock-ian"), 3, SpaceTemplate.Family.defaultEnabledModules),
        Space("arcinteractive", "ArcInteractive", "⚡", "#2F855A", "Standup starts at 9:00 AM", SpaceTemplate.Business, "mock-ian", listOf("mock-ian"), 7, SpaceTemplate.Business.defaultEnabledModules),
        Space("friends", "Friends", "🫶", "#D97706", "Weekend trip ideas", SpaceTemplate.Friends, "mock-ian", listOf("mock-ian"), null, SpaceTemplate.Friends.defaultEnabledModules),
        Space("photography-club", "Photography Club", "📷", "#7C3AED", "Monthly challenge is live", SpaceTemplate.Community, "mock-ian", listOf("mock-ian"), 1, SpaceTemplate.Community.defaultEnabledModules)
    )

    val pings = listOf(
        Ping("ping-1", listOf("mock-ian", "mock-mom"), listOf("Ian", "Mom"), listOf("🧑‍💻", "👩"), Date(System.currentTimeMillis() - 300_000), "text", Date(System.currentTimeMillis() - 3_600_000), Date(System.currentTimeMillis() - 300_000), 1),
        Ping("ping-2", listOf("mock-ian", "mock-sarah"), listOf("Ian", "Sarah"), listOf("🧑‍💻", "👩‍🦰"), Date(System.currentTimeMillis() - 1_140_000), "text", Date(System.currentTimeMillis() - 7_200_000), Date(System.currentTimeMillis() - 1_140_000), 2),
        Ping("ping-3", listOf("mock-dad", "mock-ian"), listOf("Dad", "Ian"), listOf("👨", "🧑‍💻"), Date(System.currentTimeMillis() - 3_600_000), "text", Date(System.currentTimeMillis() - 10_800_000), Date(System.currentTimeMillis() - 3_600_000), 0)
    )

    fun spaceById(id: String): Space? = spaces.firstOrNull { it.id == id }

    fun pingMessages(ping: Ping) = when (ping.title("mock-ian")) {
        "Mom" -> listOf(
            com.arcinteractive.spaces.data.model.SpaceMessage(id = "mom-1", senderName = "Mom", text = "Hey, call me when you can.", timestamp = "6:12 PM", isOutgoing = false),
            com.arcinteractive.spaces.data.model.SpaceMessage(id = "ian-1", senderName = "Ian", text = "I can in about 10 minutes.", timestamp = "6:14 PM", isOutgoing = true, deliveryStatus = "Delivered"),
            com.arcinteractive.spaces.data.model.SpaceMessage(id = "mom-2", senderName = "Mom", text = "Perfect.", timestamp = "6:15 PM", isOutgoing = false),
            com.arcinteractive.spaces.data.model.SpaceMessage(id = "mom-3", senderName = "Mom", text = "Call me when you can", timestamp = "6:21 PM", isOutgoing = false)
        )

        "Sarah" -> listOf(
            com.arcinteractive.spaces.data.model.SpaceMessage(id = "sarah-1", senderName = "Sarah", text = "Did you need the photo?", timestamp = "4:40 PM", isOutgoing = false),
            com.arcinteractive.spaces.data.model.SpaceMessage(id = "ian-2", senderName = "Ian", text = "Yeah, where did you drop it?", timestamp = "4:42 PM", isOutgoing = true, deliveryStatus = "Seen"),
            com.arcinteractive.spaces.data.model.SpaceMessage(id = "sarah-2", senderName = "Sarah", text = "I sent it in the Family Space", timestamp = "4:43 PM", isOutgoing = false)
        )

        else -> listOf(
            com.arcinteractive.spaces.data.model.SpaceMessage(id = "dad-1", senderName = "Dad", text = "Take a look at the grill cover.", timestamp = "1:08 PM", isOutgoing = false),
            com.arcinteractive.spaces.data.model.SpaceMessage(id = "ian-3", senderName = "Ian", text = "Just saw it.", timestamp = "1:12 PM", isOutgoing = true, deliveryStatus = "Delivered"),
            com.arcinteractive.spaces.data.model.SpaceMessage(id = "dad-2", senderName = "Dad", text = "Looks good", timestamp = "1:15 PM", isOutgoing = false)
        )
    }
}
