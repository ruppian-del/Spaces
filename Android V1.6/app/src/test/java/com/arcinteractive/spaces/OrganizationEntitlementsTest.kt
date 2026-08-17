package com.arcinteractive.spaces

import com.arcinteractive.spaces.data.model.OrganizationEntitlements
import com.arcinteractive.spaces.data.model.OrganizationUsage
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class OrganizationEntitlementsTest {
    private val entitlements = OrganizationEntitlements(
        peopleCapacity = 75,
        activeSpaceCapacity = 5,
        enabledModuleIds = setOf("announcements", "events", "members"),
        mediaStorageCapacityBytes = 1_000L
    )

    @Test
    fun capacitiesStopAtConfiguredLimit() {
        assertTrue(entitlements.allowsAddingPerson(currentPeople = 74))
        assertFalse(entitlements.allowsAddingPerson(currentPeople = 75))
        assertTrue(entitlements.allowsActivatingSpace(currentActiveSpaces = 4))
        assertFalse(entitlements.allowsActivatingSpace(currentActiveSpaces = 5))
    }

    @Test
    fun modulesAndStorageRequireExplicitEntitlement() {
        assertTrue(entitlements.allowsModule("announcements"))
        assertFalse(entitlements.allowsModule("files"))
        assertTrue(entitlements.allowsStorageIncrease(currentBytes = 900L, additionalBytes = 100L))
        assertFalse(entitlements.allowsStorageIncrease(currentBytes = 900L, additionalBytes = 101L))
    }

    @Test
    fun missingCapacityFailsClosed() {
        val unconfigured = OrganizationEntitlements(null, null, emptySet(), null)

        assertFalse(unconfigured.allowsAddingPerson(currentPeople = 0))
        assertFalse(unconfigured.allowsActivatingSpace(currentActiveSpaces = 0))
        assertFalse(unconfigured.allowsStorageIncrease(currentBytes = 0L, additionalBytes = 1L))
    }

    @Test
    fun storageReservationAllowsExactBoundaryAndRejectsOverflow() {
        assertTrue(entitlements.allowsStorageIncrease(currentBytes = 0L, additionalBytes = 1_000L))
        assertFalse(entitlements.allowsStorageIncrease(currentBytes = 1L, additionalBytes = 1_000L))
    }

    @Test
    fun storageReleaseReclaimsUsageWithoutGoingNegative() {
        val usage = OrganizationUsage(peopleCount = 10, activeSpaceCount = 2, mediaStorageBytes = 700L)
        assertEquals(500L, usage.afterStorageRelease(200L).mediaStorageBytes)
        assertEquals(0L, usage.afterStorageRelease(900L).mediaStorageBytes)
        assertEquals(700L, usage.afterStorageRelease(-10L).mediaStorageBytes)
    }
}
