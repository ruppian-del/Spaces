package com.arcinteractive.spaces.data.organization

import android.content.Context
import com.arcinteractive.spaces.data.auth.AuthService
import com.arcinteractive.spaces.data.auth.UserProfileService
import com.arcinteractive.spaces.data.model.Organization
import com.arcinteractive.spaces.data.model.OrganizationEntitlements
import com.arcinteractive.spaces.data.model.OrganizationStatus
import com.arcinteractive.spaces.data.model.OrganizationUsage
import com.arcinteractive.spaces.data.model.OrganizationOwnedSpace
import com.arcinteractive.spaces.data.model.OrganizationMember
import com.arcinteractive.spaces.data.model.OrganizationRole
import com.arcinteractive.spaces.data.model.OrganizationMemberStatus
import com.arcinteractive.spaces.data.model.OrganizationInvite
import com.google.firebase.FirebaseApp
import com.google.firebase.Timestamp
import com.google.firebase.firestore.FieldValue
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.ListenerRegistration
import com.arcinteractive.spaces.BuildConfig
import kotlinx.coroutines.suspendCancellableCoroutine
import java.util.Date
import java.util.UUID
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

class OrganizationService(
    private val authService: AuthService = AuthService(),
    private val userProfileService: UserProfileService = UserProfileService()
) {
    suspend fun createFoundingOrganization(context: Context, name: String): Organization {
        val firestore = firestoreOrThrow(context)
        val session = authService.currentSession(context) ?: error("Sign in before creating an organization.")
        val trimmedName = name.trim()
        require(trimmedName.isNotEmpty()) { "Enter the organization name." }
        val foundingMemberName = userProfileService.fetchUserProfile(context, session.uid)?.displayName?.trim()
            .takeUnless { it.isNullOrEmpty() } ?: session.displayName

        val organizationReference = firestore.collection("organizations").document()
        val entitlements = OrganizationEntitlements.Foundation
        val batch = firestore.batch()
        batch.set(organizationReference, mapOf(
            "name" to trimmedName,
            "status" to OrganizationStatus.Active.wireValue,
            "memberIds" to listOf(session.uid),
            "createdAt" to FieldValue.serverTimestamp(),
            "entitlements" to mapOf<String, Any?>(
                "peopleCapacity" to entitlements.peopleCapacity,
                "activeSpaceCapacity" to entitlements.activeSpaceCapacity,
                "enabledModuleIds" to entitlements.enabledModuleIds.sorted(),
                "mediaStorageCapacityBytes" to entitlements.mediaStorageCapacityBytes
            ),
            "usage" to mapOf("peopleCount" to 1, "activeSpaceCount" to 0, "mediaStorageBytes" to 0L)
        ))
        batch.set(organizationReference.collection("members").document(session.uid), mapOf(
            "userId" to session.uid,
            "displayName" to foundingMemberName,
            "email" to session.email,
            "role" to "primary_admin",
            "status" to "active",
            "joinedAt" to FieldValue.serverTimestamp()
        ))
        suspendCancellableCoroutine<Unit> { continuation ->
            batch.commit().addOnSuccessListener { continuation.resume(Unit) {} }
                .addOnFailureListener(continuation::resumeWithException)
        }
        return Organization(
            id = organizationReference.id,
            name = trimmedName,
            status = OrganizationStatus.Active,
            entitlements = entitlements,
            usage = OrganizationUsage(1, 0, 0L),
            createdAt = Date()
        )
    }

    fun listenToOrganizationsForCurrentUser(
        context: Context,
        onUpdate: (Result<List<Organization>>) -> Unit
    ): ListenerRegistration? {
        val firestore = FirebaseApp.getApps(context).takeIf { it.isNotEmpty() }?.let { FirebaseFirestore.getInstance() }
            ?: run { onUpdate(Result.success(emptyList())); return null }
        val session = authService.currentSession(context) ?: run { onUpdate(Result.success(emptyList())); return null }
        return firestore.collection("organizations")
            .whereArrayContains("memberIds", session.uid)
            .addSnapshotListener { snapshot, error ->
                if (error != null) onUpdate(Result.failure(error))
                else onUpdate(Result.success(snapshot?.documents.orEmpty().mapNotNull(::mapOrganization)))
            }
    }

    fun listenToOwnedSpaces(
        context: Context,
        organization: Organization,
        onUpdate: (Result<List<OrganizationOwnedSpace>>) -> Unit
    ): ListenerRegistration? {
        val firestore = FirebaseApp.getApps(context).takeIf { it.isNotEmpty() }?.let { FirebaseFirestore.getInstance() }
            ?: run { onUpdate(Result.success(emptyList())); return null }
        var memberListeners: List<ListenerRegistration> = emptyList()
        var spaces: List<OrganizationOwnedSpace> = emptyList()
        fun publish() = onUpdate(Result.success(spaces))
        val organizationListener = firestore.collection("spaces").whereEqualTo("organizationId", organization.id)
            .addSnapshotListener { snapshot, error ->
                if (error != null) onUpdate(Result.failure(error))
                else {
                    memberListeners.forEach { it.remove() }
                    spaces = snapshot?.documents.orEmpty().map { document ->
                    val data = document.data ?: emptyMap()
                    val memberIds = (data["memberIds"] as? List<*>)?.filterIsInstance<String>().orEmpty()
                    OrganizationOwnedSpace(document.id, data["name"] as? String ?: "Untitled Space", data["emoji"] as? String ?: "🏠", memberIds.size, memberIds, data["isArchived"] as? Boolean == true)
                    }
                    val actualMemberIds = mutableMapOf<String, List<String>>()
                    memberListeners = snapshot?.documents.orEmpty().map { document ->
                        document.reference.collection("members").addSnapshotListener { membersSnapshot, membersError ->
                            if (membersError != null) {
                                onUpdate(Result.failure(membersError))
                                return@addSnapshotListener
                            }
                            actualMemberIds[document.id] = membersSnapshot?.documents.orEmpty().mapNotNull {
                                (it.getString("userId") ?: it.id).trim().takeIf(String::isNotEmpty)
                            }
                            spaces = spaces.map { space ->
                                actualMemberIds[space.id]?.let { ids -> space.copy(memberCount = ids.size, memberIds = ids) } ?: space
                            }
                            publish()
                        }
                    }
                    publish()
                }
            }
        return object : ListenerRegistration {
            override fun remove() {
                organizationListener.remove()
                memberListeners.forEach { it.remove() }
            }
        }
    }

    fun listenToMembers(context: Context, organization: Organization, onUpdate: (Result<List<OrganizationMember>>) -> Unit): ListenerRegistration? {
        val firestore = FirebaseApp.getApps(context).takeIf { it.isNotEmpty() }?.let { FirebaseFirestore.getInstance() }
            ?: run { onUpdate(Result.success(emptyList())); return null }
        return firestore.collection("organizations").document(organization.id).collection("members").addSnapshotListener { snapshot, error ->
            if (error != null) onUpdate(Result.failure(error)) else onUpdate(Result.success(snapshot?.documents.orEmpty().mapNotNull { document ->
                val data = document.data ?: emptyMap()
                val userId = data["userId"] as? String ?: document.id
                val role = OrganizationRole.entries.firstOrNull { it.wireValue == data["role"] } ?: OrganizationRole.Member
                OrganizationMember(document.id, userId, data["displayName"] as? String ?: "Member", data["email"] as? String, role, OrganizationMemberStatus.Active, (data["joinedAt"] as? Timestamp)?.toDate())
            }))
        }
    }

    suspend fun applyFoundationEntitlementsIfUnconfigured(context: Context, organization: Organization) {
        val current = organization.entitlements
        if (current.peopleCapacity != null || current.activeSpaceCapacity != null || current.mediaStorageCapacityBytes != null || current.enabledModuleIds.isNotEmpty()) return
        val foundation = OrganizationEntitlements.Foundation
        firestoreOrThrow(context).collection("organizations").document(organization.id).update(
            "entitlements", mapOf(
                "peopleCapacity" to foundation.peopleCapacity,
                "activeSpaceCapacity" to foundation.activeSpaceCapacity,
                "enabledModuleIds" to foundation.enabledModuleIds.sorted(),
                "mediaStorageCapacityBytes" to foundation.mediaStorageCapacityBytes
            )
        ).awaitResult()
    }

    suspend fun requireActiveSpaceCapacity(context: Context, organizationId: String) {
        val firestore = firestoreOrThrow(context)
        val limit = effectiveEntitlements(context, organizationId).activeSpaceCapacity
            ?: error("This organization has reached its active Space limit.")
        val spaces = suspendCancellableCoroutine<com.google.firebase.firestore.QuerySnapshot> { continuation ->
            firestore.collection("spaces").whereEqualTo("organizationId", organizationId).get().addOnSuccessListener { continuation.resume(it) {} }.addOnFailureListener(continuation::resumeWithException)
        }
        val activeCount = spaces.documents.count { it.getBoolean("isArchived") != true }
        require(activeCount < limit) { "This organization has reached its active Space limit." }
    }

    suspend fun requireUniqueMemberCapacity(context: Context, organizationId: String, admittingUserId: String) {
        val firestore = firestoreOrThrow(context)
        val limit = effectiveEntitlements(context, organizationId).peopleCapacity
            ?: error("This organization has reached its people limit.")
        val spaces = suspendCancellableCoroutine<com.google.firebase.firestore.QuerySnapshot> { continuation ->
            firestore.collection("spaces").whereEqualTo("organizationId", organizationId).get().addOnSuccessListener { continuation.resume(it) {} }.addOnFailureListener(continuation::resumeWithException)
        }
        val uniqueMemberIds = mutableSetOf<String>()
        val organizationMembers = suspendCancellableCoroutine<com.google.firebase.firestore.QuerySnapshot> { continuation ->
            firestore.collection("organizations").document(organizationId).collection("members").get()
                .addOnSuccessListener { continuation.resume(it) {} }.addOnFailureListener(continuation::resumeWithException)
        }
        uniqueMemberIds += organizationMembers.documents.map { (it.getString("userId") ?: it.id).trim() }.filter { it.isNotEmpty() }
        spaces.documents.forEach { space ->
            val members = suspendCancellableCoroutine<com.google.firebase.firestore.QuerySnapshot> { continuation ->
                space.reference.collection("members").get().addOnSuccessListener { continuation.resume(it) {} }.addOnFailureListener(continuation::resumeWithException)
            }
            uniqueMemberIds += members.documents.map { (it.getString("userId") ?: it.id).trim() }.filter { it.isNotEmpty() }
        }
        if (admittingUserId in uniqueMemberIds) return
        require(uniqueMemberIds.size < limit) { "This organization has reached its people limit." }
    }

    suspend fun requireModuleEntitlement(context: Context, organizationId: String, moduleId: String) {
        require(moduleId in effectiveEntitlements(context, organizationId).enabledModuleIds) {
            "This module is not included in the organization's enabled modules."
        }
    }

    suspend fun effectiveEntitlements(context: Context, organizationId: String): OrganizationEntitlements {
        val document = suspendCancellableCoroutine<com.google.firebase.firestore.DocumentSnapshot> { continuation ->
            firestoreOrThrow(context).collection("organizations").document(organizationId).get()
                .addOnSuccessListener { continuation.resume(it) {} }.addOnFailureListener(continuation::resumeWithException)
        }
        val values = effectiveEntitlementData(document.data.orEmpty())
        val stored = OrganizationEntitlements(
            peopleCapacity = (values["peopleCapacity"] as? Number)?.toInt(),
            activeSpaceCapacity = (values["activeSpaceCapacity"] as? Number)?.toInt(),
            enabledModuleIds = (values["enabledModuleIds"] as? List<*>)?.filterIsInstance<String>()?.toSet().orEmpty(),
            mediaStorageCapacityBytes = (values["mediaStorageCapacityBytes"] as? Number)?.toLong()
        )
        return stored
    }

    suspend fun setDebugEntitlementOverrides(context: Context, organizationId: String, entitlements: OrganizationEntitlements) {
        check(BuildConfig.DEBUG) { "Debug entitlement overrides are unavailable in release builds." }
        firestoreOrThrow(context).collection("organizations").document(organizationId).update(
            "debugEntitlementOverrides", mapOf<String, Any?>(
                "peopleCapacity" to entitlements.peopleCapacity,
                "activeSpaceCapacity" to entitlements.activeSpaceCapacity,
                "enabledModuleIds" to entitlements.enabledModuleIds.sorted(),
                "mediaStorageCapacityBytes" to entitlements.mediaStorageCapacityBytes
            )
        ).awaitResult()
    }

    suspend fun clearDebugEntitlementOverrides(context: Context, organizationId: String) {
        check(BuildConfig.DEBUG) { "Debug entitlement overrides are unavailable in release builds." }
        firestoreOrThrow(context).collection("organizations").document(organizationId)
            .update("debugEntitlementOverrides", FieldValue.delete()).awaitResult()
    }

    suspend fun reserveStorage(context: Context, spaceId: String, bytes: Long): String? {
        require(bytes >= 0) { "Invalid upload size." }
        val firestore = firestoreOrThrow(context)
        val space = suspendCancellableCoroutine<com.google.firebase.firestore.DocumentSnapshot> { continuation ->
            firestore.collection("spaces").document(spaceId).get().addOnSuccessListener { continuation.resume(it) {} }
                .addOnFailureListener(continuation::resumeWithException)
        }
        val organizationId = space.getString("organizationId") ?: return null
        val limit = effectiveEntitlements(context, organizationId).mediaStorageCapacityBytes
            ?: error("This organization has reached its pooled storage limit.")
        val reference = firestore.collection("organizations").document(organizationId)
        suspendCancellableCoroutine<Unit> { continuation ->
            firestore.runTransaction { transaction ->
                val snapshot = transaction.get(reference)
                val current = snapshot.getLong("usage.mediaStorageBytes") ?: 0L
                require(current <= limit - bytes) { "This organization has reached its pooled storage limit." }
                transaction.update(reference, "usage.mediaStorageBytes", current + bytes)
            }.addOnSuccessListener { continuation.resume(Unit) {} }.addOnFailureListener(continuation::resumeWithException)
        }
        return organizationId
    }

    suspend fun releaseStorage(context: Context, organizationId: String?, bytes: Long) {
        if (organizationId == null || bytes <= 0) return
        val firestore = firestoreOrThrow(context)
        val reference = firestore.collection("organizations").document(organizationId)
        runCatching {
            suspendCancellableCoroutine<Unit> { continuation ->
                firestore.runTransaction { transaction ->
                    val snapshot = transaction.get(reference)
                    val current = snapshot.getLong("usage.mediaStorageBytes") ?: 0L
                    transaction.update(reference, "usage.mediaStorageBytes", maxOf(0L, current - bytes))
                }.addOnSuccessListener { continuation.resume(Unit) {} }.addOnFailureListener(continuation::resumeWithException)
            }
        }
    }

    suspend fun releaseStorageForSpace(context: Context, spaceId: String, bytes: Long) {
        if (bytes <= 0) return
        val document = suspendCancellableCoroutine<com.google.firebase.firestore.DocumentSnapshot> { continuation ->
            firestoreOrThrow(context).collection("spaces").document(spaceId).get()
                .addOnSuccessListener { continuation.resume(it) {} }.addOnFailureListener(continuation::resumeWithException)
        }
        releaseStorage(context, document.getString("organizationId"), bytes)
    }

    suspend fun addPerson(context: Context, organization: Organization, email: String, role: OrganizationRole) {
        val firestore = firestoreOrThrow(context)
        val normalizedEmail = email.trim().lowercase()
        require(normalizedEmail.isNotEmpty()) { "Enter an email." }
        val user = suspendCancellableCoroutine<com.google.firebase.firestore.DocumentSnapshot> { continuation ->
            firestore.collection("users").whereEqualTo("email", normalizedEmail).limit(1).get()
                .addOnSuccessListener { snapshot -> snapshot.documents.firstOrNull()?.let { continuation.resume(it) {} } ?: continuation.resumeWithException(IllegalArgumentException("No Spaces account uses that email yet.")) }
                .addOnFailureListener(continuation::resumeWithException)
        }
        val reference = firestore.collection("organizations").document(organization.id)
        requireUniqueMemberCapacity(context, organization.id, user.id)
        val batch = firestore.batch()
        batch.update(reference, mapOf("memberIds" to FieldValue.arrayUnion(user.id), "usage.peopleCount" to FieldValue.increment(1)))
        batch.set(reference.collection("members").document(user.id), mapOf("userId" to user.id, "displayName" to (user.getString("displayName") ?: "Member"), "email" to normalizedEmail, "role" to role.wireValue, "status" to "active", "joinedAt" to FieldValue.serverTimestamp()))
        suspendCancellableCoroutine<Unit> { continuation -> batch.commit().addOnSuccessListener { continuation.resume(Unit) {} }.addOnFailureListener(continuation::resumeWithException) }
    }

    suspend fun createInvite(context: Context, organization: Organization, role: OrganizationRole): OrganizationInvite {
        val firestore = firestoreOrThrow(context)
        val session = authService.currentSession(context) ?: error("Sign in before creating an invite.")
        val code = UUID.randomUUID().toString().replace("-", "").take(8).uppercase()
        suspendCancellableCoroutine<Unit> { continuation ->
            firestore.collection("organizationInvites").document(code).set(mapOf("organizationId" to organization.id, "organizationName" to organization.name, "role" to role.wireValue, "createdBy" to session.uid, "createdAt" to FieldValue.serverTimestamp(), "active" to true))
                .addOnSuccessListener { continuation.resume(Unit) {} }.addOnFailureListener(continuation::resumeWithException)
        }
        return OrganizationInvite(code, organization.id, organization.name, role)
    }

    suspend fun updateRole(context: Context, organization: Organization, member: OrganizationMember, role: OrganizationRole) {
        require(member.role != OrganizationRole.PrimaryAdministrator && role != OrganizationRole.PrimaryAdministrator) { "The primary administrator cannot be changed." }
        firestoreOrThrow(context).collection("organizations").document(organization.id).collection("members").document(member.id)
            .update("role", role.wireValue)
            .awaitResult()
    }

    suspend fun removeMember(context: Context, organization: Organization, member: OrganizationMember) {
        require(member.role != OrganizationRole.PrimaryAdministrator) { "The primary administrator cannot be removed." }
        val firestore = firestoreOrThrow(context)
        val batch = firestore.batch()
        val organizationReference = firestore.collection("organizations").document(organization.id)
        batch.update(organizationReference, mapOf(
            "memberIds" to FieldValue.arrayRemove(member.userId),
            "usage.peopleCount" to FieldValue.increment(-1)
        ))
        batch.delete(organizationReference.collection("members").document(member.id))
        suspendCancellableCoroutine<Unit> { continuation -> batch.commit().addOnSuccessListener { continuation.resume(Unit) {} }.addOnFailureListener(continuation::resumeWithException) }
    }

    suspend fun redeemInvite(context: Context, code: String) {
        val firestore = firestoreOrThrow(context)
        val session = authService.currentSession(context) ?: error("Sign in before joining an organization.")
        val inviteReference = firestore.collection("organizationInvites").document(code.trim().uppercase())
        val invite = suspendCancellableCoroutine<com.google.firebase.firestore.DocumentSnapshot> { continuation -> inviteReference.get().addOnSuccessListener { continuation.resume(it) {} }.addOnFailureListener(continuation::resumeWithException) }
        require(invite.getBoolean("active") == true) { "That organization invite is no longer valid." }
        val organizationId = invite.getString("organizationId") ?: error("Invalid organization invite.")
        val role = OrganizationRole.entries.first { it.wireValue == invite.getString("role") }
        requireUniqueMemberCapacity(context, organizationId, session.uid)
        val batch = firestore.batch(); val org = firestore.collection("organizations").document(organizationId)
        batch.update(org, mapOf("memberIds" to FieldValue.arrayUnion(session.uid), "usage.peopleCount" to FieldValue.increment(1)))
        batch.set(org.collection("members").document(session.uid), mapOf("userId" to session.uid, "displayName" to session.displayName, "email" to session.email, "role" to role.wireValue, "status" to "active", "inviteCode" to inviteReference.id, "joinedAt" to FieldValue.serverTimestamp()))
        batch.update(inviteReference, mapOf("active" to false, "usedBy" to session.uid, "usedAt" to FieldValue.serverTimestamp()))
        suspendCancellableCoroutine<Unit> { continuation -> batch.commit().addOnSuccessListener { continuation.resume(Unit) {} }.addOnFailureListener(continuation::resumeWithException) }
    }

    private fun firestoreOrThrow(context: Context): FirebaseFirestore {
        check(FirebaseApp.getApps(context).isNotEmpty()) { "Organization setup is unavailable until Firebase is configured." }
        return FirebaseFirestore.getInstance()
    }

    private suspend fun com.google.android.gms.tasks.Task<Void>.awaitResult() {
        suspendCancellableCoroutine<Unit> { continuation ->
            addOnSuccessListener { continuation.resume(Unit) {} }.addOnFailureListener(continuation::resumeWithException)
        }
    }

    private fun mapOrganization(document: com.google.firebase.firestore.DocumentSnapshot): Organization? {
        val data = document.data ?: return null
        val name = data["name"] as? String ?: return null
        val entitlements = effectiveEntitlementData(data)
        val usage = data["usage"] as? Map<*, *> ?: emptyMap<Any, Any>()
        return Organization(
            id = document.id,
            name = name,
            status = if (data["status"] == OrganizationStatus.Suspended.wireValue) OrganizationStatus.Suspended else OrganizationStatus.Active,
            entitlements = OrganizationEntitlements(
                peopleCapacity = (entitlements["peopleCapacity"] as? Number)?.toInt(),
                activeSpaceCapacity = (entitlements["activeSpaceCapacity"] as? Number)?.toInt(),
                enabledModuleIds = ((entitlements["enabledModuleIds"] as? List<*>)?.filterIsInstance<String>() ?: emptyList()).toSet(),
                mediaStorageCapacityBytes = (entitlements["mediaStorageCapacityBytes"] as? Number)?.toLong()
            ),
            usage = OrganizationUsage(
                peopleCount = (usage["peopleCount"] as? Number)?.toInt() ?: 0,
                activeSpaceCount = (usage["activeSpaceCount"] as? Number)?.toInt() ?: 0,
                mediaStorageBytes = (usage["mediaStorageBytes"] as? Number)?.toLong() ?: 0L
            ),
            createdAt = (data["createdAt"] as? Timestamp)?.toDate()
        )
    }

    private fun effectiveEntitlementData(data: Map<String, Any>): Map<*, *> {
        if (BuildConfig.DEBUG) {
            (data["debugEntitlementOverrides"] as? Map<*, *>)?.let { return it }
        }
        return data["entitlements"] as? Map<*, *> ?: emptyMap<Any, Any>()
    }
}
