package com.arcinteractive.spaces

import com.arcinteractive.spaces.data.model.Space
import com.arcinteractive.spaces.data.model.SpaceModules
import com.arcinteractive.spaces.data.model.SpaceTemplate
import org.junit.Assert.assertEquals
import org.junit.Test

class SpaceCustomizationTest {
    @Test
    fun defaultModuleOrderKeepsSettingsLast() {
        val moduleIds = SpaceTemplate.Business.defaultModuleOrder.map { it.id }

        assertEquals(
            listOf(
                SpaceModules.General.id,
                SpaceModules.Photos.id,
                SpaceModules.Members.id,
                SpaceModules.Announcements.id,
                SpaceModules.Events.id,
                SpaceModules.Files.id,
                SpaceModules.Polls.id,
                SpaceModules.Settings.id
            ),
            moduleIds
        )
    }

    @Test
    fun visibleModulesRespectPersistedOrderAndHideDisabledModules() {
        val space = Space(
            id = "space-1",
            name = "Test",
            emoji = "🏠",
            colorHex = "#4F46E5",
            description = "Description",
            template = SpaceTemplate.Custom,
            ownerId = "owner",
            memberIds = listOf("owner"),
            unreadCount = null,
            enabledModules = listOf(
                SpaceModules.General,
                SpaceModules.Photos,
                SpaceModules.Members,
                SpaceModules.Polls
            ),
            moduleOrder = listOf(
                SpaceModules.Members,
                SpaceModules.Polls,
                SpaceModules.General,
                SpaceModules.Files,
                SpaceModules.Photos,
                SpaceModules.Events,
                SpaceModules.Settings
            )
        )

        assertEquals(
            listOf(
                SpaceModules.Members.id,
                SpaceModules.Polls.id,
                SpaceModules.General.id,
                SpaceModules.Photos.id,
                SpaceModules.Settings.id
            ),
            space.modules.map { it.id }
        )
    }
}
