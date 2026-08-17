import FirebaseCore
import FirebaseFirestore
import Foundation

@MainActor
final class OrganizationService {
    private let authService = AuthService()
    private let userProfileService = UserProfileService()
    private let firestore = FirebaseApp.app().map { _ in Firestore.firestore() }

    func createFoundingOrganization(named name: String) async throws -> Organization {
        guard let firestore else { throw OrganizationServiceError.notConfigured }
        guard let session = authService.currentSession() else { throw OrganizationServiceError.notSignedIn }
        let trimmedName = name.trimmingCharacters(in: .whitespacesAndNewlines)
        guard !trimmedName.isEmpty else { throw OrganizationServiceError.invalidName }
        let profileName = (try? await userProfileService.fetchUserProfile(uid: session.uid))?.displayName
        let foundingMemberName = profileName?.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty == false
            ? profileName!
            : session.displayName

        let reference = firestore.collection("organizations").document()
        let entitlements = OrganizationEntitlements.foundation
        let batch = firestore.batch()
        batch.setData([
            "name": trimmedName,
            "status": OrganizationStatus.active.rawValue,
            "memberIds": [session.uid],
            "createdAt": FieldValue.serverTimestamp(),
            "entitlements": [
                "peopleCapacity": entitlements.peopleCapacity as Any,
                "activeSpaceCapacity": entitlements.activeSpaceCapacity as Any,
                "enabledModuleIds": Array(entitlements.enabledModuleIDs).sorted(),
                "mediaStorageCapacityBytes": entitlements.mediaStorageCapacityBytes as Any
            ],
            "usage": ["peopleCount": 1, "activeSpaceCount": 0, "mediaStorageBytes": 0]
        ], forDocument: reference)
        batch.setData([
            "userId": session.uid,
            "displayName": foundingMemberName,
            "email": session.email ?? NSNull(),
            "role": OrganizationRole.primaryAdministrator.rawValue,
            "status": OrganizationMemberStatus.active.rawValue,
            "joinedAt": FieldValue.serverTimestamp()
        ], forDocument: reference.collection("members").document(session.uid))
        try await commit(batch)
        return Organization(id: reference.documentID, name: trimmedName, status: .active, entitlements: entitlements, usage: OrganizationUsage(peopleCount: 1, activeSpaceCount: 0, mediaStorageBytes: 0), createdAt: Date())
    }

    func listenToOrganizationsForCurrentUser(
        onUpdate: @escaping (Result<[Organization], Error>) -> Void
    ) -> ListenerRegistration? {
        guard let firestore else {
            onUpdate(.success([]))
            return nil
        }
        guard let session = authService.currentSession() else {
            onUpdate(.success([]))
            return nil
        }

        return firestore.collection("organizations")
            .whereField("memberIds", arrayContains: session.uid)
            .addSnapshotListener { snapshot, error in
                if let error {
                    onUpdate(.failure(error))
                    return
                }
                onUpdate(.success(snapshot?.documents.compactMap(self.mapOrganization(document:)) ?? []))
            }
    }

    func listenToOwnedSpaces(
        in organization: Organization,
        onUpdate: @escaping (Result<[OrganizationOwnedSpace], Error>) -> Void
    ) -> ListenerRegistration? {
        guard let firestore else { onUpdate(.success([])); return nil }
        return firestore.collection("spaces")
            .whereField("organizationId", isEqualTo: organization.id)
            .addSnapshotListener { snapshot, error in
                if let error { onUpdate(.failure(error)); return }
                onUpdate(.success(snapshot?.documents.map { document in
                    let data = document.data()
                    return OrganizationOwnedSpace(
                        id: document.documentID,
                        name: data["name"] as? String ?? "Untitled Space",
                        emoji: data["emoji"] as? String ?? "🏠",
                        memberCount: (data["memberIds"] as? [String])?.count ?? 0,
                        memberIDs: data["memberIds"] as? [String] ?? [],
                        isArchived: data["isArchived"] as? Bool == true
                    )
                } ?? []))
            }
    }

    func listenToMembers(in organization: Organization, onUpdate: @escaping (Result<[OrganizationMember], Error>) -> Void) -> ListenerRegistration? {
        guard let firestore else { onUpdate(.success([])); return nil }
        return firestore.collection("organizations").document(organization.id).collection("members")
            .addSnapshotListener { snapshot, error in
                if let error { onUpdate(.failure(error)); return }
                onUpdate(.success(snapshot?.documents.compactMap { document in
                    let data = document.data()
                    guard let userID = data["userId"] as? String, let role = OrganizationRole(rawValue: data["role"] as? String ?? "") else { return nil }
                    return OrganizationMember(id: document.documentID, userID: userID, displayName: data["displayName"] as? String ?? "Member", email: data["email"] as? String, role: role, status: .active, joinedAt: (data["joinedAt"] as? Timestamp)?.dateValue())
                } ?? []))
            }
    }

    func refreshCurrentMemberDisplayName(in organization: Organization) async throws {
        guard let firestore else { throw OrganizationServiceError.notConfigured }
        guard let session = authService.currentSession() else { throw OrganizationServiceError.notSignedIn }
        guard let profile = try await userProfileService.fetchUserProfile(uid: session.uid) else { return }
        let profileName = profile.displayName.trimmingCharacters(in: .whitespacesAndNewlines)
        guard !profileName.isEmpty else { return }
        let reference = firestore.collection("organizations").document(organization.id).collection("members").document(session.uid)
        try await withCheckedThrowingContinuation { continuation in
            reference.setData(["displayName": profileName], merge: true) { error in
                error.map { continuation.resume(throwing: $0) } ?? continuation.resume()
            }
        }
    }

    func applyFoundationEntitlementsIfUnconfigured(to organization: Organization) async throws {
        guard organization.entitlements.peopleCapacity == nil,
              organization.entitlements.activeSpaceCapacity == nil,
              organization.entitlements.mediaStorageCapacityBytes == nil,
              organization.entitlements.enabledModuleIDs.isEmpty else { return }
        guard let firestore else { throw OrganizationServiceError.notConfigured }
        let foundation = OrganizationEntitlements.foundation
        try await setData([
            "entitlements": [
                "peopleCapacity": foundation.peopleCapacity as Any,
                "activeSpaceCapacity": foundation.activeSpaceCapacity as Any,
                "enabledModuleIds": Array(foundation.enabledModuleIDs).sorted(),
                "mediaStorageCapacityBytes": foundation.mediaStorageCapacityBytes as Any
            ]
        ], for: firestore.collection("organizations").document(organization.id))
    }

    func requireActiveSpaceCapacity(for organizationID: String) async throws {
        guard let firestore else { throw OrganizationServiceError.notConfigured }
        let entitlement = try await effectiveEntitlements(for: organizationID)
        guard let limit = entitlement.activeSpaceCapacity else { throw OrganizationServiceError.activeSpaceCapacityReached }
        let spaces = try await getDocuments(firestore.collection("spaces").whereField("organizationId", isEqualTo: organizationID))
        let activeCount = spaces.documents.filter { $0.data()["isArchived"] as? Bool != true }.count
        guard activeCount < limit else { throw OrganizationServiceError.activeSpaceCapacityReached }
    }

    func requireUniqueMemberCapacity(for organizationID: String, admitting userID: String) async throws {
        guard let firestore else { throw OrganizationServiceError.notConfigured }
        guard let limit = try await effectiveEntitlements(for: organizationID).peopleCapacity else { throw OrganizationServiceError.peopleCapacityReached }
        let spaces = try await getDocuments(firestore.collection("spaces").whereField("organizationId", isEqualTo: organizationID))
        var uniqueMemberIDs = Set<String>()
        let organizationMembers = try await getDocuments(firestore.collection("organizations").document(organizationID).collection("members"))
        uniqueMemberIDs.formUnion(organizationMembers.documents.compactMap { ($0.data()["userId"] as? String) ?? $0.documentID })
        for space in spaces.documents {
            let members = try await getDocuments(space.reference.collection("members"))
            uniqueMemberIDs.formUnion(members.documents.compactMap { ($0.data()["userId"] as? String) ?? $0.documentID })
        }
        if uniqueMemberIDs.contains(userID) { return }
        guard uniqueMemberIDs.count < limit else { throw OrganizationServiceError.peopleCapacityReached }
    }

    func requireModuleEntitlement(for organizationID: String, module: SpaceModule) async throws {
        let enabled = try await effectiveEntitlements(for: organizationID).enabledModuleIDs
        guard enabled.contains(module.rawValue) else { throw OrganizationServiceError.moduleUnavailable(module.title) }
    }

    func effectiveEntitlements(for organizationID: String) async throws -> OrganizationEntitlements {
        guard let firestore else { throw OrganizationServiceError.notConfigured }
        let data = try await getDocument(firestore.collection("organizations").document(organizationID)).data() ?? [:]
        let values = effectiveEntitlementData(from: data)
        let stored = OrganizationEntitlements(
            peopleCapacity: (values["peopleCapacity"] as? NSNumber)?.intValue,
            activeSpaceCapacity: (values["activeSpaceCapacity"] as? NSNumber)?.intValue,
            enabledModuleIDs: Set(values["enabledModuleIds"] as? [String] ?? []),
            mediaStorageCapacityBytes: (values["mediaStorageCapacityBytes"] as? NSNumber)?.int64Value
        )
        return .effective(stored: stored)
    }

#if DEBUG
    func setDebugEntitlementOverrides(for organizationID: String, entitlements: OrganizationEntitlements) async throws {
        guard let firestore else { throw OrganizationServiceError.notConfigured }
        try await withCheckedThrowingContinuation { continuation in
            firestore.collection("organizations").document(organizationID).updateData(["debugEntitlementOverrides": [
                "peopleCapacity": entitlements.peopleCapacity as Any,
                "activeSpaceCapacity": entitlements.activeSpaceCapacity as Any,
                "enabledModuleIds": Array(entitlements.enabledModuleIDs).sorted(),
                "mediaStorageCapacityBytes": entitlements.mediaStorageCapacityBytes as Any,
                "updatedAt": FieldValue.serverTimestamp()
            ]]) { error in error.map { continuation.resume(throwing: $0) } ?? continuation.resume() }
        }
    }

    func clearDebugEntitlementOverrides(for organizationID: String) async throws {
        guard let firestore else { throw OrganizationServiceError.notConfigured }
        try await withCheckedThrowingContinuation { continuation in
            firestore.collection("organizations").document(organizationID).updateData(["debugEntitlementOverrides": FieldValue.delete()]) { error in
                error.map { continuation.resume(throwing: $0) } ?? continuation.resume()
            }
        }
    }
#endif

    func reserveStorage(forSpaceID spaceID: String, bytes: Int64) async throws -> String? {
        guard bytes >= 0 else { throw OrganizationServiceError.storageCapacityReached }
        guard let firestore else { throw OrganizationServiceError.notConfigured }
        let space = try await getDocument(firestore.collection("spaces").document(spaceID))
        guard let organizationID = space.data()?["organizationId"] as? String else { return nil }
        let limit = try await effectiveEntitlements(for: organizationID).mediaStorageCapacityBytes
        guard let limit else { throw OrganizationServiceError.storageCapacityReached }
        let reference = firestore.collection("organizations").document(organizationID)
        try await withCheckedThrowingContinuation { (continuation: CheckedContinuation<Void, Error>) in
            firestore.runTransaction({ transaction, pointer -> Any? in
                do {
                    let snapshot = try transaction.getDocument(reference)
                    let current = ((snapshot.data()?["usage"] as? [String: Any])?["mediaStorageBytes"] as? NSNumber)?.int64Value ?? 0
                    guard current <= limit - bytes else { throw OrganizationServiceError.storageCapacityReached }
                    transaction.updateData(["usage.mediaStorageBytes": current + bytes], forDocument: reference)
                    return nil
                } catch { pointer?.pointee = error as NSError; return nil }
            }) { _, error in error.map { continuation.resume(throwing: $0) } ?? continuation.resume() }
        }
        return organizationID
    }

    func releaseStorage(organizationID: String?, bytes: Int64) async {
        guard let organizationID, bytes > 0, let firestore else { return }
        let reference = firestore.collection("organizations").document(organizationID)
        _ = try? await withCheckedThrowingContinuation { (continuation: CheckedContinuation<Void, Error>) in
            firestore.runTransaction({ transaction, pointer -> Any? in
                do {
                    let snapshot = try transaction.getDocument(reference)
                    let current = ((snapshot.data()?["usage"] as? [String: Any])?["mediaStorageBytes"] as? NSNumber)?.int64Value ?? 0
                    transaction.updateData(["usage.mediaStorageBytes": max(0, current - bytes)], forDocument: reference)
                    return nil
                } catch { pointer?.pointee = error as NSError; return nil }
            }) { _, error in error.map { continuation.resume(throwing: $0) } ?? continuation.resume() }
        }
    }

    func releaseStorage(forSpaceID spaceID: String, bytes: Int64) async {
        guard bytes > 0, let firestore else { return }
        guard let snapshot = try? await getDocument(firestore.collection("spaces").document(spaceID)),
              let organizationID = snapshot.data()?["organizationId"] as? String else { return }
        await releaseStorage(organizationID: organizationID, bytes: bytes)
    }

    func addPerson(email: String, to organization: Organization, as role: OrganizationRole) async throws {
        guard let firestore else { throw OrganizationServiceError.notConfigured }
        let email = email.trimmingCharacters(in: .whitespacesAndNewlines).lowercased()
        guard !email.isEmpty else { throw OrganizationServiceError.invalidName }
        let user = try await getDocuments(firestore.collection("users").whereField("email", isEqualTo: email)).documents.first
        guard let user else { throw OrganizationServiceError.personNotFound }
        try await requireUniqueMemberCapacity(for: organization.id, admitting: user.documentID)
        let data = user.data()
        let ref = firestore.collection("organizations").document(organization.id)
        let batch = firestore.batch()
        batch.updateData(["memberIds": FieldValue.arrayUnion([user.documentID]), "usage.peopleCount": FieldValue.increment(Int64(1))], forDocument: ref)
        batch.setData(["userId": user.documentID, "displayName": data["displayName"] as? String ?? "Member", "email": email, "role": role.rawValue, "status": "active", "joinedAt": FieldValue.serverTimestamp()], forDocument: ref.collection("members").document(user.documentID))
        try await commit(batch)
    }

    func createInvite(for organization: Organization, role: OrganizationRole) async throws -> OrganizationInvite {
        guard let firestore else { throw OrganizationServiceError.notConfigured }
        guard let session = authService.currentSession() else { throw OrganizationServiceError.notSignedIn }
        let code = String(UUID().uuidString.replacingOccurrences(of: "-", with: "").prefix(8)).uppercased()
        try await setData([
            "organizationId": organization.id,
            "organizationName": organization.name,
            "role": role.rawValue,
            "createdBy": session.uid,
            "createdAt": FieldValue.serverTimestamp(),
            "active": true
        ], for: firestore.collection("organizationInvites").document(code))
        return OrganizationInvite(id: code, organizationID: organization.id, organizationName: organization.name, role: role)
    }

    func updateRole(of member: OrganizationMember, in organization: Organization, to role: OrganizationRole) async throws {
        guard member.role != .primaryAdministrator, role != .primaryAdministrator else { throw OrganizationServiceError.protectedPrimaryAdministrator }
        guard let firestore else { throw OrganizationServiceError.notConfigured }
        try await setData(["role": role.rawValue], for: firestore.collection("organizations").document(organization.id).collection("members").document(member.id))
    }

    func remove(_ member: OrganizationMember, from organization: Organization) async throws {
        guard member.role != .primaryAdministrator else { throw OrganizationServiceError.protectedPrimaryAdministrator }
        guard let firestore else { throw OrganizationServiceError.notConfigured }
        let batch = firestore.batch()
        let organizationReference = firestore.collection("organizations").document(organization.id)
        batch.updateData([
            "memberIds": FieldValue.arrayRemove([member.userID]),
            "usage.peopleCount": FieldValue.increment(Int64(-1))
        ], forDocument: organizationReference)
        batch.deleteDocument(organizationReference.collection("members").document(member.id))
        try await commit(batch)
    }

    func redeemInvite(code: String) async throws {
        guard let firestore else { throw OrganizationServiceError.notConfigured }
        guard let session = authService.currentSession() else { throw OrganizationServiceError.notSignedIn }
        let inviteReference = firestore.collection("organizationInvites").document(code.trimmingCharacters(in: .whitespacesAndNewlines).uppercased())
        let invite = try await getDocument(inviteReference)
        guard let data = invite.data(), data["active"] as? Bool == true,
              let organizationID = data["organizationId"] as? String,
              let role = OrganizationRole(rawValue: data["role"] as? String ?? "") else { throw OrganizationServiceError.invalidInvite }
        try await requireUniqueMemberCapacity(for: organizationID, admitting: session.uid)
        let batch = firestore.batch()
        let orgReference = firestore.collection("organizations").document(organizationID)
        batch.updateData(["memberIds": FieldValue.arrayUnion([session.uid]), "usage.peopleCount": FieldValue.increment(Int64(1))], forDocument: orgReference)
        batch.setData(["userId": session.uid, "displayName": session.displayName, "email": session.email ?? NSNull(), "role": role.rawValue, "status": "active", "inviteCode": inviteReference.documentID, "joinedAt": FieldValue.serverTimestamp()], forDocument: orgReference.collection("members").document(session.uid))
        batch.updateData(["active": false, "usedBy": session.uid, "usedAt": FieldValue.serverTimestamp()], forDocument: inviteReference)
        try await commit(batch)
    }

    private func commit(_ batch: WriteBatch) async throws {
        try await withCheckedThrowingContinuation { continuation in
            batch.commit { error in
                error.map { continuation.resume(throwing: $0) } ?? continuation.resume()
            }
        }
    }

    private func getDocuments(_ query: Query) async throws -> QuerySnapshot {
        try await withCheckedThrowingContinuation { continuation in
            query.getDocuments { snapshot, error in
                if let error { continuation.resume(throwing: error) }
                else if let snapshot { continuation.resume(returning: snapshot) }
            }
        }
    }

    private func getDocument(_ reference: DocumentReference) async throws -> DocumentSnapshot {
        try await withCheckedThrowingContinuation { continuation in
            reference.getDocument { snapshot, error in
                if let error { continuation.resume(throwing: error) }
                else if let snapshot { continuation.resume(returning: snapshot) }
            }
        }
    }

    private func setData(_ data: [String: Any], for reference: DocumentReference) async throws {
        try await withCheckedThrowingContinuation { continuation in
            reference.setData(data) { error in error.map { continuation.resume(throwing: $0) } ?? continuation.resume() }
        }
    }

    private func mapOrganization(document: DocumentSnapshot) -> Organization? {
        guard let data = document.data(), let name = data["name"] as? String else { return nil }
        let entitlementData = effectiveEntitlementData(from: data)
        let usageData = data["usage"] as? [String: Any] ?? [:]
        let moduleIDs = Set((entitlementData["enabledModuleIds"] as? [String]) ?? [])
        return Organization(
            id: document.documentID,
            name: name,
            status: OrganizationStatus(rawValue: data["status"] as? String ?? "") ?? .active,
            entitlements: OrganizationEntitlements(
                peopleCapacity: (entitlementData["peopleCapacity"] as? NSNumber)?.intValue,
                activeSpaceCapacity: (entitlementData["activeSpaceCapacity"] as? NSNumber)?.intValue,
                enabledModuleIDs: moduleIDs,
                mediaStorageCapacityBytes: (entitlementData["mediaStorageCapacityBytes"] as? NSNumber)?.int64Value
            ),
            usage: OrganizationUsage(
                peopleCount: (usageData["peopleCount"] as? NSNumber)?.intValue ?? 0,
                activeSpaceCount: (usageData["activeSpaceCount"] as? NSNumber)?.intValue ?? 0,
                mediaStorageBytes: (usageData["mediaStorageBytes"] as? NSNumber)?.int64Value ?? 0
            ),
            createdAt: (data["createdAt"] as? Timestamp)?.dateValue()
        )
    }

    private func effectiveEntitlementData(from organizationData: [String: Any]) -> [String: Any] {
        let stored = organizationData["entitlements"] as? [String: Any] ?? [:]
#if DEBUG
        if let override = organizationData["debugEntitlementOverrides"] as? [String: Any] { return override }
#endif
        return stored
    }
}

enum OrganizationServiceError: LocalizedError {
    case notConfigured, notSignedIn, invalidName, personNotFound, invalidInvite, protectedPrimaryAdministrator, activeSpaceCapacityReached, peopleCapacityReached, storageCapacityReached, moduleUnavailable(String)
    var errorDescription: String? {
        switch self {
        case .notConfigured: "Organization setup is unavailable until Firebase is configured."
        case .notSignedIn: "Sign in before creating an organization."
        case .invalidName: "Enter the organization name."
        case .personNotFound: "No Spaces account uses that email yet."
        case .invalidInvite: "That organization invite is no longer valid."
        case .protectedPrimaryAdministrator: "The primary administrator cannot be changed or removed."
        case .activeSpaceCapacityReached: "This organization has reached its active Space limit."
        case .peopleCapacityReached: "This organization has reached its people limit."
        case .storageCapacityReached: "This organization has reached its pooled storage limit. Delete content or increase its storage capacity before uploading."
        case .moduleUnavailable(let module): "\(module) is not included in this organization's enabled modules."
        }
    }
}
